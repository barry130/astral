# orchestrator-07-current：AiChatOrchestrator.java 当前完整阅读与局部问题证据

- 对象：`F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java`（单文件，task-20 唯一 scope）。
- 方法：本轮由 `read` 工具**从第 1 行连续读到 EOF，每次调用 ≤80 行**，共 23 次；区间见 §1，无跳读、无补读、
  未复制旧报告或 failed `418fdc60` 的 hash/区间/结论。
- 性质：**静态源码观察**。未运行 Maven/测试/服务/DB，未读其他源文件、测试或 controller 去补因果；
  文中「未测试」项即本文件无法单独判定的部分。不认证 runtime 漏洞、不认证整 AI 域或 task-14 完成。

## 1. 版本锚点与读取区间（读前 = 读后）

| 项 | 值 |
| --- | --- |
| 绝对路径 | `F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java` |
| 字节 | 85936（pre = post） |
| raw 行 | 1805（pre = post，`read` 工具 total 同为 1805） |
| 非空行 | 1693（pre = post） |
| SHA256 | `d60547ffa6a29453e7dabd10507b7529aa14fcdf8be1672636db3f1a4f81d1ea`（pre = post） |
| mtime | 2026-11-07T09:39:11.0732652+08:00（读取前后未变） |

区间（每段 ≤80 行，首尾相接覆盖 1–1805）：

`1-80`, `81-160`, `161-240`, `241-320`, `321-400`, `401-480`, `481-560`, `561-640`, `641-720`, `721-800`,
`801-880`, `881-960`, `961-1040`, `1041-1120`, `1121-1200`, `1201-1280`, `1281-1360`, `1361-1440`,
`1441-1520`, `1521-1600`, `1601-1680`, `1681-1760`, `1761-1805`。

## 2. 关键控制流（据本文件事实，逐条给位置）

1. **入口**：`start`（133–208）校验非空/长度 → 幂等命中直接返回旧请求（151–156）→ 落用户消息、请求、助手空壳 →
   `executor.submit(runSafely)`（205）。`resume`（211–213）只把 `runSafely` 重新提交，本文件内**不校验审批结论**。
2. **主流程** `run`（231–342）：先判 CANCELLED（233）→ `resolve`（242）→ `markRunning`（243）→ **额度预占（248–249）
   与用量预占（250）都在 try 之外**，try 从 257 开始 → 工具轮循环（273–307）→ 兜底轮（313–326）→
   `finalize` + `succeeded = true`（331–332）→ `finally`（333–341）：成功 settle，否则 release。
3. **模型调用** `callModel`（346–435）：流式监听；`started` 标志保证 `MESSAGE_STARTED` 与 `markFirstToken` 只发一次（389–395）；
   `onCompleted` 仅在全程无增量时补发内容（409–411）；`onError` 记录后在流返回后抛出（428–430）。
4. **工具执行** `executeTools`（439–558）：未知工具 → tool 消息 + `TOOL_COMPLETED(REJECTED)`，**不写 `ai_tool_call`**（447–461）；
   需审批 → 插入 `WAITING_APPROVAL` 记录 + 两个事件 + tool 消息 → `return true`（471–512）；
   否则插入 `RUNNING` 记录 → 事件 → `invoke(..., false, null)`（538）→ 写 result/状态 → `TOOL_COMPLETED` + tool 消息（539–555）。
5. **单工具入口** `invoke`（561–608）：权限码解析失败即 `rejected`（fail-closed，574–576）；
   **先查注册组**（577–581），**再查配置组**（仅当两组不同，582–587）；两处都用 `request.getUserId()` 与
   `hasAnyPermissionByLoginId`（组内 ANY）；`approved/approvedBy` 只写入 `AiToolContext` 交给 handler（588–599），
   **不参与鉴权**；handler 异常被转成 `FAILED` 结果（600–607）。
6. **审批门** `requiresApproval`（611–616）：`risk == HIGH` 无条件返回 true；否则 approvalPolicy ≠ NONE 时 true。
7. **收尾** `finalize`（673–742）：解析 → `sanitizeRecommended`（686）→ 写消息 → 事件 → `markSucceeded`（741）。
8. **失败收尾** `fail`（744–770）：请求不存在或已 CANCELLED 直接返回（746–752），否则消息置 FAILED + 事件 + `markFailed`。

## 3. 12 条观察（分类 / 位置 / 反证 / 未测试 / 下一步公开 seam）

> 全为**观察**，不是 runtime 漏洞认定。位置均落在 §1 已读区间内。

