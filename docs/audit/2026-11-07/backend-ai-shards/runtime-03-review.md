# runtime-03 报告纠偏与两测试有界重读（task-18 · revision 2）

> Lead 的实际 task-18 只读 get 收据已保存：[任务回收记录](<../task-recovery-records.json>)，revision 2、ownerName `lead`、status `in_progress`、无 blocker。此记录确认原提示的同一两测试/已提交摘录/四报告范围，不扩大授权；当前 plain 子任务目录不暴露 Team get 且 UUID 消息不可路由，因此由共享文件提供实际记录。本段仅为回收协调，不是旧四个 runtime 源文件的 fullRead 证据。

## 0. 当前纠偏边界（优先于以下历史报告文字）

原报告属于 task-16，曾错误声称「18 条确认问题」和「所有读取单次 ≤80 行」。这两项主张撤回：18 条是混合性质的静态观察，不是 18 个已确认缺陷；测试历史区间 81–178 含 98 行，81–164 含 84 行，违反上限。旧区间与 hash 只是原报告所记主张，不能据此倒推真实调用历史。先前 durable reader 在输出/声明前失败，不为此次或历史 fullRead 提供证据。

本次 task-18 只纠正报告，并重新连续读取两测试；四个运行时源文件保持冻结，仅使用原报告主张，不重读、不重新背书其 fullRead/hash/源码事实。两测试的当前实读收据见 §6（完成前不声明 fullRead）。未运行测试/Maven/Git/服务/DB/网络/浏览器，未读取 live Blog writer 作验收。共享 task/PLAN 由 Lead 维护，本成员不修改。

### 18 条观察的当前分类（不是缺陷确认数）

| 类别 | ID | 数量 | 证据与结论边界 |
| --- | --- | --- | --- |
| 静态缺陷候选 | F01, F03, F05, F07, F09, F12, F15, F16 | 8 | 局部代码问题由历史报告描述；本次未重读对应源码，不称运行缺陷已证实 |
| 暂定 / 依赖范围外契约的候选 | F04, F06, F08, F10, F11, F13, F14 | 7 | 审批入口、恢复/配额、幂等、controller、常量、取消链路未核，不能补齐因果结论 |
| 未验证安全候选 | F02 | 1 | 管理端 testRun 的真实准入/人工确认边界未核；不能把诊断入口差异直接称越权漏洞 |
| 中性策略观察 | F17 | 1 | Schema 提示边界与服务端夹紧可以共存；未给出必须拒绝的契约，不计缺陷 |
| 正向防御观察 | F18 | 1 | REQUIRED 非法 CSV fail-closed 与 HELD 通配不同；不是安全缺陷 |

关键纠偏：F01 的包装层级是静态候选，但「约束静默失效」未验证。F03 的 NONE 不会移除 HIGH 的 requiresApproval；只可能影响审批分类。F04 的 USER_CONFIRM 记录并不单独证明用户自批执行。F06 只能描述当前执行循环提前退出，不能断言所有剩余调用永久丢弃。F08/F09 不能在未核 service 语义时断言额度泄漏。F13 未核 order()，不推断小写 HIGH 降低 effective risk。F14 的历史位置正文写成 150–152，与所列 744–752 不一致，保留为另一个原报告引文错误，本次不编造正确位置。

## 历史 task-16 报告摘录（以下所有源码位置/哈希/读取主张仅为历史报告声称）

- 原报告覆盖清单：AI 运行时 6 文件，见 §1。
- 原报告自称连续读到 EOF 并取前后 SHA256；当前不能把该自述视为新的独立证据。
- 历史静态观察不构成运行期证明，也不构成整 AI 域或 task-12 完成。
- 以下保留旧观察文本便于追溯；§0 分类和限定覆盖原先的肯定措辞。

## 1. 历史版本锚点与读取区间（原报告声明；本次不独立验证四源码）

