# Astral 开发注意事项（代码质量红线）

> 本文档持续登记已确认缺陷及长期开发约束。**【已修】** 对应原记录时点的修复，不表示本次重新验证全仓；**【禁止】** 是后续开发与评审必须检查的规则。
> 2026-11-10 更新：[全项目修复复盘](audit/2026-11-10-http/RETROSPECTIVE.md)、[验证与交付规范](VERIFICATION_GUIDE.md)。新增规则集中在 §11，适用于所有相关新增/变更入口。
> 早期审阅原文保留在本地 `logs/codereview/CODE_REVIEW_2026-09-28.md`；该日志不随仓库分发，长期规范以本文与受控审计文档为准。

---

## 0. 总原则：静态扫描不能替代行为与运行验证

PMD-CPD（重复代码）与 SpotBugs（缺陷模式）可以提供线索，但鉴权、并发、事务、异步和完整启动需要调用链审阅与实际行为证据，不能用固定百分比描述工具覆盖率。

- 告警数量属于具体版本。2026-11 最终记录为 248 条 Medium / 0 High，逐条评估见 [验证收据](audit/2026-11-10-http/verification.json)，不能沿用早期数量或笼统认定全部误报。
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

- 后端接口**不能只靠登录态**：管理资源使用声明式 `@RequiresPermission("端:域:资源:操作")`；用户新增/删除/分配角色/重置密码、角色新增/分配权限、权限定义写入及表结构写入使用 `@RequiresSuper`。个人中心等明确的本人操作按专门契约处理。
  角色元信息、菜单和字典等可下放操作使用对应权限码及字段白名单；不要在 Controller 重建一套手写鉴权，也不要把所有写操作一律升级成超管权限。
- 白名单用**精确集合匹配**，**不要 `startsWith`**：
  - 【已修】原 `AuthInterceptor` 用 `PUBLIC_PATHS.contains(uri)` + `TABLE_SCHEMA_ADMIN_PREFIX.startsWith(...)` 做放行，会被 `/api/v1/admin/../x` 之类绕过；现改为精确 `contains` + `/api/v1/admin/` 前缀网关。
- 角色删除必须清理 `sys_user_role` / `sys_role_permission`，并带引用校验。

### 3.2 凭证不能进 response【禁止】

- 任何含密码/授权码/密钥的实体字段，Jackson 上必须 `@JsonProperty(access = WRITE_ONLY)`。
  - 【已修】`SysMailAccount.password`（SMTP 授权码）已加；`User.password` 此前已有。
- 更新接口使用显式可写字段白名单；不能把客户端实体或读出的旧整行交给 `updateById`，避免 mass assignment 和并发状态回退。
  单独将敏感字段置 null 不是完整防护，默认 NOT_NULL 策略还会使合法清空失效。创建、局部修改和完整表单的区别见 §11.1。

### 3.3 路径穿越 / 上传【禁止】

- 文件落盘：Jackson `writeValue(File)` 会自动建父目录，路径校验不能只判断「目录不存在就建」，要做 `normalize().startsWith(base)` **锚定**，防 `../` 逃逸。
  - 【已修】`SchemaRegistry.resolveSchemaFile` 已锚定。

### 3.4 X-Forwarded-For 不可信【禁止】

- `X-Forwarded-For` 是客户端可任意伪造的。**绝不能无条件采信**做 IP / 审计 / 限速来源。
- 统一走 `com.astral.common.util.ClientIp.resolve(xff, xRealIp, remoteAddr, trustedProxies)`：只信任 `trustedProxies`（默认回环 + 私有网段）之内的跳，从右往左取第一个非可信地址。
  - 【已修】`AuthServiceImpl`、`RateLimitInterceptor`、`OperateLogAspect`、`LoginLogAspect`、`AppStatController`、`AppFeedbackController`、`QtMediaService`、`StorageUserController` 全部收敛到该工具。新代码**禁止**再手写 IP 解析。
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
- 控制器完整创建/整表单更新接口（`@PostMapping`/`@PutMapping` + `@RequestBody`）的实体参数**必须加 `@Valid`**，否则字段上的 `@Size`/`@NotNull` 根本不触发：
  ```java
  public Result<Void> create(@Valid @RequestBody Permission entity) { ... }
  public Result<Void> update(@PathVariable Long id, @Valid @RequestBody Permission entity) { ... }
  ```
