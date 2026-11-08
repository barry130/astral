# runtime-03 · Astral AI 运行时分片审计（6 文件）

- 分片：`runtime-03`，审计日 2026-11-07，工作区 `F:\qtMusic\astral`。
- 边界：**只读**。本分片仅写入 `docs/audit/2026-11-07/backend-ai-shards/runtime-03.md` 与 `runtime-03.json`；未修改任何源码、测试、POM、Schema、锁文件、任务板、PLAN、记忆或 Git 状态。
- 状态声明：这是 **6 个文件的运行时安全分片**，**不是**全量 AI 域审计、不是 task-14 交付、不是审计完成。结论仅覆盖下列 6 个文件及其明确标注的少量调用方片段。
- 未执行：未运行 Maven / 测试 / 构建，未连接数据库，未调用浏览器或真实凭据，未联网检索（外部引用见文末，仅本条为本地镜像核验）。

## 一、覆盖与证据（6/6 全文可见）

| # | 文件 | 原始行 | 非空行 | 字节 | SHA-256（读前 = 读后） | 实际可见范围 | 覆盖 |
|---|---|---|---|---|---|---|---|
| 1 | `astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java` | 1805 | 1694 | 85936 | `D60547FFA6A29453E7DABD10507B7529AA14FCDF8BE1672636DB3F1A4F81D1EA` | 1–1805 连续（≤80 行/窗口；本会话 481–1805，1–480 早前连续可见） | 全文 |
| 2 | `astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java` | 624 | 585 | 28839 | `6BDEB1CBB1A2E5F06C5AFB85DA94F843F5A9466642899BB29133FB00447D2D45` | 1–624 连续（本会话复核 90–159、165–184、185–244、340–399、505–584、585–624） | 全文 |
| 3 | `astral-plugin/src/main/java/com/astral/ai/tool/AiToolTypes.java` | 223 | 195 | 8499 | `73BFD6ACBAEB9B3D5F1701D3B9A518DAF98CFC469141998820D5937E2D55BDB4` | 1–223 连续 | 全文 |
| 4 | `astral-plugin/src/main/java/com/astral/ai/common/AiJson.java` | 188 | 170 | 6249 | `C899E1417B05B48C3167C6B59A20016F042AE13DDDCCFDF1025425D7B6825E46` | 1–188 连续（本会话复核 24–65） | 全文 |
| 5 | `astral-plugin/src/test/java/com/astral/ai/service/AiChatOrchestratorPermissionTest.java` | 205 | 178 | 8498 | `B18C21F21B01728BABFC97E8FC672A44BF9A0FFDC42C522737017116BF6F591B` | 1–205 连续 | 全文 |
| 6 | `astral-plugin/src/test/java/com/astral/ai/service/AiToolServiceRiskFloorTest.java` | 171 | 154 | 7965 | `85FA6550A9B7B0D7864D1703CBB12CDC3A9710F04CAA206C5EE395EC878B0D06` | 1–171 连续 | 全文 |

版本一致性：6 个文件读前/读后 SHA-256 逐一相等，无版本变化、无需重读。`git status --porcelain` 对这些路径仅显示审计前既存的 `M`/`??`（工作区本就带修改），本分片未产生任何新增差异。

### 额外可见片段（非六文件，仅用于可达性判定，不计入 6/6）

| 文件 | 可见范围 | 用途 |
|---|---|---|
| `astral-plugin/src/main/java/com/astral/ai/controller/AiAdminToolController.java` | 160–279；另 grep 确认 51–55、178–187、197–198 | 试跑端点的权限注解、审批端点的状态检查与 resume 调用 |
| `astral-plugin/src/main/java/com/astral/ai/common/AiConstants.java` | 120–229 | `RiskLevel.order`、`ApprovalPolicy.all`、`ToolCallStatus`、`ExecutorType` 取值 |
| `astral-auth/src/main/java/com/astral/auth/security/PermissionChecker.java` | 130–209 | `hasAnyPermissionByLoginId` 的 null/ANY/通配语义 |

## 二、确认问题（18 条）

严重度口径：HIGH = 可绕过一个独立安全门；MEDIUM = 可削弱声明式下限、导致重复副作用或资源/审计失真；LOW = 语义/可观测性缺陷，当前无直接越权路径。

