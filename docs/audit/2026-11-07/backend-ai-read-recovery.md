# Backend AI 只读补读与剩余问题台账（task-14）

> 独立只读审阅，交付物仅本文件与同名 `backend-ai-read-recovery.json`。
> 未改任何 Java/Schema/测试/POM/共享 PLAN，未运行 Maven/服务/API/DB，未做 Git 写操作。

## 0. 版本与读取边界（必须先看）

| 项 | 值 |
|---|---|
| 基线 commit | `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`（`git rev-parse HEAD` 仍是它） |
| 工作区状态 | **脏**：Lead 正在 TDD 写 AI 权限链，AI 域有 4 改 1 新增（见下表） |
| 源码根 | `F:/qtMusic/astral`（只读 `astral-plugin/src/{main/java/com/astral/ai, test/java/com/astral/ai, main/resources/schema/ai_*.json}`） |
| 采集时间 | 2026-11-07T09:xx+08:00（见 JSON `generatedAt`） |
| 外部资料 | 本轮未引用外部项目；结论全部来自本地源码 |

Lead 并行改动（这些文件的结论都是**WIP 快照**，随时会变，不可当冻结基线）：

| 文件 | 行数 | sha256(16) | 与本审阅的关系 |
|---|---|---|---|
| `service/AiChatOrchestrator.java` | 1809 | `72F89D7D7F22305E` | 已新增执行期权限校验（`invoke` L564-612），R4/R13 针对此版本 |
| `common/AiJson.java` | 190 | `EDFADE5797C41B03` | 新增 `splitPermissionCsv`（L145-162 附近），R4 针对此版本 |
| `service/AiToolService.java` | 624 | `6BDEB1CBB1A2E5F0` | 仅审批下限 +5 行（L219-225），R5/R6 其余未修 |
| `test/.../AiToolServiceRiskFloorTest.java` | 171 | `85FA6550A9B7B0D7` | 新增 ADMIN_CONFIRM→USER_CONFIRM 用例（未运行） |
| `test/.../AiChatOrchestratorPermissionTest.java` | 176 | `1F98CB6806BD6848` | 新增（untracked），5 个用例（含“空存量权限不得清除注册 floor”），Lead 账本称 5/5 GREEN（本审阅未复验） |

> 本审阅**不宣称**这些文件已冻结或已通过测试；测试执行归 Lead 的 Maven lane。若 SHA 变化，本节与 R4/R13 需按新版本重读。

## 1. 计数复核（旧报告 107/15/24 需更正一项，且行数口径要先对齐）

行数有两种口径：**raw**=`(Get-Content).Count`（含空行）、**non-blank**=去掉空行。旧报告用的是 non-blank，本轮 JSON 清单记录的是 raw，两者不可混用。

| 类别 | 实测（worktree） | 旧口径 | 差异 |
|---|---|---|---|
| `com.astral.ai` main | **107 个文件；raw 20387 行 / non-blank 18492 行** | 107 / 18483（non-blank） | 文件数一致；non-blank 多 9 行 = Lead WIP 新增行 |
| AI tests | **16 个文件；raw 3145 行 / non-blank 2658 行** | 15 个文件 / 2658 行（non-blank） | **+1**：`AiChatOrchestratorPermissionTest.java`（Lead WIP，未 git add） |
| `ai_*.json` schema | **24 个文件 / 3424 行** | 24 | 一致 |

> 复核命令（可复现）：`Get-ChildItem -Recurse -File -Filter *.java <ai-root>` 后对每个文件取 `(Get-Content).Count` 求和；`git status --porcelain` 确认 AI 域仅 5 个文件被改（3 main + 2 test，其中 1 个 untracked）。

旧 `backend-ai-completion.md` §9.3 与 `backend-ai-coverage.md` 的覆盖陈述**不能直接沿用**：其中对 `AiChatOrchestrator`（当时 1787 行）的“全文已读”在本次工作区已变成 1809 行的 WIP，按 Lead 要求本轮降为 partial；其余文件若 sha256 与基线相同，最多算“前一轮全读、本轮未复读”。

## 2. 覆盖台账（本轮诚实口径）

统计：**本轮 full 5 个 main + 2 个 test**；**partial 15 个 main**；**prior-full（前轮读完、本轮未复读、哈希未变）9 个 main**；**unread 78 个 main**；**24 schema 全部 unread**（仅用作名称/行数盘点）。