- **局部更新须保留省略字段的语义**：只改昵称、角色元信息或菜单排序时，不应要求重传密码、不可变编码或名称。此类入口在写入前用 `EntityPatchValidator` 对白名单中非 null 字段调用实际 Bean Validator，复用生成实体的约束；校验白名单必须与更新白名单一致。不能简单取消校验，也不能把全实体 `@NotNull` 强加到省略字段上。
- **不加的后果**：超长/缺失直接打到 DB，PostgreSQL 报 `value too long for type character varying(N)` 或 `null value in column "xxx" violates not-null constraint` → **未捕获 500**（隔离回归脚本记作 FAIL）。
  加上后变 `MethodArgumentNotValidException`/`BindException` → `GlobalExceptionHandler` 映射为 **HTTP 200 + body `code=320` `COMMON002`「参数校验失败」**
  （2026-10 响应体统一后：HTTP 状态码只表传输层，参数错误落在 body `code`；回归记作 WARN，不再是 FAIL）。
- **JSON ↔ DDL 宽度必须一致**：`sys_permission.method` 与 `sys_operate_log.request_method` 原先 schema JSON `length=10`，但 Flyway DDL（`V20260914001__init.sql`）是 `VARCHAR(16)`——发散会让生成的 `@Size(max=10)` 比库窄、误杀合法值。**已把两个 JSON 的 `length` 改成 16 对齐 DDL**。改 DDL 宽度时务必同步改对应 schema JSON 的 `length`。
- 依赖：`astral-dao`/`astral-system`/`astral-log`/`astral-sequence` 已加 `spring-boot-starter-validation`（`astral-dao` 让生成的实体能编译 `@Size`/`@NotNull`；其余三个让控制器 `@Valid` 能触发）。
- **新开表 / 加字段**时：在 schema JSON 里写对 `length` 与 `isRequired`，生成器会自动带校验；手写 Controller 时记得 `@Valid`。别等回归 500 再补。
- 本次补齐后，隔离回归 **FAIL 5 → 0**（3 个长度溢出 + 2 个 `role_permission`/`user_role` 空外键，全部 500→业务错误；响应体统一后同一场景的 body `code` 为 `320`），后端未捕获异常 0 类。

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

---

## 11. 2026-11 全项目修复后的强制规则

本节对应 [复盘问题登记](audit/2026-11-10-http/RETROSPECTIVE.md)，后续相关改动必须逐条核对。每项同时约束实现、调用方和回归；单个入口修复后必须检查同一业务的其他入口。必跑检查与命令见 [验证与交付规范](VERIFICATION_GUIDE.md)。

### 11.1 写入白名单、有效状态与生成约束

| 入口语义 | 校验 | 写入 | 最低回归 |
| --- | --- | --- | --- |
| 创建 / 完整请求 | `@Valid` 或实际 Bean Validator 验证必填与长度，再验证业务约束 | 只接收该操作可写字段，主键/时间/身份等由服务端控制 | 合法边界接受；缺失/超长/越权字段拒绝或按合同忽略；失败无残留 |
| 局部修改 / 列表开关 | 复用 Bean Validator（如 `EntityPatchValidator`）校验提供的可写字段，保留省略语义；跨字段约束合并锁内存量值后校验 | 省略字段保留，仅 SET 本次提供的白名单字段；不得要求重传密码/编码/名称 | 单字段 `{enabled}`、省略必填、未知目标、非法值及拒绝后原值保留 |
| 合并完整表单 | 按选中渠道验证有效状态与对应生成实体 | 显式 SET 白名单中的可空字段，合同允许的 null 必须真正清空 | 清空后公开读取、保留 ID、改事件后的变量、任一渠道失败整体回滚 |

