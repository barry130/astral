# astral 全代码审阅报告（2026-10-07）

> **审阅方式**：只读代码。审阅过程中**刻意不查 git 提交记录**，也**刻意不参考** `docs/CODE_REVIEW_20260930.md`
> 与 `logs/codereview/CODE_REVIEW_2026-09-28.md` 两份既有审查文档，避免先入为主。所有结论均可由当前代码直接复现。
> **覆盖范围**：`astral-server` / `astral-auth` / `astral-system` / `astral-sequence` / `astral-common` / `astral-dao` /
> `astral-schema` / `astral-log` / `astral-monitor` / `astral-plugin`（qt + storage + feedback）/ `cloudflare/storage-worker` /
> `astral-front`（重点在 `src/api/client.ts` 出口与权限相关页面）。
> **行号说明**：文中行号为**审阅当时**的版本；标注「已修」的条目在修复后行号已发生变化。

---

## 一、总体评价

整体架构清晰，**安全意识明显高于一般项目**：声明式权限、可信代理 IP 解析、fail-closed 设计（授权留空即拒绝、
密钥缺失即拒绝）、Redis 原子操作 + Lua 脚本、白名单式字段剥离，这些都不是偶然写对的，代码注释里能看出是被踩过之后固化下来的。
**未发现 SQL 注入、未发现提权后门、未发现机密硬编码。**

但确实存在 2 个中高危漏洞（邮箱验证码爆破、头像上传存储型 XSS），以及若干设计/一致性问题。
按项目规范（`AGENTS.md`）逐条核对后，五条强制约束**未发现硬违规**，仅 1 处曾被误判、1 处属边界情形（见 §3）。

---

## 二、安全发现（按严重度排序）

### 2.1 🔴 高 — 邮箱验证码可暴力枚举 → 任意账号接管（**未修复，2026-10-07 决定暂缓**）

- `astral-plugin\src\main\java\com\astral\qt\service\QtUserService.java:343`（`getValidCode`）：直接把验证码
  从 Redis `get` 出来返回，**没有任何尝试次数计数**；验证码是 **6 位纯数字**（`QtUserService.java:312`），
  TTL 长达 **10 分钟**（`QtUserService.java:89` 的 `CODE_TTL`）。
- `astral-plugin\src\main\java\com\astral\qt\controller\QtAppUserController.java:226`（`POST /api/v1/app/user/changePass`）：
  **无 `@RateLimit`、无失败锁定、无登录日志**。对照同文件 `:86` 的登录接口（IP 限流 `@RateLimit(key="ip", limit=5, duration=60)`
  + `LoginFailureStore` 计数锁定 + 登录日志三件套），改密接口一件都没有。
- **后果**：只要知道目标邮箱，即可对验证码做无限次尝试（10⁶ 空间 / 10 分钟，可并发 + 多 IP 分布式），
  命中后直接重置任意账号密码，等价于**账号接管**；对 App 用户体系而言也是全量风险。
- **修复建议**：
  1. 验证失败计数归验证码所有（同一验证码 5 次失败即作废 + 触发重新发送冷启动）；
  2. `changePass` 与登录口径对齐：加 IP 限流 `@RateLimit`，并把校验失败接入 `LoginFailureStore` 同款锁定；
  3. 验证码位数/TTL 可按风控收紧（如 6 位 / 5 分钟），并要求失败重发走同一限流。

### 2.2 🟠 中 — 存储型 XSS：头像上传无扩展名白名单 + 同源公开静态服务（**已修：接口整体下线，2026-10-07**）

- 原始问题：`QtUserService.upload`（`/api/v1/app/user/upload`）仅取原始文件名小写扩展名，**无白名单、无 magic 校验**；
  `QtWebConfig` 把 `/files/qt-upload/**` → `./data/qt-upload/` 作为**公开静态服务**，且该路径在 `/api/v1` 之外，
  `AuthInterceptor` 完全不覆盖。上传 `.html` / `.svg` 后可在**站点同源**下渲染执行。
- **处理方式（用户决策）**：该接口在三个客户端（qt-pc / qt-uniappx / astral-front）**零引用**，
  直传链路（`/avatar/ticket` + `/avatar/complete`，走 storage 文件夹策略 `upload_policy`）已完全取代它。
  故**直接删除**，不做加固：
  - 删除 `QtAppUserController.upload`（`/upload`）与 `QtUserService.upload`；
  - 删除 `QtWebConfig`（其唯一职责就是映射上述静态目录）与孤儿 DTO `QtDataVo`；
  - 清理 `QtMediaService` 中引用旧 `/files/qt-upload` 的注释。
