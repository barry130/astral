# 后端安全审计报告 · 认证 / 权限 / 系统管理域

- 基线：`a39a3f3`（11 模块 `compile` exit 0，由 Lead 统一执行）
- 审计域：`astral-auth`、`astral-system`、`astral-server` 的认证拦截与分页配置（其余 server 配置/异常/过滤器归 Lead）
- 日期：2026-11-07
- 作者：backend-chain（Team task-5）
- 证据等级：**静态审计 + 框架语义推导 + 纯单测**。本报告**不含**任何浏览器/生产环境/真实网络验证结论。

---

## 0. 结论摘要

| 编号 | 问题 | 严重度 | 状态 |
|---|---|---|---|
| S1 | Token 管理页与「我的会话」直接下发**明文会话 token**，`admin:system:token:view`（只读查看权限）即可接管任意账号（含超管） | 严重 | 已修 |
| S2 | 删除用户走逻辑删除且不吊销会话；拦截器只校验登录态、不校验账号状态，被删/被禁账号可**无限期**继续调用接口 | 严重 | 已修 |
| S3 | 拦截器用**原始 URI** 前缀判定认证区，矩阵参数（`/api/v1/admin;x/...`）可绕过管理端身份门禁；`/api/v1/app/user` 无尾斜杠会掉进 App 匿名区 | 高 | 已修 |
| S4 | 分页 `pageSize` 无上限，手写 `new Page<>` 的接口可被单请求拉全表 | 中 | 已修（全局兜底） |
| S5 | 字典/系统配置/邮件模板/短信模板 `updateById(客户端实体)` 存在 mass assignment | 中 | 已修 |
| S6 | 站内信门户接口无 `@RequiresPermission` | — | 复核为**非缺陷**（按登录人自身范围查询，身份取拦截器注入的 `userId`，不信任请求参数） |

未修复但已定级的残余项见 §6。

---

## 1. 覆盖清单（真实清点，非估算）

### 1.1 逐文件覆盖（本域主要入口）

| 文件 | 覆盖内容 | 方式 |
|---|---|---|
| `astral-auth/.../controller/ProfileController.java` | 改密/改资料/TOTP 绑定与关闭/我的会话/下线会话 | 全文件 |
| `astral-auth/.../controller/TokenController.java` | 会话分页、吊销、踢人、清理过期、全端重置 | 全文件 |
| `astral-auth/.../controller/AuthController.java`、`AllAuthController.java` | 登录、登录信息、公钥、刷新态 | 全文件 |
| `astral-auth/.../service/impl/AuthServiceImpl.java` | 登录、登录信息、失败计数、IP 解析 | 关键路径 |
| `astral-auth/.../service/impl/TokenServiceImpl.java` | 会话枚举、指纹化、吊销解析 | 全文件 |
| `astral-auth/.../security/*` | `PermissionCache`、`DataScopeResolver`、`LoginUserTypeResolver`、`LoginFailureStore`、`RsaKeyManager`、`TotpUtil`、`PasswordPolicy` | 全目录 |
| `astral-server/.../interceptor/AuthInterceptor.java` | 认证区判定、CSRF 双提交、管理端身份、账号状态兜底 | 全文件 |
| `astral-server/.../interceptor/RequestPathSupport.java` | 路径归一化与段边界比较（新增） | 全文件 |
| `astral-server/.../config/MybatisPlusConfig.java` | 分页上限 | 全文件 |
| `astral-system/.../controller/UserController.java` | 增删改、状态、角色、重置密码、`user_type` | 全文件 |
| `astral-system/.../controller/RoleController.java`、`PermissionController.java`、`MenuController.java`、`RolePermissionController.java`、`UserRoleController.java` | 角色/权限/菜单/关联写路径与提权面 | 全文件 |
| `astral-system/.../controller/DictController.java`、`SysConfigController.java` | 字典/配置写路径 | 全文件 |
| `astral-system/.../controller/MailTemplateController.java`、`SmsTemplateController.java`、`SmsProviderController.java`、`MailAccountController.java` | 模板/供应商/账户的密钥出参与写路径 | 全文件 |
| `astral-system/.../notify/*`（`NotifyPublisher`、`NotifyRuleController`、`InappServiceImpl`、`MessageTemplateService`）、`notice/*` | 通知投递、规则、站内信 | 关键路径 |
| `astral-system/.../controller/InappPortalController.java` | 门户站内信 | 全文件 |
| `astral-front/src/api/profile.ts`、`.../system/token/page.tsx` | 与 S1 相关的调用契约 | 只读比对 |

### 1.2 判据