### 2.1 本轮全读（1..N 连续读完）

- `common/AiJson.java`（基线 172 行全文 + WIP diff 全文 → 190 行整文件等效全读）
- `common/AiCaller.java`（87）
- `service/AiAccessService.java`（155）
- `service/AiToolService.java`（基线 623 全文 + WIP diff）
- `controller/AiAdminToolController.java`（299）
- `test/.../AiToolServiceRiskFloorTest.java`（基线 161 + diff）
- `test/.../AiChatOrchestratorPermissionTest.java`（176）

### 2.2 本轮部分读（只读了列出的连续区间）

| 文件 | 已读 | 未读 |
|---|---|---|
| `service/AiChatOrchestrator.java` | 1-146, 200-379, 430-619, 280-449（重叠）, 555-619；另 `git diff` 全文 | 147-199、620-1809（`findTool/toolSpecs/history/finalize/...`） |
| `controller/AiAppController.java` | 90-234, 240-309 | 1-89、235-239、310-377 |
| `tool/AdminToolProvider.java` | 755-1034、1036-1165 | 1-754（仅用 grep 提取构造点/权限码，正文未读） |
| `tool/declarative/SqlToolExecutor.java` | 1-215 | 216-441 |
| `common/AiConstants.java` | 118-177（RiskLevel/ApprovalPolicy/ToolStatus/ExecutorType） | 1-117、178-390 |
| `dto/AiAdminDtos.java` | 610-689 | 其余 ~800 行 |
| `tool/AiToolTypes.java` | 1-140 | 141-223 |
| `controller/AiAdminMcpController.java` | 仅权限/映射行（grep） | 方法体 |
| `controller/AiAdminProviderController.java` | 仅权限/映射行（grep） | 方法体 |
| `controller/AiAdminAgentController.java` | 仅权限/映射行（grep） | 方法体 |
| `controller/AiAdminChatController.java` | 仅权限/映射行（grep） | 方法体（513 行） |
| `service/AiProviderService.java` | 仅 baseUrl/secretRef/apiKey/test/resolveApiKey 行（grep） | 方法体 |
| `service/AiMcpServerService.java` | 仅 requireEndpoint/headers 行（grep） | 方法体 |
| `service/AiAgentValidationService.java` | 仅 L223-231 三条 scope 错误（grep） | 方法体 |
| `tool/declarative/HttpToolExecutor.java` | 仅 guardHost/headers/validate 行（grep） | 方法体 |

### 2.3 前轮全读、本轮未复读、哈希未变（可引用但未本轮复核）

`AiQuotaService`(534)、`AiUsageService`(214)、`AiAgentService`(546)、`AiEventService`(150)、`declarative/McpClient`(427)、`McpToolExecutor`(197)、`security/AiSecretCipher`(176)、`security/AiUrlGuard`(183)、`entity/AiToolEntity`(77)。
这些文件与基线 sha256 一致（本审阅只验证了“未被本轮工作区修改”），其内部结论标注为 `prior-report` 证据等级。

### 2.4 未读（78 个 main）

按包：`entity` 24、`mapper` 25、`service` 9、`config` 5、`provider` 5、`controller` 4、`tool` 2、`ai` 2、`common` 1、`dto` 1。完整路径清单见 JSON `coverage.unread`。
其中对结论影响较大的未读项：`service/AiKnowledgeService`(712)、`config/AiSeedInitializer`(564)、`provider/OpenAiCompatibleProvider`(483)、`controller/AiAdminRequestController`(356)、`service/AiRequestService`(370)、`tool/MusicToolProvider`(537)、`tool/QaToolProvider`(456)、`entity/AiToolCallEntity`（仅用 grep 看字段，未通读）。

### 2.5 测试与 schema

- 本轮全读 test 2/16；其余 14 个（`AiJsonTest` 112、`AiAgentValidationServiceTest` 556、`AiQuotaServiceTest` 345、`SqlToolExecutorTest` 277、`MusicToolProviderTest` 269、`HttpToolExecutorTest` 253、`McpClientTest` 221、`AiChatOrchestratorFormatTest` 161 等）**未读**，不评其覆盖质量。
- 24 个 `ai_*.json` **全部未读**：`ai_tool.json`(158) 的 `permissionCodes` 字段定义与 `AiToolService` CSV 存取是否一致，**本轮未能核实**（前一报告也把它列为待办）。

