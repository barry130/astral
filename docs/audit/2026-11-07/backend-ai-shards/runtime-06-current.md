# AI runtime 三源当前整读回收（task-19 / runtime-06-current）

- 任务：task-19（owner backend-chain），限定只读回收。前序 plain reader `418fdc60` failed-before-finish、无 closing、无产物，本报告**不继承**其前置 hash、三源 full 声明或 Orchestrator 局部已读。
- 范围：3 个源码文件，从 offset=1 连续 read 到 EOF，单次 actual read ≤80 行；前后 SHA256/bytes/raw/nonblank 独立采样且一致。
- 明确不含：`AiChatOrchestrator`（另分独立任务）、controller、DTO/entity/mapper/constants/properties、executor 实现（Http/Sql/Mcp）、测试、schema、Maven/Git/DB/网络/browser/shared PLAN。
- 本报告**不是**全 AI 完成、不是原四源完成、不是 task-14 完成；3 源在 closing 前冻结。
- 验证层级：静态源码阅读。未跑 Maven、未起服务、未执行工具、无任何运行期证据；跨文件因果一律标“未读”。

## 1. 覆盖账本（前/后哈希相同）

| 文件 | bytes | raw | nonblank | SHA256（前=后） | ranges | coverage |
|---|---:|---:|---:|---|---|---|
| tool/AiToolTypes.java | 8499 | 223 | 195 | 73BFD6ACBAEB9B3D5F1701D3B9A518DAF98CFC469141998820D5937E2D55BDB4 | 1-80, 81-160, 161-223 | full |
| common/AiJson.java | 6249 | 188 | 170 | C899E1417B05B48C3167C6B59A20016F042AE13DDDCCFDF1025425D7B6825E46 | 1-80, 81-160, 161-188 | full |
| service/AiToolService.java | 28839 | 624 | 585 | 6BDEB1CBB1A2E5F06C5AFB85DA94F843F5A9466642899BB29133FB00447D2D45 | 1-80, 81-160, 161-240, 241-320, 321-400, 401-480, 481-560, 561-624 | full |

行数口径：raw=`Get-Content` 行数；nonblank=Trim 后非空行数，二者不同口径。

## 2. 分类观察（12 项，最多 12）

分类：`静态事实`=源码可直接确认；`候选`=源码事实成立但影响取决于未读调用方；`正向`=当前源码体现的安全约束；`未测试`=运行期未验证。

### O1 静态事实/正向：权限码解析方向是严格 REQUIRED 格式，显式拒绝通配与 3 段码
- `AiJson.java:146-159`：`splitPermissionCsv` 先 `trim().toLowerCase`，再逐个匹配 `(?:admin|user|all):[a-z0-9_-]+:[a-z0-9_-]+:[a-z0-9_-]+(?::[a-z0-9_-]+)*`，不匹配立即 `throw new AiException("AI008", ...)`。
- 效果：`*:*:*`、`admin:system:user:*`、3 段码（如 `admin:ai:view`）都会被拒；`split(",", -1)` 保留尾部空项，因此 `"a:b:c:d,"` 的空项也会失败 → fail-closed。
- 反证/边界：这是**解析方向**的约束（谁消费该列表由未读代码决定）；本函数不允许通配，因此这 3 个文件里不存在“HELD 通配”的数据结构。

### O2 候选：写入方向未执行同一格式校验，DB 可存通配/3 段/大写权限码
- `AiToolService.java:120-121`（syncFromCode）与 `:416-417`（applyDeclarative）都用 `AiJson.joinCsv(...)` 落 `permission_codes`；`AiJson.joinCsv:161-173` 只 trim/丢弃空项/逗号拼接，**不校验正则、不拒绝 `*`、不小写化**。
- 后果：后台声明式工具或代码定义可把任意字符串写入该列；随后若消费方调用 `splitPermissionCsv` 会抛 AI008（工具不可用，fail-closed），若消费方走别的解析则行为未知（Orchestrator 未读）。
- 反证：`createDeclarative` 的同码唯一性有校验（`:301-303`），但只防编码重复，不防权限码格式；DTO 是否另有 `@Pattern`（未读，未知）。