- 权限码全部符合 `端:域:资源:操作[:范围]`，管理端接口均声明式标注（复审未发现控制器手写 `hasPermission`）。
- 提权面（角色/权限/用户角色/`user_type`/重置密码/表结构）均为 `@RequiresSuper`。
- 公开面（免登录）只有：精确白名单端点、`/api/v1/app/**` 非 user 区、`/api/v1/all/storage/worker|origin`（HMAC）。无宽前缀放行。

---

## 2. S1 · 明文会话 token 外泄（严重，已修）

**证据（修复前）**
- `TokenServiceImpl.pageFromSaToken`：`TokenInfo.builder().id(tokenValue).token(tokenValue)` —— 直接把 Sa-Token 值放进出参。
- 暴露面：`GET /api/v1/admin/system/token/page`（`admin:system:token:view`，**只读**权限）、`GET /api/v1/admin/profile/sessions`。

**危害链**：`admin:system:token:view` 是「只读查看」语义，实际持有者可用返回的明文 token 直接冒充该会话属主（token 即凭据，无二次因子），包括超级管理员 ⇒ 一个只读角色退化为全员账号接管；也使「把 `token:edit` 下放」的前提失效（view 已足够）。

**修复**
- 新增 `astral-auth/.../security/TokenFingerprint.java`：`SHA-256(token)` 截断 128 比特、32 位小写十六进制；`looksLikeFingerprint` 校验形态。
- `TokenInfo.id` / `TokenInfo.token` 均改为指纹（字段名保留，避免前端契约破坏）；抽取 `TokenServiceImpl.toSessionInfo(...)` 作为唯一组装点。
- `revokeToken(sessionId)` 先由指纹反查真实 token 再 `logoutByTokenValue`；非指纹入参视为未命中，幂等返回成功。
- `DELETE /api/v1/admin/profile/sessions/{sessionId}`：路径参数语义改为指纹（路径会进 access log / 代理日志 / 浏览器历史，不得承载凭据），并在 logout 前校验**归属**。

**前端契约影响**：`astral-front/src/api/profile.ts` 与 `system/token/page.tsx` 原本就把 `token`/`id` 原样回传，因此**零改动**；唯一可见差异是 Token 列显示指纹。此结论已交 Lead 转业务前端同步文案。

**回归**：`TokenFingerprintTest`（SHA-256 已知向量、稳定性、形态、明文不被接受）、`TokenServiceImplSessionInfoTest`（DTO 零明文、其余展示字段不丢）、`ProfileControllerRevokeTest`（越权下线被拒且不 logout；未知/失效会话被拒；本人会话精确 logout）。

---

## 3. S2 · 被删/被禁账号的会话永不失效（严重，已修）

**证据**
- `UserController.delete`（修复前）：`userService.removeById(id)` 为**逻辑删除**，仅改 `sys_user.deleted`；未调用 `StpUtil.kickout`，`sys_user_role` 也不级联删除。
- `AuthInterceptor` 只判 `StpUtil.isLogin()`；`sa-token.active-timeout=-1` 关闭闲置过期、token 每次请求滑动续期 ⇒ 被删账号的 token **不会自然过期**，权限继续按 `sys_user_role` 解析。

**修复（两条路径，互补）**
1. 即时路径：`UserController.delete` 调 `StpUtil.kickout(id)`；`changeStatus` 原有 kickout 保留。
2. 兜底路径：`AuthInterceptor` 在管理端区与 App 用户区插入账号状态门禁 —— `LoginUserStateResolver.isActive(userId)`：
   - 只 `select id, status` 两列（**不加载 password / TOTP secret**），MyBatis-Plus 逻辑删除插件自动追加 `deleted=0`；
   - 查不到（=逻辑删除/不存在）或 `status=0` → `StpUtil.logout()` + 401；
   - 30 秒短 TTL 内存缓存（上限 10000 条），`evict/evictAll` 供显式路径调用。

**fail-closed 语义（按 Lead 指令修正）**：状态查询异常时**拒绝本次请求** —— `IllegalStateException` → HTTP 503 + `COMMON001`，**不吊销、不踢出**会话（瞬时 DB 故障不应导致全体重新登录）；日志只留「用户 id + 异常类型」，不打印 SQL/凭据。异常不写缓存，故障恢复后立即回源。

**生效延迟（如实记录）**：显式删除/封禁走 kickout 即时生效；**直接改库**（含逻辑删除）最迟 30 秒生效。这是刻意取舍：不为此每请求查一次库。

**回归**：`LoginUserStateResolverTest`（查不到→不可用、`status=0`→不可用、`status=1`/`null`→可用、30s 缓存只查一次、`evict` 后回源、**异常→抛 `IllegalStateException` 且不缓存不静默放行**）。

---

## 4. S3 · 原始 URI 判定导致管理端身份门禁被绕过（高，已修）