## 3. 剩余可复现问题（按危害排序）

证据等级：`源码`=本轮逐行读过；`grep+源码`=关键行读过、周边未读；`前轮`=引用既有报告且文件哈希未变。

### R1（high，源码）`test-run` 仍是未标注的超管级执行旁路，且不经过新的权限校验

- `controller/AiAdminToolController.java` L178-187：只有 `@RequiresPermission("admin:ai:tool:edit")`，**无 `@RequiresSuper`**；`@OperateLog("测试运行 AI 工具")` 存在（L180），所以“后台无留痕”不准确，但**不写 `ai_tool_call`**。
- `service/AiToolService.java` L358-383：`testRun(Long id, String argumentsJson)` 自建 `AiToolContext(null, null, null, null, "test-run", CUSTOM, ASTRAL_FRONT, args, snapshot, true, null)`（L370-379）并直接 `handler.execute(context)`：
  - `userId=null` → 新 `invoke` 的请求者权限校验完全不经过；
  - `approved=true` → `AdminToolProvider.HighRiskTool.execute`（L780-786）直接 `apply()`，HIGH 工具真实副作用（改配置/禁用用户/改角色/发公告/发邮件/删文件）**无需审批**；
  - 该路径不解析 `permission_codes`、不查 `registeredPermissions`、不写 `ai_tool_call`。
- 危害：任何持 `admin:ai:tool:edit` 的非超管（该码按 AGENTS 属“可下放”）都能构造/修改工具（含任意 HTTP/SQL）并立即执行任意 HIGH 工具，且审批人、原请求者、工具权限三者都不在场。这是当前唯一**同时绕过审批 + 绕过新权限链 + 无工具审计行**的入口。
- 建议：接口 `@RequiresSuper`；`testRun` 改为接收真实 operator（`AiCaller.requireUserId`），按工具 `riskFloor` 决定“禁止/返回 REQUIRE_APPROVAL/允许”，执行前跑与 `invoke` 同一套权限校验（抽公共方法，不要复制），补 `ai_tool_call(source=test-run)` 审计行，`approved` 由真实审批产生而非硬编码。

### R2（high，源码）`approve` 无 CAS：并发双审批会重复执行副作用工具

- `controller/AiAdminToolController.java` L202-243：`selectById` → 内存判 `status==WAITING_APPROVAL`（L206-208）→ `call.setStatus(RUNNING)` + `updateById`（L235-236）→ 执行 → 再 `updateById`（L243）。
- 判定与写入之间没有条件更新/版本号：`entity/AiToolCallEntity.java` L15-67 **没有 `@Version` 字段**，全部是 `updateById(entity)` 盲写。
- 两个并发 `POST /tool-calls/{id}/approve` 都能读到 `WAITING_APPROVAL` 并各自执行一次；对 `send_email`/`publish_notice`/`change_user_status`/`delete_file` 属真实双副作用。执行抛异常/进程崩溃后状态停在 `RUNNING` 或仍 `WAITING_APPROVAL`，重试同样重复执行（无幂等键）。
- 建议：条件更新 `UPDATE ai_tool_call SET status='RUNNING' WHERE id=? AND status='WAITING_APPROVAL'`（MyBatis-Plus `lambdaUpdate()` + 影响行数=1 才执行）；补 `@Version` 或状态机 CAS；执行前写 `started_at`，失败区分“可重试/不可重试”；对非幂等工具记录 execution id。

### R3（high，源码）审批内容 TOCTOU：批准的是旧计划，执行的是**当前**工具配置