| 文件 | 历史 raw 行主张 | 历史非空行主张 | 历史字节主张 | 历史 SHA256 主张 | 历史报告所记区间（不是当前实读） |
| --- | --- | --- | --- | --- | --- |
| [AiToolTypes.java](<../../../../astral-plugin/src/main/java/com/astral/ai/tool/AiToolTypes.java>) | 223 | 194 | 8499 | `73bfd6acbaeb9b3d5f1701d3b9a518daf98cfc469141998820d5937e2d55bdb4` | 1–80, 81–160, 161–223 |
| [AiJson.java](<../../../../astral-plugin/src/main/java/com/astral/ai/common/AiJson.java>) | 188 | 169 | 6249 | `c899e1417b05b48c3167c6b59a20016f042ae13dddccfdf1025425d7b6825e46` | 1–80, 81–160, 161–188 |
| [AiToolService.java](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java>) | 624 | 584 | 28839 | `6bdeb1cbb1a2e5f06c5afb85da94f843f5a9466642899bb29133fb00447d2d45` | 1–80, 81–160, 161–240, 241–320, 321–400, 401–480, 481–560, 561–624 |
| [AiChatOrchestrator.java](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java>) | 1805 | 1693 | 85936 | `d60547ffa6a29453e7dabd10507b7529aa14fcdf8be1672636db3f1a4f81d1ea` | 1–80, 81–160, 161–240, 241–320, 321–400, 401–480, 481–560, 561–640, 641–720, 721–800, 801–880, 881–960, 961–1040, 1041–1120, 1121–1200, 1201–1280, 1281–1360, 1361–1440, 1441–1520, 1521–1600, 1601–1680, 1681–1760, 1761–1805 |
| [AiChatOrchestratorPermissionTest.java](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiChatOrchestratorPermissionTest.java>) | 205 | 177 | 8498 | `b18c21f21b01728babfc97e8fc672a44bf9a0ffdc42c522737017116bf6f591b` | 1–80, 81–178, 179–205 |
| [AiToolServiceRiskFloorTest.java](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiToolServiceRiskFloorTest.java>) | 171 | 153 | 7965 | `85fa6550a9b7b0d7864d1703cbb12cdc3a9710f04caa206c5ee395ec878b0d06` | 1–80, 81–164, 165–171 |

历史统计方法及前后 SHA 相等也是原报告自述，不代表本次验证。尤其两测试历史非空行 177/153 与当前 178/154 不符，当前按 UTF-8 ReadAllLines 后逐行 Trim 判非空；旧数不追改。旧测试区间 81–178（98 行）/81–164（84 行）明确无效。相等端点 hash 只说明采样内容相同，不能证明期间绝无改动，也不恢复未知的旧工具调用史。当前有效实读收据另列于 §6。

## 2. 历史六轴判定摘录（仅原报告主张；当前实读测试限定见 §6）