### O3 候选：`joinCsv` 把“空列表”写成 NULL，无法区分“无 REQUIRED 码”与“未知/HELD”
- `AiJson.java:162-165,172`：空/null/全空白 → 返回 `null`。
- 因此 `permission_codes` 列上的“未设置”“空要求”“未知”在持久层同形（NULL）；本 3 文件没有任何 HELD 标记位，通配概念也不存在。
- 后果：若审批/权限门禁依赖“NULL 表示无下限、非空表示 REQUIRE”的二分，无法表达第三种状态（未读消费方，未知）；是否构造该区分由未读代码决定。

### O4 候选：绑定级 approval 覆盖无枚举/下限校验，且读取侧只对 risk 做提升
- `AiToolService.java:543-547`：`addBinding` 仅比较 `riskOverride` 与工具风险（“只能提高”），不校验 `riskOverride` 是否为合法枚举；`:557-560` 仅 `toUpperCase` 后落库 `approvalPolicy`，**无枚举成员校验、无下限校验**。
- 读取侧 `:603-607`：`risk = strongerRisk(tool.riskLevel, binding.riskOverride)`（下载时仍不能降低），但 `approval = binding.approvalPolicy == null ? tool.approvalPolicy : binding.approvalPolicy` 原样返回，没有与 `riskFloor` 对齐。
- 因此 HIGH 风险工具可以绑定出 `approval=NONE` 的记录（静态事实）。
- 反证/未读：该值是否真的导致“跳过审批”取决于未读的审批决策代码（Orchestrator/controller），本报告不推断结果；**“HIGH+NONE 自动 bypass”是此前已撤回的错误主张，本报告不重述**。

### O5 静态事实/候选：`testRun` 的上下文无 actor、`approved=true`、非法参数静默为空对象
- `AiToolService.java:348-383`：`testRun(Long id, String argumentsJson)` 签名单里没有操作人；构造的 `AiToolContext` 中 `requestId/conversationId/userId/agentId` 全为 null，`approvedBy=null`，`agentCode="test-run"`，`agentType=CUSTOM`，`clientType=ASTRAL_FRONT`，`approved=true`。
- `argumentsJson` 非空时经 `AiJson.readObject`，非法 JSON 静默变空对象（`AiJson.java:55-60`），不抛 320；随后直接 `handler.execute`。
- javadoc（`:349-353`）：不写 `ai_tool_call`，“留痕交给后台操作日志”；“由管理员手动触发，等同人工确认，因此 HIGH 也直接执行”。
- 未读：后台 controller 是否标 `@OperateLog`、是否有权限/超管/二次确认、是否传可信 actor——均未知，不能从本文件推断审计归属。

### O6 候选：服务层不拦截空 `toolCode`
- `AiToolService.java:298-305`：`toolCode` 为 null 时归一为 `""`；`findByCode("")` 因 blank 直接返回 null（`:178-181`），所以重复校验不触发，`entity.setToolCode("")` 后直接 insert（`:304-312`）。
- 后果：若上游 DTO/controller 没有 `@NotBlank`（未读，未知），会落一条空 toolCode 的工具（模型可见的函数名为空）。
- 反证：`createDeclarative` 会先 `validateDeclarative`，但该方法只校验 executorConfig/schema/hosts（`:402-410`），不含 toolCode。