### O01 [审批] HIGH 恒触发审批门，但“审批身份”由 approvalPolicy 决定
- 位置：`AiChatOrchestrator.java:471–477`、`611–616`。
- 事实：`requiresApproval` 对 `risk=HIGH` 无条件 true（612–614），与 approvalPolicy 无关；门在 471 处先于 538 的
  `invoke`，所以 **HIGH+NONE 不会被自动执行**，不存在自动绕过。
  但在需要审批的分支里，记录的 `approvalStatus` 只由 `approvalPolicy` 决定：`ADMIN_CONFIRM.equals(...)` 才记 ADMIN_CONFIRM，
  其余一律 `USER_CONFIRM`（474–477）。故「HIGH + approvalPolicy≠ADMIN_CONFIRM」的组合会以 `USER_CONFIRM` 挂起。
- 反证：614–615 的第二条件是 `approval != null && != NONE`，与 risk 判断是**或**关系；613 的 return true 在 615 之前，
  说明作者明确把风险当独立下限，不存在“policy=NONE 就不问”的分支。
- 未测试：`WAITING_APPROVAL` 记录由谁消费、`approved=true` 由哪条路径传入、是否按 approvalStatus 区分审批人角色——
  都在本文件之外。
- 下一步 seam：审批入口（controller/service）是否按 `record.getApprovalStatus()` 与 risk 联合校验；本文件不提供该证据。

### O02 [权限] 原请求人活权限 + 两组独立 ANY，比较键是 permissionCodes 文本而非语义集合
- 位置：`577–587`；`568–576`。
- 事实：两处判定都用 `request.getUserId()`（579、585），`approvedBy` 不参与；注册组与配置组是两个独立 `if`
  （577、582），**任一组不满足即 rejected**；配置组只在 `!permissions.equals(registeredPermissions)` 时才查（583）——
  比较的是**规范化后的文本列表**，顺序或元素差异会决定是否触发第二道检查。
- 反证：若两组相同（例如都没配），第二道被跳过，第一道已在 577 先执行；空列表带 `isEmpty()` 守卫，不会因空组误拒。
- 未测试：`PermissionChecker.hasAnyPermissionByLoginId` 是否把 `request.getUserId()` 为 null 判为拒绝（本文件不判 null）。
- 下一步 seam：调用方是否保证 `request.getUserId()` 非空；权限展开语义在 `PermissionChecker`，不在本文件。

### O03 [模型协议] response_format 的包装层级在本文件内自相矛盾（局部包装错误）
- 位置：`1247–1254`。
- 事实：`format.putObject("json_schema")` 得到子节点 `wrapper`，`name`/`strict` 写在 `wrapper`（1250–1252），
  但 `schema` 用 `format.set("schema", schema)` 写到了**父节点**（1253）。因此本方法产出的对象里，
  `json_schema` 内缺 `schema` 键，而父级多出一个 `schema` 键。
- 反证：1242–1246 对已是协议格式的输入原样返回；1235 对空 schema 返回 null；1250–1252 的写法说明意图是“包装”，
  唯独 1253 落错节点——这是**本方法内的局部不一致**，可静态确认。
- 未测试：各 provider 实现如何处理这种对象（忽略 / 报错 / 自行纠正），以及是否另有构造路径；**不能据此断言
  “provider 必然失败”或“模型必然不返回 JSON”**。
- 下一步 seam：`responseFormat` 是 `static` 包内可见，可被同包测试直接观察其返回值（本轮未运行任何测试）。

### O04 [额度/用量] 预占在 try 之外，usage 预占抛错会留下未释放的 quota 预占
- 位置：`247–250` 与 `257`（try 起点）、`333–341`（finally）。
- 事实：`quotaService.reserve`（248–249）与 `usageService.reserve`（250）都先于 try 执行。若 250 抛异常，
  248 的 `reservation` 不会进入 333 的 `finally`，也就不会被 `release`。
  另外注释 246 声称“已预占过则跳过，resume 时复用”，但 248 的调用**没有条件判断**，每次 `run` 都会再预占一次。
- 反证：252–256 的局部变量初始化也在 try 之外，说明 try 的范围是刻意从“模型调用”开始，覆盖不到预占阶段；
  341 之后没有补偿路径。
- 未测试：`AiQuotaService.reserve/release` 是否幂等、是否自带去重或超时回收——在别的源文件，未读、不补因果。
- 下一步 seam：对 248–250 单独 try 或在外面包一层；验证需覆盖“usage 预占失败”的分支（本轮未执行）。