- 计划期只把 `toolCode/toolName/riskLevel/argumentsJson` 落库（`AiToolCallEntity` L26-41），**没有 `executor_config` 摘要、没有 approval/risk 快照**。
- 审批期 `AiAdminToolController.java` L230-238 用 `resolve(versionId, clientType, toolCode)` **重新解析当前 EffectiveTool**（`AiToolService.resolveEffective` L584-612：risk=`strongerRisk`、approval 取 binding 覆盖、handler 取当前注册表），再 `orchestrator.invoke(...)`。
- 两次解析之间，`admin:ai:tool:edit` 可改 `ai_tool.executor_config`（HTTP url/body/headers、SQL 文本、MCP serverId/toolName）、`risk_level`/`approval_policy`（`update` L189-238）、binding 的 risk/approval（`addBinding` L533-567）。因此“管理员确认的 GET /a”可能实际执行被改成 POST /b 的配置；审批界面展示的 `riskLevel` 行也可能与真实执行风险不一致（`call.riskLevel` 是旧快照，`effective.risk()` 是新值，代码未比对）。
- 建议：审批时校验 `effective.tool().getExecutorConfig()` 哈希（或版本号）与计划期快照一致；不一致直接拒绝并要求重新计划；把 risk/approval/executor_config 哈希写进 `planPayload` 并展示给审批人。

### R4（medium-high，源码，WIP）新权限 CSV 解析器与真实权限契约不兼容，且是 ANY 语义

- `common/AiJson.java`（WIP）`splitPermissionCsv`：小写化后按 `(?:admin|user|all):[a-z0-9_-]+:[a-z0-9_-]+:[a-z0-9_-]+(?::[a-z0-9_-]+)*` 校验，不匹配**抛 `AI008`**。
- 不兼容的真实编码：① 超管 `*:*:*`；② 层级通配 `admin:system:user:*`；③ 3 段码（代码库真实存在 `admin:qt:admin`、`admin:demo:view` 等）；④ `user:qt:update:channel:beta` 可过，但含 `*` 的 DATA 权限不可表达。
- 执行期 `AiChatOrchestrator.invoke` L572-591：`registeredPermissions = splitPermissionCsv(joinCsv(handler.definition().permissionCodes()))`；`permissions = splitPermissionCsv(effective.tool().getPermissionCodes())`。只要注册定义里含上述任一码，**解析即抛异常 → 该工具对所有用户（包括超管）永久 `REJECTED("工具权限配置异常，已拒绝执行")`**，属功能性 DoS；反之 R5 的空码工具仍全放行。
- 语义：两处都用 `permissionChecker.hasAnyPermissionByLoginId(...)`（L582-583/L588-589）⇒ 多码是 **ANY**；而“工具声明多个资源权限”通常意味着需要**全部**资源（AGENTS `@RequiresPermission` 有 `Mode.ALL`）。当前没有任何注释/契约说明，属隐蔽语义选择。
- 另有实现瑕疵：`permissions.equals(registeredPermissions)`（L587）按**列表顺序**比较，同集合不同序会多跑一次检查（无害但说明“去重”逻辑不可靠）；`joinCsv→split` 往返只为复用解析，属不必要的绕行。
- 建议：解析器支持 `*` 段与 2-3 段短码，`*:*:*` 单独识别为“要求超管”（`isSuperUser`），非法仍 fail-closed；明确 ANY/ALL 并写进 `AiToolDef` 注释；`invoke` 直接对 `definition().permissionCodes()` 与 DB CSV 合并去重（`LinkedHashSet`）后一次性判定，不做 `equals`。

### R5（medium，源码/grep）binding 的 approval 仍无枚举校验、无 floor，可把“声明要确认”的工具降成自动执行

- `service/AiToolService.addBinding` L533-567：`riskOverride` 只校验“不低于 `tool.risk_level`”（L543-546）；`approvalPolicy` **只 `toUpperCase` 后直接落库**（L556-560），既无 `ApprovalPolicy.all().contains` 校验，也不与 `riskFloor` 的 approval 下限比较。
- `resolveEffective` L604-606：`approval = binding.approvalPolicy == null ? tool.approvalPolicy : binding.approvalPolicy`，原样进入 `EffectiveTool`；`requiresApproval`（`AiChatOrchestrator` L615-619）= `risk==HIGH || approval!=NONE`。
- 因此：**非 HIGH** 的工具（如代码工具声明 `DRAFT + ADMIN_CONFIRM`，或未来任何 `USER_CONFIRM` 工具）一旦被 binding 覆盖成 `NONE`，就**真的不再需要确认**（HIGH 工具仍被 risk 兜底，这一点旧报告已更正）。`AgentToolSaveReq.approvalPolicy` 只有 `@Size(max=24)`，任意字符串可入库，`requiresApproval` 会把垃圾值当“要确认”（fail-safe），但 `executeTools` L477-479 会把非 ADMIN_CONFIRM 一律记成 `USER_CONFIRM`，审计口径被污染。
- 建议：`ApprovalPolicy.order()`（NONE<USER_CONFIRM<ADMIN_CONFIRM，依据 `all()` 声明序）+ `addBinding`/`update` 统一“目标 order ≥ floor order 且 ≥ 工具当前 order”，非法值 `AI008`；`riskOverride` 同样按 floor（`riskFloor(entity)`）而非仅 DB 当前值比较。