### O7 正向：执行器写操作下限在服务层写死，后台不可放开
- `AiToolService.java:427-432`：HTTP 非 `GET` 强制 `HIGH` + `ADMIN_CONFIRM`（`method != GET` 即视为写）；SQL 允许后台选风险但副作用固定“只读 SQL 查询”。
- `:441-468 riskFloor`：HTTP 非 GET → HIGH/ADMIN_CONFIRM；MCP → 仅当 server.readOnly 且工具 hint 认定只读才 READ/NONE，否则 HIGH；SQL → READ/NONE；CODE → 取 handler 定义。
- `:204-227 update`：风险低于 floor 抛 AI008；floor 为 HIGH 时只允许 ADMIN_CONFIRM（设置 USER_CONFIRM 也会被拒）。
- `validateDeclarative:402-410`：HTTP 保存期校验 executorConfig/argumentsSchema/allowedHosts，SQL 校验配置与 schema。
- 反证/未读：`HttpToolExecutor.validate/create`、`McpToolExecutor.readOnlyOf/create` 的实现未读，method 缺省值语义、allowedHosts 为 null 时的行为、MCP readOnly 组合逻辑均未知；本项只确认“服务层下调不动下限”。

### O8 候选：`syncFromCode` 每次启动覆盖后台元数据，且重新上线的代码工具不会自动启用
- `AiToolService.java:101-154`：已存在的代码工具，代码侧覆盖 `name/description/handlerKey/clientTypes/permissionCodes/argumentsSchema/resultSchema/sideEffects`（`:117-124`），风险取 `strongerRisk` 只升不降（`:131`），`approvalPolicy`/`status` 保持库值（`:125-132` 只在 isNew 时写入）。
- 后果一：后台改过代码工具的 `name/description/permissionCodes` 会在下次启动被覆盖（javadoc 只承诺“后台可调风险/确认/启停”，与实现一致，但运营常误以为名称可持久化）。
- 后果二：曾被下线标记 `DISABLED` 的代码工具，如果代码里重新加回同 toolCode，sync 只更新字段、不恢复 ENABLED，需后台手工启用。
- 反证：这是设计取舍（避免审计断链/不允许降低风险），不是安全缺陷；未测试实际启动行为。

### O9 候选：`syncFromCode` 仅按 toolCode 匹配，未按 executor_type，可能改写声明式工具行
- `AiToolService.java:106-108`：`selectOne(eq(toolCode))`，未过滤 `executor_type`。
- 若后台已存在同 toolCode 的声明式工具（HTTP/SQL/MCP），代码新增同 code 的 CODE 工具时，sync 会把该行的 `handlerKey/schemas/permissions/name` 改成代码值，但不改 `executor_type`；随后 `resolveHandler:267-278` 按 `executor_type` 分派，仍走 Http/Sql/Mcp 执行器，代码 handler 永远不可达，声明式配置也被覆盖（大概率 create 失败 → 工具失效）。
- 反证：`createDeclarative:301-303` 阻止“先有代码工具再建同名声明式”，因此碰撞路径是“先有声明式、代码后加同名”或直接改库；未读 DB 约束，未测试。

### O10 候选：`resolveEffective` 的客户端过滤在 `clientType==null` 时被跳过，且大小写敏感
- `AiToolService.java:599-602`：`if (!clients.isEmpty() && clientType != null && !clients.contains(clientType)) continue;` → `clientType` 为 null 时不做客户端限制；列表由 `splitCsv` 生成（已大写），而传入值未归一化，若调用方传小写会被过滤掉（fail-closed）。
- 未读：调用方是否可能传 null（Orchestrator 未读，未知）；未测试。

### O11 候选：处理器注册的重复裁决依赖 Bean 顺序
- `AiToolService.java:69-91`：遍历 `List<AiToolProvider>`，`handlers` 已有同 `handlerKey` 或 `byCode` 已有同 `toolCode` 即 `continue`（首个生效），仅打 warn；provider 列表顺序无 `@Order` 约定（本文件未声明）。
- 后果：同一 handlerKey/toolCode 出现在多个 provider 时，生效实现取决于 Spring 注入顺序（未测试）；第二个 handler 的 handlerKey 也一并丢失。