### O05 [取消/resume] 取消只做“提前返回”，消息终态与预占处理依赖 finally；resume 不复核审批
- 位置：`233–235`、`274–276`、`328–330`、`333–341`、`211–213`、`744–752`、`300`。
- 事实：三处 CANCELLED 判断都只 `return`；进入 finally 时 `succeeded=false` ⇒ release（338–339）。
  `274–276` 与 `328–330` 的返回**不改助手消息状态**，消息停留在 `STREAMING`（300 也把它置为 STREAMING 后返回）。
  `resume` 只是重新提交 `runSafely`，本文件内既不读 `WAITING_APPROVAL` 记录、也不看 approved 参数，也不清理旧工具调用记录。
  `fail` 对 CANCELLED 请求直接返回（750–752），因此失败路径不会给已取消请求补终态。
- 反证：300 显式把状态写成 STREAMING 再 return，说明挂起态是有意保留给下游；`fail` 的 CANCELLED 分支说明作者认为
  取消链路自行负责终态（该链路不在本文件）。
- 未测试：取消接口是否兜底把消息改成 CANCELED、resume 是否由审批入口在外部把状态改回并传入 approved=true——均在文件外。
- 下一步 seam：审批/取消入口与本文件的衔接（`resume` 是 public，调用者决定语义）。

### O06 [工具轮] 同轮多工具调用中，首个“需审批”调用会静默丢弃后续调用
- 位置：`447–512`（`return true` 在 511）、`656–669`、`899–921`。
- 事实：`executeTools` 顺序遍历 `calls`，命中需审批就 `return true`；该轮剩余 call 不写 `ai_tool_call`、不发事件、
  不追加 tool 消息。而进入执行前，`persistAssistantToolCalls` 已把**全部** call 写进助手消息 blocks（665）。
- 反证：`dropOrphanToolMessages` 要求“声明集合 == 结果集合”才保留（915–918），因此下一轮读历史时，这段
  “声明多、结果少”的助手消息会被整段丢弃（919–921）——这是作者已知的配对风险，也反证缺失的结果确实不存在。
- 未测试：模型一轮返回多个工具调用的实际概率；客户端在挂起时是否另行处理未执行的调用。
- 下一步 seam：挂起时是否应把未执行的 call 也落记录/事件（当前无声丢弃）。

### O07 [状态映射] `SUCCEEDED` 由 `result.isOk()` 决定，事件里用的是原始 status
- 位置：`618–626`（映射）、`545–553`（事件）、`540`（落库）。
- 事实：落库状态取 `mapToolStatus(result)`，`isOk()` 为真即 `SUCCEEDED`（619–621）；同一次结果的事件里
  `completed.put("status", result.status())` 用**原始状态字符串**（548），NEED_CLIENT 还会额外带 plan/needClient（550–553）。
  两者语义源不同，一旦 `isOk()` 覆盖了非 SUCCEEDED 的状态，就会 DB 与事件不一致。
- 反证：622–624 对 REJECTED 单独分流，说明映射表只有三档；`isOk()` 的定义不在本文件。
- 未测试：`AiToolResult.isOk()` 具体覆盖哪些状态（定义在 `AiToolTypes`，未读、不补因果）。
- 下一步 seam：若要求 DB 与事件同源，应改为按 `result.status()` 显式映射。

### O08 [上下文] `allowUserContext` 只影响提示词，不参与上下文裁剪；只有 fileContent 受开关控制
- 位置：`809–811`、`1102–1119`、`1121–1126`。
- 事实：`allowUserContext` 仅决定是否追加一句提示词（809–811）；`sanitizeContext` 的允许集合是
  `musicKeys ∪ adminKeys`（1107–1108），与 agent 的 allowUserContext 无关，只对 `fileContent` 按 `allowFileContext` 删除（1115–1117）。
  另外 `snapshotInput` 落的是**原始** context 的 key 集合（1124），而 `request.create` 落的是**净化后**的 context（176），
  两处存储口径不同。
- 反证：1115–1117 证明作者会在该函数里按键删除，说明“按开关删键”的写法可行；此处没有对 userContext 复用该模式。
- 未测试：`AiConstants.ContextKey.adminKeys()/musicKeys()` 的实际键值、客户端是否真的上送敏感键——均在本文件外。
- 下一步 seam：若 allowUserContext 是数据面开关，需要在 `sanitizeContext` 内按同一规则裁剪。

### O09 [记录一致性] 未知工具不落 `ai_tool_call`；本文件也不存在“审批通过后更新挂起记录”的代码
- 位置：`447–461`、`471–478`、`539–542`。
- 事实：未授权/未知工具的调用只产生 tool 消息与 `TOOL_COMPLETED(REJECTED)` 事件，**没有 `ai_tool_call` 行**（447–461）；
  需审批的分支只 `insert` 一条 `WAITING_APPROVAL`（478），本文件内**没有**把该行改成 RUNNING/SUCCEEDED 的路径。
