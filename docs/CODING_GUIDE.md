# Astral 开发注意事项（代码质量红线）

> 本文档由一次全量代码审阅 + 逐项源码核实 + 本轮修复沉淀而来。
> 凡是后面标记 **【已修】** 的，说明对应缺陷已在本次改动中处理完毕；标记 **【禁止】** 的是新代码绝对不能再犯的写法；
> 标记 **【注意】** 的是容易踩坑、但改动风险高的存量逻辑，新功能请绕开它的坑。
> 审阅报告原文见 `logs/codereview/CODE_REVIEW_2026-09-28.md`。

---

## 0. 总原则：静态扫描只能发现 20% 的问题

本次用 PMD-CPD（重复代码）+ SpotBugs（缺陷模式）跑全量，结论是：**所有真正的 P0（鉴权、并发、事务、异步）都是语义级问题，静态工具一个都抓不到**。

- SpotBugs 的 48 条告警里 29 条是 `EI_EXPOSE_REP*`（返回可变内部数组/集合），绝大多数是误报或低危。
- 不要因为 "SpotBugs 没报错" 就认为逻辑安全。并发、事务边界、代理失效这类必须**读源码逐条确认**。

依赖（无需改 pom 即可跑）：

```bash
mvn org.apache.maven.plugins:maven-pmd-plugin:3.26.0:cpd -Dcpd.minimumTokens=70
mvn com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs -Dspotbugs.effort=Max -Dspotbugs.threshold=Medium
```

> CPD 产出的 XML 默认带命名空间 `https://pmd-code.org/schema/cpd-report`，解析时必须 `t.split('}')[-1]` 剥离，否则 `iter('duplication')` 拿不到任何节点。

---

## 1. 异步 `@Async` 必须落在「另一个 Bean」上【禁止复发】

Spring 的 `@Async` 是**靠代理实现的**：只有「外部 Bean 调用代理对象的方法」才会经过 `AsyncExecutionInterceptor`。
**同一个类里的方法直接调用自己（self-invocation），代理完全不生效** —— 异步方法变成同步执行，且异常会直接抛给调用方。

- 【已修】`OperateLogAspect` 原本在自己类里 `@Async saveLogAsync` 又自己调，已抽出独立 `OperateLogWriter`。
- 【已修】`GeneratorFactory` 的 3 个 `@Async` 历史/统计写入已抽到 `SequenceAsyncWriter`；`SequenceMetaObjectHandler` 也随之改为注入 `SequenceAsyncWriter`，去掉了 `@Lazy` 循环依赖。
- 判断标准：凡是 `@Async` 方法，确认它的**唯一调用方在另一个 Spring Bean 里**。

### 1.1 裸 `@Async`（不带执行器名）默认不安全【禁止】

若工程里**没有注册默认执行器 Bean**，`@Async`（无名字）会退化成 `SimpleAsyncTaskExecutor`：**每来一个任务就新建一条平台线程，不复用、不限流**。
高并发下（如反馈提交/回复触发通知）会无上限建线程直至 OOM。

- 【已修】`AsyncConfig` 现实现 `AsyncConfigurer.getAsyncExecutor()`，裸 `@Async` 统一收敛到受控的 `sequenceAsyncExecutor`（虚拟线程模式，无队列压力）。
- 新写异步任务：**优先显式 `@Async("sequenceAsyncExecutor")`**；确需独立池的，先注册一个有界 `ThreadPoolTaskExecutor` 再引用其 Bean 名。

### 1.2 被 MyBatis-Plus `MetaObjectHandler` 引用的异步 Bean 必须用 `@Lazy`【血泪教训】

`SequenceMetaObjectHandler` 在 **SqlSessionFactory 构建阶段**就需要就绪（MyBatis-Plus 装配 `sqlSessionFactory` 时要它），
而它本身引用了 `SequenceAsyncWriter`；`SequenceAsyncWriter` 的构造依赖 `SequenceHistoryService` / `SequenceStatisticsMapper`，
这两者又依赖 `sqlSessionFactory`。**直接（非 `@Lazy`）注入就会形成构造期循环依赖，后端启动即失败**：
`APPLICATION FAILED TO START … bean cycle: metaObjectHandler → sequenceAsyncWriter → historyService → sqlSessionFactory → metaObjectHandler`。