### R6（medium，源码）`update` 的审批下限只对 HIGH floor 生效，非 HIGH 的声明确认仍可被清空

- `service/AiToolService.update` L219-225（WIP 修完 HIGH 分支）：`floorRisk==HIGH` 时要求目标=`ADMIN_CONFIRM`；但 `riskFloor` 对 CODE 返回的是 **handler 注解声明的 [risk, approval]**，对 `floorRisk!=HIGH` 的工具（如 `READ/DRAFT + USER_CONFIRM`）**完全不校验 approval**，`NONE` 可写入。
- 结论与 R5 同源：floor 是二元组，当前只用了 risk 维。修复应复用同一 `order()` 比较（Lead 已计划）。

### R7（medium，源码 partial）声明式 SQL 工具没有表/函数白名单，可读宿主敏感表

- `tool/declarative/SqlToolExecutor.java` L33-49 自述护栏：单条 SELECT/WITH、具名参数 `#{}` 走 `PreparedStatement`、`setReadOnly(true)`+rollback、`maxRows` 默认 50/硬顶 200、`columns` 显式声明、超时 5/15s；L60-92 关键字黑名单；L161-200 `requireValidShape`。
- **本次读 1-215 行未见任何 schema/table 白名单**（216-441 未读，结论标注 partial）；JdbcTemplate 是主数据源。⇒ 持 `admin:ai:tool:edit` 者可定义 `SELECT username,password FROM sys_user`（`columns` 由自己声明），让模型把结果吐给会话；同理 `sys_mail_account`、token 表、`system_config` 非敏感项等。
- 关键字黑名单只挡词边界内的字面量，**不拦函数调用**：`pg_read_file(...)`（若 DB 角色有权限）、`lo_import`、`dblink`/`postgres_fdw` 扩展若安装、`pg_sleep`（超时缓解）等均未被 `FORBIDDEN_WORDS` 覆盖；只读事务挡写，不挡读外泄。
- 建议：工具配置增加 `tables` 白名单（校验 SQL 解析后的表引用）或至少禁止 `pg_catalog/information_schema` 与宿主前缀表；对函数调用加允许列表；把结果列再按 `columns` 强校验（已做）+ 敏感列名黑名单（password/token/secret）。

### R8（medium，grep+源码）`provider:edit` 可把已存凭据重定向到任意公网端点（凭据外泄）

- `service/AiProviderService.java`：create/update 接 `baseUrl/apiKey/secretRef`（L96-148），`normalizeBaseUrl` 过 `AiUrlGuard`（只挡私网/环回，公网任意 host 放行，L310-316）；`resolveApiKey`（L241-279）解析 `encryptedApiKey` 或 `secretRef`（环境变量/密钥名）；`test(id, modelCode)`（L199-240）用 `entity.getBaseUrl()` + `resolveApiKey(entity)` 发起真实请求。
- 组合：持 `admin:ai:provider:edit`（controller L71-117，**非超管**）的非超管把 baseUrl 改成自己的 https 端点、保留/设置 `secretRef=OPENAI_API_KEY` 之类，然后调 `test` 或发起聊天 ⇒ 明文 apiKey 被发到攻击者域名。这等于“编辑供应商 = 读取环境密钥”。
- 建议：provider 建改升 `@RequiresSuper`（Lead 裁决已含）；`secretRef` 改名/改指向必须超管并审计；`test` 只允许用当前已存配置且记录目标 host；对 `secretRef` 解析值做长度/前缀脱敏日志。

### R9（medium，grep+源码）MCP server 的 headers 明文落库，且建改只需 `mcp:edit`