- 反证：514–516 与 540–542 证明正常路径会 insert RUNNING 再 updateById；挂起分支在 511 直接 return，未复用该写法。
- 未测试：审批入口是否在外部 update 该记录，或是否通过再次 `resume` 产生一条新记录（若后者，会出现同一逻辑调用的多条记录）。
- 下一步 seam：审批执行链路是否更新原记录——本文件不提供，需在对应入口核对。

### O10 [幂等] 幂等命中直接复用旧请求，不比对消息内容
- 位置：`150–156`。
- 事实：`findByIdempotency(userId, agent.getId(), idempotencyKey)` 命中即返回旧 `requestId`/旧助手消息 ID，`content` 完全不参与判断；
  142–148 只校验非空与长度。
- 反证：`snapshotInput`（1121–1126）会存 content 与 contextKeys，说明内容是可拿到的，但未参与幂等键判断。
- 未测试：`findByIdempotency` 对 null key 的处理与判定条件（不在本文件）。
- 下一步 seam：相同 key 不同 content 的语义需在入口层约定，否则会返回与本次内容不符的旧回答。

### O11 [路由] 未绑定路由时取“任一启用模型”，请求参数随之退化
- 位置：`774–792`、`1159–1174`。
- 事实：`route == null` 时 `modelService.list(null)` 取第一个 `enabled` 的模型（781–785），provider 由该模型反查（786）；
  此时 `routeTemperature/routeTopP/routeMaxTokens` 都拿到 null/模型默认（1159–1174），即路由级参数全部不生效。
  另外 `MAX_TOOL_ROUNDS` 默认值 4，可被 `tool_policy_json.maxRounds` 覆盖并夹在 [1,8]（1263–1273）。
- 反证：785 在无模型时抛 AI006，说明这是刻意的兜底而非遗漏；789–790 的正常分支会按 route 取 model/provider。
- 未测试：`modelService.list(null)` 的排序与租户过滤（不在本文件），因此“第一个”是否稳定未验证。
- 下一步 seam：是否要求版本必须绑定路由；当前实现允许无路由运行。

### O12 [历史] 空摘要的 tool 消息会被历史窗口跳过，进而触发整段配对丢弃
- 位置：`825–827`（跳过空白 content）、`640–654`（tool 消息写入）、`899–921`（配对修复）。
- 事实：`appendToolMessage` 在 `summary == null` 时写空串（645）；`history` 遇到 blank content 直接 `continue`（825–827），
  于是这条 tool 结果不会进入消息序列；而对应助手消息仍声明了该 toolCall → `dropOrphanToolMessages` 判为未配对，
  把助手段与结果段一起丢弃（919–921）。
- 反证：855–858 要求 `blocksJson` 里能取到 `toolCallId` 才拼 tool 消息，说明作者已依赖 blocks 元数据；
  但 825 的空白判断在更前面，先于 847 的角色分支。
- 未测试：是否真的存在 summary 为 null 的工具实现（本文件只保证不 NPE，不保证非空）。
- 下一步 seam：空摘要时应写占位文案，或让 825 的跳过条件放行 TOOL 角色。

## 4. 明确不覆盖 / 边界

1. 仅本文件；`AiToolTypes`（`isOk` 定义）、`AiJson.splitPermissionCsv`、`AiQuotaService`/`AiUsageService`、
   `PermissionChecker`、各 `AiProvider` 实现、审批/取消 controller 均未读，**不据此补因果**。
2. 未执行 Maven/测试/服务/DB/网络/浏览器；所有结论为静态阅读事实，**不认证 runtime 行为**。
3. 不认证整 AI 域完成、不认证 task-14 完成；不继承 failed `418fdc60` 的任何 hash/区间/结论。
4. 本文件只被读，未修改（pre/post SHA、字节、mtime 均一致），符合冻结约定。

## 5. 自检

- [x] 1–1805 连续读完，23 段，每段 ≤80 行，无缺口。
- [x] pre/post SHA256、bytes、raw、nonblank 独立测量并一致。
- [x] 12 条观察，均为分类观察，带位置/反证/未测试/下一步 seam。
- [x] HIGH+NONE 只记录“恒触发审批门 + 身份取 approvalPolicy”，**未**写成自动绕过。
- [x] response_format 只确认本方法内的包装层级错误，**未**外推 provider 必失败。