- 明确省略、null、空串的语义。现有局部接口的省略/null 保留与完整表单 null 清空不能混用；局部显式清空需要字段存在性信息，不能凭普通 nullable 实体猜测。
- 更新只能写本次操作拥有的字段。禁止“读旧整行→覆盖少数字段→整行回写”；旧状态可能撤销并发封禁、权限或密码变化。
- 校验白名单与写入白名单一致。跨字段约束在事务锁内合并有效状态校验，但不能把合并后的整行直接作为更新补丁。
- DDL 最终迁移结果、schema JSON 和生成实体注解一致。VARCHAR 上界、TEXT、required、默认值及自动填充分别核对；不能为拦错缩小合法上界。
- 生成实体规则修改 JSON/生成器并实际执行生成。禁止只改生成 `.java` 或跳过 codegen 后声称已修。历史 Flyway 脚本保持 checksum，通过新迁移或明确兼容机制修正。

### 11.2 事务、并发与提交后缓存

- 同一数据不变量的 Controller、Service、AI 工具、批量/直接关联和初始化入口共用公开 Service 约束，不能依赖某个 Controller 的补救逻辑。
- 先获取共同父行/集合锁，再读取目标存在性、可用管理员数、关联集合和引用；锁内验证并写入同一事务。通知规则先锁父行，再读渠道和收件人；删除路径也遵守协议。
- 有序获取同类 ID 锁；跨角色/权限写入保持现有角色→权限顺序，新增入口检查与删除路径是否形成锁环。JVM 锁不能替代 PG 跨实例约束。
- 授权关系写入使用 `PermissionCache.bumpAfterCommit()`；事务未提交或回滚不发布新权限版本。模板/公告缓存同样在提交后失效，不在 Controller 提前重复清缓存。
- 最后管理员、权限引用等保护只对参与共同协议的入口成立。新增入口必须加入，不扩大为所有继承 CRUD、任意 SQL 或全部用户状态均已保证。
- 并发修复用真实 PG、独立连接和受控时序验证两种先后提交。H2 模式、固定 sleep 或单线程正常路径不能证明 PG 行锁成立。

### 11.3 通知渠道、模板与供应商身份

- 单渠道与合并入口执行相同约束：事件可用渠道、必达渠道、事件×渠道唯一性、变量归属、SMS 编码/审核状态及字段长度。
- 只校验所选渠道使用的字段；SMS-only 不解析隐藏 EMAIL/INAPP 的旧变量 JSON。保留尚未启用渠道的合法预配置合同。
- 供应商创建完整校验，更新允许启停/单字段补丁；提供名称/类型时仍拒绝空白、重名或未知 SPI。
- 校验、保存与投递使用同一已注册 SPI 身份。保存实际匹配的 `type()`，历史读取复用去空格/大小写匹配；不能校验接受别名、落库原值、路由使用另一套匹配。测试含 tr-TR 与已接受的 Unicode 等价值，不依赖默认 Locale。
- `EMAIL/SMS/INAPP` 是渠道，`web/app-*` 是平台，受众另有合法集合；不得混写。公告走宿主服务维护缓存与平台规则，未知受众拒绝。
- 邮件专用工具只允许 EMAIL，通用事件保留多渠道。按实际投递成功/失败/部分成功报告，不以“已调用 publish”宣称发送成功。

### 11.4 条件表单与异步界面

- 字段卸载后注销规则、错误和旧验证代际，保留草稿；重挂载及 StrictMode replay 恢复当前规则。同路径控件替换不得误删新规则。
- payload 在 API 边界按选中功能/渠道构造；隐藏的无关字段不参与解析/校验，不能只做视觉隐藏。
- 切换记录/来源/版本、关闭弹窗、组件卸载或退出登录后，旧响应不得覆盖新状态或恢复旧会话；使用请求身份/代际及生命周期清理。
- 失败保留可恢复草稿，busy 状态能够结束，重复保存有明确约束；加载、合法空结果与服务错误分别显示。
- 使用真实 React 生命周期及实际请求 payload 回归。布局、IME、焦点、滚轮、跨 tab 单独做浏览器验证，合成 DOM 不冒充浏览器。参见 [UI 规范](UI_GUIDE.md)。