- `service/AiMcpServerService.java` L80-108：`McpClient.requireEndpoint(req.url(), allowedHosts())` 做了 URL 校验（正向），随后 `entity.setHeadersJson(blankToNull(req.headers()))` **原样落库**；对比 provider 的 `apiKey` 走 `AiSecretCipher.encrypt`，MCP headers 里的 `Authorization`/token 是明文，后台列表/导出/DB 备份即泄露。
- `controller/AiAdminMcpController.java` L63-97：create/update/refresh 全为 `admin:ai:mcp:edit`，无超管、无审计要求；可指向任意公网 MCP 端点并把本地 headers（含凭据）发过去。
- 建议：headers 中的敏感键（Authorization/api-key/token/secret）加密存储或改 secretRef；建改升超管或至少“凭据变更需超管”；出参脱敏。

### R10（medium，grep+源码）`AdminToolProvider` 全部 22 个工具权限码为空，且副作用真实

- `tool/AdminToolProvider.java`：16 处 `List.of()` 权限码（L135,172,205,236,273,305,358,410,454,501,558,601,644,693,741,811）；HIGH 基类 `high(...)` L796-815 同样 `List.of()`；`apply` 直接写库/发信：改配置 `configService.updateById`+`evictCache`（L893-897）、改用户状态 `userService.updateById`（L937-938）、整体换角色 `userRoleService.assignRoles`（L985）、删文件 `requestDelete`（L1017）、插公告 `noticeMapper.insert`（L1059）、发信 `notifyPublisher.publish`（L1097-1098）。
- 新 `invoke` 校验只看工具自身 permissionCodes：空 ⇒ **该层对 admin.* 工具完全无效**。会话入口 `AiAdminChatController` 用 `admin:ai:config:view` 覆盖“查看智能体/发消息/执行动作”等（L95,122,133,148,163,171,182,192,215,250,268,303,319），即任何能打开 AI 配置页（config:view）的管理员都能让管理员助手读系统日志/反馈/存储/配置摘要，并在 `admin:ai:tool:approve` 持有者确认后改配置/改用户角色/发公告/发信。
- 建议：逐工具给具体资源码（用已存在的：`admin:log:view`、`admin:feedback:view`、`admin:storage:view`、`admin:monitor:view`、`admin:system:config:view/edit`、`admin:system:user:view/edit`、`admin:system:permission:view`、`admin:message:edit`、`admin:ai:provider:edit`），`change_role_permission` 用 `*:*:*`（新解析器需支持，见 R4）；空码只保留纯数学/日期类工具。

### R11（low-medium，源码）审批审计口径混用常量，且不校验“确认策略是否匹配审批人”

- `AiAdminToolController.approve` L217-218：`approvalStatus = approved ? ApprovalPolicy.ADMIN_CONFIRM : ToolCallStatus.REJECTED` —— 把 `ApprovalPolicy` 与 `ToolCallStatus` 两个枚举写进同一列；对一条 `USER_CONFIRM` 调用，记录也永远是 `ADMIN_CONFIRM`；拒绝时该列变成状态值。列语义（`ai_tool_call.approval_status`）失守。
- 同方法未校验 `call` 原定策略：`USER_CONFIRM` 表示“应由原用户确认”，但接口只要求 `admin:ai:tool:approve`，任何审批者（包括非原用户）都能批准；也没有“审批人 ≠ 申请人”的隔离（对 ADMIN_CONFIRM 尚可，对 USER_CONFIRM 语义错）。
- 建议：`approval_status` 只存策略枚举，拒绝用单独 `decision`/`status` 表达；按 `call` 策略选择校验分支（USER_CONFIRM 要求 `approvedBy == request.userId`），ADMIN_CONFIRM 要求审核人具备对应工具的 `admin` 资源权限（与 R10 同一套码）。

### R12（low，源码）`resume` 的额度/用量预占与注释不一致，可能重复扣减

- `AiChatOrchestrator.run` L247-251：注释“额度预占（已预占过则跳过，resume 时复用）”，但代码无条件 `quotaService.reserve(request.getUserId(), agent, version, inputChars(request), limits)` 与 `usageService.reserve(...)`；`reserve` 签名不含 requestId，无法按请求识别“已预占”。`resume` 入口 L212；`approve` L248 执行完即 `resume`。
- 结论（需作者确认）：同一请求“计划→审批→恢复”会再预占一次；若配额按“用户+日”累计，恢复路径可能被自己的预占挤爆，或结算时双重扣减。属源码推断（`AiQuotaService` 本轮未复读，标注 prior-report 依赖）。
- 建议：预占带 `requestId` 幂等键，或 `run` 前查 `ai_usage`/`ai_request` 既有 reservation 并复用；补一条“resume 不重复扣减”的单测。