### R3-01（HIGH）试跑端点以 `admin:ai:tool:edit` 直接执行 HIGH 工具，绕过审批权限与审批记录

- 证据：`AiAdminToolController.java:178-187`（`POST /api/v1/admin/ai/tools/{id}/test-run`，仅 `@RequiresPermission("admin:ai:tool:edit")`）；`AiToolService.java:358-383`（`testRun` 直接 `handler.execute(context)`，上下文 `approved=true`、`approvedBy=null`、`userId=null`、空 `contextSnapshot`）。
- 说明：审批动作本身由独立权限 `admin:ai:tool:approve` 把守（controller:197-198、`AiPlugin.java:139`），但「试跑」用 edit 权限达到了同一效果：HIGH 工具（含声明式 HTTP 写操作、SQL、MCP 写）被直接执行，且不写 `ai_tool_call`（javadoc 明说留痕只走 OperateLog）。按仓库规范「下放写权限前必须剥离提权字段 / 执行类操作需更高门」，`admin:ai:tool:edit` 实际是「可执行任意已登记工具」的权限，权限分层名不副实。
- 建议：试跑同样要求 `admin:ai:tool:approve`（或对 HIGH 工具要求），并写一条可关联的审计记录（至少 toolId+operator+参数摘要）。
- 置信度：高（端点、注解、执行路径均直接可见）。

### R3-02（MEDIUM）试跑不校验工具状态、客户端范围与权限码下限

- 证据：`AiToolService.java:169-175`（`require` 只做存在性检查）；`358-383`（未检查 `status`、`clientTypes`、`permissionCodes`）；对照 `resolveEffective` 的三道过滤 `591-602`。
- 说明：`DISABLED` 工具、仅限某些 `clientTypes` 的工具、声明了权限码的工具，都能被 `admin:ai:tool:edit` 持有者试跑。`permissionCodes` 是工具的调用下限（编排路径在 `AiChatOrchestrator.java:577-587` 强制），试跑完全跳过。
- 建议：试跑复用 `resolveEffective` 的过滤条件（或显式拒绝非 `ENABLED`），并对声明了权限码的工具要求操作者自身具备该权限。
- 置信度：高。

### R3-03（MEDIUM）审批 TOCTOU：并发两次 approve 可重复执行同一 HIGH 工具

- 证据：`AiAdminToolController.java:202-208`（`selectById` 后判断 `WAITING_APPROVAL`）、`235-243`（直接置 `RUNNING` 并执行，无按状态的条件更新）、`248`（resume）。
- 说明：状态检查与状态写入之间没有乐观锁/条件 UPDATE（如 `update ... where status='WAITING_APPROVAL'`），两个管理员同时点确认都会通过检查并各执行一次；对发通知、改配置、外部写接口这类 HIGH 工具即重复副作用。
- 建议：改为条件更新并以受影响行数作为唯一执行许可，或对 `ai_tool_call` 行加版本号。
- 置信度：高（代码路径确定；实际并发触发需两个请求，未实测）。

### R3-04（MEDIUM）审批执行结果不回灌对话：resume 从模型轮重新开始，模型看不到工具结果

- 证据：`AiAdminToolController.java:237-248`（执行结果只写 `ai_tool_call`，随后 `resume`）；`AiChatOrchestrator.java:211-212`（`resume` 直接提交 `runSafely`）；`run` 从模型调用重新进入循环（`257` 起）；`appendToolMessage` 只在工具执行路径（`555`）与「需确认」占位路径（`505-510`）被调用，审批成功后没有任何代码把结果写入会话。
- 说明：审批后模型收到的仍是「该操作需要确认后执行」这条占位 tool 消息，看不到成功/失败；模型很可能再次调用同一工具，重新进入待确认，形成反复审批，或以「未执行」为前提作答。
- 建议：审批成功后追加一条 TOOL 结果消息（携带 toolCallId 与结果摘要）再 resume，或让 resume 以「已执行结果」为输入继续该轮。
- 置信度：高（调用点缺失可直接验证）。

### R3-05（MEDIUM）`USER_CONFIRM` 没有用户侧确认通路，实际与 `ADMIN_CONFIRM` 等价