**证据（修复前）**：`preHandle` 用 `request.getRequestURI()` 原串 `startsWith("/api/v1/admin")`。Spring MVC 路由前会剥离矩阵参数，`/api/v1/admin;x/system/user/page` 命中 `admin/**` 控制器，但原串不满足 `startsWith("/api/v1/admin/")` ⇒ 管理端 `user_type=ADMIN` 门禁整段跳过；登录态仍被要求，所以任何自助注册的 APP 用户登录后即可直连管理端接口（具体可用性取决于该接口自身权限码，属「少一道门」而非「无门」，但安全边界已破）。同类漏判：`/api/v1/app/user`（无尾斜杠）掉进 App 匿名区。

**修复**
- 新增 `RequestPathSupport`：`normalize`（去查询串/片段、剥矩阵参数、折叠重复斜杠、去尾斜杠、保证前导 `/`，**不做百分号解码**）与 `under`（**路径段边界**比较，容忍前缀尾斜杠）。
- 拦截器优先取 `HandlerMapping.LOOKUP_PATH_ATTRIBUTE`（Spring 已剥离 contextPath、矩阵参数、重复斜杠），并以 `BEST_MATCHING_PATTERN_ATTRIBUTE` 复核；两者都取不到才回退 `normalize(rawUri)`。
- 区域判定抽出 `AuthInterceptor.classify(lookupPath, matchedPattern) → RequestZone`，顺序：精确白名单 → App 公共区（排除 user 区）→ storage 服务身份 → App 用户区 → 管理端区 → 其余需登录区。
- **未引入 App 显式匿名名单**：已发布的客户端路由清单未核实，无证据改窄会断公开接口；仍按区域放行 + 段边界排除 user 区（App 公共区语义与修复前一致，报告不宣称其安全性提升）。

**回归**：`RequestPathSupportTest`（矩阵参数/重复斜杠/尾斜杠/查询串/根路径；段边界：`/api/v1/adminfoo`、`/api/v1/app/users` 不误判）、`AuthInterceptorZoneTest`（**路由级**：矩阵绕过形态归一后落 `ADMIN`、无尾斜杠 `/api/v1/app/user` 落 `APP_USER`、storage 服务区窄口径、`/api/v1/all/**` 是需登录区、Spring 命中模式单独也能支撑判定）。

---

## 5. S4 / S5（中，已修）

### S4 分页上限
- 证据：`PermissionController:58`、`DictDataController:62`、`MailLogController:42`、`SmsLogController:44`、`RolePermissionController:52`、`UserRoleController:52`、`AdminNoticeController:66-67`、`NoticeServiceImpl:154` 等手写 `new Page<>(pageNum, pageSize)` 无上限。
- 修复：`MybatisPlusConfig` 里 `PaginationInnerInterceptor.setMaxLimit(PageQuery.MAX_PAGE_SIZE)`（**引用 `PageQuery` 常量，不引入新的魔法数**）。语义为「超大 pageSize 被截断到上限」，不报错（与既有客户端兼容）。
- 残余：`TokenServiceImpl.pageFromSaToken` 是手工分页（不走拦截器），已用 `PageQuery.pageNum/pageSize` 夹取。

### S5 mass assignment 白名单
- 证据与修复（`updateById(客户端实体)` → `lambdaUpdate().eq(id).set(白名单)`，逐字段判 null）：
  - `DictController.update`（dict 数据 9 个可编辑列）
  - `SysConfigController.update`（config 名称/键/值/类型/说明）
  - `MailTemplateController.update`（模板 8 列；保留原有 `validateTemplate` 校验）
  - `SmsTemplateController.update`（短信模板 9 列；保留原有 `validateTemplate` 校验）
  - `UserController.update`（只写 `username/nickname/email/phone/avatar`；剥离 `password`、`pwd_update_time`、`totp_secret`、`totp_enabled`、`user_type`、`deleted`、`status`、`status_reason`、`must_change_password`、`device_id`、`login_ip`、`login_time`、`create_time`）
  - `RolePermissionController.update`（`roleId`/`permissionId`）
  - `UserRoleController.update`（`userId`/`roleId`）
- 对 `@RequiresSuper` 的接口同样适用该白名单：规范要求「下放写权限前剥离提权字段」，super 只是现有持有者的最小集合，不代表可以接受整行覆盖（避免将来把接口降级为业务权限时遗留后门，也避免客户端误传内部列）。
- 依据：MP 3.5.17 默认 `updateStrategy=NOT_NULL`，`updateById` 会把实体上**所有非 null 字段**写进 SET；「置 null 剥离」不成立（null 被跳过），「先查后回填」不可靠（可能读到会话缓存旧值）。

---

## 6. 已定级但未修 / 非缺陷（如实记录）