### R13（更正旧报告的三处口径）

1. **“HIGH + binding NONE ⇒ 自动执行”不成立**：`requiresApproval`（L615-619）先判 `risk==HIGH`，而 risk 经 `strongerRisk` 只抬不降，故 HIGH 工具无论如何都要确认。真实的缺陷是 R5/R6 的 **floor 不变量丢失**（非 HIGH 的声明确认可被清空）+ 元数据任意字符串，不是 HIGH 绕过。
2. **test-run “不写审计”不准确**：`@OperateLog` 存在（L180），有操作日志；缺的是 `ai_tool_call` 行与审批/请求者上下文（R1）。
3. **旧覆盖数字沿用风险**：`AiChatOrchestrator` 已从 1787 行变为 1809 行（WIP），旧报告“全文已读”的结论需按新版本重读；测试由 15 变 16。

### R14（正向确认，源码）

- 唯一执行咽喉成立：`/api/v1/app/ai/actions/execute`（`AiAppController` L252-266）只做类型白名单+回执，不执行工具；工具执行只有 `executeTools`→`invoke`、`approve`→`invoke`、以及 `testRun` 旁路。
- WIP `invoke` 的请求者身份用 `request.getUserId()`（DB 实体，非模型参数/线程上下文），且**无论 `approved` 真假都执行同一权限校验**（L581-591），因此“管理员批准不能恢复已被撤销的原请求者权限”这一设计已落地（有对应新测试断言）。
- `AiAccessService` fail-closed（空 userId/权限查询失败即拒）；MCP/HTTP 出站有 SSRF 守卫（`McpClient.requireEndpoint`、`HttpToolExecutor.guardHost`）；SQL 只读+参数绑定；`AiJson.splitCsv` 与 `joinCsv` 未被 WIP 改动，客户端类型仍按大写解析。

## 4. 未完成门禁（本轮明确没做）

| 门禁 | 状态 |
|---|---|
| Maven 编译/测试 | **未运行**（任务禁止；Lead 独占 lane） |
| Lead WIP 的 5 个 permission 用例 RED→GREEN | 未复验；Lead 侧账本记 5/5 GREEN，本报告视为“待验证” |
| 真实服务/HTTP/DB/Redis | 未运行、未连接 |
| 14 个未读测试、24 个 schema | 未读；`ai_tool.json.permissionCodes` 与代码 CSV 一致性**未核实** |
| 78 个 main 未读文件 | 未读（其中 `AiKnowledgeService`、`AiSeedInitializer`、`AiAdminRequestController`、`AiRequestService`、`OpenAiCompatibleProvider`、`MusicToolProvider`、`QaToolProvider` 对“全量 AI 审阅”仍是缺口） |
| Git | 只读（rev-parse/status/ls-files/diff）；无写操作 |

## 5. 建议 Lead 的下一步顺序

1. 先修 **R1（testRun 超管+真实 actor+审批/审计）**，否则 R4 的 `invoke` 权限链有现成旁路；同批把 `invoke` 的权限判定抽成公共方法供 testRun 复用。
2. **R2/R3（审批 CAS + 配置快照）**：这两条决定“审批还有没有意义”，比继续补工具码优先。
3. **R4 解析器契约**（支持 `*`/短码/超管标记）+ **R5/R6 floor 单调**：与 Lead 正在写的 `ApprovalPolicy.order` 一并收口。
4. **R10 逐工具权限码** + **R8/R9/R7 危险配置边界**（provider/MCP/SQL），再补 `@RequiresSuper` 注解与对应测试（注解断言 + service 拒绝路径）。
5. 拿真实测试收据前，不要把这些结论写成“已修复/GREEN”。

## 6. 交付物

- 本文件：`astral/docs/audit/2026-11-07/backend-ai-read-recovery.md`
- 覆盖 JSON：`astral/docs/audit/2026-11-07/backend-ai-read-recovery.json`（含 baseline/head、107+16+24 清单与 sha256_16、full/partial/prior-full/unread 分组、R1-R14 结构化、门禁状态）