- 证据：整个 `com/astral/ai` 包 grep `testRun|resume\(|approvedBy|approve` 仅命中 `AiAdminToolController.java:197` 一个审批端点（`admin:ai:tool:approve`）；`AiPlugin.java:139` 也只登记这一条审批权限；`requiresApproval` 只看「是否非 NONE」（`AiChatOrchestrator.java:611-616`），执行时策略落地为 `ADMIN_CONFIRM.equals(...) ? ADMIN_CONFIRM : USER_CONFIRM`（`471` 附近）。
- 说明：配置成 `USER_CONFIRM` 的工具，仍需管理端 `admin:ai:tool:approve` 才能执行；真正的请求者（App 用户）无法确认自己的高风险操作。三值策略在运行时退化为两值（NONE / 需管理员），配置语义与文档不一致。
- 建议：要么补 App 侧「本人确认」端点（校验 `request.getUserId()`），要么在工具编辑界面禁用 `USER_CONFIRM` 并明确语义。
- 置信度：中高（`ai` 包内已穷举 grep；未审计 App 控制器目录之外的其它模块）。

### R3-06（MEDIUM）`syncFromCode` 不会把代码声明的 `approvalPolicy` 同步到已存在的工具行

- 证据：`AiToolService.java:125-132`：仅 `isNew` 分支写入 `setApprovalPolicy(def.approvalPolicy())`；已存在分支只做 `setRiskLevel(strongerRisk(...))`。
- 说明：代码处理器把确认策略从 NONE 提升为 `USER_CONFIRM/ADMIN_CONFIRM` 后，只要工具行已存在，DB 仍是 NONE；非 HIGH 工具于是继续自动执行。风险等级有「只升不降」保护，确认策略没有，属于代码下限在持久层被静默丢弃。
- 建议：对已存在行同样做「确认策略只能加强」的合并（`NONE < USER_CONFIRM < ADMIN_CONFIRM`），并补一条回归。
- 置信度：高。

### R3-07（MEDIUM）绑定级 `approvalPolicy` 无枚举与下限校验，可清掉非 HIGH 工具的确认要求

- 证据：`AiToolService.java:543-547`（只比较 `riskOverride` 的 `order`，未校验枚举成员；且依赖库中 `tool.getRiskLevel()`）、`557-560`（`approvalPolicy` 仅 `toUpperCase`，无 `ApprovalPolicy.all()` 校验、无下限）；`584-608`（`resolveEffective` 中 `binding.getApprovalPolicy()` 原样生效，`606-607`）。
- 说明：给 DRAFT/READ 工具绑定 `approvalPolicy=NONE`（或任意非法串）即可抹掉工具级 `ADMIN_CONFIRM`；HIGH 工具因编排层硬判断（`611-614`）不受影响，所以是「下限被削弱但不至于自动执行 HIGH」。非法串（如 `BOGUS`）会被当作「非 NONE」从而强制确认，属反向失真。
- 建议：绑定保存时复用 `update` 的校验（枚举 + HIGH floor），并禁止绑定把策略降到工具级之下。
- 置信度：高。

### R3-08（MEDIUM）配额预占在 try/finally 之外：usage 预占失败会泄漏已预占配额

- 证据：`AiChatOrchestrator.java:243-250`（`markRunning`、`quotaService.reserve`、`usageService.reserve`），`257` 才开始 `try`，`333-` 的 `finally` 只覆盖其后逻辑。
- 说明：`quotaService.reserve` 成功而 `usageService.reserve`（或两者之间任何调用）抛异常时，`runSafely` 走 `fail`，但没有任何释放已预占配额的补偿；`finally` 里的 settle/release 尚未进入。高频失败会持续吞掉用户额度。
- 建议：把两次 reserve 纳入 try 并记录已完成步骤，finally 中按步骤补偿；或让 reserve 幂等 + 统一 release。
- 置信度：高（结构可见）；实际泄漏幅度取决于 `AiQuotaService` 实现（未读）。

### R3-09（MEDIUM）固定线程池使用无界队列，且配额在出队后才计，缺少准入控制