| 轴 | 判定 | 关键证据 |
| --- | --- | --- |
| REQUIRED CSV 必须拒绝 wildcard / 3 段 / 空段 | **成立** | [AiJson.java:146–159](<../../../../astral-plugin/src/main/java/com/astral/ai/common/AiJson.java#L146-L159>)：`split(",", -1)` 保留空段 + 正则 `(?:admin\|user\|all):[a-z0-9_-]+:[a-z0-9_-]+:[a-z0-9_-]+(?::[a-z0-9_-]+)*`，最少 4 段、`*` 不在字符类内、空段必不匹配 → 一律 `throw AiException("AI008","工具权限码格式错误")`；[AiChatOrchestrator.java:574–576](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L574-L576>) 捕获后直接 `rejected(...)`（fail-closed） |
| HELD 通配不得与 REQUIRED 混为一谈 | **6 文件内不存在混用通路** | 这 6 个文件里没有任何 HELD/通配分支；`*:*:*`、`seg:*` 一旦写进 `ai_tool.permission_codes` 就必然被上条正则拒绝，不会退化成「通配即放行」。通配只可能出现在调用者角色侧（`PermissionChecker`，不在本 6 文件范围），属**范围外待复核** |
| 注册组与配置组「两组都必需」 | **成立，但组内是 ANY 不是 ALL** | [AiChatOrchestrator.java:577–581](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L577-L581>)（注册组）与 `582–587`（配置组，仅当两组不等时）是**两个独立 if + 各自 early-return**，任一组不过即拒；但两处都调 `hasAnyPermissionByLoginId(..., 一组码)`，即**组内任一命中即可** |
| HIGH 恒审批 | **执行侧成立，记录侧被降级** | `requiresApproval`（[AiChatOrchestrator.java:611–616](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L611-L616>)）：`risk=HIGH` 无条件返回 true，与 `approvalPolicy` 无关；风险下限另有三处防守（[AiToolService.java:130–132](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L130-L132>)、`211–213`、[AiChatOrchestrator.java:603–605](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L603-L605>)）。但审批**身份**只由 `approvalPolicy` 决定（`474–477`），HIGH + 非 ADMIN_CONFIRM 会被记成 USER_CONFIRM → 见 F04/F03 |
| 原请求人活权限（审批者不可替代） | **成立** | `invoke` 鉴权只用 `request.getUserId()`（`577–587`），`approved/approvedBy` 不参与鉴权；[AiChatOrchestratorPermissionTest.java:96–107](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiChatOrchestratorPermissionTest.java#L96-L107>) 反向证明：原请求人被撤权后，即使审批人是 `*:*:*` 超管也 `REJECTED` 且处理器调用次数不增 |
| `testRun` 确有操作日志 | **本轮无法独立证实** | 6 文件内**没有** `@OperateLog`（注解与切面都在范围外）。[AiToolService.java:348–352](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L348-L352>) 只有注释声称「不写 `ai_tool_call`，留痕交给后台操作日志」。本项只能引用 Lead 已确认的收据，**不作为本任务的独立结论**；见 §4 待复核 |

## 3. 历史静态观察清单（18 条；旧严重度/标题/事实措辞保留，当前分类以 §0 为准）

> 约定：位置一律 `文件:行`，行号均落在 §1 已读区间内。「历史所列反证」仅指原报告自称检查过的相反证据，不是 task-18 重读四源码的声明。

### F01 {static_defect_candidate} [高] `responseFormat` 把 schema 挂错层级，JSON 输出约束静默失效
- 当前判定：Parent-level schema wrapper described by old report; provider rejection or silent JSON constraint loss not verified.
- 位置：[AiChatOrchestrator.java:1247–1254](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L1247-L1254>)（构造 `{"type":"json_schema","json_schema":{...}}`）。
- 历史报告声称：`format.putObject("json_schema")` 返回子节点 `wrapper`，`name`/`strict` 写在 `wrapper` 上，
  但 `format.set("schema", schema)` 写回了**父节点** `format`。实际产出形如
  `{"type":"json_schema","json_schema":{"name":"agent_response","strict":false},"schema":{...}}`，
  而 OpenAI 兼容协议要求 `schema` 位于 `json_schema` 之内。
- 历史所列反证：同文件 `1234–1246` 对已是协议格式的输入原样透传、`1235` 对空 schema 返回 null，说明作者意图就是「正确包装」；
  `1250–1252` 的 `name`/`strict` 确实写在 `wrapper` 上——唯独 `schema` 例外，排除「有意打平」。
- 未测边界：本 6 文件内的两个测试类都没有覆盖 `responseFormat`；提供方实现（`AiProvider` 各实现）是否自行纠正未知字段、
  以及模型是否因此仍输出 JSON，需运行验证。

### F02 {unverified_security_candidate} [高] `testRun` 绕过启停/客户端/权限，且伪造 `approved=true` 与 `userId=null`
- 当前判定：Administrative testRun differs from requester execution. Controller authorization/manual-confirmation contract and handler semantics not checked; do not label proven privilege bypass.
- 位置：[AiToolService.java:358–383](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L358-L383>)（`require(id)` 在 `359`、上下文构造在 `370–381`、直调 `382`）。
- 历史报告声称：入口只按 id 取行，**不查** `status=DISABLED`、**不查** `client_types`、**不校验**工具自身 `permission_codes`；
  上下文写死 `userId=null, requestId=null, agentCode="test-run", approved=true, approvedBy=null`，
  随后 `handler.execute(context)`。注释（`348–352`）自述「等同人工确认，因此 HIGH 也直接执行」。
- 历史所列反证：正常执行路径 `AiChatOrchestrator.invoke`（`568–587`）对同一组字段做了两道权限校验 + 用真实 `request.getUserId()`；
  `resolveEffective`（`584–612`）也会剔除 DISABLED 与 clientTypes 不匹配的工具 —— 说明这些约束是**别处刻意施加**的，
  `testRun` 是唯一不做这些检查的执行入口。
- 未测边界：`testRun` 的实际调用者与权限（controller 层 `admin:ai:tool:edit`）不在本 6 文件内；
  处理器在 `userId=null` 下的行为（是否 NPE / 是否把操作写成系统行为）无法静态确定。

### F03 {static_defect_candidate} [中高] 绑定层 `approvalPolicy` 未做枚举校验也未夹紧，可把 HIGH 工具降为 NONE
- 当前判定：Binding approval override is a static candidate. HIGH still requiresApproval independent of NONE; candidate concerns policy classification, not removal of all approval.
- 位置：[AiToolService.java:559–560](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L559-L560>)（落库）与 `603–608`（生效）。
- 历史报告声称：`addBinding` 只对 `riskOverride` 做「不能降低」校验（`543–547`），`approvalPolicy` 仅 `toUpperCase` 后直接落库，
  无枚举校验、无 floor；`resolveEffective` 里 risk 走 `strongerRisk`（只升），approval 却是
  `binding.getApprovalPolicy() == null ? tool.getApprovalPolicy() : binding.getApprovalPolicy()`（`606–607`）**原样生效**。
- 历史所列反证：工具级 `update`（`204–227`）对 HIGH floor 明确拒绝降为 NONE/USER_CONFIRM，并有测试
  [AiToolServiceRiskFloorTest.java:88–106](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiToolServiceRiskFloorTest.java#L88-L106>) 覆盖 —— 说明「HIGH 必须 ADMIN_CONFIRM」是既定不变量，绑定层是它的漏口。
- 未测边界：任意的非枚举字符串（如 `"WHATEVER"`）落库后在执行侧如何被解释：
  `requiresApproval`（`611–616`）只判 `!= NONE` 即要求审批（偏保守），
  但 `474–477` 会把非 ADMIN_CONFIRM 一律记为 USER_CONFIRM —— 具体交互需运行验证。

### F04 {tentative_candidate} [中] HIGH 风险的审批身份被降级为 USER_CONFIRM（记录侧与策略不一致）
- 当前判定：USER_CONFIRM record does not independently prove user self-approval/execution. Approval entry and role/state checks out of scope.
- 位置：[AiChatOrchestrator.java:474–477](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L474-L477>)（配合 `611–616`）。
- 历史报告声称：`requiresApproval` 因 `risk=HIGH` 返回 true 后，`approvalStatus` 由
  `ADMIN_CONFIRM.equals(effective.approval()) ? ADMIN_CONFIRM : USER_CONFIRM` 决定 —— **只读 approvalPolicy，不看 risk**。
  于是「风险 HIGH 但 approvalPolicy=NONE」的行（例如后台把 READ 工具的风险抬到 HIGH、审批策略保留 NONE，[AiToolService.java:206–227](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L206-L227>) 允许这种组合）
  会以 USER_CONFIRM 挂起，即用户自己确认即可执行 HIGH 工具。
- 历史所列反证：[AiToolService.java:222–225](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L222-L225>) 只禁止「floor 为 HIGH 时降审批」，对「floor 为 READ 的工具被抬到 HIGH」不施加审批下限，
  说明该组合在配置面是可达的；`RiskFloorTest` 未覆盖这一组合。
- 未测边界：审批落库与放行逻辑（谁有权点确认、确认后是否再校验审批人角色）在本 6 文件之外，无法静态判定最终是否被利用。

### F05 {static_defect_candidate} [中] `allowUserContext` 只改提示词文案，不拦用户上下文的实际投递
- 当前判定：Reported allowUserContext prompt/sanitizer mismatch is a static candidate; actual key definitions and intended context contract unverified.
- 位置：[AiChatOrchestrator.java:809–811](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L809-L811>)（提示词）与 `1102–1119`（`sanitizeContext`）。
- 历史报告声称：`Boolean.TRUE.equals(agent.getAllowUserContext())` 仅决定是否追加一句「可以使用上下文中的用户信息」；
  `sanitizeContext` 无论该开关如何，都把 `musicKeys ∪ adminKeys` 全量放行（唯一条件是非 null），
  并以 `contextSnapshot` 落库（`168–176`）。真正被拦截的只有 `fileContent`（`1115–1117`，受 `allowFileContext` 控制）。
- 历史所列反证：同文件 `1115–1117` 证明作者具备「按开关删键」的写法，`allowUserContext` 处没有对应实现 —— 排除「另有分支」；
  `sanitizeContext` 调用点仅 `176` 一处。
- 未测边界：`AiConstants.ContextKey.adminKeys()` 的具体键值与前端是否真的会传这些键（`AiConstants` 不在 6 文件内）；
  若客户端不传，则该缺口仅为「缺一道防御」。

### F06 {tentative_candidate} [中] 同轮多个工具调用中，一旦有一个挂起审批，其余调用被静默丢弃
- 当前判定：Reported loop exits at first approval; permanent silent loss/replay behavior not established without complete resume contract.
- 位置：[AiChatOrchestrator.java:447–512](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L447-L512>)（循环内 `472–511` 直接 `return true`）。
- 历史报告声称：`executeTools` 逐个处理 `calls`，遇到第一个需要审批的调用就 `return true`；
  该轮剩余调用既不写 `ai_tool_call`、也不发 `TOOL_PLANNED/TOOL_COMPLETED` 事件、也不追加 tool 消息。
- 历史所列反证：`persistAssistantToolCalls`（`656–669`）在进入工具执行前已把**全部** `toolCalls` 写进助手消息 blocks；
  而历史修复 `dropOrphanToolMessages`（`899–921`）要求「声明集合 == 结果集合」才保留，
  所以下一轮这段历史会被整体丢弃 —— 为避免 400 而设计的兜底，反过来证明「未执行部分确实没有结果」。
- 未测边界：真实模型一次返回多工具调用的频率；以及挂起→确认→resume 后这些被丢弃的调用是否会重放（`resume` 只重跑 `run`，不再补执行）。

### F07 {static_defect_candidate} [中] `NEED_CLIENT` 在库里被记为 SUCCEEDED，与事件里的状态不一致
- 当前判定：Reported NEED_CLIENT/SUCCEEDED mapping differs across channels; whether completion semantics are defective depends on contract/consumers.
- 位置：[AiChatOrchestrator.java:618–621](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L618-L621>)（`mapToolStatus` 用 `result.isOk()`）+ [AiToolTypes.java:157–159](<../../../../astral-plugin/src/main/java/com/astral/ai/tool/AiToolTypes.java#L157-L159>)（`isOk` 含 NEED_CLIENT）。
- 历史报告声称：`NEED_CLIENT` 时 `ai_tool_call.status` 落 `SUCCEEDED`，而同一结果的 `TOOL_COMPLETED` 事件带 `status=NEED_CLIENT` 与 `needClient=true`（`548–553`）；
  tool 消息 blocks 里也是 `NEED_CLIENT`（`651`）。
- 历史所列反证：`mapToolStatus` 对 REJECTED 单独分流（`622–624`），说明作者是按状态映射的；`isOk()` 的语义（「不算失败」）
  被当成了「已完成」，是语义借用而非显式设计。
- 未测边界：后端是否有按 `ai_tool_call.status='SUCCEEDED'` 统计成功率的报表/监控（范围外）。

### F08 {tentative_candidate} [中] 审批挂起时已预占的额度/用量被释放，恢复路径依赖「已预占则跳过」
- 当前判定：Release versus resume comment is tentative; reserve/release semantics not checked.
- 位置：[AiChatOrchestrator.java:247–250](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L247-L250>)（预占）、`296–302`（挂起 return）、`333–341`（finally 释放）。
- 历史报告声称：挂起走 `return`，`succeeded` 仍为 false → `finally` 执行 `quotaService.release(reservation)` 与 `usageService.release(...)`；
  而 `246` 的注释写的是「已预占过则跳过，resume 时复用」，与「已释放」矛盾。
- 历史所列反证：`334–340` 只有 `succeeded=true` 才 settle，逻辑上确实会 release；注释与代码至少有一处过时。
- 未测边界：`AiQuotaService.reserve/release` 是否幂等、resume 时能否重新预占成功（该类不在 6 文件内）——**必须运行验证**。

### F09 {static_defect_candidate} [中] 额度预占后若用量预占抛错，额度预占泄漏（try 起点过晚）
- 当前判定：Reported second reserve outside cleanup region is a local cleanup candidate; actual quota leak/rollback behavior unverified.
- 位置：[AiChatOrchestrator.java:248–250](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L248-L250>) 与 `257`（`try` 从 257 开始）。
- 历史报告声称：`quotaService.reserve(...)`（249）与 `usageService.reserve(...)`（250）都在 `try/finally` 之外；
  后者抛异常时前者不会被 `release`（`338`）。
- 历史所列反证：`252–256` 的计数器初始化也在 try 之外，说明 try 的范围是有意从「模型调用」开始，覆盖不到预占阶段；
  同文件其余失败路径（`744–770` 的 `fail`）只处理请求/消息状态，不回收预占。
- 未测边界：`usageService.reserve` 的失败概率与其是否自行回滚（范围外）。

### F10 {tentative_candidate} [低中] 幂等键复用不校验内容，同 key 不同内容会静默返回旧请求
- 当前判定：Key reuse without content comparison is not a defect without an idempotency payload-conflict contract.
- 位置：[AiChatOrchestrator.java:150–156](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L150-L156>)。
- 历史报告声称：`findByIdempotency(userId, agentId, idempotencyKey)` 命中即返回旧 `requestId` 与旧助手消息，不比对 `content`；
  `idempotencyKey` 为空时是否参与查询取决于 `requestService`（范围外）。
- 历史所列反证：`142–148` 只校验非空与长度，没有任何「内容指纹」参与幂等键的构造。
- 未测边界：客户端是否保证同 key 同内容（App 侧重试语义），以及 `findByIdempotency` 对 null key 的处理。

### F11 {tentative_candidate} [低中] `clientType` 为 null 时跳过工具客户端过滤，全部工具可见
- 当前判定：Null client filtering path reported; controller validation and null policy not checked.
- 位置：[AiToolService.java:599–602](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L599-L602>)。
- 历史报告声称：`if (!clients.isEmpty() && clientType != null && !clients.contains(clientType)) continue;`
  —— `clientType` 为 null 时该条件恒 false，声明了 `client_types` 限制的工具也会被下发。
- 历史所列反证：`AiChatOrchestrator.start`（`133–141`）接受 `clientType` 入参但**不校验非空**，`request.setClientType` 原样落库（`168–176`），
  `run` 里 `resolveEffective(version, request.getClientType())`（`258–259`）直接透传 —— 空值可达。
- 未测边界：controller 层是否强制传 `clientType`（范围外）。

### F12 {static_defect_candidate} [低中] `syncFromCode` 会把「与代码工具同码的声明式行」改写成混合配置
- 当前判定：Same-code sync collision is a static candidate; actual tool-code collision not established.
- 位置：[AiToolService.java:106–124](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L106-L124>)（按 `toolCode` 查行后无条件覆盖）+ `141–151`（只跳过声明式的停用，不跳过覆盖）。
- 历史报告声称：同步按 `tool_code` 命中行即覆盖 `name/description/handlerKey/client_types/permission_codes/arguments_schema/result_schema/side_effects`，
  但不改 `executor_type`；若该行本是 HTTP/SQL/MCP 声明式工具，则会得到「声明式执行器 + 代码工具元数据」的混合行
  （`resolveHandler` 仍按 `executor_type` 走声明式，`278`）。
- 历史所列反证：`createDeclarative` 有同码拦截（`301–303`），但反向（代码侧新增同名工具）没有对应拦截，只能靠人工避免。
- 未测边界：现网是否存在同码冲突；`AiToolProvider` 实现清单（范围外）决定真实工具码。

### F13 {tentative_candidate} [低] `addBinding` 用未归一原串做风险比较，小写输入的行为取决于 `RiskLevel.order`
- 当前判定：Unnormalized risk comparison is tentative; RiskLevel.order not read and effective stronger-risk floor must not be assumed bypassed.
- 位置：[AiToolService.java:543–547](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L543-L547>)（比较）与 `557–558`（落库前 `toUpperCase`）。
- 历史报告声称：比较时 `AiConstants.RiskLevel.order(riskOverride)` 直接吃原始大小写字符串，落库时才大写；
  若 `order()` 对未知串返回默认值（如 0），`"high"` 可能被判为「不高于 READ」而放行或误拒。
- 历史所列反证：`474–480`/`484–492` 的 `riskOrDefault`/`approvalOrDefault` 都先 `toUpperCase` 再校验，同一文件里存在正确写法 —— 此处不一致。
- 未测边界：`AiConstants.RiskLevel.order` 的实现（不在 6 文件内）决定这是「误放行」还是「误拒绝」。

### F14 {tentative_candidate} [低] `fail()` 遇到已取消请求直接返回，助手消息停留 PENDING/STREAMING
- 当前判定：Cancellation message terminal-state responsibility out of scope; old MD body 150-152 contradicts listed 744-752, retained as historical citation error.
- 位置：[AiChatOrchestrator.java:744–752](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L744-L752>)。
- 历史报告声称：`150–152` 若状态已 CANCELLED 就 `return`，不再改消息状态；而 `run` 的取消路径（`274–276`、`328–330`）也是直接 return，
  同样不落消息终态。取消后助手消息的终态只能由取消接口负责（范围外）。
- 历史所列反证：`753–761` 对非取消请求会写 FAILED，说明作者关心终端状态；取消分支是有意让位给取消链路。
- 未测边界：取消接口是否兜底更新消息状态（不在 6 文件内）。

### F15 {static_defect_candidate} [低] `testRun` 的 `arguments == null` 分支是死代码
- 当前判定：Reported redundant null branch is low-impact static code-quality candidate, not demonstrated behavior failure.
- 位置：[AiToolService.java:364–369](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L364-L369>)。
- 历史报告声称：`AiJson.readObject(...)` 从不返回 null（[AiJson.java:44–60](<../../../../astral-plugin/src/main/java/com/astral/ai/common/AiJson.java#L44-L60>) 失败即返回空对象/默认值），
  因此 `367–369` 的重建分支不可达；真正的空参兜底在 `364–366` 的三元表达式。
- 历史所列反证：`AiJson.readObject` 的实现保证非 null；同文件其它调用（`471`、`597`）同样直接使用返回值。
- 未测边界：无（纯静态可判定）；影响仅为可读性/覆盖率噪声。

### F16 {static_defect_candidate} [低] `joinCsv` 不校验权限码格式，写回路径绕过 `splitPermissionCsv` 的强校验
- 当前判定：Late read-side REQUIRED rejection may be save-time validation candidate; fail-closed behavior is not permission bypass.
- 位置：[AiJson.java:162–173](<../../../../astral-plugin/src/main/java/com/astral/ai/common/AiJson.java#L162-L173>)；调用点 [AiToolService.java:121](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L121>)（代码工具同步）与 `417`（声明式保存）。
- 历史报告声称：`joinCsv` 只 trim 后拼接；格式校验只存在于读侧 `splitPermissionCsv`。因此非法码可以先落库，
  直到某次执行时才在 `invoke`（`571–576`）炸成 `REJECTED`，表现为「工具突然不可用」而非保存期报错。
- 历史所列反证：[AiToolService.java:402–410](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiToolService.java#L402-L410>) 的 `validateDeclarative` 校验了执行配置却未校验 `permissionCodes`；
  `syncFromCode` 更是直接信任 handler 定义。
- 未测边界：是否有后台表单在保存前做同格式校验（前端范围外）。

### F17 {neutral_policy_observation} [低] `argInt` 钳制与 Schema 声明的边界是两套规则
- 当前判定：Schema bounds and server-side clamping can intentionally coexist; no reject-required contract. Neutral policy observation, not confirmed defect.
- 位置：[AiToolTypes.java:89–92](<../../../../astral-plugin/src/main/java/com/astral/ai/tool/AiToolTypes.java#L89-L92>)（`Math.max(min, Math.min(max, value))`）。
- 历史报告声称：越界参数被**夹紧**为边界值而不是拒绝；而 `intParam`（`206–212`）会同时把 `minimum`/`maximum` 写进 Schema 交给模型，
  两处对「越界」的处置不同（模型被要求守界、服务端却静默改写）。
- 历史所列反证：同文件的 `arg()`/`argList()` 没有类似钳制，说明 `argInt` 是特例。
- 未测边界：是否有处理器依赖「越界即拒绝」来保证业务正确性（处理器不在 6 文件内）。

### F18 {positive_defense_observation} [提示] 通配权限码在工具权限通道被硬拒，通配能力只存在于调用者角色侧
- 当前判定：Positive fail-closed defense; HELD wildcard is not REQUIRED CSV. Reread tests assert invalid text and separator-only rejection for a superuser holder, not a direct wildcard REQUIRED test.
- 位置：[AiJson.java:146–159](<../../../../astral-plugin/src/main/java/com/astral/ai/common/AiJson.java#L146-L159>)（拒绝）与 [AiChatOrchestrator.java:574–576](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L574-L576>) / [582–587](<../../../../astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java#L582-L587>)（拒绝即 `REJECTED`）。
- 历史报告声称：`*:*:*`、`admin:system:user:*` 这类写法无法出现在 `ai_tool.permission_codes`；
  一旦出现，工具对该请求永久不可用（fail-closed），不会「因为通配而放行」。
- 历史所列反证：测试 [AiChatOrchestratorPermissionTest.java:88–94](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiChatOrchestratorPermissionTest.java#L88-L94>)（`*:*:*` 持有人 + 非法 CSV → REJECTED）与
  `118–125`（`*:*:*` 持有人 + `",,"` → REJECTED）都从执行侧证明「通配不能替代工具权限码」。
- 未测边界：`PermissionChecker.hasAnyPermissionByLoginId` 内部如何展开角色侧通配（范围外），
  即「调用者自身的 `*:*:*` 是否能满足一条具体的 REQUIRED 码」这一点本轮未复核。

## 4. 明确未覆盖 / 待他人复核（不得当作已验证）

1. **`testRun` 的操作日志**：`@OperateLog` 不在本 6 文件内。本报告只记录「`testRun` 自身不写 `ai_tool_call`」（注释与代码一致），
   审计留痕是否真的落在后台操作日志，需 controller 侧证据（Lead 已有收据）。
2. **审批接口**（把 `approved=true` 传给 `invoke`/`resume` 的入口）：不在 6 文件内。`invoke` 的鉴权只看原请求人，
   但「谁能审批、审批时是否再校验角色/请求状态」必须由该入口负责复核。
3. **`AiConstants`（RiskLevel/ApprovalPolicy order、ContextKey、状态常量）**、`AiQuotaService`/`AiUsageService` 的幂等语义、
   `PermissionChecker` 的通配展开、`HttpToolExecutor`/`McpToolExecutor` 的 SSRF 与只读判定 —— 均在 6 文件之外。
4. **运行期验证**：本会话未运行 `mvn`/测试/服务，F01/F03/F04/F08/F09 的「是否真的被触发」均需运行或用例覆盖。
5. **旧 report 关系**：既有 runtime-03 report 的全文、前后 hash、早前可见主张**未由 Lead 接受**，本报告不引用、不背书其结论；
   若与本报告冲突，以本报告 §1 的版本锚点 + §3 的源码位置为准。

## 5. 历史完成度声明的纠正

- 原报告声称「6 文件全部读到 EOF、前后 hash 相同」；当前只保留为历史主张。两测试的旧区间超限，不能称符合 ≤80 规则；四源文件不在本次实读范围。
- 原报告「18 条确认问题」撤回，改为 §0 的 8 静态候选 / 7 暂定候选 / 1 未验证安全候选 / 1 中性策略观察 / 1 正向观察；不计任何已证实运行缺陷。
- 本次只交付报告纠偏、两测试有效实读收据和独立 Blog 固定基线报告，不代表整 AI 或 task-12 完成。

## 6. task-18 当前实际两测试重读（不追改旧历史）

| 文件 | raw / 非空 / 字节 | 读前与读后 SHA256 | 当前 read 区间 | continuous / fullRead |
| --- | --- | --- | --- | --- |
| [AiChatOrchestratorPermissionTest.java](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiChatOrchestratorPermissionTest.java>) | 205 / 178 / 8498 | `b18c21f21b01728babfc97e8fc672a44bf9a0ffdc42c522737017116bf6f591b` | 1–80, 81–160, 161–205 | true / true |
| [AiToolServiceRiskFloorTest.java](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiToolServiceRiskFloorTest.java>) | 171 / 154 / 7965 | `85fa6550a9b7b0d7864d1703cbb12cdc3a9710f04caa206c5ee395ec878b0d06` | 1–80, 81–160, 161–171 | true / true |

每测试 3 次实际 read，单次最大 80 行，连续 1–EOF；最后分别显示 total 205 / 171。读前时间分别为 2026-11-07T12:48:53.7482760+08:00 / 12:48:53.8132935+08:00，读后分别为 12:49:35.9304987+08:00 / 12:49:35.9733903+08:00。UTF-8 ReadAllLines 计 raw、逐行 Trim 判非空、Get-Item 计字节、Get-FileHash 取 SHA256。相同 hash 只证明两采样内容相等，不证明采样间绝无短暂修改。

### 当前测试源码实际提供的证据（未执行，不称 pass）

- [PermissionTest:39–60](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiChatOrchestratorPermissionTest.java#L39-L60>)使用真实 PermissionChecker 与 production invoke seam；模型/存储依赖为 null，权限源与 handler 是受控 fixture。完整文件有 6 个 @Test，而不是运行成绩。
- [PermissionTest:67–125](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiChatOrchestratorPermissionTest.java#L67-L125>)断言无权限拒绝、CSV trim/lowercase 成功、非法文本拒绝、原请求人撤权后审批者不能替代、空 stored 不能移除 registered floor、separator-only 拒绝。[127–158 行](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiChatOrchestratorPermissionTest.java#L127-L158>)断言所有检查对象为原请求人，在 executor worker 调用 invoke；effective fixture 是 READ/NONE。
- 此文件 **没有**直接把 wildcard/3 段码作为 REQUIRED 的测试，F18 不应把「HELD 超管 + 非法文本/分隔符拒绝」扩写成所有 wildcard parser case 已测；也没有两个不同注册/配置组都分别不满足的测试，更没有 HIGH 审批入口身份测试。
- [RiskFloorTest:38–75](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiToolServiceRiskFloorTest.java#L38-L75>)以 mocked mapper/JdbcTemplate 调真实 service.update；10 个 @Test 覆盖 HTTP 写风险下限/保持 ADMIN_CONFIRM、GET 可调整、未知风险拒绝、MCP 可写限制、只读 server 不能覆盖远端 write hint、SQL read floor，见 [77–171 行](<../../../../astral-plugin/src/test/java/com/astral/ai/service/AiToolServiceRiskFloorTest.java#L77-L171>)。
- RiskFloorTest **没有** addBinding/resolveEffective、READ floor 被抬为 HIGH、真实 controller/审批/日志/网络执行器行为测试。因此不能凭这些测试源码确认 F02/F03/F04 的完整漏洞因果或触发。

### 与 Blog 基线及共享任务的关系

独立 Blog 基线实读清单见 [blog-baseline-parity](<../blog-baseline-parity.md>) / [JSON](<../blog-baseline-parity.json>)。该清单只读 Lead 固定提取物，不读 live writer，不作 live acceptance。当前成员已读取 [Lead 实际 task-get 收据](<../task-recovery-records.json#L37-L69>)：task-18 revision 2、owner lead、in_progress、blockedBy 空；精确范围与本次工作一致。此为 Lead 的真实 get 收据，不是子成员直接调用 Team 工具。共享 task/PLAN 不修改。