- **结论**：攻击面随接口一同消失，优于「加白名单 + magic」的加固方案（后者仍需维护一条与直传链路重复的旧路径）。

### 2.3 🟠 中 — 存储 Worker 下载与永久链接同样允许 SVG inline

- `StorageWorkerController` 层没有 mime 白名单限制 `svg`；`detectImageMime`（`StorageFileService.java:431-449`）**不认 svg**；
  Worker 允许 `image/*`（`cloudflare\storage-worker\worker.js` 的 mime bypass）。
- CDN 域独立时风险被限制在文件域，但**签名 URL 的 CORS 为 `*`**（`worker.js:35`），意味着任意页面都可以带签名读取文件内容。
- **修复建议**：下载/永久链接统一走 `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff`，
  或把 `svg` 排除在白名单外；同时把签名 URL 的 CORS 收敛到已知前端域。

### 2.4 🟠 中 — `/api/v1/app/**` 宽前缀匿名 + 权限切面对未登录静默放行（**经确认＝既定设计，不再视为缺陷**）

- `astral-server\src\main\java\com\astral\server\interceptor\AuthInterceptor.java:124`：
  `uri.startsWith(APP_PUBLIC_PREFIX) && !uri.startsWith(QT_USER_PREFIX_NEW)` → 非 user 子树**匿名放行**。
  写法与同文件 `:115-117` 注释中明确移除的宽前缀白名单模式一致：今后任何新控制器落在 `/api/v1/app/` 下都默认匿名。
- `astral-auth\src\main\java\com\astral\auth\security\PermissionAspect.java:69`：`checkPermission` 中
  `!StpUtil.isLogin()` 直接 `return` —— 未登录时 `@RequiresPermission` **不生效、不抛 401**。
- 两者叠加在此区域形成「双重失效」：即使控制器标了 `@RequiresPermission`，未登录请求也不会被拒。
- **结论（用户 2026-10-07 确认）**：**默认放行无问题，就这样设计的**。App 端由 qt 插件自己的 Bearer 拦截器
  负责鉴权（App 用户已并入宿主 `sys_user`，`user_type='APP'`，统一走 Sa-Token）。
  保留此条仅作**设计记录**：该模式下新增 App 接口必须自行确认是否落在受保护子树内。

### 2.5 ✅ 已修 — 存储删除任务无租约回收，Worker 崩溃后永久卡死 `RUNNING`

- **问题**：`astral-plugin\src\main\java\com\astral\storage\service\StorageFileService.java` 的 `pullDeleteTasks`
  把任务标记为 `RUNNING` 后没有租约/超时回收。Worker 拉取后崩溃且没有 `ack`，该任务将**永远停在 `RUNNING`**，
  既不会被重试也不会进入 `DEAD`，远端对象永不删除（`STORAGE_REQUIREMENTS.md` A40 的失败上限形同虚设）。
- **修复（`StorageFileService.java`）**：
  - 新增 `private static final long DELETE_TASK_LEASE_MINUTES = 10;`（`:644`）；
  - 拉取前先回收 `RUNNING` 且 `updateTime < now - 10 分钟` 的行（`LIMIT 100`，`:657`），
    统一走 `ackDeleteTask(task.getId(), false, "lease expired: worker did not ack within " + DELETE_TASK_LEASE_MINUTES + " minutes")`（`:662`），
    从而**复用既有 2ⁿ 分钟退避 + `MAX_DELETE_RETRY=5` → `DEAD`** 的失败路径，不新增状态机；
  - `PENDING → RUNNING` 改为 CAS 认领（`LambdaUpdateWrapper` 上 `.eq(id).eq(status, PENDING).set(status, RUNNING).set(updateTime, now)`），
    仅当 `rows == 1` 才把任务返回给 Worker，避免两个 Worker 拿到同一任务。

### 2.6 ✅ 已修 — `sys_storage_file.upload_id` 的 check-then-insert 竞态可产生重复行

- **问题**：`StorageFileService.registerFromCallback` / `registerFromBrowser` 都是「先 `selectOne(upload_id)` 判重、再 `insert`」，
  而 `sys_storage_file.upload_id` **只有普通索引**（`astral-server\src\main\resources\db\migration\V20260914001__init.sql:1628`
  的 `idx_storage_file_upload`，列定义见 `:1608` `upload_id VARCHAR(48)`），并发回调（浏览器直传回调 + 服务端轮询同时到达）
  可插入重复行，导致文件记录重复、后续删除/配额统计错乱。