- 【已修】`SequenceAsyncWriter` 的 DAO 依赖一律 `@Lazy` 注入：构造时只拿到延迟代理，真正的 Bean 在**首次异步调用时**（此时容器早已启动完成）才解析。
- 凡是「被 `MetaObjectHandler` / `sqlSessionFactory` 装配链引用」的 Bean，其任何依赖 DAO/Mapper 的下游 Bean 都必须 `@Lazy`，否则同样会循环依赖。
- 这条只有在**真正把后端跑起来**时才会暴露（`mvn compile` 看不出来）—— 异步/元对象处理器相关的改动务必跑一次启动验证。

---

## 2. 事务 `@Transactional`【禁止复发】

- **裸 `@Transactional` 只回滚 `RuntimeException`**，受检异常（如某些 `BusinessException` 声明）**不会回滚**。一律写 `@Transactional(rollbackFor = Exception.class)`。
  - 【已修】`FeedbackService`、`QtLikeService` 的 5+3 处裸注解已补齐。
- `@Transactional` 必须加在**被外部调用的 public Service 方法**上；与 `@Async` 同理，类内自调用不生效。
- 多步写操作（建/改/删 + 关联表清理）要放在同一个带事务的方法里，否则中间失败会留孤儿数据。
  - 【已修】`RoleController.delete` / `UserRoleService.assignRoles` / `RolePermissionService.assignPermissions` 已用单事务。
  - 【已修】`SysMenuController.delete` 原来只删一层子菜单且两条 `delete` 不在同一事务，已抽到 `SysMenuService.deleteWithChildren`（递归 + 单事务）。

### 2.1 跨实例安全：不要自己加锁做「读-改-写」

- 【已修】`DatabaseGenerator` 原先用 JVM `ReentrantLock` +「读旧值→写新值」，两个问题：(1) 锁在 `finally` 释放但事务更晚提交，锁与事务边界不重合会重号；(2) JVM 锁跨实例失效。
  改为一条 `UPDATE ... SET current_value = current_value + #{step} ... RETURNING current_value`，由数据库行锁保证原子。
- 序号/计数自增：**一律用数据库原子语句**（PostgreSQL `RETURNING` / `ON CONFLICT DO NOTHING`），不要应用层加锁。

---

## 3. 安全红线

### 3.1 越权：管理端接口必须显式鉴权【禁止】

- 后端接口**不能只靠「登录态」**：任何 `/api/v1/admin/**` 的读接口都要 `permissionChecker.require("xxx:view")`，
  提权类（改角色、改他人密码、建菜单/权限）要 `permissionChecker.requireSuper()`。
  - 【已修】`UserController`、`RoleController`、`TableSchemaController`、`PermissionController`、`SysMenuController` 已统一补鉴权。
- 白名单用**精确集合匹配**，**不要 `startsWith`**：
  - 【已修】原 `AuthInterceptor` 用 `PUBLIC_PATHS.contains(uri)` + `TABLE_SCHEMA_ADMIN_PREFIX.startsWith(...)` 做放行，会被 `/api/v1/admin/../x` 之类绕过；现改为精确 `contains` + `/api/v1/admin/` 前缀网关。
- 角色删除必须清理 `sys_user_role` / `sys_role_permission`，并带引用校验。

### 3.2 凭证不能进 response【禁止】

- 任何含密码/授权码/密钥的实体字段，Jackson 上必须 `@JsonProperty(access = WRITE_ONLY)`。
  - 【已修】`SysMailAccount.password`（SMTP 授权码）已加；`User.password` 此前已有。
- 删除/更新接口**不要整实体 `updateById`**（mass assignment）：客户端可改写 `userType`/`deleted`/`status` 等敏感字段。
  - 【已修】`UserController.update` 显式置空敏感字段；`RoleController.update`、`PermissionController.update` 同理。

### 3.3 路径穿越 / 上传【禁止】

- 文件落盘：Jackson `writeValue(File)` 会自动建父目录，路径校验不能只判断「目录不存在就建」，要做 `normalize().startsWith(base)` **锚定**，防 `../` 逃逸。
  - 【已修】`SchemaRegistry.resolveSchemaFile` 已锚定。