### 11.5 权限、敏感信息与外部副作用

- HTTP 身份门禁、动作权限与结果数据范围分别检查；APP 用户即使有管理角色也不能越过管理端身份门禁。AI 工具不能绕过宿主公开服务的业务约束。
- 审批计划由服务端生成并持久化授权/目标快照；执行时在同一锁协议下复核。模型传入快照不可信；等待期间状态变化必须使旧计划过期，不能重建计划悄悄覆盖用户的新修改。
- 外部调用使用持久操作身份和有界请求/响应；结果 UNKNOWN 保留待核，不自动重发可能已成功的副作用。费用/额度按实际 usage 和已存快照核对，不能编造历史价格或换汇。
- 密钥、完整 Token、执行配置中的认证头不得进入列表/详情、计划日志或错误回显；登录等专用认证接口仅按其既有合同返回令牌。掩码保留、显式清空与重新输入按接口合同分开处理。
- HTTP/MCP/下载使用现有地址策略及传输层的 DNS/TLS、重定向、时限和大小约束；SQL 工具保持 AST 来源/表列/函数限制。不得为恢复可用性关闭这些检查。

### 11.6 Spring 装配与依赖兼容

- Spring Bean 存在多个构造器时明确生产注入入口；测试构造器不能改变容器选择。为测试增加 seam 后必须验证完整应用装配，不能只手工实例化。
- Bean、配置、认证或运行依赖变更必须运行真实入口，等待 readiness，再验证加密登录、会话恢复和登出；相关安全策略回归同时保留。
- Redis 最低指令兼容版本为 6.0，部署选择仍受维护版本。`SET KEEPTTL` 会话更新实际成功才是兼容证据；能连接/PING 不够。
- 构建执行当前 schema generator；已构建产物的隔离启动关闭源码同步并明确工作目录，防止启动时覆盖源码或读到其他环境配置。完整命令见 [验证规范 §3](VERIFICATION_GUIDE.md#3-后端与前端检查命令)。

### 11.7 真实请求契约与测试边界

- 正常客户端、测试 helper 和运维脚本均按契约带 `X-App-Ut`、`X-App-Version`、`X-Device`、`X-OS`；Web 平台为 `web`，不要自造 `ASTRAL_FRONT`。非法头测试明确标注负例。
- 同时判断 HTTP 状态与统一响应体 `code/msg/errorCode`。校验错误通常为 HTTP 200 + 320；`BusinessException.of(320, msg)` 可没有 errorCode，不能虚构 `COMMON002` 断言。路由/方法错误按真实 404/405 处理。
- 回归优先观察公开响应、持久化后的公开读取和实际投递参数；替身放在外部边界，不用 mock 掉要验证的事务/权限机制。
- 夹具编译、元数据缺失、错误预期和环境安装失败分开登记，不作为产品行为 RED。不得删断言、跳过用例或降低规则来取得通过。
- H2、PG、合成 DOM、HTTP、浏览器、外部生产系统各自声明范围。缺少 `audit.postgres.port` / `audit.redis.port` 导致 HTTP 测试跳过时，不得称完整验收通过。

### 11.8 可追溯交付与运行操作

- 交付固定提交、构建输入及证据归属。复用历史结果核对必要输入一致；不把旧 XML、后继覆盖报告或前端继承结果算成本次重跑。
- 打包不包含真实凭据/环境文件；发布使用同一批完整不可变镜像标签后再提升 latest，测试使用临时 Git/fake Docker，不触发真实发布。
- 启动前核对版本、配置、端口和数据目录；各服务分别检查实际可用性。只有后端运行时不能交付“前端可测”的结论。
- 停止服务核实 PID/可执行文件/目录/监听归属，退出后复查；数据与日志默认保留。执行策略拒绝如实记录，不改变工具或 shell 绕过。
- 静态告警逐项评估，不把“0 error”写成“0 warning”；未执行的浏览器、真实投递、部署验收保持明确边界。具体门槛见 [验证规范](VERIFICATION_GUIDE.md)。