- **修复**：
  1. 服务层加数据库级互斥：`StorageFileMapper` 新增
     `@Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))") String lockUploadKey(@Param("key") String key);`
     （`:25`，沿用 `QtLikeSyncMapper#lockUser` 的「void 返回型 PG 函数用 String 接收」写法），
     两个 register 入口在幂等查询前分别调用 `fileMapper.lockUploadKey("storage-upload:" + uploadId)`（`StorageFileService.java:159`、`:219`）；
  2. 数据库层加唯一约束兜底：新增迁移
     `astral-server\src\main\resources\db\migration\V20261007001__storage_file_upload_id_unique.sql` ——
     先按 `upload_id` 去重（保留 `max(id)` 一行；其余置 `upload_id = NULL`、`status='DELETED'`、补 `deleted_time`/`update_time`，
     **故意不投递远端删除任务**，因为重复行指向同一个远端对象），再 `DROP INDEX IF EXISTS idx_storage_file_upload`
     + `CREATE UNIQUE INDEX IF NOT EXISTS uq_storage_file_upload ON sys_storage_file (upload_id)`；
  3. 同步登记元数据：`astral-plugin\src\main\resources\schema\sys_storage_file.json` 的索引项改为
     `uq_storage_file_upload` 且 `"isUnique": true`；生成器脚本 `scripts\gen-plugin-schema-json.py:225` 同步。
- **注意**：历史迁移 `V20260914001__init.sql` **不可修改**（Flyway checksum），因此唯一索引只能靠新迁移落地。

### 2.7 ✅ 已修 — `clientIp` 口径不一致（可伪造 `uploader_ip`）

- **问题**：`astral-plugin\src\main\java\com\astral\qt\service\QtMediaService.java:346-359` 的 `clientIp()`
  **无条件采信 `X-Forwarded-For` 第一段**，客户端可任意伪造，导致落库的 `uploader_ip` 失去取证价值、
  按 IP 的风控形同虚设；而项目其他位置已统一使用 `com.astral.common.util.ClientIp#resolve`（可信代理白名单 + 从右往左跳代理）。
  该文件里通过 `@Value` 注入的 `trustedProxies`（`QtMediaService.java:52`）**此前从未被使用**。
- **修复**：`QtMediaService.clientIp` 改为
  `ClientIp.resolve(request.getHeader("X-Forwarded-For"), request.getHeader("X-Real-IP"), request.getRemoteAddr(), trustedProxies)`
  （`QtMediaService.java:356-360`），与全项目口径对齐。
- **现状（全项目已收口的 9 处调用点）**：`QtMediaService`、`StorageUserController`、`AppStatController`、
  `AppFeedbackController`、`RateLimitInterceptor`、`UserLoginMarker`、`AuthServiceImpl`、`OperateLogAspect`、`LoginLogAspect`。

### 2.8 ⏸ 保持现状 — `UserController.getById` 返回 BCrypt 密码散列

- `astral-system\src\main\java\com\astral\system\controller\UserController.java:127` 返回完整 `User` 实体（含 `password` 散列），
  持有 `admin:system:user:view` 即可读取；而 `exportCsv` 明确不含散列，**口径不一致**。
- **结论（用户 2026-10-07 确认）**：只给管理权限、且是加密后的散列，**保持现状**。
  建议（非阻塞）：后续可顺手在出参置 null，与 `exportCsv` 口径统一。

### 2.9 🟡 低 / 设计类问题一览