### 3.4 X-Forwarded-For 不可信【禁止】

- `X-Forwarded-For` 是客户端可任意伪造的。**绝不能无条件采信**做 IP / 审计 / 限速来源。
- 统一走 `com.astral.common.util.ClientIp.resolve(xff, xRealIp, remoteAddr, trustedProxies)`：只信任 `trustedProxies`（默认回环 + 私有网段）之内的跳，从右往左取第一个非可信地址。
  - 【已修】`AuthServiceImpl`、`RateLimitInterceptor`、`OperateLogAspect`、`LoginLogAspect`、`StatIngestController`、`AppStatController`、`AppFeedbackController`、`QtMediaService`、`StorageUserController` 全部收敛到该工具。新代码**禁止**再手写 IP 解析。
- CORS：默认 `*` 配 `allowCredentials=true` 是非法组合，会污染凭据。`application.yml` 已把 `*` 改为 `http://localhost:3000`，新增跨域来源走配置。

### 3.5 模板/日志注入【禁止】

- 邮件模板变量渲染对**值做 HTML 转义**（`MailServiceImpl.render` 已实现），否则用户可控内容（昵称、反馈标题）进 HTML 邮件即成 XSS/钓鱼。
- `X-Request-Id` 等客户端可控响应头必须校验格式（如 `^[A-Za-z0-9_-]{1,64}$`），不合法重新生成，防日志伪造 / 头注入。
  - 【已修】`RequestIdFilter` 已校验。

### 3.6 入参必须过 Bean Validation（长度 + 非空），且校验只能写进「生成器」【禁止复发】

> **【血泪教训】实体是代码生成的，手写 `.java` 会被覆盖。**
> `Permission` / `OperateLog` / `SequenceConfig` / `RolePermission` / `UserRole` 等**所有 `com.astral.dao.entity.*` 都是 `astral-schema` 插件（绑定 `generate-sources`）按 `astral-schema/src/main/resources/schema/*.json` 重新生成的**。
> 直接给实体 `.java` 加 `@Size`/`@NotNull` **下次 `mvn package` 就被抹掉**（本次就踩了：第一次手写注解，回归依旧 5 个 FAIL，查 `javap` 才发现 class 里根本没有 `@Size`）。
> **正确做法**：校验规则写在 `SchemaCodeGenerator.generateEntity`（生成 `@Size`/`@NotNull`）+ schema JSON（列宽 `length`、是否 `isRequired`），控制器 `@Valid` 手写在对应 Controller 里（Controller 不被生成，手写持久）。

- 实体 `String` 字段对应库表 `VARCHAR(N)`：**自动生成 `@Size(max = N)`**（`generateEntity` 对 `isVarchar && length>0` 的字段生成，max 严格等于 schema JSON 里的 `length` = DDL 宽度，绝不改小，否则合法长值被误判 400）。
- 库表 `NOT NULL` 且无默认值、非自动填充的列：**自动生成 `@NotNull`**（`generateEntity` 对 `isRequired && !主键 && defaultValue==null && isAutoFill==null` 的字段生成；有默认值/自动填充的列不标，否则误杀「靠默认/填充补全」的合法请求）。
- 控制器写接口（`@PostMapping`/`@PutMapping` + `@RequestBody`）的实体参数**必须加 `@Valid`**，否则字段上的 `@Size`/`@NotNull` 根本不触发：
  ```java
  public Result<Void> create(@Valid @RequestBody Permission entity) { ... }
  public Result<Void> update(@PathVariable Long id, @Valid @RequestBody Permission entity) { ... }
  ```
- **不加的后果**：超长/缺失直接打到 DB，PostgreSQL 报 `value too long for type character varying(N)` 或 `null value in column "xxx" violates not-null constraint` → **未捕获 500**（隔离回归脚本记作 FAIL）。
  加上后变 `MethodArgumentNotValidException`/`BindException` → `GlobalExceptionHandler` 已映射为 **HTTP 400 `COMMON002`「参数校验失败」**（回归记作 WARN，不再是 FAIL）。