| 项 | 判定 | 理由 |
|---|---|---|
| `sys_user_role` 不随逻辑删除级联 | 残余（低） | 只遗留孤立关联行；ID 不复用、重新登录被登录态/状态门禁挡住，不构成权限路径 |
| App 公共区按区域放行（非名单） | 设计现状 | 无已发布路由清单，改窄有断客户端风险；如需收紧须由 Lead 提供清单 |
| 站内信门户 `/api/v1/all/notify/inapp/**` 无权限码 | 非缺陷 | 身份取拦截器注入 `userId`，仅查/改自己的收件箱，`markRead` 带 user 维度（`InappServiceImpl`） |
| 登录/刷新接口返回 token | 非缺陷 | 返回的是**调用者本人**刚签发的凭据，登录语义必需；S1 修复后没有任何接口返回他人的 token |
| `NotifyRuleController.update`、`MessageTemplateService` 的 `updateById` | 非缺陷 | 由服务端从 DTO 重建实体（只 set 已知字段），非客户端实体直传 |
| 邮件/短信/通知密钥出参 | 复核通过 | `access_secret`/password 类字段出参抹除、编辑留空不改（本轮复核 `MailAccountController`、`SmsProviderController` 保持原样；未做代码变更） |

---

## 7. 回归测试与验证命令（由 Lead 串行执行）

新增测试（纯单测，不起 Spring 上下文）：

| 测试 | 模块 | 覆盖 |
|---|---|---|
| `TokenFingerprintTest` | astral-auth | 指纹=SHA-256 前 128 位（已知向量）、稳定唯一、形态、明文拒绝 |
| `TokenServiceImplSessionInfoTest` | astral-auth | **DTO 零明文凭据**、其余展示字段保留 |
| `LoginUserStateResolverTest` | astral-auth | 删除/禁用→不可用、缓存去重、evict 回源、**DB 异常→fail-closed 且不缓存** |
| `ProfileControllerRevokeTest` | astral-auth | **下线会话归属校验**：他人会话/未知/失效被拒且不 logout；本人精确 logout |
| `RequestPathSupportTest` | astral-server | 归一化与段边界 |
| `AuthInterceptorZoneTest` | astral-server | **路由级**认证区判定（矩阵绕过、尾斜杠、兄弟前缀、白名单精确匹配） |
| `AuthInterceptorPreHandleTest` | astral-server | **真实 `preHandle` 执行路径**（`mockStatic(StpUtil)` + 桩 Resolver）：查询异常→503/`COMMON001`/零 logout；禁用/删除→200+401 且 logout 恰好一次（管理端）或按 token 精确登出（App 区）；身份门禁 APP→403/`COMMON005`、ADMIN→放行并写 userId |

```bash
mvn -o -q -pl astral-auth -am -DskipTests compile
mvn -o -pl astral-auth test -Dtest=TokenFingerprintTest,LoginUserStateResolverTest,TokenServiceImplSessionInfoTest,ProfileControllerRevokeTest
mvn -o -pl astral-server -am test -Dtest=RequestPathSupportTest,AuthInterceptorZoneTest,AuthInterceptorPreHandleTest -DfailIfNoTests=false
```

- 新增测试依赖（`astral-auth/pom.xml`，`test` scope、版本走 spring-boot-dependencies）：`junit-jupiter`、`mockito-core`。
- `ProfileControllerRevokeTest` 用 `Mockito.mockStatic(StpUtil.class)` 直接驱动真实控制器方法（inline mock maker，Mockito 5 默认）。

**未验证项（诚实声明）**：本波次未编译、未跑测试（Maven 由 Lead 统一执行）；未做任何真实 HTTP/浏览器验证；`MockedStatic` 依赖 JDK 25 上 inline mock maker 可用（若失败请回退为「归属判定抽纯函数」的方案，测试断言不变）。

---

## 8. 变更文件清单（写范围 freeze）

`astral-auth`：`security/TokenFingerprint.java`（新）、`security/LoginUserStateResolver.java`（新）、`security/LoginUserTypeResolver.java`、`dto/TokenInfo.java`、`service/TokenService.java`、`service/impl/TokenServiceImpl.java`、`controller/ProfileController.java`、`pom.xml`（测试依赖）、`src/test/java/...`（4 个测试类）

`astral-system`：`controller/UserController.java`、`DictController.java`、`SysConfigController.java`、`MailTemplateController.java`、`SmsTemplateController.java`

`astral-server`：`interceptor/AuthInterceptor.java`、`interceptor/RequestPathSupport.java`（新）、`config/MybatisPlusConfig.java`、`src/test/java/...`（2 个测试类）

**未触碰**：`astral-server` 其余配置/异常处理器/过滤器（归 Lead）、`astral-front`、`astral-plugin`（qt/storage/feedback）、`pom.xml`（除 astral-auth 测试依赖）、任何迁移脚本。