| # | 问题 | 位置 | 处理 |
|---|---|---|---|
| 1 | `requestDelete` 在 `@Transactional` 内做网络 I/O，事务期间占用数据库连接 | `StorageFileService` | 未修（低） |
| 2 | RSA 私钥文件 `./data/rsa-key.pair` 未收紧文件权限；解密未显式指定 padding（默认 PKCS1，登录传输场景可接受；RSA 2048 合规） | `RsaKeyManager.java:68` | 未修（低） |
| 3 | `LoginFailureStore` 按 username 原样拼 key，未归一大小写/空白（与登录判定口径一致即可） | `LoginFailureStore.java:48` | 未修（次要） |
| 4 | 前端 token 存 `localStorage`，XSS 场景可被窃取（行业通用取舍） | `astral-front\src\api\client.ts:35` | 保持 |
| 5 | Worker `handleUpload` 用 `request.formData()`，实际会把整个请求体物化进 Worker 内存，与注释「正文不驻留内存」不符（20MB 上限双保险兜底） | `cloudflare\storage-worker\worker.js` | 未修（低，建议对齐注释） |
| 6 | `StorageConfigService.java:61` 自建 `ObjectMapper`（未复用全局配置） | `StorageConfigService.java:61` | 未修（次要） |
| 7 | `FeedbackService` 状态流转用 `updateById(全实体)` 回写，并发覆盖窗口小 | `FeedbackService.java` | 未修（低） |
| 8 | 默认可信代理含全部私网段；若后端直接暴露给内网客户端，XFF 伪造仍可能（当前 nginx 同机部署下正确） | `ClientIp.java:26-27` | 保持 |
| 9 | qt 邮件验证码存放于 Redis（key `qt:email:code:`，`QtUserService.java:91`），**不存在** `qt_email_code` 物理表 | —— | 见 §3 说明 |

---

## 三、AGENTS.md 五条强制约束逐条核对

| # | 约束 | 结论 | 依据 |
|---|---|---|---|
| 1 | 接口路径三层前缀 `admin/app/all` | ✅ 合规 | `admin` / `app` / `all` 均正确落位（原 `/files/qt-upload/**` 静态映射已于本次清理一并删除） |
| 2 | 新建表纳入表结构管理 + 对应 schema JSON | ✅ 合规（**一处曾误判，已澄清**） | 插件表 schema JSON 齐全（qt_app_update / qt_github_accel / qt_like_* / qt_user* / qt_source_* / sys_feedback* / sys_storage_*）；**审阅中曾把 `qt_email_code` 判为「有表无 JSON」，复核后确认是误判**：全仓库不存在 `CREATE TABLE qt_email_code`，运行期验证码只存 Redis（`QtUserService.java:91`）；当时看到的 8 行 `COMMENT ON COLUMN qt_email_code.*` 是历史残留（被 `ResourceDatabasePopulator.setContinueOnError(true)` 静默吞掉），本轮已清理 |
| 3 | 前端枚举值走数据字典 | ✅ 基本合规 | 抽查 storage / feedback 渠道枚举均走 `fetchDictOptions` / 字典表 |
| 4 | shadcn/ui + 请求统一走 `src/api/client.ts` | ✅ 合规 | `src\api\client.ts` 为唯一出口（仅对象存储预签名直传走原生 `fetch`，符合规范例外） |
| 5 | 声明式权限 + 提权接口 `@RequiresSuper` + 降级写接口字段剥离 | ✅ 合规 | 全部控制器走 `@RequiresPermission`/`@RequiresSuper`，未见手写 `hasPermission` 抛异常；`RoleController` create 强制 `isSuper=0`（`:92`）、update 用 `lambdaUpdate` 白名单逐字段判 null（`:117-137`）；`UserController.update` 置 null 敏感字段（`:163-169`）；提权类接口（用户增删/分配角色/重置密码/改 `user_type`/表结构写）均 `@RequiresSuper` |

---

## 四、值得肯定的实现

- **权限缓存**（`astral-auth\src\main\java\com\astral\auth\security\PermissionCache.java:68-71`）：
  全局版本号 + 随机 UUID 版本（避免并发写互相覆盖）+ 5 分钟超龄兜底，降级回源不影响鉴权正确性。
- **角色写接口白名单剥离**（`astral-system\...\controller\RoleController.java:117-137`）：
  注释明确记录了 MyBatis-Plus 3.5.17 `updateById` 会把显式 null 写进 SET 子句的陷阱，实现正确。
- **邮件服务**（`astral-system\...\mail\MailServiceImpl.java`）：用户变量 HTML 转义（`:411-425`，防品牌仿冒/邮件 XSS）、
  Redis 原子配额 + Lua 回补（`:169-181`，防「凭空 -1」常驻 key）、fail-closed 授权（MAIL018，`:196`）、先渲染后占额度（不白扣配额）。