- **JSON ↔ DDL 宽度必须一致**：`sys_permission.method` 与 `sys_operate_log.request_method` 原先 schema JSON `length=10`，但 Flyway DDL（`V20260914001__init.sql`）是 `VARCHAR(16)`——发散会让生成的 `@Size(max=10)` 比库窄、误杀合法值。**已把两个 JSON 的 `length` 改成 16 对齐 DDL**。改 DDL 宽度时务必同步改对应 schema JSON 的 `length`。
- 依赖：`astral-dao`/`astral-system`/`astral-log`/`astral-sequence` 已加 `spring-boot-starter-validation`（`astral-dao` 让生成的实体能编译 `@Size`/`@NotNull`；其余三个让控制器 `@Valid` 能触发）。
- **新开表 / 加字段**时：在 schema JSON 里写对 `length` 与 `isRequired`，生成器会自动带校验；手写 Controller 时记得 `@Valid`。别等回归 500 再补。
- 本次补齐后，隔离回归 **FAIL 5 → 0**（3 个长度溢出 + 2 个 `role_permission`/`user_role` 空外键，全部 500→400），后端未捕获异常 0 类。

---

## 4. SQL 与注入

- Mapper 层一律 `#{}` 占位，**禁止 `$` 拼接**（全仓已合规，保持）。
- **唯一**的注入面在 schema 代码生成链（表名/列名拼进 DDL）：必须过 `SqlIdentifiers.isValid/quote` 校验 + 引用。
  - 【已修】新增 `SqlIdentifiers` 工具类，已对 43 张表 / 474 列全量校验（0 违规），并在 `SchemaRegistry` / `SchemaCodeGenerator` 的写路径强制调用。
- 字符集：`new String(byte[])` 依赖 JVM 默认字符集，容器里可能不是 UTF-8 → 解密乱码。
  - 【已修】`RsaKeyManager.decryptPasswordBase64` 改 `new String(bytes, StandardCharsets.UTF_8)`。

---

## 5. 并发与状态机

- 段号生成器切段用 `AtomicReference.compareAndSet`，CAS 失败应**返回当前段**而非抛异常（原实现会跳过整段）。
  - 【已修】`SegmentGenerator.loadOrSwitchSegment` / `tryNextValue` 已修正。
- 雪花 worker-id/datacenter-id 越界应**启动即失败**，不要静默回退到 1（多实例重号）。
  - 【已修】`SnowflakeGenerator` 构造期校验，越界抛 `IllegalStateException`。
- 池化对象（如 Caffeine `windowMap`）必须**有容量上限 + 过期**，否则攻击者轮换 IP 刷登录接口可 OOM。
  - 【已修】`RateLimitInterceptor` 改用 Caffeine（`expireAfterAccess` + `maximumSize`）。

---

## 6. 日志与可观测

- 默认 profile 不再用 `StdOutImpl` 打印全 SQL（会把绑定参数里的敏感值打到控制台），改为 SLF4J；
  `logging.level.com.astral` 默认 `INFO`（原 `DEBUG` 会持续刷屏且泄漏参数）。
  - 要看 SQL：临时把具体 `com.astral.dao.mapper` / `com.astral.schema` 调到 `DEBUG`，不要全局开。
- 审计日志 `@OperateLog` **目前只覆盖 sequence 模块**。用户/角色/权限/配置变更应该也加，否则越权操作无迹可查（存量 TODO，新接口建议顺手补）。
- 前端请求层轮询/心跳必须传 `{ silent: true }`，并 `useEffect` 内 `alive` 标志 + `clearInterval` 清理，否则加载条每 5 秒闪、组件卸载后还 `setState`。

---

## 7. 前端

- 筛选/搜索：`loadData` 不要从渲染闭包读 `filters` 再 `setState` 后立即调用 —— 第一次筛选不生效。改为「先算 `next` → `setFilters(next)` → `loadData(..., next)`」。
  - 【已修】`system/mail/log`、`feedback`、`NoticeManagement`、`system/user` 四处的旧闭包。
- `columns` / `filteredMenuConfig` 等数组、派生值用 `useMemo` 包起来，**依赖数组列全**，否则 `ResizableTable` 的 `useMemo([columns])` 永远重建表头。
  - 【已修】`layout`、`statistics`、`feedback`、`qt`、`NoticeManagement` 已 memo 化。