- 证据：`AiChatOrchestrator.java:115`（`Executors.newFixedThreadPool(8, ...)`，其内部为无界 `LinkedBlockingQueue`）、`205`/`212`（`executor.submit`，无拒绝处理、无队列上限），配额预占发生在 worker 内（`249`）。
- 说明：请求可无限排队，每条约占一行 `ai_request` 与一份历史消息，排队期间不消耗配额、不受限流约束；一次突发即可把内存/DB 连接压满。`PreDestroy` 的 `shutdownNow`（`123`）也无法清理已排队任务。
- 建议：改为有界队列 + 明确的拒绝策略（返回业务繁忙码），并在入队前做配额预检或按用户限流。
- 置信度：高。

### R3-10（LOW）一条助手消息含多个工具调用时，首个需确认即中止整批，后续调用被静默丢弃

- 证据：`AiChatOrchestrator.java:471-512`（命中需确认即写一条 `WAITING_APPROVAL`、追加占位消息后 `return true`）；`executeTools` 调用方据此 `markWaiting`（`299`）并结束本轮。
- 说明：同一轮第 2..n 个工具调用既不执行、也不落 `ai_tool_call`、也不产生 tool 消息；用户与模型都不知道它们存在。恢复后模型需重新生成调用，参数可能不同。
- 建议：为批内其余调用写入 `PLANNED/SKIPPED` 记录并在 resume 时按原参数续跑。
- 置信度：高。

### R3-11（LOW）`approvalStatus` 列混用策略值与调用状态值

- 证据：`AiAdminToolController.java:217-218`：通过时写 `ApprovalPolicy.ADMIN_CONFIRM`，拒绝时写 `ToolCallStatus.REJECTED`；拒绝分支又把 `status` 同样置 `REJECTED`（`223`）。
- 说明：同一列既存「确认策略」又存「拒绝状态」，按 `approvalStatus` 查询/展示会把两种语义混在一起；与 `AiConstants` 中两套常量（`149-159`、`192-202`）的定义冲突。
- 置信度：高。

### R3-12（LOW）工具异常原文进入用户可见内容与事件

- 证据：`AiChatOrchestrator.java:600-607`（`catch` 直接 `AiToolResult.failed(e.getMessage())`）；`539`、`555`（结果与摘要写入 `ai_tool_call.result_json` 与 TOOL 消息）；`744-766`（`fail` 把 `"请求失败：" + safe(message)` 写进助手消息正文，并把 `message` 写进 `REQUEST_FAILED` 事件）。
- 说明：执行器/提供方的原始异常文本（SQL 片段、JDBC/URL、上游响应体）会同时进入模型上下文与 App 用户可见的回答；`safe()` 只截断 500 字，不做脱敏。仓库响应体规范要求「未知异常只回固定文案」。
- 建议：对外只回稳定错误码与通用文案，明细仅入日志/内部字段。
- 置信度：中（本分片可见其传播路径；具体泄露内容取决于未读的执行器/提供方实现）。

### R3-13（LOW）`parseAnswer` 接收但不使用 `responseSchemaSnapshot`，schema 快照形同装饰

- 证据：`AiChatOrchestrator.java:681`（传入 `request.getResponseSchemaSnapshot()`）、`979-1001`（`responseSchema` 形参在方法体内从未被引用）。
- 说明：请求冻结的响应 schema 不参与解析或校验，模型输出只要能被 `pickAnswerNode` 认出即被接受；schema 一致性完全依赖提示词。
- 置信度：高。

### R3-14（LOW）`resume` 为 public 且无状态/归属守卫，重复调用会重复收尾

- 证据：`AiChatOrchestrator.java:211-212`（无状态检查直接提交）；`run` 只拦 `CANCELLED`（`221-228` 附近）；`finalize` 会再次更新同一助手消息、追加 `MESSAGE_COMPLETED`（`717-728`）并 `markSucceeded`（`741`）。
- 说明：对 `SUCCEEDED` 请求调用 `resume` 会重复模型调用、重复追加完成事件与重复结算；目前仅控制器在审批后调用，属服务层不变式缺失（与 R3-03 叠加时风险更高）。
- 建议：`resume` 内校验请求处于 `WAITING_APPROVAL`（或 RUNNING 且有待续调用）。
- 置信度：高。

### R3-15（LOW）试跑参数非法 JSON 被静默降级为 `{}` 并照常执行