### O12 静态事实/信息：AiJson 帮助方法刻意静默失败，`AiToolResult` payload 可为 null
- `AiJson.java:27-40`：`write(Object)` 对 `String` 直接原样返回（把调用方字符串当已序列化 JSON），其余异常吞掉返回 null；`:42-52` `read` 非法/空 → null；`:175-181` `truncate(text,max)` 在 `max<0` 时 `substring(0,-1)` 会抛 `StringIndexOutOfBoundsException`，且按 UTF-16 截断可能切断代理对（emoji）。
- `AiToolTypes.java:116-154`：`ok/approval/needClient` 把 payload 原样放入（允许 null），`rejected/failed` 构造空对象；消费方必须判 null（未读）。
- `:188-212` 的 `objectSchema/stringParam/intParam` 用字符串拼接生成 JSON，description 未转义 → 仅当传入含 `"`/`\` 的文案时坏 schema；这些方法由代码 handler 使用，声明式工具走 DTO 的 `argumentsSchema`（`AiToolService:418`），故不构成后台输入注入路径。
- 反证：这些是“不中断主流程”的既定设计（类注释明确），不是缺陷；仅记录语义与边界。

## 3. 反证与不可主张（防误报）

- **已撤回错误**：“HIGH 风险 + binding approval=NONE 会自动绕过审批”是此前撤回的错误主张。本报告只记录 O4 的静态事实（override 无 enum/floor 校验、读取侧 approval 未对齐 floor），不推断其审批效果；审批决策代码未读。
- **strict REQUIRED 与 HELD wildcard**：本 3 源只有 `splitPermissionCsv` 的解析侧严格格式（O1）；写侧不校验（O2）；本 3 源不存在 HELD 通配结构。不能把 DB 中的通配/3 段值视为“被接受的 REQUIRED”，也不能据此宣称运行时会拒绝或放行（消费方未读）。
- **testRun 的 actor/manual approval/floor/super/status/schema**：本 3 源只可见无 actor 参数、`approved=true`、无 `ai_tool_call` 写入（O5）；权限/超管/操作日志/审批下限的最终判定在未读 controller 与编排层 → 未知，不结论。
- **binding risk/approval 准入**：risk 下限在 `update` 与 `resolveEffective` 两处有保护（O7/O4）；approval 只在 `update` 有 HIGH→ADMIN_CONFIRM 保护，绑定覆盖无下限（O4）。
- **code/declarative code collision**：仅 O9 的静态路径；DB 唯一约束与运行期启动顺序未读/未测。
- **saveCSV/client filters**：`joinCsv` 空→NULL（O3）、写侧不校验（O2）、client 过滤 null/大小写（O10）；均无运行期证据。
- **未测**：未跑 Maven/测试/服务/API/DB/网络；无 background job；未执行任何工具；未验证启动期 sync、并发、事务回滚或真实 SQL。

## 4. 未覆盖

- `AiChatOrchestrator`（if any 的审批/权限/工具调用路径）、`AiAdminToolController`、`AiAdminAgentController` 的调用点、DTO（`DeclarativeToolSaveReq` 校验注解）、`AiConstants`（RiskLevel/ApprovalPolicy/ExecutorType 的 `all()/order()` 实现）、`AiProperties.HttpTool.allowedHosts` 默认值、`HttpToolExecutor`/`SqlToolExecutor`/`McpToolExecutor`、entity/mapper/schema、测试。
- 跨文件因果（例如 O2/O3/O4/O5/O9/O10 的最终运行效果）均依赖上述未读文件，本报告不作结论。

## 5. 产物

- 本文件：`astral/docs/audit/2026-11-07/backend-ai-shards/runtime-06-current.md`
- JSON：`astral/docs/audit/2026-11-07/backend-ai-shards/runtime-06-current.json`
- 产物自身 SHA256 见 closing（不能内嵌）。