- 重型库（echarts / 语法高亮）用 `next/dynamic(ssr:false)` 动态加载；非首屏页面不要全量 import。
  - 【已修】`statistics`、`feedback`、`table-schema` 已改为动态加载（echarts 暂未做按需核心模块注册，避免 option 用到未注册组件导致白屏）。
- 前后端契约：前端 `api/auth.ts` 的 `LoginResponse` 已对齐后端真实字段（`id/username/nickname/token/userType/roles/permissions`，**删掉了不存在的 `userId/avatar/loginTime`**）。改后端 DTO 时**同步改前端类型**，别用 `as any` 掩盖。

---

## 8. 启动期实体同步（高危，务必读）

`AstralApplication.main` 在 **Spring 容器启动前**执行 `SchemaEntitySync.syncOnStartup()`：

- 它**读不了 `application.yml`**（那时还没 Spring 上下文），只能通过 JVM 系统属性 `-Dastral.schema.sync-on-startup=false` 或环境变量 `ASTRAL_SCHEMA_SYNC_ON_STARTUP=false` 关闭。原来写的 `application.yml` 同名配置**完全无效**。
- **默认开启**，且会**删除「没有对应 schema JSON」的 entity `.java` 源文件**。手写实体若忘了登记 JSON，启动即被静默删除。
- **生产环境务必关闭**：`ASTRAL_SCHEMA_SYNC_ON_STARTUP=false`（Dockerfile / 启动命令里设置）。
- 【已修】`AstralApplication` 现已支持环境变量 + 默认开启时打印醒目 WARN。

---

## 9. 部署：不要用宝塔面板的「升级容器」做发布【重要】

- 宝塔 Docker 面板的「升级」= **拉新镜像 + 用旧容器的参数重建**，**不读 `docker-compose.yml`**。
  会丢失 `extra_hosts` / `env_file` / `healthcheck` / `networks` / 命名卷，且重建的容器缺少 `com.docker.compose.project` 标签，
  之后 `docker compose` 会报 `Conflict. The container name "/astral-backend" is already in use`。
- **正确发布**：在 compose 项目目录里 `git pull` + 跑项目的 `update.sh`（它按 compose 文件重建）。
  若已误用面板升级，恢复步骤见 `DEPLOY_GUIDE.md`「回到 compose」一节（重建前先核对卷名，勿 `docker rm -f` 误删数据卷）。

---

## 10. 其它已修的 P2 项（速查）

- `LogController.getOperateLogPage` 的 `operation/startTime/endTime` 之前声明了却没参与查询（前端筛选静默失效）→ 已真正生效。
- `StatMetricHourlyMapper.incrementMetric` 注解从 `@Insert` 改为 `@Update`（原错标）。
- `SequenceSegment` 缺 `@TableId(type = IdType.AUTO)` → 已补（与另外 3 张 sequence 表一致）。
- 实体校验缺 Bean Validation（长度 + 非空）→ 超长/缺失入参打到 PG 变未捕获 500（回归 5 个 FAIL）。**关键坑：实体是 `astral-schema` 按 JSON 生成的，手写 `.java` 会被下一轮 `mvn package` 覆盖**——正确做法是改 `SchemaCodeGenerator.generateEntity`（VARCHAR 自动 `@Size(max=length)`、必填非默认列自动 `@NotNull`）+ schema JSON（列宽 `length`；并把与 DDL 发散的 `sys_permission.method`/`sys_operate_log.request_method` 的 `length` 由 10 改 16 对齐），控制器 `@Valid` 手写在各 Controller。最终 `role_permission`/`user_role` 关联表也补 `@NotNull`+`@Valid`。回归 FAIL 5 → 0，未捕获异常 0 类。
- 死代码：重复的 `astral-server/.../CreateTableRequest`（与 system 模块重复）已删；`DictTypeService.getAllCached` 等无人读的缓存层已清。
- 权限树 `setChildren` 原递归无环检测，库里成环会 `StackOverflowError` → 已加 `visiting` 环检测。
- 邮件每日额度原来是「先 COUNT 再发送」的非原子写法（并发可超额轰炸）→ 已改 Redis `INCR + EXPIRE` 原子占位，Redis 不可用时降级为 DB 计数。