- 证据：`AiToolService.java:364-369`；`AiJson.java:43-61`（`read` 失败返回 null，`readObject` 返回空对象）。
- 说明：填错 JSON 不会报错，工具以空参数执行，可能触发「全量/默认」行为；对写操作类工具尤其危险。
- 建议：试跑入口用严格解析并在失败时返回参数错误。
- 置信度：高。

### R3-16（LOW）`invoke` 吞掉处理器栈且 `AiException` 分支零日志

- 证据：`AiChatOrchestrator.java:600-607`：`catch (AiException)` 直接返回、无日志；`catch (Exception)` 只记录 `e.getMessage()`，不传 throwable。
- 说明：工具失败无法定位（无堆栈、无 toolCode 之外的关联信息），与审计/排障要求不符。
- 置信度：高。

### R3-17（LOW）声明式工具保存不校验权限码 CSV，可保存后永久 REJECTED

- 证据：`AiToolService.java:416-417`（`setClientTypes`/`setPermissionCodes` 直接 `joinCsv` 请求值）；对照调用期校验 `AiJson.java:146-161`（`splitPermissionCsv` 要求 4+ 段小写、空片段抛 `AI008`）。
- 说明：保存时接受任意字符串（如 `foo`、`admin:system`、含空片段），工具落库成功但每次调用都在 `invoke` 入口被判「权限配置异常」而拒绝——配置看似生效、实际不可用，且没有保存期报错。
- 建议：保存期即调用 `splitPermissionCsv` 校验（失败返回参数错误）。
- 置信度：高。

### R3-18（LOW）`AiJson.write` 序列化失败静默返回 null，审计字段无声丢失

- 证据：`AiJson.java:27-40`。
- 说明：`result_json`、`blocks_json`、`input_snapshot` 等字段在序列化异常时被写成 null，调用方不感知；审计链出现空洞且无日志。
- 置信度：高。

## 三、已证伪 / 不成立的主张

| 主张 | 结论 | 证据 |
|---|---|---|
| 「HIGH 且 approval=NONE 会免确认自动执行」 | **证伪** | `requiresApproval` 先判 `RiskLevel.HIGH` 直接 true（`AiChatOrchestrator.java:611-614`），与 approvalPolicy 无关；`update` 亦禁止 HIGH 降为低确认（`AiToolService.java:222-225`）。 |
| 「后台 `update` 可把确认策略改成任意值」 | **证伪** | `update` 对 `approvalPolicy` 做枚举校验并按 floor 拦截（`AiToolService.java:216-227`）。缺陷只在**绑定**层（R3-07）。 |
| 「试跑端点是公开/未鉴权 handler，构成公开绕过」 | **证伪（但降级为 R3-01）** | 路由前缀 `/api/v1/admin/ai` 且方法标注 `admin:ai:tool:edit`（controller:51-55、178-179）；非公开。真实问题是 edit 权限粒度过大，而非匿名可达。 |
| 「绑定 `approvalPolicy=NONE` 可让 HIGH 工具免确认」 | **证伪** | 同第一条：HIGH 由编排层硬判断兜底（`611-614`）。 |
| 「公共 `invoke` 守卫可被 `*:*:*`、空片段或大写绕过」 | **证伪** | `splitPermissionCsv` 小写化 + 4 段起 + `split(",",-1)` 空片段必抛（`AiJson.java:146-161`）；`*:*:*` 由真实 `PermissionChecker.matchesAny` 处理（`PermissionChecker.java:184-195`）；2 个测试文件覆盖 6 条路径。 |
| 「为兼容 held 通配需要放宽 REQUIRED 解析器」 | **不成立** | 解析器未放宽、也不应收紧或放宽；通配由权限检查器单点处理。 |

### `invoke` 公共守卫复核（6/6，按源码逐条）

| 检查点 | 结论 | 证据 |
|---|---|---|
| 使用真实 `PermissionChecker`，仅 `StpInterface` 为假 | 成立 | 测试 `AiChatOrchestratorPermissionTest` 构造真实 `PermissionChecker` + `MutationPermissionSource`（仅 SP I） |
| 严格使用原始 `request.getUserId()` | 成立 | `AiChatOrchestrator.java:579`、`585`、`591`；测试断言审批人不能替代请求者 |
| 每个「已登记 / 已配置」组各自 ANY 通过 | 成立 | `577-581`（handler 定义组）、`582-587`（工具配置组） |
| 两个独立组都必需 | 成立 | 两组分别检查；仅当两组文本完全相同（`583`）时第二条被跳过，此时首条已覆盖同一集合 |
| 必填 CSV 小写、4+ 段、拒绝空片段 | 成立 | `AiJson.java:146-161`；测试 `",,"` 与畸形 CSV 均被拒 |
| `*:*:*` 由独立通配路径处理 | 成立 | `PermissionChecker.hasAnyPermissionByLoginId` → `matchesAny`（`184-195`）；测试中畸形 CSV 即便带 `*:*:*`+approved 仍拒绝 |