- **客户端 IP 解析**（`astral-common\...\util\ClientIp.java:41-71`）：可信代理 + 从右往左跳代理，注释讲清了为什么不能直信 XFF。
- **登录失败锁定**（`astral-auth\...\security\LoginFailureStore.java`）：Redis 计数为主 + 内存兜底，语义完整的降级。
- **存储 Worker HMAC 层**：常量时间比较、密钥缺失 fail-closed、nonce 防重放、`contentVersion` 版本化缓存失效。
- **全局异常处理**（`astral-server\...\exception\GlobalExceptionHandler.java:204-220`）：
  真实 HTTP 状态码 + 兜底 500 固定文案不泄露堆栈 + 异步错误登记。

---

## 五、处理结论与优先级

### 本轮已修（2026-10-07）

| 项 | 改动文件 |
|---|---|
| 删除任务租约回收 + CAS 认领 | `astral-plugin\src\main\java\com\astral\storage\service\StorageFileService.java` |
| `upload_id` 数据库级互斥 | `astral-plugin\src\main\java\com\astral\storage\mapper\StorageFileMapper.java` |
| `upload_id` 唯一索引迁移 | `astral-server\src\main\resources\db\migration\V20261007001__storage_file_upload_id_unique.sql`（新增） |
| schema JSON 索引同步 | `astral-plugin\src\main\resources\schema\sys_storage_file.json` |
| 生成器脚本同步 | `scripts\gen-plugin-schema-json.py` |
| `clientIp` 口径统一 | `astral-plugin\src\main\java\com\astral\qt\service\QtMediaService.java` |
| 清理 `qt_email_code` 残留注释 | `astral-plugin\src\main\resources\sql\qt-schema.sql` |
| 启动日志表名修正 | `astral-plugin\src\main\java\com\astral\qt\config\QtSchemaInitializer.java` |
| 下线零引用头像上传接口（§2.2 攻击面消除） | `astral-plugin\src\main\java\com\astral\qt\controller\QtAppUserController.java`、`...\service\QtUserService.java`、`...\config\QtWebConfig.java`（删除）、`...\dto\vo\QtDataVo.java`（删除） |

编译校验：`mvn -pl astral-plugin -am compile -DskipTests` → **BUILD SUCCESS**（JDK 25 / Maven 3.9.6）。

### 经确认的既定设计（不再视为缺陷）

1. `/api/v1/app/**` 宽前缀匿名放行（`AuthInterceptor.java:124`）+ 权限切面对未登录静默放行（`PermissionAspect.java:69`）。
2. `UserController.getById` 向 `admin:system:user:view` 返回 BCrypt 散列。

### 待办（按优先级）

1. **立即**：`changePass` 验证码尝试限制 + 接口限流/失败锁定（账号接管风险，§2.1）。
2. **短期**：Worker 下载/永久链接禁止 SVG inline + 收敛签名 URL 的 CORS（§2.3）。
3. **随缘**：事务内网络 I/O、RSA 私钥文件权限、Worker 注释与实现对齐、`UserController.getById` 出参置 null。

> §2.2（头像存储型 XSS）已通过**删除零引用旧接口**闭环，不再是待办；直传链路 `/avatar/ticket` + `/avatar/complete` 由 storage `upload_policy` 参数化管控大小/类型/次数。

---

## 附录 A：审阅覆盖范围

- **宿主**：`astral-server`（拦截器/异常处理/配置）、`astral-auth`（登录、Token、权限缓存、失败锁定）、
  `astral-system`（用户/角色/权限/菜单/字典/配置/Token/表结构/邮件）、`astral-sequence`（5 种生成器 + 号段对齐）、
  `astral-dao`、`astral-schema`、`astral-log`、`astral-monitor`、`astral-common`。
- **插件**：`astral-plugin` 的 `qt`（App 用户、媒体、点赞、打卡、版本更新）、`storage`（多供应商直传/签名/删除任务）、
  `feedback`（反馈与通知）。
- **其他**：`cloudflare/storage-worker`（Telegram 图床分发 Worker）、`astral-front`（API 出口与权限相关页面）。

## 附录 B：验证方法

- 全部结论来自静态阅读（`read`/`grep`/`glob`）+ 关键路径交叉验证（如 `upload_id` 索引在迁移中的定义、
  `qt_email_code` 全仓库检索、`ClientIp.resolve` 的调用点清点）。
- 唯一一次动态验证：`astral-plugin` 全链路编译（`mvn -pl astral-plugin -am compile -DskipTests`）确认修复不破坏编译。
- `V20261007001__storage_file_upload_id_unique.sql` **尚未在真实数据库上执行过**，由下一次启动时 Flyway 应用；
  其中去重语句按 `upload_id` 保留 `max(id)` 一行，执行前建议先备份。