## 四、未证实 / 需上游补证

1. `ai_tool.risk_level` 是否允许 NULL：若历史行可为 NULL，则 `effective.risk()` 可能为 null，`requiresApproval` 与 `executeTools` 的风险判断都会失效（`611-614`）。Schema 不在本分片范围，未读。
2. `AiQuotaService.reserve` / `AiUsageService.reserve` 是否幂等：决定 R3-08 的实际泄漏与 resume 重入是否重复计费。未读该服务。
3. 是否存在本分片未见的 App 侧确认端点（R3-05 的反例）：已对 `com/astral/ai` 全包 grep `approve|approvedBy|resume`，仅管理端一处；但 `astral-server` 或其它模块是否另开入口未穷举。
4. `requestService.markWaiting/markRunning` 是否带状态机约束：若二者允许任意迁移，R3-14 的重复收尾更容易触发。未读。
5. R3-12 的实际泄露内容：取决于 `HttpToolExecutor` / `SqlToolExecutor` / provider 实现的异常包装。未读（超出本分片 6 文件）。
6. 声明式 HTTP 工具是否存在 URL 白名单/内网地址拦截：本分片只能看到「保存即执行、试跑直接触发」，无法判断 SSRF 防护；相关执行器未读。对照 OWASP《Server-Side Request Forgery Prevention Cheat Sheet》（本地镜像 `target/audit-reference-owasp-ssrf-20261107-1045.html`，canonical `https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html`，title 于第 30 行核验）——**仅作为对照基准，未据此判定本仓存在 SSRF**。
7. 两个测试文件均未覆盖：试跑越权（R3-01/02）、审批并发（R3-03）、审批结果回灌（R3-04）、`syncFromCode` 策略漂移（R3-06）、绑定策略校验（R3-07）、配额泄漏（R3-08）、批内多调用丢弃（R3-10）。

## 五、覆盖计数与报告哈希

- 覆盖计数：**full = 6，partial = 0，version-split = 0**（6 个目标文件全部全文可见，无版本变化）。
- 本分片只写 2 个文件：本文件与 `runtime-03.json`。

哈希约定（可复现，按**原始字节**计算，避免 PowerShell 5.1 默认按 ANSI 读 UTF-8 造成中文定位失败）：`runtime-03.md` 取文件开头至 `## 五、覆盖计数与报告哈希` 首字节之前的字节串；`runtime-03.json` 取文件开头至 `"reportHashes"` 首字节之前的字节串（排除末尾该成员，使摘要不被嵌入的哈希值影响）。

```powershell
function Prefix-Sha256([string]$Path, [string]$Marker) {
  $b = [IO.File]::ReadAllBytes($Path); $m = [Text.Encoding]::UTF8.GetBytes($Marker)
  for ($i = 0; $i -le $b.Length - $m.Length; $i++) {
    if (-not (Compare-Object $b[$i..($i + $m.Length - 1)] $m)) {
      return (Get-FileHash -Algorithm SHA256 -InputStream ([IO.MemoryStream]::new($b[0..($i - 1)]))).Hash
    }
  }
}
Prefix-Sha256 "docs/audit/2026-11-07/backend-ai-shards/runtime-03.md" "## 五、覆盖计数与报告哈希"
Prefix-Sha256 "docs/audit/2026-11-07/backend-ai-shards/runtime-03.json" '"reportHashes"'
```

- `runtime-03.md` body SHA-256：`EBD73960C1958F2F79F4B58BD994EB23990CE9CAE03D492814A5996A298CF3B3`
- `runtime-03.json` body SHA-256：`B848249E05B685ACC61C914B2ACE6B11AB721B75CA78700059DBB7C4FC2B1700`