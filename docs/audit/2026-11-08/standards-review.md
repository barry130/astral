# Standards 独立复审（2026-11-08）

## 核心结论

**R3-F1已关闭：最终渲染请求头同时用于摘要和发送；实际secret引用值A→B会改变新计划hash，旧快照保持。** 最新日志为5项生命周期与1项MCP快照共6项GREEN。固定基线 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`，审查对象为WIP；历史发现按时间保留，以下为最新状态。

- **LIFE-STD-F1/P1待修：** 阶段预占后、结算finally前解析提供方密钥；解析失败遗留RESERVED及日/月预占。
- **LIFE-STD-F3/P2待修：** onCompleted先发布内容后接收实际tokens；取消/超时阻止发布时会丢掉已知用量，以零结算。
- **R3-F2/P2待修：** 新构造器提前展开缺失secret引用，异常穿透handlerAvailable，工具列表整页失败，也违背坏配置跳过的现有服务合同。
- **LIFE-STD-F2实现已改、待回归证据：** failStaleRequests现以过期lease条件CAS终止，并显式清并发标记/执行token/lease；原终态占并发路径消除。崩溃时未知实际消耗不据此视为已结算。
- **此前关闭项保留：** S1–S6、R9-F1–F3、SYS-F1、SEQ-F1、PROVIDER-F1、ALERT-F1、SQL-AST-F1–F3、HTTP-TR-F1–F5；其日志及限制见对应追加。监控指定修复已核验。

最新6项GREEN不覆盖上述新增边界。request/usage/tool-call新增字段在实体、JSON与增量迁移中对应，INPUT及自动填充保留；未新增强制Fowler smell结论。仅核读源码、测试及既有日志，未运行测试、构建、数据库或网络，不作全仓通过结论。

## 证据与修复建议

### S1：敏感配置在业务表加密后，仍流入操作日志

- 新建入口：`astral-plugin/src/main/java/com/astral/ai/controller/AiAdminMcpController.java:73–76`，`@OperateLog("新建 AI MCP 服务器")`；首参是 `McpServerSaveReq`。
- 输入形状：`astral-plugin/src/main/java/com/astral/ai/dto/AiAdminDtos.java:591` 的 `headers` 为字符串，传输内容形如 `{"headers":"{\"Authorization\":\"Bearer ...\"}"}`。
- 持久化链：`astral-log/src/main/java/com/astral/log/aspect/OperateLogAspect.java:268–276` 序列化 `args[0]` 后调用 `LogSanitizer.mask`。其 `JSON_PAIR` 把外层 headers 字符串整体匹配，非敏感键原样保留，不递归分析该字符串。
- 标准：`astral-common/src/main/java/com/astral/common/util/LogSanitizer.java:8–18`、`OperateLogAspect.java:274–276` 明确解释日志凭据隔离；不是仅凭泛化“response 禁止凭据”规则推导。
- 独立验证：在现有编译产物上用 JDK 25 JShell 调用 `LogSanitizer.mask`，嵌套 Authorization 测试值原样返回。未构建、未联网、未写数据库；该验证不替代当前源码重新编译测试。
- 建议：新建敏感配置入口用 `@OperateLog(value = "…", recordPayloads = false)`；保留操作人、目标、结果元数据，补切面请求采集路径回归。相同 JSON-string 形状的 HTTP/SQL 工具配置值得同步核对。
- 范围修正：updateServer 的第一参数是 id，该切面不会采集其第二参数请求体；**不把更新请求体记录列为已证实泄露路径**。这是此前日志设计的缺口，在本轮 MCP 凭据保护改动后仍存在，不称为新引入漏洞。

### S2：显式清空与“不提供”的语义需要区别

关键 hunk：

```java
if (req.headers() != null) {
    entity.setHeadersJson(AiMcpHeaderSecurity.protectAtRest(
            properties.getSecretKey(), blankToNull(req.headers()), previousHeaders));
}
// ...
serverMapper.updateById(entity);
```

`AiMcpServerEntity.java:36` 的 headersJson 无 ALWAYS 更新策略，应用配置未设置非默认策略。空串清空得到 null，默认 MyBatis-Plus NOT_NULL 不生成该列 SET；服务返回的内存对象可能已为空，重新读取仍有旧凭据。此为源码及框架默认策略证明，未声称运行真实 SQL。修复建议为 null 表示不改、空串表示清空的白名单 wrapper；用隔离实际 mapper 验证存储结果，单纯捕获 updateById 参数的 mock 无法证明 SQL 会写入 null。

### S3：白名单更新绕开领域副作用

`astral-system/src/main/java/com/astral/system/controller/UserRoleController.java:96–103`：

```java
var update = userRoleService.lambdaUpdate().eq(UserRole::getId, id);
// 按非空字段 .set(userId / roleId)
update.update();
```

旧入口 `updateById` 的 override 位于 `UserRoleServiceImpl.java:84–100`；其读取前后状态，在同用户时发角色差异通知、用户迁移时通知原用户和新用户。wrapper 调用不经过该 override。权限版本 bump 仍执行，因此**不是权限缓存失效遗漏**。应新增显式事务服务操作，在白名单写之后保留 before/after 通知；测试需走 controller 委托的新路径。文档来源为 `NotifyEventRegistry.java:57–58,146–148` 及 `UserRoleChangeNotifier` 的事务后通知合同。

启发式补充（判断项，不额外计数）：possible Shotgun Surgery——同一角色变更的持久化与通知约束散在 controller、通用 ServiceImpl override 和 notifier；本次改写更新入口即遗漏约束。采用单一领域操作可减少这一风险。仓库支持 Service 层，不能因此移除正常分层。

### S4 / S5：统一在配置确定后验证类型约束

S4 的触发条件是 `defaultType=SNOWFLAKE`、业务键以 `_id` 结尾、请求 type 为 null/空，且配置不存在；第 290 行跳过校验，第 299 行创建 SNOWFLAKE，`nextResult:144–147` 按实际配置调度。默认部署为 SEGMENT 限制了通常触发面，但不消除全路径锁定标准。现测试仅覆盖显式 SNOWFLAKE。

S5 的触发条件是并发首次相同 bizKey 使用不同显式类型：两者先读 null，败方 insert 得 DuplicateKeyException，重读获胜行后直接继续。仅 outer else 存在一致性检查。现测试 `concurrentAutoCreateRereadsWinningConfigAfterRealUniqueConflict` 使用相同 SIMPLE，未覆盖不同类型。

建议把“实体 ID 必须 SEGMENT、显式请求与实际类型一致、配置启用”放在正常读/新建/唯一冲突重读之后统一验证；在写入前仍保留非法类型和实体类型保护，避免错误配置落库。补省略类型的 `_id`、不同类型 winner、已存异常 `_id` 配置三个回归。

### S6：已读源码的存量标准违例

1. `astral-system/src/main/java/com/astral/system/controller/TableSchemaController.java:145–146,213–215`：`includeCommonFields=true` 采用 `id.setIsAutoIncrement(true)` 与 `id.setMybatisPlusIdType("AUTO")`。应为常规业务 schema 生成 INPUT/非自增，并保留时间填充字段。`PLUGIN_GUIDE.md:233–235` 与 AGENTS 一致。未执行实际生成器或插入，不称为已观察到运行故障。
2. `astral-system/src/main/java/com/astral/system/service/impl/SysMenuServiceImpl.java:58–93`：`PatchValues.orDefault(menu.getName(), existing.getName())` 等把未提交值写回。拖拽只改 sort/parentId 与另一管理员改名并发时，旧读值可能覆盖新名称。直接按字段是否提供决定 SET；显式空串仍可清空 icon/path。未运行并发实验，不声称任意非白名单列可写。

## 全项目收口的其他已核验线索

以下来自历史报告，但每项已重新读当前源码。它们不是本次 diff 新引入，也不全部归类为独立 Standards 发现；已发送 Lead 处理。未用历史“已修”/“待修”状态替代源码证据。

| 文件/位置 | 当前源码证据和影响 | 标准/性质 |
| --- | --- | --- |
| `MessageTemplateService.java:129–183` | 修改 code/scene 时删除旧 EMAIL/INAPP 行，existingMail 仍含删除对象，之后用非空 id 执行 updateById，忽略 false；模板可能消失但接口成功。 | 业务完整性；另 checkCodeAvailable 先于 originalCode 解析，改码可能自冲突，需隔离回归。 |
| `NotifyPublisher.java:249–277,333–355` | GET quota → 发送 → INCR 分离，limit=1 时两个并发请求可各自读0并发送。 | 配额原子性；源码证明窗口，未执行 Redis 并发。 |
| `InappServiceImpl.java:135–140` 与 `NotifyPublisher.java:263–277` | 插入失败被吞，调用者正常返回成功且消费配额。 | 不应伪造投递成功；publisher 已有逐次 catch，无需下层静默。 |
| `NotifyPublisher.java:95–108` | 渠道/收件人查询与 resolveRecipients 在 delivery catch 外，异常可中断后续规则。 | `AGENTS.md:212–213` 要求逐条隔离、错误进入 PublishResult 不外抛。 |
| `PermissionController.java:201–208` | 缺 domain 的权限以 code 第一段回填，admin:system:... 得 admin。 | `AGENTS.md:333` domain 为业务域第二段；新前端 helper 已跳过 side，后端未统一。 |
| `AiToolService.java:725`、MCP handler 解析 | plan hash 仅含 executorConfig/risk/approval；MCP 服务 URL/headers 当前读取，编辑服务后 pending plan hash 不变。 | Spec/安全审批绑定问题，已交 Lead；不以 Standards 条目重复计数。 |

## 范围与证据限制

- 标准源：AGENTS（优先）、CODING_GUIDE、UI_GUIDE、COMPONENTS_GUIDE、PLUGIN_GUIDE，以及领域公开合同；原需求 `docs/audit/2026-11-07/SPEC.md` 仅用于明确整体收口与授权边界。
- 读取了大范围 WIP 清单与选定 diff，重点深读 auth/权限、system/通知、AI、sequence、schema、storage、monitor，以及管理端请求/任务/会话与 blog BFF 边界。**不宣称逐行读取全部约400个变更文件，不宣称已完成视觉或浏览器验收。**
- 12 类 smell 均作为启发式检查：Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest。未将 DTO、字典字符串、链式 wrapper、规定 Service 分层机械判为缺陷；S3 的 Shotgun Surgery 仅为附加设计判断。无工具已强制格式/lint 项。
- 本子任务只读源码并写此报告；无 Maven/前端构建、真实 DB、外部网络、浏览器、提交或兄弟目录修改。已用现有编译产物做一次纯函数 JShell 脱敏探针。后续修复测试由 Lead 独占构建通道执行。

## R9 定向复核追加（2026-11-08）

### 已修证据与当前结论

本轮仅复核 `AiMcpHeaderSecurity`、`AiMcpServerService.update`、`AiAdminMcpController` 及对应测试，读取调用所必需的 JSON/cipher/render/log 实现，不扩展新审阅范围。

- **null/空白输入 NPE 已修：** `AiMcpHeaderSecurity.java:60–63` 对 `requireObject` 返回 null 立即返回。`requireObject:160–168` 使用固定 AI008 文案，非法 JSON 不回显用户内容。
- **S1 已修：** `AiAdminMcpController.java:73,83` 建改均 `recordPayloads=false`；真实 `OperateLogAspect.java:98–100,117–119` 不采集请求、响应与异常消息正文，但保留状态和异常类型。
- **S2 已修：** `AiMcpServerService.java:126–136` 改用白名单 wrapper；仅当 `req.headers()!=null` 时执行 headersJson SET，所以省略表示保留，空串变成显式 SET NULL。其他字段也按是否提供控制 SET，没有把未提交属性从旧行回写。
- **执行证据：** Lead 的 `target/takeover-r9-green.log:281,296` 为 29 tests、0 failures/errors/skips、BUILD SUCCESS。组成：controller 权限2、header security11、cipher11、真实 mapper 持久化2、service header3。本子任务只核读日志，未重新运行 Maven。
- **持久化测试有效边界：** `AiMcpServerPersistenceTest:99–107` 用真实 MyBatis-Plus/H2 service.create → service.update → service.require 验证清空，不只捕获 mapper 参数；第二项通过真实 AspectJ 代理执行日志切面、writer 与 LogService，最终日志 mapper 使用 mock，断言确实有成功记录且不含测试凭据。这证明 SQL 清空与切面采集行为；不是 Spring 事务代理、真实异步线程池、日志表数据库或完整 HTTP 鉴权验证。

**本轮 R9 尚不能关闭：以下三项由当前源码及本轮编译产物的纯函数探针确认。**

### R9-F1 / P2：仅更改头名大小写会静默删除凭据

位置：`AiMcpHeaderSecurity.java:81–85`。

```java
if (MASK.equals(value)) {
    JsonNode old = previous == null ? null : previous.get(name);
    if (old == null || old.isNull()) {
        return;
    }
```

HTTP 字段名不区分大小写，但掩码合并使用 JSON 键精确匹配。旧行 `Authorization`，编辑提交 `authorization:******` 时输出 `{}`，新 wrapper 将其真正写入数据库，凭据消失。已用当前编译产物调用公开 `protectAtRest` 确认 `case_change={}`。

固定修复契约：大小写变更视作同一头，掩码应保留旧值；同一输入中存在大小写冲突的重复头名应明确拒绝，避免合并歧义；真正清空仍通过空 headers/null 值或移除键完成。补公开 service 保存→重读→reveal 验证，不能仅断言内部查找辅助函数。

### R9-F2 / P2：非字符串 JSON 值没有受控处理

位置：`AiMcpHeaderSecurity.java:72,86,121,154`。

顶层 JSON 对象通过 `requireObject` 后，无条件 `node.asString()`。Jackson 3 对数值/布尔转字符串，对数组/对象抛 `JsonNodeException`。当前产物探针确认：`Authorization:[]/{}` 保存抛未经转换异常；`maskForDisplay({"X-Meta":{...}})` 同样抛异常，含此遗留行的列表可整体失败。null 值当前按注释省略，不是本项缺陷。探针异常未包含测试凭据值，因此此项是校验/可用性问题，不虚构额外泄露。

固定修复契约：写入只允许字符串或 null；数值、布尔、数组、对象统一拒绝为固定 AI008，不带字段值。回显/调用面对遗留非法值应省略或返回 null，不能因一个坏值打断列表；掩码沿用旧值也要检查类型。覆盖每类非字符串及旧行非字符串路径。

### R9-F3 / P1：仅前缀判断允许敏感明文绕过加密

位置：`AiMcpHeaderSecurity.java:77–79,89–90`。

```java
if (value.startsWith("v1:") || value.startsWith("${secret:")) {
    result.put(name, value);
    return;
}
```

当前产物探针确认，即使 KEK 为空，`Authorization` 值为 `${secret:SCHEME} confidential-fixture` 或未闭合 `${secret:unfinished-confidential-fixture` 仍原样落库，`revealForCall` 也原样返回。secret 前缀并不证明整个值只有引用；尾部/未闭合片段仍可能为真实凭据。违反 R9 “敏感明文加密落库，KEK 未配置拒绝明文”的公开合同。`v1:confidential-fixture` 同样原样落库，但调用时丢弃；此探针只证明伪密文被接收，不证明其可被远端使用。

固定修复契约：仅完整、合法且覆盖整个值的 `${secret:KEY}` 可免加密；含引用的复合字符串应整体加密，解密后沿用既有 MCP 模板渲染。客户端不能仅凭 `v1:` 免加密：需要受控验证/来源策略，使“沿用服务器旧值”与“客户端输入”可区分；掩码继承旧值同样不能放行伪引用/伪密文。测试至少覆盖完整引用、引用加尾部明文、未闭合引用、伪密文、正常旧密文保留，以及缺 KEK 时应拒绝的情况。

### 复核执行边界

JShell 使用本轮 surefire XML 记录的 classpath；`AiMcpHeaderSecurity.class` 时间 10:41:57 晚于对应源码 10:26:46，与本轮 GREEN 相符。仅调用纯函数，没有数据库、网络、浏览器或源码修改。已把 R9-F1–F3 发给 Lead，Lead 接手修复；在再次复核前不把其标为已解决。

## R9、序列与角色通知最终定向复核（2026-11-08 11:20）

### R9：S1、S2、R9-F1–F3 均关闭

读取当前实现与 `target/takeover-r9-final-green.log`，其中 `:281` 汇总 34 项测试，0 failures/errors/skips，`:296` 为 BUILD SUCCESS。16 项 header security、11 项 cipher、3 项 service header、2 项 mapper/AOP 持久化、2 项 controller 权限断言均有对应测试条目。本子任务未自行重跑 Maven。

| 条目 | 当前源码与验证 | 状态 |
| --- | --- | --- |
| null/blank 与输入回显 | `AiMcpHeaderSecurity:65–68` 早返回；`:182–190` 非法顶层输入固定 AI008，不包含原文。 | 关闭 |
| S1 操作日志 | `AiAdminMcpController:73,83` 建改维持 `recordPayloads=false`，切面策略未回退。真实 AOP 测试确有日志记录且不含凭据，最终日志 mapper 为 mock。 | 关闭 |
| S2 清空持久化 | `AiMcpServerService:126–136` 仍按请求是否提供字段执行白名单 SET，headers 空串→null 明确写库。真实 MyBatis/H2 create→update→require 测试通过。 | 关闭 |
| R9-F1 大小写掩码 | `AiMcpHeaderSecurity:100–113` 通过 `equalsIgnoreCase` 从旧行取值；`:71–76` 拒绝解析后的大小写冲突头名。新增回归覆盖正常密文改头名大小写后的解密结果。 | 关闭 |
| R9-F2 非字符串 | `:78–83` 写入只允许字符串/null；`:140–142,173–175` 调用/回显安全省略非字符串。数组、对象、数值、布尔写入均固定 AI008；旧值掩码分支也先 `isString`。 | 关闭 |
| R9-F3 前缀旁路 | `:39,96–98` 用完整正则匹配 secret ref；复合/未闭合引用进入加密，缺 KEK 返回 AI006。`:89–94` 直接输入 v1 密文先以当前 KEK 验证，失败为固定 AI008。 | 关闭 |

对“已存密文沿用”的界限单独核验：掩码分支仍允许原样沿用服务器旧行 `v1:` 值，以免 KEK 更换时一次无关编辑破坏旧配置；它不从本次客户端字符串获取旧密文。调用出口仍对无法解密值省略。本轮未发现该策略造成新的可证实凭据泄露，不把旧密文沿用误报为再次接受任意客户端伪密文。

使用本轮 surefire classpath 对当前编译产物再次调用公开纯函数，结果如下：

- 旧 Authorization 加密值→提交小写 authorization 掩码→reveal 后与原值一致：`case_preserved=true`。
- 数值、布尔、数组、对象写入均 `invalid_code=AI008`。
- 遗留对象/数组字段回显仅保留安全字符串：`legacy_read={"X-Trace":"ok"}`。
- 复合引用、未闭合引用在空 KEK 下均 AI006，伪 v1 输入 AI008。
- 可选 null 输入返回 null。

对应源码时间 11:06:51，编译产物时间 11:07:34；探针没有网络或数据库调用。本轮限定范围内**无新确认缺陷**。测试不等同于完整 HTTP、生产数据库、浏览器或真实远端 MCP 验收。

### S4 / S5：序列类型约束关闭

`GeneratorFactory.java:289–294` 对所有 `_id` 请求将省略类型也收束为 SEGMENT，显式非 SEGMENT 在读库前拒绝。`:321–327` 把实际类型比较移至普通读取、新建、唯一冲突重读的公共出口，之后才返回给 next/batch 调度。已有错误 `_id` 配置也会在该比较处拒绝，不会按错误类型发号。

核读 `GeneratorFactoryTest.java:122–131,179–185`：分别模拟唯一冲突 winner 类型与显式请求不同、全局默认 SNOWFLAKE 时 `_id` 省略类型。`target/takeover-sequence-green.log:216,229` 显示该类 10 项、0 failures/errors/skips、BUILD SUCCESS。mapper 和生成器为 mock，证明调度与拒绝分支，不声称真实并发数据库竞争已执行。首轮 S4/S5 的具体缺陷已消除。

### S3：角色更新通知关闭

`UserRoleController.java:93` 委托 `updateAssignment(id,userId,roleId)`。`UserRoleServiceImpl.java:93–106` 为外部 public、`@Transactional(rollbackFor=Exception.class)`，按非空白名单字段更新，随后调用共享 `notifyUpdated`；`:109–123` 读取更新后行并覆盖同用户角色差异与旧/新用户迁移通知。`UserRoleChangeNotifier` 仍注册事务 afterCommit 回调，通知失败隔离不变。

`UserRoleNotificationTest.java:213–223` 经过真实 controller 委托进入带 Spring 事务代理的服务，断言持久化角色已改且存在新增角色通知；全类同时覆盖回滚不通知、相同角色不通知和异步分发失败隔离。`target/takeover-role-notify-green.log:336,350` 为 10 项 GREEN、BUILD SUCCESS。

测试使用真实 Spring 事务与 H2 写入，mapper 为转换 wrapper 后执行 JDBC 的替身，publisher 为 mock；controller 直接调用不包含 HTTP 参数校验或鉴权代理。此证据足以确认本次“绕过服务通知”回归关闭，不把其他未测边界扩张成已通过验收。S6 和历史附录不在本次复核范围，保留待 Lead 处理/后续复核的状态。

## 系统、DATABASE 序列与监控定向复核（2026-11-08）

本轮只读核验 Lead 指定的六处修复及必要调用边界，未修改实现、运行构建或真实数据库。已核读下列执行日志，不将日志中的其他测试数量充作目标修复的额外覆盖。

| 修复 | 当前源码与回归证据 | 复核结论 |
| --- | --- | --- |
| 菜单局部更新（S6） | `SysMenuServiceImpl:80–90` 每列按请求是否非 null 决定 SET，不再从 existing 回填；icon/path/permission 空串仍用 blankToNull 清空。`SysMenuPatchTest` 在服务读取后、实际 wrapper SQL 执行前插入另一已提交 name 修改，排序后保留该名字。`takeover-menu-green.log:242,256` 总计11项 GREEN，其中菜单直接相关1项，另10项为模板测试。 | **关闭 S6 菜单部分**；H2 + mapper/JDBC替身受控交错，非真实多连接并发实验。 |
| Schema 默认主键（S6） | `TableSchemaController:213–215` 为非自增/INPUT，create_time INSERT fill 保留。`TableSchemaDefaultsTest` 子进程在临时目录走 public createSchema→generateEntity，检查实际 JSON 文件和 IdType.INPUT 源码输出。`takeover-schema-default-green.log:232,246` 1项 GREEN。 | **关闭 S6 schema部分**；未执行生成实体真实数据库插入。 |
| 权限域回退 | admin/app/all 第二段可用，但缺少规范端 user，见 SYS-F1。`takeover-permission-domain-green.log` 1项 GREEN，fixture 只含 admin、app 和历史 system。 | **未关闭原附录权限域项**。 |
| DATABASE batch 尾值循环 | `DatabaseGenerator:101–108` 用 subtractExact 计算起点，再以 int offset/count 有界遍历；最大 long 尾值不会递增溢出后无限循环。子进程测试 mapper 返回 Long.MAX_VALUE、count2，结果恰为最后两个 long。`takeover-database-sequence-green.log:218,231` 11项 GREEN，其中边界1项，GeneratorFactory10项。 | **count 循环修复通过**；下层真实 MyBatis 返回值存在独立重大残余 SEQ-F1。 |
| 指标硬上限及 drain | `ApiMetricCollector:39–73` record/drain 共享同步锁；最多19998普通桶及2个固定溢出桶。已存在桶继续原维度，新键只进入按 `<400`/`>=400` 区分的溢出桶；与 `StatApiHourlyMapper:45–52` 报表成功/失败定义一致。drain 在锁内复制、清空并重置溢出引用，旧值之后不会被采集方更新。 | **通过本次定向复核**。 |
| 指标路由模板 | `ApiRequestMetricInterceptor:64–67` 使用 BEST_MATCHING_PATTERN_ATTRIBUTE，不再用含实际ID的 URI；无匹配统一归 unmatched。测试20个不同ID仅形成1个模板桶，count20。 | **通过本次定向复核**。 |

指标执行证据为 `takeover-metric-route-green.log:244,324,346`：collector2项与 interceptor1项，共3项 GREEN。collector 测试实测30k个不同维度键的桶数≤20k、总调用30k、耗时60k毫秒、失败15k；并发8个写者与 drain 共计80k请求无丢失/重复。它是当前内存聚合实现的并发验证，不是压力基准或真实路由服务器/数据库落库验收。饱和后放弃新请求的细分维度是实现明确选择，本轮不把这一已声明的降级泛化为新缺陷。

### SYS-F1 / P2：权限端 user 被误当业务域

- 位置：`astral-system/src/main/java/com/astral/system/controller/PermissionController.java:210–214`。
- 标准：`AGENTS.md:328–334` 明确权限码端为 admin/user/all；app 是 `/api/v1/app/**` 路径命名，不是替代 user 权限前缀。实际 `QtPlugin` 常量为 `user:qt:source:channel:beta`，`AiPlugin` 也使用 user:ai。
- 当前 hunk：`Set.of("admin", "app", "all").contains(parts[0])` 漏掉 user。显式 domain 缺失时，该类真实权限仍回退第一段，产生 user 分组而非 qt/ai。
- 独立证据：当前编译产物的公开 `PermissionController.getTree("domain")`，以 service mock 返回 `user:qt:source:channel:beta`，输出 `user_domain=user`。不依赖反射私有 domainOf，也未调用网络/DB。
- 建议：识别 user，可保留 app 作为历史兼容；新增公开分组路径测试，覆盖 user:qt、user:ai 和 all 以及已有 admin/历史码。它是已有问题的修复不完整，不称为安全提权漏洞。

### SEQ-F1 / P1：RETURNING Mapper 实际返回更新计数

- 位置：`astral-dao/src/main/java/com/astral/dao/mapper/SequenceStatisticsMapper.java:47–51`，由 `DatabaseGenerator.next:62`、`batch:91` 调用。
- 当前声明为 `@Update("UPDATE sequence_statistics ... RETURNING current_value") Long incrementAndGet(...)`。MyBatis 为 UPDATE command 执行更新通道，返回 JDBC updateCount 并转换为 Long，不消费 `RETURNING` 的 ResultSet。PostgreSQL 返回行的路径对应 updateCount=-1；本实现可把 -1 当非 null 序列值返回。即使某 JDBC 情况返回受影响行数1，仍不是递增的 current_value。
- 标准：`AGENTS.md:124` DATABASE 持久化发号，以及 `DatabaseGenerator:12–16`、Mapper `:28–45` 的原子取回新值/全局唯一合同。该问题属于既有 Mapper，不是本轮 count 循环新引入。
- 独立验证：通过当前实际 MybatisConfiguration、SqlSessionFactory、`SequenceStatisticsMapper` 代理，底层仅 DataSource/Connection/PreparedStatement 使用 Mockito 替身，配置 execute=true、getUpdateCount=-1（表示 SQL 产生 ResultSet）。实际输出 `command_type=UPDATE`、`returned=-1`、`resultset_reads=0`。因此证明框架未读取返回行；**没有宣称运行真实 PostgreSQL**。
- 当前 GREEN 的 `DatabaseGeneratorBoundaryTest` 直接 mock mapper.incrementAndGet 返回 Long.MAX_VALUE，测试了循环终止但无法发现映射层问题。
- 建议：将该条 DML 返回行按 `@Select(value=..., affectData=true)`（或对应 XML select affectData）映射，并显式核对 `flushCache/useCache`，确保每次分配都实际执行；增加真实 MyBatis→JDBC ResultSet 替身回归，断言 public next/batch 取得 current_value 而非 updateCount。修复前不应把 DATABASE 生成器整体标为正确。

本轮没有确认监控其他重大残余。上述两项已实时通知 Lead，等待实现修复与后续独立复核；其余历史条目状态不因本轮局部 GREEN 自动关闭。

## 序列真实 PG、告警凭据及 Provider 约束定向追加（2026-11-08）

本轮按 Lead 指定范围只读源码、测试与已有日志。**没有运行 Maven、数据库、HTTP 请求或新探针，没有修改实现。** 以下结论仅适用于所列缺陷，不作全仓结论。

### SEQ-F1：真实 PostgreSQL 证据支持关闭

`SequenceStatisticsMapper.java:48–53` 当前为：

```java
@Select(value = "UPDATE sequence_statistics ... RETURNING current_value", affectData = true)
@Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
Long incrementAndGet(...);
```

SELECT 映射消费返回行，affectData 标记修改语义，flushCache 在执行前清理会话缓存，useCache=false 不使用二级缓存。保留 `DatabaseGenerator` 按count展开的有界循环。

`DatabaseGeneratorPostgresTest.java:20–49` 使用 `127.0.0.1` 的可抛弃 PostgreSQL、随机 schema、真实 MyBatis Mapper。在**同一 session** 中断言40→next41→next42→batch43,44，再断言首次新业务键生成1；finally删除自己的随机schema。该测试只有设置 audit.postgres.port 时启用，以下日志明确不是 skipped：

- `target/takeover-sequence-pg-red-2.log:205` 原实现 expected41、actual-1，证明原缺陷真实重现。
- `target/takeover-migration-green.log:268,272` 该测试1项、0 failures/errors/skips；`:617` BUILD SUCCESS。日志还包含 FreshDatabaseMigrationTest，后者不计入本条发号验证。

因此关闭 SEQ-F1，证据已从先前JDBC替身上升为隔离真实PG公开 next/batch 回归。**本子任务只核读证据，没有访问该实例或生产。**

### SYS-F1：user 权限域回退关闭

`PermissionController.java:211` 当前前缀为 admin/user/app/all，规范中的user已覆盖。`PermissionDomainTest.java:21–30` 新增 `user:storage:file:view` 并要求storage分组。`takeover-permission-schema-final.log:234–240,254` 权限1项、schema1项均GREEN。本轮未重新执行测试。

### 告警指定修复成立，但 ALERT-F1 仍待修

`AlertAdminController.java:76–99` 返回 `ChannelView`，WEBHOOK url与secret输出掩码，EMAIL/SMS只投影支持的收件字段，不再把整份config或可变实体原样交给列表。`:105–149` 写入校验JSON对象及string/null值、限制字段白名单；url/secret掩码只从相同渠道类型的旧配置合并；没有旧值的孤儿掩码拒绝。`:216–228` wrapper把整体规范config写回，所以secret空串确实清除，未受NOT_NULL策略影响。

`AlertChannelCredentialTest` 的第一项验证列表响应没有secret和URL token，且原实体未被掩码就地修改；第二项使用真实MyBatis/H2及临时loopback HttpServer，经public list→updateChannel→testChannel验证掩码重存后仍向原路径携带原secret，显式清secret后下一次发送不含该头。`takeover-alert-save-green.log:249–253,268` 为2项GREEN。该证据不覆盖非法字符失败链路，也不是完整HTTP鉴权验收。

**ALERT-F1 / P1：非法非CR/LF字符使secret进入异常与记录。**

1. `writableConfig` 允许JSON字符串中的 `\u0001`、DEL或大于255的Unicode字符；`AlertEngine.validateChannelConfig:339–352` 只增加URL策略校验。
2. `AlertEngine.deliverWebhook:288–293` 仅拒绝secret的CR/LF，随后调用 `HttpRequest.Builder.header(headerName, secret)`。
3. 本机JDK25 `src.zip` 中 `java.net.http/jdk/internal/net/http/common/Utils.java:555–567` 拒绝上述非HTTP字符；`HttpRequestBuilderImpl.java:115–116` 对失败抛 `invalid header value: "<完整value>"`。
4. `AlertAdminController.testChannel:263` 将 `e.getMessage()` 返回调用者；定时告警路径 `AlertEngine:184–199` 将该消息写日志和 `AlertRecord.errorMsg`。`AlertAdminController.recordPage:425–438` 的channel:view权限可读失败记录，因此新列表脱敏并未覆盖这个输出面。

这与 `docs/CODING_GUIDE.md:86–89` 凭据不得进入出参的安全规则冲突。它是源码及实际使用JDK源码确认的错误传播链，不声称执行真实发送或观察到生产泄露。

可执行修复：保存与发送前采用同一header-value校验（包含非CR/LF非法字符），错误用固定、不带值的文案；在Builder边界捕获可能携带值的IllegalArgumentException并转固定安全错误，覆盖遗留坏行。回归应涵盖合法凭据保留、非法非CR/LF secret拒绝、public testChannel错误及定时AlertRecord.errorMsg/日志不含原凭据。现有2项正向GREEN保留，但本告警凭据专项需等待该项收口。

### PROVIDER-F1：显式更新的必要契约

当前快照 `AiProviderService.update:141–169` 设置 encryptedApiKey/secretRef 后调用 `providerMapper.updateById(entity)`；`AiProviderEntity:37–41` 无ALWAYS策略，应用无覆盖默认NOT_NULL。API key→ref时旧encrypted列仍在，而 `resolveApiKey:251–278` 明确先尝试encrypted，所以会继续使用旧key；ref→key时旧ref未真正清除。`AGENTS.md:364–370` 已明确禁止依赖置null完成此更新。

应把两凭据列作为一个有明确输入语义的更新单元；推荐保持现有DTO公开合同：

| 请求形态 | 必要持久化行为 |
| --- | --- |
| 非空apiKey | 加密新值，显式SET encryptedApiKey；显式SET secretRef=NULL。apiKey优先于同时提交的ref，与DTO“apiKey空时ref生效”一致。 |
| apiKey空/未传，secretRef非空 | SET规范化ref；显式SET encryptedApiKey=NULL，保证切换生效。 |
| apiKey空/未传，secretRef未传null | 两凭据列均不SET，保留原来源。 |
| apiKey空/未传，secretRef空串/空白 | 显式清ref；不要自动清encrypted。前端编辑将无ref填成空串，apiKey留空明确表示不改，普通改名不可清掉存量key。 |

其他必要约束：

- 普通配置仅SET本次提供的白名单字段；updatedBy/updateTime服务端填写，providerCode/createdBy/createTime不随编辑回写；尤其不能把require时读到的healthStatus/lastHealthTime/lastHealthMessage覆盖掉并发健康检查。
- 输入加密、registry类型与URL校验应在写入前完成，失败不落半套配置。create同时提交key/ref也应按同一优先规则存单一来源或明确拒绝，不能创建互相掩盖的双来源。
- `ProviderPanel.tsx:418` “引用优先于数据库密钥”与当前后端和DTO的apiKey优先语义相反，改为准确的切换说明，避免用户误解。保留`:411–415` apiKey留空不修改的约定。
- 用真实mapper保存后重新读取验证两个方向的切换、清ref、只改名称时保留key、空apiKey不改、双输入规则。增加受控交错健康更新，验证白名单SQL不写旧health字段；mock只断言entity置null不构成持久化证据。
- 切换后检查credentialSource及resolveApiKey实际选择新来源；ref解析可用隔离cipher替身，避免依赖真实环境变量或外部提供方。

PROVIDER-F1在本次读取时仍未实施；上述是修复前合同与已确认的旧值残留问题，不是已完成验证。已将ALERT-F1与Provider必要约束发送Lead。

## Provider、告警修复复核与 SQL 兼容建议追加（2026-11-08）

本轮仅核读当前源码、测试与 Lead 已有日志，未运行构建、测试、数据库或网络请求；仅更新本报告。前节的“待实施/待修”是历史快照，本节覆盖这两项的当前状态。

### PROVIDER-F1：显式凭据更新关闭

`AiProviderService.java:133–179` 使用 `LambdaUpdateWrapper`，每个普通可编辑字段仅在本次提供时 SET；updatedBy/updateTime 由服务端设置。`:149–156` 对非空新 key 显式清 secretRef；对非空 ref 显式清 encryptedApiKey；空 ref 仅清 ref，不清仍有效的密钥；两者未提供则不写凭据列。没有回写旧 name/healthStatus/lastHealthTime/lastHealthMessage。更新后重新 require，且检查受影响行数，不再以内存对象冒充存储结果。这符合 `AGENTS.md:364–370` 的白名单更新及明确清空合同。

create `:101–103` 同样保证单一来源，显式 key 优先。`ProviderPanel.tsx:418` 已改为“API Key 留空时，填写引用会替换已存密钥。同时填写时使用新的 API Key”，与 DTO/API 语义一致。

`AiProviderPersistenceTest.java:89–141` 使用真实 MyBatis-Plus Mapper、隔离 H2 和真实 cipher。覆盖 key→ref、ref→key、空 ref 清除及空 key 保留、凭据省略、create 双输入、更新前另一连接已提交 name/health 变更。写入交错由 Executor interceptor 注入，检查的是公开服务下一次读取结果；新 key 还经过 resolveApiKey 验证。

- `target/takeover-provider-red-2.log:256,332`：六项中的五项在旧实现失败、BUILD FAILURE；具体包含清除不持久化、双来源和并发名称覆盖。
- `target/takeover-provider-alert-green.log:303,323`：六项全部通过、无跳过、BUILD SUCCESS。

因此关闭 PROVIDER-F1。本证据覆盖持久化语义，不等同于生产数据库、真实环境变量或外部提供方验证；测试没有启用服务 Spring 事务代理。未确认本修复范围新增缺陷。

### ALERT-F1：非法 header 值泄露路径关闭

`AlertEngine.java:300–311` 的共享校验拒绝除 HTAB 外的控制字符、DEL、超过 0xff 的字符，规则与本机 JDK header-value 校验一致，异常为固定“WEBHOOK secret 含有非法请求头字符”，不带输入。`:289–291` 在 HTTP Builder 接收 secret 前校验，保护遗留数据的发送路径；`:356–362` 保存校验也调用同一函数。此前 JDK 将完整非法值拼入异常的已证实路径被截断，满足 `docs/CODING_GUIDE.md:86–89` 的凭据不进入出参规则。

`AlertChannelCredentialTest.java:39–70` 新增遗留非法 secret fixture，经公开 `testChannel` 断言异常不含凭据标记，再经 `evaluate()` 捕获 FAIL 记录并检查 errorMsg；其 mapper 为 mock，固定校验发生在实际发送之前。已有两项继续验证列表安全投影以及真实 MyBatis/H2 + 临时 HttpServer 上的掩码重存和空 secret 清除。

`target/takeover-provider-alert-green.log:251,323` 为三项全部通过、无跳过、BUILD SUCCESS。关闭 ALERT-F1；新测试没有单独捕获 logger 输出，不把日志内容断言表述为已执行，固定错误文案的源头控制支持原日志传播路径修复。未确认本修复范围新增缺陷。

### SQL AST 兼容范围：设计建议，尚非实现复核结论

约束来源：`AI_MODULE_DESIGN.md:875–883` 定义 SQL 为后台配置的本系统报表/明细查询；`:918–931` 明确 `astral.sys_user` 的 id/name/status 示例、单条 SELECT/WITH SELECT、尾分号、命名绑定及显式回传列、行数/超时限制。`columns` 约束输出标签，未定义来源表/列授权。`:1810` 不允许 AI 执行任意 SQL，应与固定声明式模板共同理解，不能把模型参数升级成原始 SQL。

本轮 Lead 另明确要求保留 COUNT 与引用标识符。已有 `SqlToolExecutorTest.java:55–60,104,127–143` 覆盖表 CTE/常量 CTE、尾分号、含关键字的字符串和 update_time 等合法标识符。`ToolPanel.tsx:69–103,785–810` 的配置/表单只有 sql、columns、maxRows、timeoutSeconds，没有来源表/列字段。

可执行建议（不列为当前新确认缺陷）：

1. 按 AST 区分 CTE/子查询别名与真实来源；在所有层级及 WHERE/JOIN/GROUP/HAVING/ORDER 表达式检查真实列来源，避免只查 SELECT 列导致敏感列通过别名、表达式或谓词绕过。COUNT(*) 应允许对已获准关系计数，与 SELECT */table.*、整行 JSON 投影分别处理。
2. 不按 sys_ 前缀一刀切，至少保留明确约定的 sys_user(id,name,status)。正确解析引号、schema 与别名，保留 PostgreSQL 引号标识符的大小写语义，不能把引号内容剥掉后当作无来源查询。
3. 来源白名单应由可信服务器策略定义，工具声明只能收紧；在同一可编辑 executor_config 中自填 allowlist 不能单独构成权限边界。为其他业务报表提供明确的服务器配置扩展，否则“默认仅 sys_user”会收窄原有能力，需说明迁移/拒绝原因。
4. 若增加必填工具配置字段，需同步保存校验、前端 buildExecutorConfig/编辑回填、已有行迁移及文档；当前表单重新保存会丢弃未知字段。常用只读聚合/表达式应与危险函数区别处理；未知 AST 形状可拒绝，但须有明确错误与回归矩阵。

以上是原设计与本轮兼容要求的边界建议，未对正在修改的 AST 实现做通过结论，也未访问数据库验证查询。

## SQL AST 安全与兼容独立复核追加（2026-11-08）

范围：`SqlReadPolicy`、`SqlToolExecutor`、`AiProperties.sqlTool`、调用入口、现有测试与日志、相关真实 DDL/JSON。只读实现；通过 javap 检查已安装 JSqlParser 5.2 的字段和解析器字节码，没有执行解析探针、Maven、测试或数据库。Lead 同时修复的 ORDER BY复杂别名、LIKE ESCAPE及物理表schema绑定不重复列为未修发现。

### 已实现的边界与现有验证

- validate/create共用来源政策；部署级 `allowedColumns` 不取自可编辑 executor_config。执行路径 `SqlToolExecutor:322` 再次调用 prepare，使用已校验AST生成的SQL，未限定schema的物理表被绑定到astral，CTE名称保留自身作用域。
- 逐一核读 SELECT/CTE/派生表/集合查询、FROM/JOIN ON/USING、WHERE/HAVING/GROUP BY/分组集合/DISTINCT ON、ORDER/LIMIT/OFFSET/FETCH，以及函数参数/函数内ORDER、二元式、CASE、CAST、IN/EXISTS、标量子查询。未知表达式走default拒绝；未知来源拒绝，表函数、系统表、整行`u`和星号投影已有否定回归。COUNT(*)为单独例外，新增保护拒绝星号的EXCEPT/REPLACE附加结构。
- 只读连接、手动事务rollback、PreparedStatement绑定、行数与超时上限保留；SQL异常返回固定文案，不再传播原SQL错误。有限支持语法是安全策略，不代表支持全部PostgreSQL SELECT；较复杂安全语法应在文档说明，不凭猜测扩充放行。
- `target/takeover-sql-policy-red.log:244` 首版17项有16项RED；`green-2.log:251–257` 旧14+新17共31项GREEN。后续 `edge-red.log:244–262` 明确复现LIKE/ORDER和影子schema返回；`scope-red.log:261–262` 复现下述F1。
- 最新 `takeover-sql-policy-edge-green.log:251–257,272` 为旧14+新27共41项，0 failures/errors/skips、BUILD SUCCESS。实际H2执行覆盖绑定参数的引用标识符CTE/count、未限定表名读取获准schema；业务扩展JOIN/UNION及显式别名关联子查询经过校验。不是PostgreSQL全语法或生产鉴权验收。

### SQL-AST-F1/P1：裸列错误回退外层——已关闭

首版 `Scope.column` 在本层可读列集合无匹配后调用 parent.column，把“未授权”误当成“物理列不存在”。最小查询：

```sql
select (select password from astral.sys_user limit 1) as name
from (select 'safe' as password) x
```

内层password原本被外层安全x.password授权，而数据库优先绑定内层真实password。`takeover-sql-policy-scope-red.log:261–262` 参数化第23项证明旧校验未拒绝。当前 `Scope.column:424–431` 对裸列本层无匹配立即拒绝；只有明确限定表别名的列允许继续向外层寻找。41项GREEN含该拒绝与安全`v.id=u.id`关联路径，关闭。

### SQL-AST-F2/P1：Column附加数组表达式未检查——已关闭

JSqlParser5.2 的 Column 除名字外还持有ArrayConstructor；parser.Column()会将下标表达式放入该字段，Column.toString也会输出它。首版`case Column -> scope.column(column)`未访问该字段，遗漏边界不同于已知LIKE ESCAPE。提交给Lead的回归：

```sql
select a[(select length(password) from astral.sys_user limit 1)] as name
from (select '{1,2}'::integer[] as a) x
```

当前 `SqlReadPolicy:283–287` 直接拒绝非null `column.getArrayConstructor()`，符合有限支持语法；独立ORDER BY输出别名例外也要求该字段为空。41项GREEN中包含该输入拒绝。此项是源码/解析器结构确认的遗漏及修复；未声称旧输入已经经过数据库执行，现有日志没有它单独的RED。

### SQL-AST-F3/P1：不存在的默认来源列可被解释为整行——待修

`SqlReadPolicy:51–55` 默认允许sys_user的name/user_name，却没有真实username/nickname；sys_dict_data允许sort而非dict_sort，sys_dict_type没有sort。真实依据是 `V20260914001__init.sql:84–99,167–194`、`schema/sys_user.json:19,39`、`schema/sys_dict_data.json:44` 及当前生成实体；后续迁移未重命名这些字段。

这不只是合法报表被误拒。PostgreSQL对不存在的裸列可按同名FROM别名解析整行，以下查询会被当前默认名单判为已获准name：

```sql
select name as value from astral.sys_user name
```

实际sys_user没有name列，该表达式指向整行，包含password/totp_secret等本来未授权的列。`SqlToolExecutor.readRow`仅检查输出标签value，`:446` 对未知JDBC对象使用`String.valueOf(value)`，PG复合值会进入结果。依据是实际DDL、PG名字绑定语义和出口源码；本子任务未执行数据库，不把它表述为已观察的生产泄露。

违反的具体约束为 `AI_MODULE_DESIGN.md:1803,1810` 凭据不得进入前端/普通消息、AI不得执行任意SQL，以及本次来源列政策的明确授权合同；`AGENTS.md:88,132` 规定实际PostgreSQL/Schema生成实体是运行事实。旧设计示例`name`不构成把不存在字段加入来源政策的理由。

可执行修复：默认名单按真实schema允许username/nickname/dict_sort，移除不存在来源列；把文档和真实执行fixture改为`nickname AS name`或`username AS name`保留输出契约。可进一步把唯一授权的裸列在AST中绑定为`sourceAlias.column`，避免部署策略误列假字段时再次退化为整行别名；来源修复优先，限制未知复合JDBC输出只作第二道保护。回归必须使用贴近真实sys_user的列结构并拒绝上面的整行别名输入，不能在fixture里添加实际不存在的name掩盖问题。

前节“保留sys_user(id,name,status)示例”的建议在真实DDL核验后修正：保留id/name/status输出形状，name应由真实列显式别名产生，不能保留不存在的来源授权。已实时发送Lead，本报告暂不关闭本项。

## SQL F3 与共享 HTTP 传输独立复核追加（2026-11-08）

本轮只读实现、测试和已有日志；未运行 Maven、前端构建、测试、DNS/HTTP请求、数据库或浏览器。检查 Apache HttpClient/Core 的现有依赖字节码仅为确认配置默认值，不构成执行验证。下列行号为本次审阅时快照，Lead 同时修复，后续以追加复核为准。

### SQL-AST-F3/P1：真实来源列与整行隔离——已关闭

`SqlReadPolicy.java:51–55` 已按真实DDL改为username/nickname/dict_sort，移除name/user_name及不存在的sort；`:423–430` 把本层唯一匹配的裸列改写为带引号的来源别名限定列，防止错误部署列配置退化为整行引用。`SqlToolExecutor.java:421–446` 只序列化明确支持的标量/日期等类型，未知复合JDBC类型固定拒绝，删除`String.valueOf(value)`兜底。

`SqlToolReadPolicyTest` 使用接近真实sys_user的nickname列，不再创建假name；添加`select name as value from astral.sys_user name`拒绝，安全查询通过`nickname AS name`保留输出契约。`target/takeover-sql-columns-red.log:245,288` 为28项中2失败1错误；`target/takeover-sql-columns-green.log:251–257,272` 为旧14+新28共42项全部GREEN、无跳过、BUILD SUCCESS。因此关闭源码确认的F3。它不是PostgreSQL全语法验收；文档示例/支持子集由Lead在收口时同步。

### 传输已确认的控制与58项既有证据

- `BoundedHttpTransport.java:74–95` 仅向实际连接返回政策批准的地址快照，resolver拒绝切换主机；HTTP/MCP使用`HttpToolExecutor.approveTarget`，Webhook使用`WebhookUrlPolicy.approve`。每次请求重新批准，固定原hostname，未通过改URL为IP绕过TLS主机名校验。
- 传输关闭重定向、自动重试、压缩与协议升级；声明长度过限立即取消，未知长度最多读`maxBytes+1`并在超限时取消，不等EOF。读取阶段有硬取消和socket超时；响应体保持UTF-8约定，MCP session、发现/调用/DELETE接线保留。
- 请求头在进入Apache构建器前检查，传输IOException和HTTP状态不带原URL、请求头或远端正文。Apache Core5.4.4默认响应头限制为单行8192/最多100头，未发现无界头缓冲证据。
- `target/takeover-http-mcp-alert.log:60,287–295,331–340,356` 为common3、monitor12、plugin43，合计58项GREEN、BUILD SUCCESS。覆盖获准DNS固定且Host保留、不跟随重定向、chunked响应未EOF即超限返回、流读取超时、非法header不回显、HTTP/MCP真实调用与关闭、告警既有凭据语义。夹具使用临时回环HttpServer与注入政策；不宣称外部TLS/实际DNS测试。

### HTTP-TR-F1/P2：截止时限遗漏政策/DNS解析——待修

关键hunk为`BoundedHttpTransport.send`先执行`policy.approve(target).clone()`，之后才计算完整`budget.toMillis()`并schedule deadline（审阅快照`:74,96–113`）。实际政策调用`InetAddress.getAllByName`；慢解析可无限等待，返回后还重新获得完整HTTP预算。`McpClient.connect:80` 另在send之前同步`requireEndpoint`，后者也解析DNS，仅修common仍遗漏公开connect入口。

违背本类“absolute deadline”公开合同，以及`AI_MODULE_DESIGN.md:910`的配置超时边界；`:1805`对模型总耗时的要求只能作为一致性依据，不把模型条款自动扩大成所有API的硬规则。建议从入口建立绝对截止，把政策解析置于有界可超时任务，连接只用剩余预算；超时即使解析随后完成也不能发请求。最小回归为注入忽略中断的阻塞policy、短预算调用及时返回、释放policy后目标命中仍为0。仅源码确认，新的RED/GREEN尚待Lead。

### HTTP-TR-F2/P1：私网开关漏拦IPv6云元数据——待修

`WebhookUrlPolicy.java:37–38` 保存`fd00:ec2::254`字符串；`:123–127` 将`InetAddress.getHostAddress()`与集合比较，后者IPv6为展开形式`fd00:ec2:0:0:0:0:0:254`。若已批准的业务域名解析到该地址，主机字面量检查不命中，字符串地址比较也不命中，`allowPrivateNetwork=true`又跳过一般私网拒绝，于是批准实际元数据目标。

直接违反该类`:22–23,33`“云元数据即使开启私网也不放行”的明确合同。使用地址字节/规范化InetAddress集合比较；注入resolver返回此IPv6，配置普通批准origin与allowPrivate=true即可回归，无需真实DNS。一般公网分类可与`NetworkAddresses`共享，但应保留元数据无条件禁止的独立约束。

### HTTP-TR-F3/P1：MCP协议错误仍可传播凭据——待修

`McpClient.request:215–217` hunk把远端`error.message`直接拼入AiException；`McpToolExecutor:137–143`记录/回传该文本，`AiMcpServerService.refresh:181–183`将其交给失败记录器并重抛。服务器或认证中间层已经收到Authorization，若HTTP200 JSON-RPC错误含`Invalid token: <收到的Authorization>`，传输层固定IOException并不能阻断该路径。

同一错误传播还有`McpClient.callTool:145–169`的`result.isError=true`内容，经`McpToolExecutor:128–130`直接成为失败摘要；只修envelope.error仍不完整。`McpClient.requireEndpoint:371,375`另在非法URL/缺主机时拼接原URL，可能回显含query密钥的配置。

违反`AI_MODULE_DESIGN.md:1803,2222`“密钥不进入错误消息/客户端/日志”。建议协议错误与工具失败用固定文案（必要时保留安全数值code），或只按实际展开凭据作精确脱敏并限长；URL形状错误固定化。用同一回环MCP夹具在envelope.error、isError两条路径回显Authorization，检查公开调用错误/失败记录无标记。该结论针对失败面，不要求丢弃正常工具结果全部业务内容。

### HTTP-TR-F4/P2：公网分类把精确保留段扩大为/16——待修

`NetworkAddresses.java:22`：

```java
&& !(first == 192 && (second == 168 || second == 0 || second == 2))
```

该表达式拒绝整个192.0.0.0/16与192.2.0.0/16，误拒普通公网192.0.3.1、192.2.1.1；TEST-NET-1实际为192.0.2.0/24。HTTP/MCP都调用本分类，即使部署显式allowlist也无法访问这些公网地址。属于源码可确定的兼容正确性缺陷，依据`AI_MODULE_DESIGN.md:909`对私网/保留地址隔离的边界，不把保守拒绝称作可利用SSRF。建议按精确CIDR比较，并用不解析网络的地址字节夹具覆盖上述两个公网正例与192.0.2.1/192.168.1.1反例。

### HTTP-TR-F5/P2：告警URL规范化在保存/发送两端不一致——待修

`AlertEngine.deliverWebhook:269`由基线`URI.create(url.trim())`改成`URI.create(url)`。保存仍经`WebhookUrlPolicy.parseTarget:78`的trim校验，`AlertAdminController.writableConfig`原样持久化字符串。因此合法URL前后带空格可保存、旧版本也可发送，当前发送却在进入批准政策前必然抛URL非法。

这是替换传输时的明确兼容回归，合同来自现有校验/发送行为，不虚构仓库强制格式标准。建议发送统一trim或保存规范化（兼顾既存行）；复用真实MyBatis/H2+回环HttpServer测试，在URL两端添加空白仍应到达相同路径。

本轮未新增Fowler硬性结论。possible Duplicated Code仅作判断提示：Webhook的地址分类与新`NetworkAddresses`重复；共享政策能减少漂移，但不能因此删除Webhook独有的元数据及私网部署规则。上述五项均已逐项发给Lead，当前58项GREEN不覆盖新发现边界。

## HTTP-TR-F1–F5 修复关闭复核追加（2026-11-08）

本节覆盖前节“待修”历史快照。只读当前实现、测试及Lead已有RED/GREEN日志，未运行任何构建、测试、数据库或网络；除本报告未写文件。未确认本轮修复范围新增实质缺陷，不作全仓通过结论。

### F1：解析纳入截止且隔离不可中断残留——已关闭

`BoundedHttpTransport.java:79–83,105,119–126`在解析前建立单一`expiresAt`；Future等待、连接/读取timeout及硬取消均使用该截止的剩余时间，进入实际执行前再次检查剩余时间。`:174–196`把政策放到虚拟线程Future中，超时/中断返回固定错误并取消Future；任务仅解析，没有后续发送权，故忽略中断的DNS稍后返回也不能触发迟到请求。`:32–35,175–183`的32槽Semaphore由解析任务自身finally释放，调用方超时不提前放回槽位，限制无法取消的原生解析残留。

`McpClient.connect:80–83`仅同步解析URL形状，删除同步DNS预检；批准发生于有截止的send。配置入口`requireEndpoint:360–367`使用`BoundedHttpTransport.approve`和20秒预算，亦不再无限等待解析。

`BoundedHttpTransportTest:25–61`注入忽略中断的阻塞policy：250ms预算时调用在外部2秒上限内返回超时、resolver尚未释放；释放后目标命中仍为0。`target/takeover-http-review-red.log:59–69`旧实现超时失败；最终GREEN四项共同覆盖解析及流读取截止、DNS固定和提前限体积。32槽饱和/原生DNS的运行行为未单独测试，以上仅源码核验；不因此否定已修的原无限等待路径。

### F2：IPv6元数据比较及共享公网分类——已关闭

`NetworkAddresses.isMetadata:33–45`以字节识别IPv4/IPv6元数据，不依赖IPv6输出格式；`WebhookUrlPolicy:120–126`先无条件检查metadata，再按私网开关调用共享`isPublic`。因此批准域名解析至`fd00:ec2::254`时，即使allowPrivate=true也拒绝；此前possible Duplicated Code的地址分类主体已移除，Webhook独有origin/私网规则保留。

`WebhookUrlPolicyTest.rejectsIpv6MetadataBehindAllowedInternalHostname`使用注入resolver，`target/takeover-http-review-red-2.log:311–324`曾断言未抛异常，最终十项全部GREEN。该证据不需要真实元数据访问，也不等同于对所有云厂商未来地址的保证。

### F3：协议/工具失败与非法URL固定错误——已关闭

`McpClient.request:219–221`不再读取远端error.message；`callTool:145–148`在isError=true时返回固定文本及仅含isError的payload，阻断工具执行器摘要里的第二条回显路径；`:382–393`的URL形状/缺host/用户信息错误也不带原URL。后续已有日志/lastError传播只接收该固定文本，正常成功工具内容不受影响。

`ToolHttpTransportTest.mcpProtocolErrorsAndMalformedUrlsNeverEchoCredentials`的回环服务器把收到的Authorization放入HTTP200 JSON-RPC错误，调用异常无凭据标记；另检查两类含query密钥的坏URL。既有完整MCP会话测试追加isError内容回显，断言返回text和payload均无Authorization。`target/takeover-http-review-red-2.log:365–380`确认协议错误旧路径RED；最终五项GREEN包括两种协议/工具失败。测试未直接捕获服务失败记录器/日志，但固定源头及传播源码足以关闭本项，不宣称执行了新的日志AOP测试。

### F4：精确192网段——已关闭

`NetworkAddresses.java:22`仅拒绝192.168/16、192.0.0/24与192.0.2/24，不再误拒整个192.0/16、192.2/16。`NetworkAddressesTest`断言192.2.1.1、192.0.3.1通过，同时192.0.0.1、192.0.2.1、192.168.1.1及其他文档地址拒绝。`target/takeover-http-review-red-2.log:62–76`明确192.2.1.1旧判断RED；最终该测试GREEN。测试仅构造IP字面量，不发网络请求。

### F5：告警URL空白兼容——已关闭

`AlertEngine.deliverWebhook:269`恢复`URI.create(url.trim())`，与保存校验一致且兼容既存原样URL。`AlertChannelCredentialTest:120–142`在真实MyBatis/H2插入两端带空格的URL，经公开channelList取掩码、updateChannel保存、testChannel实际投递到回环HttpServer，断言路径和secret仍正确；空secret清除后再发也无旧header。`target/takeover-http-review-red-2.log:301–306`曾在公开testChannel报URL非法；最终三项全部GREEN。

### 最终日志边界

`target/takeover-http-review-green.log:60–66,289–297,333–342,358`：common5、monitor13、plugin44，合计62项，0 failures/errors/skips，BUILD SUCCESS。该轮涵盖HTTP/MCP/告警既有回归及F1–F5原触发路径；此前58项日志仍保留为历史基准。测试使用本机夹具/隔离H2/注入resolver，没有外部TLS、真实公网DNS或生产验证证据。HTTP-TR-F1–F5现全部关闭，其他领域历史项状态不随本轮GREEN改变。

## R3 MCP审批快照定向复核追加（2026-11-08）

范围仅为`AiToolService.planHash/resolveEffective`、`McpToolExecutor`、计划/审批两端接线、已有相关测试及日志；为核实新问题可达条件，另读部署配置与启动遗留请求清理。只读实现，未运行Maven、测试、数据库、网络；只写本报告。前文表格的“hash仅三项，MCP服务器行不受约束”已是历史状态。

### 已实现的服务器行绑定与风险下限

- `McpToolExecutor:51–78`复制工具、服务器及使用到的部署属性，endpoint/headers/readOnly/status不再持有调用方原实体；服务器id/url/headersJson/readOnly/status生成SHA-256摘要，公开仅返回digest。实体字段均为标量/String/不可变时间值，本次BeanUtils浅复制足以固定这些值；属性集合另建List/Map。
- `AiToolService.planHash(EffectiveTool):750–757`把旧三参数摘要与handler的服务器摘要组合；`AiChatOrchestrator:480`计划写入、`AiAdminToolController:260–265`审批重算均已调用此重载。旧/缺失MCP摘要会不匹配而拒绝，未静默兼容旧不安全计划。
- 审批先解析一次当前EffectiveTool并比hash，再CAS认领，随后`:279–280`把同一effective交给invoke，后者直接执行该handler；未在hash通过后重新读取服务器替换endpoint/headers。
- `McpToolExecutor.definition:135–145`按复制服务器readOnly与保存的readOnlyHint给出风险/确认下限；`resolveEffective:698–712`合并工具、binding和handler下限，只升不降，HIGH最终强制ADMIN_CONFIRM。因此即使旧tool行仍READ/NONE，服务器转可写也不能维持自动执行。

这些修复符合`AI_MODULE_DESIGN.md:413,1702,1808`不可变运行快照、工具版本追溯及确认前重新核验对象版本的合同。原“编辑MCP服务器行而计划hash不变”路径关闭。

### 18项证据与覆盖边界

`AiMcpPlanSnapshotTest`通过真实`AiToolService.resolveEffective`和MCP执行器、mock Mapper验证URL变更使hash不同、headersJson变更使hash不同、readOnly=false使HIGH/ADMIN_CONFIRM，以及原handler的服务器快照hash保持。`target/takeover-mcp-plan-red.log:255–270`旧实现在URL变更断言RED；`target/takeover-mcp-plan-green.log:255–274,289`为CAS4、控制器快照3、MCP快照1、确认floor7、旧三参数hash3，合计18项全部GREEN。

新MCP测试没有执行远端网络，也没有走实际controller审批完整MCP场景；已有controller测试单独证明hash不匹配/缺失不认领也不invoke、匹配后执行。两端调用重载和执行同一快照的结论另有上述源码证据。不能把这18项表述为真实MCP服务器/数据库审批集成验收。

### R3-F1/P2：secret引用实际身份未进入摘要——待修

关键hunk为构造器复制`properties.getHttpTool().getSecrets()`，但摘要只写：

```java
snapshot.put("headers", server.getHeadersJson());
```

当headersJson为`{"Authorization":"${secret:MCP_TOKEN}"}`，引用文本不变而部署的MCP_TOKEN从账号/租户A变成B时，服务器摘要及最终planHash完全相同。新handler实际执行仍由`McpToolExecutor.execute:152–163`传入新的secrets，再由`McpClient.renderHeaders`与`HttpToolExecutor.lookupSecret:564–572`取配置/环境值，因此批准A计划可实际以B身份调用。类似地，KEK改变导致已存密文从可解密变为省略，也不会改变摘要；主发现以有效引用值A→B为准，不把普通同账号轮换必然称为权限提升。

**可达前提明确：** `AiProperties`是部署配置，未发现RefreshScope或后台热改写入口；不是普通mcp:edit可直接改变secrets。当前重启入口`AiSeedInitializer:93`→`AiRequestService.failStaleRequests:304–343`仅清理request/message，不清tool_call；当前approve只验证tool_call的WAITING_APPROVAL，不检查request终态，旧计划跨部署仍能通过。后续R11若拒绝终态request可阻断这一个跨重启入口，但不能令未绑定的secret值变成已有摘要事实；本报告不重复计数生命周期问题。

违反的约束是同一不可变执行配置/实际身份与审批一致（`AI_MODULE_DESIGN.md:413,1702,1808`），不是要求明文密钥落库。建议把实际使用的引用值或渲染后header仅做内存摘要加入fingerprint，执行使用同一值快照；避免hash无关secrets造成无关凭据轮换也使计划失效。可在现有无网络fixture用相同headersJson、两套AiProperties.secrets值A/B断言新hash不同，原snapshot稳定；再覆盖配置缺失/解密失败的固定错误。若产品明确允许部署凭据替换后继续旧审批，应作为明确合同例外而不是宣称实际凭据已完整绑定。

此项已发送Lead；截至本节读取未有对应修复或RED/GREEN，不关闭R3整体。无其他源码证实的本轮新增问题；旧三参数摘要接口保留并通过既有测试，未据此要求改变非MCP合同。

## R3-F1关闭及AI request/usage/schema有界复核追加（2026-11-08）

本节覆盖上节R3-F1“待修”历史状态。范围为最终请求头快照、当前计划/审批接线、request/usage的阶段预占/终态/恢复，以及对应三份实体/JSON/新增迁移；只读生产源码、测试及已有日志，未运行Maven、数据库、服务、网络，不涉及真实凭据。检查过程中Lead正在修改恢复代码，状态分别注明。

### R3-F1：实际引用身份绑定与固定发送——已关闭

`McpToolExecutor.java:69–76`构造时解密并展开最终headers，使用`Map.copyOf`固定；`effectiveHeaders`按TreeMap稳定排序进入内部SHA-256摘要，公开仍仅返回digest。`:161–165`把同一`renderedHeaders`交给`McpClient.connectWithHeaders`，后者`:80–88`只复制header并连接，不再按当前配置二次render。部署引用实际值与审批、执行已绑定同一快照，未将明文密钥落入tool_call。新审批入口已迁到`AiChatOrchestrator.approve`，仍先对`planHash(effective)`核验，再通过同一ApprovalClaim持有的EffectiveTool执行。

`AiMcpPlanSnapshotTest.java:65–71`保持headersJson引用文本不变，把配置MCP_TOKEN由测试值A改B，断言新解析hash不同，原快照hash稳定。`target/takeover-ai-lifecycle-green-3.log:267–273,288`记载生命周期5项、MCP快照1项全部通过及BUILD SUCCESS。前一份`takeover-ai-lifecycle-red-3.log`内MCP测试已通过，RED来自新增生命周期测试，不能声称该日志证明secret测试本身先RED。此fixture不发真实MCP请求；“实际发送复用快照”另由上面的接线源码证明。

### R3-F2/P2：缺失secret引用使工具列表与整轮对话失败——待修

新增hunk提前解析请求头：

```java
this.renderedHeaders = Map.copyOf(McpClient.renderHeaders(
        AiMcpHeaderSecurity.revealForCall(this.properties.getSecretKey(), this.server.getHeadersJson()),
        this.properties.getHttpTool().getSecrets()));
```

`HttpToolExecutor.lookupSecret:564–570`对配置/环境均缺失的引用确定抛AiException；`McpToolExecutor.create:115`与`AiToolService.resolveHandler:292–295`均未捕获。公开`AiAdminToolController.tools:83–88`逐项调用`toVo:233`→`handlerAvailable:260–261`，任一ENABLED MCP服务器引用缺失即使整个GET列表失败。`resolveEffective:690–696`也在客户端过滤之前构造handler，因此不适用当前客户端的坏MCP工具亦能中断当前对话。

这是构造时机变化带来的明确可用性回归，违反`AiToolService.resolveHandler:276–280`自身文档“声明式执行配置非法时返回null……避免一条坏配置拖垮整轮对话”；不伪称为AGENTS强制风格规则。建议create捕获受控配置异常、固定错误日志并return null，继续保持缺凭据工具不可执行。无网络fixture可配置一个不存在的测试secret引用，断言handlerAvailable=false、列表和其余工具仍可返回。未实际运行该fixture，不能将已有MCP GREEN作为本边界验证。

### LIFE-STD-F1/P1：预占成功后密钥解析失败未释放阶段——待修

`AiChatOrchestrator.callModel:504–508,528,536–585`的边界为：

```java
AiUsageEntity stage = usageService.reserveStage(...);
ChatRequest chatRequest = new ChatRequest(..., providerService.resolveApiKey(...), ...);
AiProvider provider = providerRegistry.require(...);
try {
    provider.stream(...);
} finally {
    usageService.settleStage(stage.getId(), tokens[0], tokens[1]);
}
```

`reserveStage`是独立服务事务，已更新日/月token与reserved_cost、插入RESERVED行并提交；`AiProviderService.resolveApiKey`对缺失/错误KEK的已存密文会确定抛异常。该异常在finally之前离开，`runSafely`只经markFailed清请求执行状态，未结算usage，导致尚未调用Provider就持续占用token/金额。明确违反`AI_MODULE_DESIGN.md:1193–1201`失败释放未使用预占和原子扣减合同。

建议无外部副作用的配置/密钥准备放预占前，或reserve返回后立即由完整try/finally覆盖全部准备。真实H2夹具可使用不可解的测试密文，断言Provider未调用、无残留RESERVED、日/月预占归零；不需要真实密钥或外部服务。该发现已发送Lead，尚未读取对应修复/GREEN。

### LIFE-STD-F2/P1：恢复后FAILED请求继续占并发——实现已修、待回归证据

首次读取的旧恢复循环仅设置FAILED/error/finishedAt再`updateById(entity)`，保留新增quota_active=true。`AiUsageService.reserveStage:74–80`按user/agent/quota_active统计，不过滤终态，因此反复中断至maxConcurrent会拒绝后续请求；终态cancel直接返回亦不能补清。属于并发额度生命周期正确性缺陷，依据同一设计合同`:1193–1201`。

已通知后，当前`AiRequestService.failStaleRequests:344–374`改为仅找RUNNING/WAITING_TOOL的过期或缺失lease，以相同条件CAS更新，并显式设置quotaActive=false、workerToken=null、leaseUntil=null；原“变FAILED但继续占并发”的hunk已消失。本轮没有相应回归日志，不声称运行验证或整个恢复已闭环。建议真实H2预置过期RUNNING+quota_active=true，清理后验证可再次预占；未来lease的其他worker、WAITING_APPROVAL不应被错误清理。崩溃遗留RESERVED可能已产生未知费用，必须采用显式待对账/保守结算策略，不能一概按零释放；这是与原并发标记问题分开的未验证恢复边界。

### LIFE-STD-F3/P2：取消发生在完成回调时丢失已知实际用量——待修

`AiChatOrchestrator.onCompleted:566–573`先调用`onDelta(result.content())`补发非流式Provider内容，再把`promptTokens/completionTokens`写入tokens数组；而`onDelta:540–542`在取消或lease过期时立即抛异常。合法Provider只回调onCompleted并提供非空内容与实际用量时，如果此前已取消，当前结果中的已知用量被跳过，finally以初始(0,0)结算阶段。

`AI_MODULE_DESIGN.md:1062`明确“Provider已经产生的费用不能因为客户端取消而假设为零”；这里无需推测断连后的未知用量，真实计量已在ChatResult中。建议优先保存计费字段再进入可中断的内容发布。复用生命周期真实夹具，在Provider完成前取消，然后返回携带100/10 tokens的onCompleted，断言request仍CANCELLED且usage/day/month保留实际消耗。现有5项生命周期GREEN没有覆盖该时序，未运行新测试。

### Schema与基线判断边界

新增worker/lease/quota标记、provider_tool_call_id/消息关联/execution_token、stage_key/日月行/预占tokens/单价快照在三份实体、对应JSON与`V20261108001__ai_request_execution_ledger.sql`一致；业务ID仍INPUT、填充字段仍在，满足`AGENTS.md:128–132`。迁移保留旧stage_key=null的usage，并用部分唯一索引约束新阶段，未发现本轮字段类型/列名不匹配。JSON未声明新索引不直接等同运行缺陷：此处DDL以Flyway为权威，未据此提出缺少唯一约束的错误结论。

本轮没有把上述错误硬套为Fowler smell；跨request/usage/quota的分工有明确领域职责，三组事务入口也各有传播目的，未据此要求消除分层/委托。以上三项当前开放发现均有精确源码触发条件，未泛化为AI全模块结论。

## AI 生命周期定向复核第二轮追加（2026-11-08）

本节只复核上节 R3-F2、LIFE-STD-F1/F2/F3 及其相邻 request/usage 收尾；依据当前源码与 Lead 提供的既有日志，未运行 Maven、测试、数据库、网络或服务。

### 上轮发现状态

- **R3-F2 已关闭。** `McpToolExecutor.create:115–120`现在捕获受控 `AiException`、只记固定告警并返回 `null`；`AiMcpPlanSnapshotTest`清空 `MCP_TOKEN` 后断言 `handlerAvailable=false`、`resolveEffective`为空。`target/takeover-mcp-invalid-secret-green.log:250–263,278`为该测试 1 项 GREEN、无失败；日志未接触真实凭据或MCP网络。
- **LIFE-STD-F1 已关闭。** `AiChatOrchestrator.callModel:586–609`先构造 `ChatRequest` 并解析密钥/Provider，再于 `:618`进入 `reserveStage`；密钥和注册表失败不会创建阶段。`takeover-ai-ledger-green.log:334–342,357`包含 18 项生命周期、全绿；缺凭据测试源码断言 Provider 调用数为零且无usage行。
- **LIFE-STD-F2 已关闭。** `failStaleRequests:359–374`只按过期/缺失 lease 做条件更新，清理 `quotaActive/workerToken/leaseUntil`；`recoverPending:147–175`周期调用清理、未知工具结果对账和队列派发。真实H2 `restartCleanupPreservesPendingApprovalAndExpiresOnlyLostWorkers`覆盖待审批保留及失租约并发释放，位于同一 18 项 GREEN 日志。
- **LIFE-STD-F3 已关闭。** `onCompleted:646–650`先保存非负实际 tokens，再尝试发布内容；无增量完成后取消不会把已知用量当零。`cancellationAfterProviderCompletedKeepsKnownActualUsage`验证 50/5 与 `SETTLED`，`takeover-ai-cancel-usage-green.log:286–305`为 8 项 GREEN。丢失响应仍转 `UNKNOWN`，没有把未知消耗假设为零。

### 新确认待修项

#### LIFE-STD-F4/P2：自动工具轮失败标错助手消息

`runSafely:432–443`始终携带首次 `assistantMessageId`。自动工具轮在 `run:511–529`完成后创建 `nextAssistant`；若下一次 Provider 调用失败，`fail:1035–1056`只将首次助手置 `FAILED`，当前助手仍 `PENDING`，而已完成的工具轮声明被错误归入旧消息。违反 `AI_MODULE_DESIGN.md:1063`（已产生增量需保留，失败消息标记 FAILED 并保存错误摘要），也会让终态请求留下不可恢复的待处理助手。触发条件是至少一轮成功自动工具后下一次模型失败；不涉及审批、传输或 answer 累积。建议按请求当前未终态助手（或持久轮次ID）完成失败收尾，并保留已完成工具消息。未运行新夹具。

同一收尾路径还违反 `docs/CODING_GUIDE.md:63`多步关联写需同一事务的规则：`fail:1041`先独立提交request终态，再独立写助手/`REQUEST_FAILED`；后续写失败会留下已终态请求及未完成消息，恢复器也不再处理。建议三者以短事务和终态CAS共同提交。客户端结束事件读取时仍须避免“先查空事件、后见终态”竞争窗口，不能仅以写事务替代最后游标复查。本项作为同一收尾责任的补充，不重复计数。

#### LIFE-STD-F5/P2：首次额度行并发创建的会话缓存窗口

`AiQuotaService.ensureRow:236–252`先用 `AiUserQuotaMapper.selectOne` 查空，再调用 `lockQuotaKey`，获得 PostgreSQL advisory lock 后用同一 `selectOne` 重读。`AiUserQuotaMapper.lockQuotaKey:23–24`是无 `flushCache` 的 `@Select`；`selectOne`最终委托相同Mapper的`selectList`，MyBatis 默认 SESSION 本地缓存按同一语句、SQL与参数保留第一次“无行”结果；仓库未配置STATEMENT覆盖。两个首请求可都缓存空：后者获锁时看不到已提交行，随后 `insert` 撞 `uk_ai_user_quota_period`（迁移/`ai-schema.sql`定义），整个阶段预占回滚。Javadoc声称咨询锁“避免并发首请求撞唯一索引”，但该实现仍可撞唯一键，属于标准事务/并发正确性问题；38项H2账本测试预置DAY/MONTH行，不能覆盖首次建行。建议锁前不做可缓存空读，或让锁语句清缓存再重读；需隔离双事务首次建行回归，未运行探针。

基线归属另核：固定基线已经包含同一`ensureRow`和无flushCache的`lockQuotaKey`；这是新账本调用链仍可到达的存量缺陷，不称本轮新引入。无阶段数据损坏结论：唯一索引仍阻止重复行，实际影响是本可成功的一个首请求异常失败。

#### LIFE-STD-F6/P2：审批恢复重置请求级 `maxRounds`

`resolveMaxToolRounds:1549–1564`读取并夹限版本 `tool_policy_json.maxRounds`，但计数器只存在 `run:483–489`的局部 `for`。待审批在 `:525–526`返回，`finishApproval:404–406`重新排队并调度 `run`，下一次又从 `round=0`开始。配置 `maxRounds=1`、Provider 每轮继续返回待确认工具时，连续审批会创建超过一轮的模型阶段和待批调用，预算护栏只约束单次运行。建议在请求快照中保存已消耗轮数/稳定轮身份，恢复时使用剩余预算；达到上限后仅允许无工具收束。现有18项没有该连续审批/预算边界测试，未运行新夹具。

这些新项是源码可达的有限结论；未将既有 Provider 传输、多轮答案累积、自动工具长事务、角色查询 fail-open 或币种混算问题重复计入。本轮没有新增可确认的 Fowler smell。

### 同轮收尾源码状态与证据边界

Lead在本次复核期间已改F4/F6：最终读取的`fail:1048–1069`在同一短事务中锁request、CAS失败终态、选`latestAssistant`且不修改COMPLETED、写消息及失败事件，消除原错消息与非原子写hunk；`run:490–500`按本请求持久ASSISTANT.toolCalls计`previousRounds`，达到预算时直接进入无工具收束。两项暂记**实现已修、待本项定向回归**。新`takeover-ai-auto-tool-green.log:324–345`为生命周期20+权限6共26项，但当前新增自动工具测试检查事务外执行、已提交claim及最终答案，未覆盖F4后续Provider失败或F6连续审批预算，不能借该GREEN关闭测试证据。

**本轮最终结论（有限范围）：** 原R3-F2、LIFE-STD-F1/F2/F3已关闭；F4/F6原触发代码已消除，待针对性回归；F5首次额度行缓存竞争仍待修。`takeover-ai-ledger-green.log`38项由生命周期18、控制器权限2、编排权限6、配额12组成，另`takeover-ai-text-green.log:259–278`普通文本回答1项GREEN，共覆盖所述19项生命周期；不将39项全部称为真实H2流程，不等同PG首次建行、真实Provider/MCP或生产验证。按本轮授权仅追加报告，无实现/测试执行。

## Provider 有界流与自动工具事务定向追加（2026-11-08）

本节以当前源码和指定既有日志复核 `OpenAiCompatibleProvider`、`AiUrlGuard`、`BoundedHttpTransport.stream` 及自动工具短事务接线。本审阅未运行构建、测试、数据库、网络或服务，只追加报告。以下状态覆盖上节对应项。

### 核心结论

- **LIFE-STD-F4、F6 已关闭。** `takeover-ai-followup-green.log:251,330–349` 为 Provider 2 项、真实 H2 生命周期 22 项全绿；新增 `failureAfterAutomaticToolClosesOnlyCurrentAssistant` 明确检查首轮 COMPLETED、失败轮 FAILED，`approvalResumeUsesRemainingPersistentToolRoundBudget` 检查 `maxRounds=1` 审批恢复只再调用无工具模型。对应修复仍在源码。
- **LIFE-STD-F5 实现已修，真实 PG 并发证据待补。** `AiUserQuotaMapper:24–26` 的锁查询现在显式 `flushCache=TRUE,useCache=false`，原会话缓存空结果根因已消除；上述 H2 测试不能代替 PG 首次建行并发回归。
- **新增待修 PROV-TR-F1/P2：Provider 连接超时配置失效。** 新调用只传读取超时作为总预算，连接层也采用该预算，独立保存的 `connectTimeoutMs` 没有生效。
- **新增待修 LIFE-STD-F7/P2：自动工具轮后仍可向已失败请求插入 PENDING 助手。** `owns` 检查与后续助手插入无锁事务；过期恢复可在两者之间完成，留下不会再次清理的待处理消息。
- DNS 批准地址固定、截止取消、字节上限和固定错误路径未发现本轮新实锤绕过。未把缺少单个压力夹具等同实现漏洞，也未新增 Fowler smell 或全仓通过结论。

### PROV-TR-F1/P2 证据：连接预算未接线

`OpenAiCompatibleProvider.java:226–228` 新 hunk：

```java
int budget = request.readTimeoutMs() > 0 ? Math.min(request.readTimeoutMs(), 300000) : 90000;
return BoundedHttpTransport.stream(target, "POST", headers, buildBody(request, stream), Duration.ofMillis(budget),
        MAX_RESPONSE_BYTES, uri -> AiUrlGuard.approve(uri, AiUrlGuard.configuredPrivateHosts()), handler);
```

`BoundedHttpTransport.java:119–126` 从同一剩余预算生成 `timeout`，同时用于 `.setConnectTimeout(timeout)`、socket 和 response。活跃调用链未读取请求的 `connectTimeoutMs`。`AiProviderService:104–105` 仍默认保存连接 15 秒、读取 120 秒；因此连接黑洞可从原先约 15 秒延长到总预算约 120 秒。健康检测 `OpenAiCompatibleProvider:197–198` 虽复制连接上限 8 秒，实际也只受读取上限 15 秒控制。固定基线 `buildClient` 原来确实单独采用 `request.connectTimeoutMs()` 配置 Java HttpClient，故这是新传输接线引入的兼容回归。

违反 `AI_MODULE_DESIGN.md:566–567` 的独立超时配置语义及 `:1805` 连接、读取、总耗时限制合同；本项不声称总耗时无限。建议共享传输增加连接预算，并取 `min(配置连接预算, 总剩余时间)`，保留同一个绝对总截止。现有 Provider 夹具请求工厂 `OpenAiCompatibleProviderTransportTest:94–97` 将连接/读取设为相同值，无法验证差异；需增加不同连接/总预算的有界连接夹具。未运行该测试。

### LIFE-STD-F7/P2 证据：恢复终态后插入助手

`AiChatOrchestrator.java:549–552` 新 hunk：

```java
if (!requestService.owns(requestId, token)) {
    return;
}
assistant = nextAssistant(request, roundAssistant.getId());
```

`nextAssistant:728–736` 直接插入 PENDING，不核对 request 状态。可触发交错是：自动工具完成，`owns` 在 lease 到期前返回 true；调度线程到期运行 `AiRequestService.failStaleRequests:369–385`，将 request 置 FAILED 并清理当时存在的 PENDING/STREAMING；原 worker 随后执行上述插入。下一轮 `owns=false` 返回；若已达工具轮上限，最终 `markSucceeded` 的状态/lease CAS 亦失败。后续恢复仅扫描 RUNNING/WAITING_TOOL，新增 PENDING 因 request 已终态而永久漏清。前端 `ChatPanel.tsx:118` 将 PENDING 当作仍在流式处理，故不仅是内部冗余行。

此确定状态交错违反 `AI_MODULE_DESIGN.md:1063` 失败消息必须标记 FAILED，并对应 `docs/CODING_GUIDE.md:63` 关联状态变更需事务保护的规则。建议在短事务内锁 request、重验 token/lease 并创建下一助手，与终态恢复串行化；审批 `finishApproval→nextAssistant` 已在 request 行锁事务内，不属于此缺陷。可用 latch 固定检查后、插入前，再由独立事务到期恢复，断言终态后无 PENDING，未实际运行。既有 F4 测试覆盖下一次 Provider 明确失败，未覆盖此恢复交错，因此不与已关闭 F4 重复计数。

### 传输核验与证据边界

`takeover-provider-transport-green.log:59–63,285–308,323` 分别为 common 4 项、plugin 24 项、BUILD SUCCESS，共 28 项；后者含 Provider 1、URL guard 12、Provider 持久化 6、HTTP tool 5。`takeover-ai-followup-green.log` 的 Provider 2 项增加首个 SSE delta 在 EOF 前可见、停滞流按截止失败，以及截断流不假报成功；错误回显 fixture 断言凭据测试标记不进入公开错误。

源码中 `expiresAt` 在 DNS 前建立，`approveBefore` 用同一剩余时间等待且限制 32 个未退出解析任务；批准后的全地址数组由固定 resolver 提供连接，不二次查询任意地址。`AiUrlGuard.approve` 检查每个解析地址，metadata 无条件拒绝，私网仅部署白名单可放行。响应读逐次检查剩余时间/字节数，Provider 另限单行大小，非成功或协议错误使用固定消息。

回调提前返回时 `BoundedHttpTransport:175–179` 在 Apache 响应处理器执行清理之前调用 `request.cancel()`。对实际测试 classpath 的 HttpClient 5.6.5 做只读字节码核验：取消会摘除并以 IMMEDIATE 关闭 endpoint，再发生响应 consume/close。因此没有根据认定 `[DONE]` 后会无限 drain。现有指定日志尚无“先发合法 `[DONE]`、随后连接保持开放”专门夹具；此项仅是运行覆盖边界，不列已确认缺陷。上述证据不代表真实外部 TLS、生产 DNS 或全模块回归验证。

## C01–C06/C19 账户、会话与存储定向复核追加（2026-11-08）

范围仅为本轮凭据更新、会话撤销、上传网关、目录/文件登记、操作恢复、额度及新增 charge schema/migration。只读当前实现、测试与指定既有日志，未运行 Maven、数据库、服务或网络，未改实现。先前 PROV-TR-F1、LIFE-STD-F7 保持待修，本节不重复计数。

### 核心结论

C01 的白名单凭据 CAS、C02 的固定诊断、C04 的 DNS 总截止与 C19 的撤销失败传播已有源码和定向 GREEN，可关闭原触发路径。C03 新操作阻删与回执保留已补，但旧 ticket 尚无持久在途占位；C05 删除/移动不退款的消费记录已落地，拒绝退款存在非原子写；C06 并发 create 已串行，SENDING 回流仍能突破上限。

确认六项残余：**ST-REV-F1/P1** 一般 TLS I/O 误归 NOT_SENT，可能重复上传；**F2/P1** 有效旧票据在登记前允许目录删除，成功远端对象无持久归属；**F3/P1** 当前授权复核与 SENDING 提交未共锁，撤权提交后仍可能发送；**F4/P2** FAILED 与消费退款分两次提交，故障后持续占额；**F5/P2** 恢复器先完成登记时主路径误报冲突；**F6/P2** SENDING 期间新建意图后，失败回流可令每用户活跃意图从2变3。

指定日志为 account/storage 6、token 7、storage intent 46 项 GREEN；46 中仅6项为真实 PostgreSQL 服务/Mapper流程。新迁移未由这些夹具执行，不能声明迁移验收通过。本轮无新增 Fowler smell，不将局部 GREEN 扩大为全域通过。

### 已修路径与证据边界

| 项 | 当前源码及已有运行证据 | 本轮状态 |
| --- | --- | --- |
| C01 凭据旧值覆盖 | `ProfileController.changePassword`仅SET密码/更新时间/临时标志，并比较旧hash；TOTP setup/enable/disable共用原hash/secret/enabled条件。`UserController.resetPassword`白名单SET且BCrypt一次，0行报告冲突。`AccountCredentialPersistenceTest`真实MyBatis/H2公开端点验证并发status/type保持、旧口令与旧TOTP校验结果不能覆盖新凭据。 | 原缺陷关闭。2项测试没有逐个运行所有TOTP成功/失败组合，不扩称完整账户安全验证。 |
| C02 诊断泄密 | `HttpUploadGateway.upstreamSuffix:647–650`只使用可信HTTP状态，不读取或裁剪上游正文。`probeErrorsNeverReflectTruncatedOrEncodedCredentials`实际输入长明文跨截断边界，断言秘密片段及正文填充符均不在输出。 | 原回显路径关闭。测试名含Encoded但当前fixture未另构造编码正文；固定字段实现另有源码证据。 |
| C04 DNS超时 | `send:334,381–407`在渲染/DNS前建立绝对截止，借共享 `BoundedHttpTransport.approve` 有界等待，连接固定使用已批准地址，迟到解析不会建客户端。`dnsTimeoutReturnsBeforeResolverAndDoesNotConnectLater`通过。 | 原DNS无限等待关闭；不等同TLS/所有网络阶段实测。 |
| C19 撤销误报成功 | `TokenServiceImpl:60–77,244–270`扫描失败抛固定业务错误，取上限+1作完整性哨兵；已命中可撤销，截断未命中失败，完整未命中幂等。`cleanExpiredTokens`保留永久会话，bulk写失败传播。 | 原缺陷关闭。4项撤销夹具使用真实Sa-Token逻辑和隔离DAO，另3项DTO测试；不是生产Redis验证。 |
| C03 新操作路径 | create/claim/commit/recovery与folder delete共享 `storage-folder-security`；delete阻止有效CREATED、RECEIVING、SENDING、REMOTE_SUCCEEDED、UNKNOWN。缺失历史目录时恢复保留safeResult与HELD、不造悬空文件。 | 已覆盖的新操作分支通过，旧ticket缺口见F2。 |
| C05 消费归属 | 新旧登记采用folder→quota→upload顺序；`StorageFileMapper.lockUploadKey`有 `flushCache=TRUE,useCache=false`，等待后重读不会复用首次空查询。file与charge同登记事务，usage排除有charge的旧file避免双计；成功文件删除/移动不改charge，operation用其原folder/actor/day。 | 删除不退款、旧旧争额、旧新共享额度及存在文件重放已获PG证据；拒绝退款见F4。 |
| C06 新建竞争 | `createLocked:203–210`在共享intent锁后重新查幂等、清理/计数并插入；两目录同时争同用户最后名额仅一方成功。 | 原并发create竞争已修；回流增量见F6。 |

`target/takeover-account-storage-green.log:228–232,276–295`记录H2账户2+网关4；`takeover-token-green.log:205–233`记录会话DTO3+撤销4；`takeover-storage-intent-green.log:258–294`记录file14、folder13、PG persistence6、operation8、quota5。仅引用这些日志当时的已执行测试；共享工作区后来增加的Qt用例不计入这46项。

### ST-REV-F1/P1：一般 TLS I/O 被当成确定未发送

`HttpUploadGateway.java:532–555`新增传输异常分支包含：

```java
} catch (SSLException ex) {
    return deadlineHit.get() ? Result.unknown(null, "ATTEMPT_TIMEOUT") : Result.notSent("TLS_ERROR");
}
// IOException分支对hasCause(ex, SSLException.class)同样返回NOT_SENT。
```

`SSLException`并非握手专属；请求正文已被上游接收后，读取HTTP响应头所需的TLS record仍可抛一般SSL协议/关闭错误。异常发生在 `client.execute` 返回响应之前时进入上述分支，而非内部响应流的UNKNOWN分支。`UploadOperationService:489,621–650`据此切下一候选或退回额度，造成“可能已创建”被允许重传。该分支与 `docs/THIRD_PARTY_STORAGE_GROUP_DESIGN.md:608–613` 的规则冲突：NOT_SENT必须有尚未提交HTTP请求的证明；请求可能送达后的断线应UNKNOWN，保持HELD。

建议一般SSLException及其包装原因归UNKNOWN，仅保留明确连接/初次握手前未写HTTP的证据分类。最小夹具可在HttpClient边界记录正文已接收再抛一般SSLException及其IOException包装，验证UNKNOWN、不切候选、不退款；不需要真实凭据或外网。本轮未运行。旧领域报告将所有SSLException称作握手失败的结论不能充当关闭证据。

### ST-REV-F2/P1：旧 ticket 的目录删除窗口仍可丢远端事实

`UploadTicketService.issue:241–247`只把ticket上下文保存到Redis；`StorageFolderService.delete:394–413`只检查数据库operation与file，不查询任何旧票据的持久在途占位。确定序列是：签发有效旧ticket，远端完成上传但回调/浏览器登记尚未开始；目录仍无file/operation，删除成功；随后 `registerFromCallback:204` 或 `registerFromBrowser:286`要求目录存在而失败。callback中的Telegram定位信息或浏览器对象键没有写入可恢复账本。

本轮增加的folder锁消除了“登记写事务与删除同时穿插”，未覆盖ticket至登记之间的有效期。依据 `docs/THIRD_PARTY_STORAGE_GROUP_DESIGN.md:316,548,551,984` 活动引用保护和已授权在途远端事实保留；该文`:585`明确接受的是旧额度登记满窗口，不是目录删除抹掉归属。该残余属于C03尚未完整关闭，不称新引入。

建议签发前建立共享folder锁保护的旧链持久占位/归属快照，在回调成功后转成登记事实；成功回执异常须仍可追踪，不依赖扫Redis全部ticket。PG夹具应使用公开签发/登记边界的合成存储适配器，先取得ticket、延迟回调，再尝试delete并验证阻删或保留可恢复回执。当前6项只覆盖新CREATED阻删和历史REMOTE_SUCCEEDED缺目录保留，未证明此路径。

### ST-REV-F3/P1：发送前授权检查未与 SENDING 提交串行化

`UploadOperationService.sendContent:337–355`读取folder、调用 `requireCurrentCandidate`、载入Channel并检查版本，随后独立调用 `markSending`；整段无事务、无folder安全锁、无config行锁。`markSending:425–448`只用RECEIVING+leaseToken CAS。与此同时，`StorageFolderService.savePermissions/update`确实在folder安全锁内写新授权/版本。因此检查完成后，另一事务可撤销UPLOAD、停用目录或轮换配置并提交，旧线程随后仍提交SENDING，用已加载Channel出站。

这不只是跨系统撤销无法强杀在途I/O：发送意图本身尚未提交。违反 `docs/THIRD_PARTY_STORAGE_GROUP_DESIGN.md:544–551`明确规定的SENDING线性化点、共用folder安全锁和相关配置行锁。prepareContent较早取得的锁已经随接收准入事务释放，不能替代发送前短事务。

建议最新folder/ACL/配置版本核验及SENDING CAS在统一顺序的短事务中完成，网络发送仍在提交后进行。使用受控latch让候选校验后发生撤权提交，断言网关未发送；反向次序应保留已授权发送事实。当前6项PG未执行发送竞争，本轮未运行新夹具。

### ST-REV-F4/P2：拒绝退款与 FAILED 不是同一事务

`StorageFileService.verifyContentAfterRegistration`按设计含网络且不加事务；其私有 `markVerificationFailed:435–443`先 `fileMapper.update`提交AVAILABLE→FAILED，再经 `policyService.refundRejectedLegacy`提交charge=false。第二次写故障时，文件已FAILED但charge仍true，`UploadQuotaService.usage`继续计额；重试验证在`:359`看到非AVAILABLE直接返回，普通已登记回放也不补退款。

违反 `docs/CODING_GUIDE.md:63`关联写应同事务，以及 `UploadQuotaService:80`自身“Content rejection is an explicit refund”合同。建议本地状态CAS与charge退款放在独立可调用短事务内，远端内容读取/清理留在外部；明确CAS=0时是否有可退款的拒绝事实，不能对已经成功删除的任意行盲目退款。真实Mapper在charge UPDATE处注入失败应验证本地两项一起回滚或可恢复重试。现有PG删除不退款测试没有该故障分支。

### ST-REV-F5/P2：恢复器先完成会让主上传误报失败

`persistRemoteSucceeded:576`经 `persistResult:548–549`把lease清空后提交；恢复扫描`:65–68`立即可选中该REMOTE_SUCCEEDED，且 `recoverRemoteSucceeded`没有本进程inflight跳过。若恢复先取得folder锁并完成file+COMMITTED，主线程随后在 `commitRemoteSucceeded:585–592`取得同锁、读到COMMITTED，因不是REMOTE_SUCCEEDED而抛STORAGE055。

共享folder锁与数据库事务阻止了重复file，但公开PUT此时返回冲突/失败，实际远端与本地均已成功。与 `docs/THIRD_PARTY_STORAGE_GROUP_DESIGN.md:460,519,539–540`完成状态和幂等结果合同不符。建议接受已COMMITTED的权威终态并返回同一结果，包含文件后续已删除时的事实，不重新调用远端；在两次主提交之间触发真实恢复，验证单file/单消费与PUT完成。当前6项PG未覆盖恢复胜出场景。

### ST-REV-F6/P2：失败回流绕过2/64意图上限

`enforceIntentBudget:750–767`只计CREATED/RECEIVING，新增共享锁只保护create。`switchToNextTarget:632–650`在确定NOT_SENT且有下一候选时将SENDING直接改回CREATED，不进入该预算锁/检查。无需两个create并发即可触发：同用户已有A/B；A进入SENDING，预算数只剩B；用户创建C合法，达到2；A连接确定失败并切候选，退回CREATED后A/B/C均占用，变为3。全局64同理。

违反 `UploadOperationService` 的 `MAX_CONCURRENT_INTENTS_PER_USER=2` / `MAX_CONCURRENT_INTENTS=64` 及C06修复目标。建议预算覆盖仍可回流的SENDING，或所有增加计数的转换共同执行同一预算契约，避免为守上限丢弃已存在可重试意图。可用阻塞网关NOT_SENT+第二候选，在发送期间create第三意图，再放行失败结果；断言预算始终有界。该场景未被当前“两个目录同时create”测试覆盖。

### Charge schema、迁移与锁边界

新增entity/JSON/migration字段名、Java/SQL类型一致；业务ID为INPUT且createTime有INSERT fill，符合 `AGENTS.md` schema同步与全局表序列要求。迁移以旧file ID建立消费记录，缺uploadId用历史前缀，按uploadId去重，并初始化对应sequence段；已有文件删除/移动不再改变charge归属。迁移前已物理删除的历史无法回填，不能宣称补齐不存在的数据。

`StoragePersistenceTest.setUp:99–113`按schema fields手工建表，只自行创建file.upload_id和operation幂等索引，未执行 `V20261108002__storage_upload_charge.sql`，也未创建charge.upload_id唯一约束、真实NOT NULL/default/部分索引。其合成ID填充器不是实际SequenceMetaObjectHandler。因此六项PG证明实际Mapper/事务/咨询锁行为，不能证明charge migration、迁移幂等、回填/唯一约束与实际全局序列接线。后续门禁需实际Flyway升级带旧file的隔离库、核回填记录/唯一约束/序列高水位，并再次运行真实登记。

本次读到的folder→quota→upload锁顺序和咨询锁清缓存没有新增可确认死锁/陈旧会话缓存缺陷；没有据此断言所有存储写路径已经满足设计。F3缺失的发送边界与F4/F5/F6状态转换是独立、可执行的残余结论，均已告知Lead。

## ST-REV-F1–F6 修复与直传回执、Worker 复核追加（2026-11-08）

本节覆盖上一节同名结论的当前状态；历史证据保留。只读共享源码、测试和已有日志，仅追加本报告；未运行构建、数据库、服务或网络，未改实现。复核期间 Lead 同步修复了 F7–F10，下列状态以再次读到的源码为准。

### 核心结论

**F1–F6 原触发路径关闭。** 普通 TLS I/O 归 UNKNOWN；发送授权与 SENDING 在 folder→config 短事务内；拒绝退款同事务；恢复抢先 COMMITTED 幂等返回；SENDING 占意图槽。旧票据持久占位、回执独立提交和恢复已有 PG 证据。

本轮发现并及时反馈五项：**F7/P1** 删除后新副本遗漏清理、**F8/P2** 旧失败回执饿死后续恢复，现已修并有新 PG GREEN；**F9/P1** 大小/MIME 拒绝发生在回执保存之前，现已补 Worker 门禁与服务端先保存后补偿，Worker GREEN，新增两个 PG 用例待运行；**F10/P2** 两条重复回执路径使用不同锁导致重复清理任务，已统一锁，尚无该交错运行证据。**F11/P2 仍待修：任意重复副本的成功清理 ack 能把权威回执误标 CLEANED。**

Worker `contentMatches` 和每次下载先检查 300 秒授权元数据的原修复已核对。charge/ticket 实际 Flyway 升级现已通过，不能继续标成“未执行迁移”；不代表生产验证或所有存储流程通过。本轮不新增 Fowler smell。

### 原六项关闭证据

| 项 | 当前实现及已有定向证据 | 关闭边界 |
| --- | --- | --- |
| F1 TLS 归因 | `HttpUploadGateway:544–546,565–567` 将普通/包装 SSLException 归 UNKNOWN；`tlsFailureAfterSendingCannotBeClassifiedAsSafeToRetry` 同时覆盖两种异常形状。 | 确认异常分类；该项 fixture 在 HttpClient 边界注入异常，不称真实 TLS 服务端断流测试。 |
| F2 旧票据归属 | `UploadTicketService.issue` 在签票返回前调用 `LegacyUploadReceiptService.reserve`；reserve 持 folder 安全锁和 config 行锁后保存上下文。folder delete 同锁查询有效 PENDING/REMOTE_SUCCEEDED。`receive` 经独立 Bean 的 REQUIRES_NEW 保存远端事实，外层登记失败不回滚回执。 | PG 验证有效 ticket 阻删、Redis 过期后登记、历史缺目录保留回执/清理、注入登记失败后恢复。关闭原有效期内删除窗口；不宣称旧浏览器直传全部配置轮换语义已改造。 |
| F3 授权线性化 | `UploadOperationService.sendContent:335–346` 在 folder 安全锁→`loadChannelForSend` config FOR UPDATE→最新候选复核→SENDING CAS 的同一事务内完成；网关 send 位于事务外。两 Mapper 锁均配置 flushCache=TRUE/useCache=false。 | PG latch 验证目录修改等待发送事务，以及先停用 config 提交使发送失败；与 `THIRD_PARTY_STORAGE_GROUP_DESIGN.md:544–548` 一致。 |
| F4 拒绝退款 | `UploadQuotaService.rejectLegacyFile:82–93` 同事务执行 AVAILABLE→FAILED CAS 和 charge=false；CAS=0 不退款。 | PG trigger 在退款 UPDATE 抛错后两项均回滚，撤 trigger 后重试成功；符合 `CODING_GUIDE.md:63` 关联写原子性。 |
| F5 恢复先完成 | `commitRemoteSucceeded:585–587` 接受权威 COMMITTED 并返回；没有重新插文件/上传。 | PG 在持久回执提交后、主线程登记前运行恢复，公开响应 COMMITTED 且单文件。只关闭原误报冲突路径。 |
| F6 意图槽 | `enforceIntentBudget:749–767` 用户与全局均计入 SENDING。 | PG 在网关发送暂停时创建第三意图，得到 STORAGE042；NOT_SENT 返回后原意图仍受预算约束。 |

`takeover-storage-residual-green-2.log:259–307,322` 为 66 项 GREEN，其中 StoragePersistenceTest 21 项实际 PG；其余网关5/file14/folder13/operation8/quota5。这里的“实际 PG”仍指隔离框架与事务夹具，不是运行生产服务。

### F7–F10 本轮发现及已采取修复

- **F7/P1，删除后新 Telegram 副本丢清理。** 初读 `payload` 对 COMMITTED 直接抛 STORAGE016；原文件硬删后，同一未过期 ticket 又产生 B，callback 在 `receive` 之前退出。新代码允许读墓碑上下文，由 `receive` 先对比并排队 B，再返回 terminal 标记；主登记通过 `getByUploadId` 返回已删事实，Browser 另用 `wasCommitted` 阻止复活。当前 `repeatedTicketReturnsOriginalAndQueuesOnlyDuplicateRemoteObjectForCleanup` 在删除 A 后提交第三个不同 locator，断言没有新文件且第三副本有清理任务。该用例已在 22 项 PG GREEN 中，关闭原缺口。依据设计 `:317,519,551` 的持久事实和删除后幂等语义。
- **F8/P2，固定前20条失败永久饿死后续回执。** 原 `recover` 固定 `ORDER BY update_time LIMIT 20`，失败无状态/时间推进；缺目录回执即使清理成功仍是 REMOTE_SUCCEEDED。当前缺目录进入 CLEANUP_PENDING；清理成功/终败分别进入 CLEANED/CLEANUP_FAILED；其他恢复失败以条件 UPDATE 移至队尾。PG `olderUnrecoverableReceiptsDoNotStarveALaterValidReceipt` 插入20条旧失败和第21条有效回执，两次恢复后后者 COMMITTED；原永久饥饿关闭。这落实设计 `:539,542` 的小批量可推进恢复；不扩大成指数退避已完整实现。
- **F9/P1，先拒绝后落回执仍留下远端孤儿。** 初读 Worker 只限制20MiB，未使用签票 maxSize，且任意 image/* 可绕过 MIME 对比；TG 已成功后 backend `validateCallback` 先拒绝，持久行仍 PENDING/无 receipt。当前 Worker:337–346 在出站前核票据大小/规范化 MIME；backend `registerFromCallback:183–195` 先独立提交 locator，再验证，拒绝时另一个短事务建立清理任务和 CLEANUP_PENDING。`takeover-worker-green-2.log` 13项 GREEN（3项实际执行 worker.fetch、替换 fetch 的行为 fixture +10项源码断言）；新增大小/MIME 两个公开 PG 用例已读到，尚无其运行结果。依据设计 `:538–539,551` 保留已获远端事实；不与 `:585` 明确接受的旧额度登记满窗口混同。
- **F10/P2，回执两入口去重锁不一致。** 初读 `receive` 使用 `storage-upload:<id>`，`receiveReplay` 使用 `storage-upload-replay:<id>`，两者均 COUNT相同payload→INSERT。可在首次 callback 读file为空后暂停、原文件提交、另一 callback 走 replay 时交错两次 COUNT=0，产生同一副本两条 DELETE_REMOTE；第二次 Telegram 删除无对象会被 Worker 记失败并重试。现 `receiveReplay:136` 已统一 `storage-upload:<id>`，与 receive/reject 相同，原并发缺口源码关闭；未把其他22项PG算成这个交错的运行证明。依据 `CODING_GUIDE.md:63` 与设计 `:548,583` 的共享幂等边界。

### ST-REV-F11/P2：副本清理 ack 能错误终结权威回执

当前 `LegacyUploadReceiptService.cleanupAcknowledged:106–113` 的新 hunk：

```java
String uploadId = json.readTree(payload).path("uploadId").asText(null);
ticketMapper.update(null, new LambdaUpdateWrapper<StorageUploadTicketEntity>()
        .eq(StorageUploadTicketEntity::getUploadId, uploadId)
        .eq(StorageUploadTicketEntity::getState, "CLEANUP_PENDING")
        .set(StorageUploadTicketEntity::getState, success ? "CLEANED" : "CLEANUP_FAILED"));
```

没有检查 task 的 messageId/fileId 是否等于该 ticket 的权威 receiptJson。确定顺序：缺目录或体积/MIME拒绝让 A 回执进入 CLEANUP_PENDING，并产生任务 TA；同票据再次上传 B，`receive` 为 B 产生独立 TB；先 `ackDeleteTask(TB,true,...)` 会将 A 所属 ticket 置 CLEANED，虽然 TA 尚未成功。TA 后来达到 DEAD 时，CLEANUP_PENDING 条件已不满足，ticket 仍错误宣称 CLEANED。任务本身未丢失，缺陷是持久回执状态与其权威对象事实相矛盾，而非“所有副本再也不可追踪”。

此路径违反设计 `THIRD_PARTY_STORAGE_GROUP_DESIGN.md:317,539,551` 保留可靠对象事实及按原归属处理的规则，也违反 `CODING_GUIDE.md:63` 关联写需要维护一致结果的要求。建议 task 明确关联它清理的 receipt/locator，只有权威对象的确认可终结该 ticket；额外副本保留独立 task 状态。可在现实际 PG fixture 中顺序创建 TA/TB，先成功 ack TB，断言 ticket 仍 CLEANUP_PENDING；再对 TA 成功或达到 DEAD，分别核 CLEANED/CLEANUP_FAILED。无需网络，本 reviewer 未执行。

### Worker、迁移及本轮验证边界更新

`StorageWorkerController.matchesReceipt` 对 fileId+messageId 返回 `contentMatches`；Worker `prewarmCaches` 明确只接受 true。原重复票据可能把 B 字节写入 A 的永久 URL 缓存的触发路径关闭。`handleDownload/handlePermanentDownload` 都先查短时元数据后查长期正文；`getOriginMeta` 检查 `__authorizedUntil`，出站 max-age 不超过剩余授权期/签名期。两 PoP 行为 fixture 让其中一个 PoP 未被 purge，250秒时客户端TTL≤50秒，301秒时回源拒绝，证实本轮缓存合同。

`takeover-storage-migration-green.log:375–379` 为22项实际PG流程GREEN，`:3593,3611` 为server12项迁移测试GREEN/BUILD SUCCESS。日志明确执行 `V20261108002` charge 和 `V20261108003` ticket（如`:687–689`）；`FreshDatabaseMigrationTest` 进一步断言历史文件消费回填、charge唯一键、消费删除不消失及两表序列高水位。故上一节“迁移未执行”现已解除。该测试仍使用隔离数据库，不包含生产存量数据；StoragePersistenceTest 自己仍按schema JSON建表并用合成ID填充，因此实际Mapper并发证据与实际Flyway结构证据应分别表述。

新增 ticket entity/schema JSON 使用 INPUT ID及INSERT填充，字段/迁移与每表sequence段一致，符合 `AGENTS.md` 的 schema同步和ID约束。本次新增独立回执事务均在外层取得登记锁前调用，未确认同线程 suspend 后等待自身持锁的死锁；不据此宣布所有旧Provider网络/事务边界已通过。

## F7–F11 与插件 E-P02/P03 收口复核追加（2026-11-08）

本节覆盖上一节 F9–F11 的待修/待运行状态。范围严格限定这五个既有存储问题与插件状态持久化，未扩大审阅；只读实现、测试和日志，未运行 Maven、数据库、服务或网络。本轮未确认新的实现缺陷。

### F7–F11 当前全部关闭

`target/takeover-plugin-storage-green.log:727,801,805,820` 已记录插件6项、实际PG存储26项，共32项零失败/错误/跳过并 BUILD SUCCESS。F7 删除后新locator先清理再返回删除事实、F8 失败队列轮转、F9 先持久回执再验证/拒绝清理均保持上述修复；F9 大小与MIME两个参数用例现有实际PG GREEN，不再仅记源码完成。

F10 `receive` 与 `receiveReplay` 均取得同一 `storage-upload:<id>` 咨询锁；`concurrentReplayPathsQueueOnlyOneTaskForTheSameExtraRemoteObject` 通过两独立线程执行真实事务/Mapper入口，最终只有一条清理任务。F11 `cleanupAcknowledged:106–121` 现读取 ticket 的权威 receipt 和 context，逐一匹配 messageId、telegramFileId、chatId 后，才允许 CLEANUP_PENDING→CLEANED/CLEANUP_FAILED。`duplicateCleanupAcknowledgmentCannotClaimTheOriginalObjectIsDeleted` 先确认额外副本，断言权威回执仍 CLEANUP_PENDING，再确认原对象才 CLEANED。原先由任意同uploadId副本提前收尾的具体路径已关闭；不把该顺序用例描述为所有并发ack顺序均已实测。

### 插件 E-P02/P03 源码核验与运行边界

- **E-P02 实现已修，真实全局ID用例结果待补。** `PluginStateRepository:24–39` 不再吞数据库读写错误；新 `SysPluginMapper.saveState:12–17` 使用实体参数 INSERT＋`ON CONFLICT(plugin_id) DO UPDATE`，未更新原行主键。`SysPlugin:16,25` 及 schema保留 INPUT和INSERT_UPDATE填充，符合 `AGENTS.md` 每表全局号段规则，替换了原 MAX(id)+1。Registry手动启停先保存期望状态，再改变运行门禁/执行hook，失败向调用者报告；关闭hook失败时API门禁已关闭。6项既有GREEN中的repository失败是mock注入，不能单独证明真实Mapper填充生效。
- **E-P03 原启动回写false缺陷关闭。** `PluginRegistryImpl:35–48,135–138` 从 `desiredStates` 恢复期望值，运行态初始false，hook成功才true；失败不保存false，必需插件失败抛异常中断启动。已有GREEN验证期望true的第一次hook失败、同registry重新注册成功及必需插件失败。不是全新Spring上下文重启测试，源码也未发现会把期望true覆盖为false的分支。
- **初始化/代码生成边界。** `@DependsOnDatabaseInitialization` 施加于加载持久态的Registry，`PluginAutoConfiguration` 注入该Registry后才发现并注册插件，符合 Flyway先初始化的依赖方向；本轮未实际启动完整Spring容器，不宣称注解依赖顺序已运行验证。`CodeGenMojo:98–123` 仅写实体，未生成/覆盖Mapper，故没有证据认定正常generate-sources会抹掉UPSERT方法。

已读新增 `PluginAndLedgerPersistenceTest`：实际Flyway→PG→MyBatis→`SequenceMetaObjectHandler`/`SegmentGenerator`，覆盖不同pluginId同时首次保存、既有pluginId更新、数据库拒绝传播和charge/ticket各自从1000001取号。当前未取得该用例执行完成日志，留作Lead运行门禁；测试源码不是GREEN证据。`PLUGIN_GUIDE.md:19,107,122` 的启停持久化/运行状态职责与Flyway规则、`AGENTS.md` 的全局ID规范为本节标准来源；不新增主观smell或全项目通过结论。

## AI 传输、终态与核算定向复核追加（2026-11-08）

本节为 LIFE-STD-F5/F7、PROV-TR-F1 及本轮 AI 核算/人工核对变更的当前状态；历史条目保留。固定基线仍为 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`，已包含本轮未跟踪的 `AiPricing`、PG 用量夹具及 08005–007 迁移。仅只读实现、测试源码和既有日志，未运行构建、数据库、服务、网络或新探针；唯一写入为本报告。

### 核心结论

**LIFE-STD-F5/F7、PROV-TR-F1 的原触发路径关闭。** 首次额度行锁清缓存已有真实 PG 并发证据；自动工具后下一助手在 request 行锁事务内重验；Provider 独立连接/TLS 超时已接线且有停滞握手回归。

**新增 LIFE-STD-F8/P2 待修：** 阶段预占提交后、Provider 调用前的取消或过期仍会留下 UNKNOWN 和未使用的额度/金额预占。已即时告知 Lead，Lead 已接收并开始补定向回归；截至本次报告核验尚未取得修复证据。

币种分组、SQL 汇总、人工核对权限及锁顺序未发现本轮可确认的新缺陷。最新 `takeover-ai-reconciliation-green.log` 为 25 项 H2 生命周期与 4 项实际 PG 用量，共 29 项 GREEN。本结论不代替 08005–007 的完整 Flyway 升级、真实权限拦截或全仓终审；本轮无新增主观 Fowler smell 结论。

### 既有三项关闭证据

| 项 | 当前源码与规则 | 定向证据及边界 |
| --- | --- | --- |
| LIFE-STD-F5：首次建额度行缓存 | `astral-plugin/src/main/java/com/astral/ai/mapper/AiUserQuotaMapper.java:24–26` 的咨询锁查询保留 `flushCache=TRUE,useCache=false`；`AiQuotaService.ensureRow` 在取得锁后重新读取。符合 `docs/CODING_GUIDE.md:68–71` 的数据库并发原子性要求。 | `AiUsagePersistenceTest.java:105–122` 经真实 PG、SqlSessionTemplate 和 Spring 事务代理同时发起首次预占，断言仅一个请求获准、日/月各一行。最终 4 项 PG GREEN 已包含此用例，解除原“PG 待补”状态。没有将合成 ID 填充器描述成生产全局序列验证。 |
| LIFE-STD-F7：终态后插入 PENDING | `astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java:589–594` 将 `lock(requestId)→owns→nextAssistant` 放在同一短事务。取消/过期恢复对 request 的 UPDATE 必须等待该锁；若终态先提交则复验拒绝插入，若插入先提交则恢复随后能够清理它。符合 `docs/CODING_GUIDE.md:63` 关联写同事务及 `AI_MODULE_DESIGN.md:1063` 终态消息要求。 | 24/25 项生命周期 GREEN 覆盖终态清理、自动工具下一轮失败、取消与恢复；未见以 latch 固定原检查后插入前交错的专门运行用例。这里以锁序源码消除原路径关闭，不宣称该交错已经实测。 |
| PROV-TR-F1：连接配置未接线 | `astral-plugin/src/main/java/com/astral/ai/provider/OpenAiCompatibleProvider.java:226–230` 独立传递 `connectTimeoutMs`；`astral-common/src/main/java/com/astral/common/http/BoundedHttpTransport.java:115–120,142–149` 对 DNS 预算及 TCP/TLS 配置使用连接预算，整体响应仍受独立截止控制。符合 `AI_MODULE_DESIGN.md:1805` 的分阶段限制。 | `OpenAiCompatibleProviderTransportTest.java:25–78` 验证 250ms 连接配置终止停滞 TLS，以及 100ms 连接配置允许已连接后 400ms 的正常响应；同类覆盖 query 前正确拼 endpoint、截断/停滞 SSE 和错误不回显凭据。只关闭原配置被忽略问题，不把这些用例扩称多地址、累计 TCP/TLS 延迟等全部组合验收。 |

传输日志 `target/takeover-ai-transport-green.log:59,285,294,313` 为 common 4、Provider 4、quota 13，共 21 项 GREEN。Provider `testConnection:183–205` 已执行实际 chat 并统一失败文案；`endpoint:309–320` 把 path 拼接放在原 query 前。当前 handler 结束后仍取消请求，未引入消费无限剩余正文的回归。

### LIFE-STD-F8/P2：确定尚未调用 Provider 的失败仍保留费用预占

新增 hunk 位于 `astral-plugin/src/main/java/com/astral/ai/service/AiChatOrchestrator.java:662–686,747–752`：

```java
AiUsageEntity stage = usageService.reserveStage(...);
try {
    int remainingBudget = requestService.remainingBudgetMillis(...);
    ChatRequest chatRequest = new ChatRequest(...);
    provider.stream(chatRequest, ...);
} finally {
    if (usageKnown.get()) usageService.settleStage(...);
    else usageService.markUnknown(stage.getId());
}
```

`AiUsageService.reserveStage:51–104` 的独立服务事务已保存 RESERVED、日/月 token 与 CNY `reserved_cost`。确定可触发的顺序是：该事务提交；取消线程将 request 改为 CANCELLED 并提交；原 worker 执行 `AiRequestService.remainingBudgetMillis:100–107`，因状态/token失效抛 AI007。另一入口是在 reserve 的额度锁等待期间租约到期，随后抛 AI012。两种情况都尚未执行 `provider.stream`，但 finally 转 UNKNOWN；`markUnknown:139–142` 只改状态，不退额度，恢复器同样不释放 UNKNOWN，因而用户必须人工核对一个实际尚未发出的模型阶段。

这是明确违反 `AI_MODULE_DESIGN.md:1196–1199`“请求失败：释放未使用预占额度”的实现问题，不是未知上游消费应如何估价的产品分歧。建议记录是否已进入 Provider 调用，对调用前确定失败按原阶段快照幂等释放预占；已发出后丢失响应仍保留 UNKNOWN，避免破坏 `:1062` 取消不能假设费用为零的要求。

建议定向回归在 reserve 返回后暂停 worker，从另一个事务取消，再继续：断言 Provider 调用数为零、阶段标记已释放、日/月预占 token 与金额回到本阶段之前；重复释放不得再次扣减。本 reviewer 未执行该用例。与原已关闭 LIFE-STD-F1 不重复：密钥/registry 解析现在确已前移，本项是本轮新增 `remainingBudgetMillis` 在预占之后重新检查执行权的窗口。Lead 已确认接收。

### 货币、人工核对与状态写入核验

- `AiPricing.java:11–21` 统一拒绝负单价与非法 ISO 币种并规范化；`AiUsageService:69–73,182–183` 的费用限额只接受完整 CNY 快照，非 CNY 金额不写入 CNY 费用计数；所有币种仍计 token。`AiUsageMapper.java:18–50` 使用参数绑定、数据库聚合与 5 秒语句限制，金额按规范化币种分组；混合币种的标量金额返回 null，区间为 `[from,to)`。PG 用例实际覆盖两币种汇总、跨日边界和请求去重，不等同所有价格精度/规模输入均已验证。
- `AiUsageService.reconcile:145–166` 校验非负实际量、有效依据、终态 request、UNKNOWN 且未核对的 usage，按 request→usage→日/月额度锁顺序结算并保存 operator/time/note。迟到 `settleStage:108–135` 采用相同 request→usage 顺序且 SETTLED 直接返回，未见重复结算入口。PG 用例检查运行中拒绝、空依据拒绝、一次核对、二次拒绝和迟到结果不覆盖；未直接并发运行人工核对与迟到结果。
- `AiChatOrchestrator.reconcileTool:426–456` 同样在 request 锁内要求终态+UNKNOWN+未核对，只记录结果和关联消息/事件，不重新 invoke 或 queueResume。`persistToolResult:357–420` 的保存重试只重新运行短事务，不重新执行工具；本轮未发现新状态竞争导致重复副作用的确定路径。
- `AiAdminUsageController.java:107–114` 和 `AiAdminToolController.java:238–243` 分别声明专门的 `admin:ai:usage:reconcile`、`admin:ai:tool:reconcile` 权限，operator 来自 `AiCaller`，均 `recordPayloads=false` 并对 DTO 做 `@Valid`。对应 DTO 在 `AiAdminDtos.java:737–738,808–809` 约束非负/结果枚举及依据长度。符合 `docs/CODING_GUIDE.md:77–80` 和 `AI_MODULE_DESIGN.md:2030` 显式授权要求；本轮只核声明/接线，没有运行完整 HTTP 权限拦截测试。

### 运行及迁移证据边界

`target/takeover-ai-terminal-green.log:332,351` 为 24 项 H2 生命周期 GREEN；`takeover-ai-summary-green.log:332–338,353` 为同 24 项加 3 项 PG 汇总/并发/币种 GREEN；`takeover-ai-reconcile-green.log:257,276` 为 PG 4 项 GREEN；最新 `takeover-ai-reconciliation-green.log:335–341,356` 为 H2 25+PG 4，共 29 项全部零失败、错误、跳过并 BUILD SUCCESS。这些日志有重叠用例，不相加为独立测试数。

新增 usage/tool-call 核对字段在 entity、`astral-plugin/src/main/resources/schema/ai_usage.json`/`ai_tool_call.json`、插件 SQL 与 `V20261108005`/`V20261108007` 一致，业务主键仍为 INPUT 并保留自动填充；`V20261108006` 追加字典状态及对应序列高水位。符合 `AGENTS.md` 的 schema同步、全局序列和增量迁移要求。当前 PG 夹具 `AiUsagePersistenceTest.java:67–96` 手动执行 base DDL、08001、08005，并使用合成 ID handler；它没有跑 Flyway 顺序升级或 08006/08007，也未启动完整 Spring 应用。完整迁移和权限运行门禁仍需 Lead 收口，不能把这些证据缺口改写成已确认实现缺陷。

## 终审准备：历史闭项核对与新增确认项（2026-11-08）

### 当前核心结论（本节优先于旧快照）

LIFE-STD-F8 已关闭：Provider 调用前取消释放阶段 token、金额及唯一未使用请求数；实际用量未知仍保留 UNKNOWN。插件全局 ID、真实 Spring 初始化顺序、08005–007 完整 Flyway 与人工核对权限的原证据缺口已补，详见下表。此前 R9、SQL、传输、R3、生命周期 F1–F7、存储 F1–F11 的关闭状态保留。

本轮确认并已交 Lead 的待修项共七组：**QT-USER-F1/P1** 的旧 User 回写可撤销封禁/恢复旧密码；**AI-REF-F1/P2** 的草稿旧引用可恢复已删除路由；**MON-STD-F1/F2/P2** 分别是关闭统计导致必需 Bean 缺失、SSE 异步重派发使请求计数泄漏；**MON-STD-F3、CFG-STD-F1、CFG-STD-F2/P3** 为 locale 指标规范化、日志属性层级、数据库示例规范冲突。Lead 已接收并准备实际回归；本节不把接收等同修复。

已读最新 reactor 的 BUILD SUCCESS（plugin586、server44），但它早于以上新增回归，不能据此关闭发现。固定基线仍是 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`，HEAD 与基线相同、提交列表为空；审查对象仍为 `git diff BASE --` 加未跟踪文件，最终提交差异复核尚待 Lead 提供 hash。没有新运行构建、测试、数据库、服务、网络或浏览器；只追加本报告。

### 新增确认项：当前源代码因果链与标准

| ID / 优先级 | 精确文件与当前触发路径 | 标准依据、最小关闭约束与证据边界 |
| --- | --- | --- |
| **AI-REF-F1 / P2** | `astral-plugin/src/main/java/com/astral/ai/service/AiAgentService.java:351–385`：`requireVersion` 读完整草稿；仅提交 `providerRouteId != null` 才 `lockRoute`，最后 `versionMapper.updateById(version)` 写旧实体。可达交错：T1 读 V→A 做仅改名并暂停；T2 改 V→B 提交；T3 `AiRouteService.delete(A)` 锁A、计数0、删除；T1 恢复，把未提交的旧A写回。新引用删除保护由此失效。 | `AGENTS.md:364–373` 明确要求白名单 SET、不得用旧行回填局部更新；`docs/CODING_GUIDE.md:63` 要求关联写维护一致性。按提交字段更新并保护版本状态/引用，使用统一锁顺序或 CAS；实际 PG 固定上述交错。这里未运行回归，也未把所有版本发布竞争额外算为已确认新项。 |
| **QT-USER-F1 / P1** | `astral-plugin/src/main/java/com/astral/qt/controller/QtAdminController.java:119–129` 全行读→改status→`updateById`；`astral-plugin/src/main/java/com/astral/qt/service/QtUserService.java:376–402,441–466` 邮件改密/资料修改同样全实体回写。用户资料请求先读status=1，管理员封禁提交status=0，迟到资料保存把status恢复1；随后kickout只撤销旧会话，重新登录仍会被允许。反向交错时封禁请求也可回写管理员读取时的旧password，覆盖已成功改密。 | 同上 `AGENTS.md:364–373`；这不是客户端可直接传status的 mass assignment，而是服务端旧快照覆盖。三个写入口分别仅 SET 自己拥有的字段，状态入口 WHERE 同时限定APP，检查影响行数；用真实Mapper/事务交错验证status/password均不被无关操作回放。原账户C01的修复没有涵盖这些QT入口，现代码仍证实路径。 |
| **MON-STD-F1 / P2** | `astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java:52` 以 `astral.stat.enabled=true` 条件创建；`AlertAdminController.java:49–61` 无同条件且构造器必需 `AlertEngine`。`astral-server/src/main/java/com/astral/server/AstralApplication.java:18` 扫描整个com.astral，未找到第二个Engine定义。官方 `STAT_ENABLED=false` 会移除依赖而保留Controller。 | 条件功能开关的公开配置合同及 `SPEC.md:21,34–39` 可运行/隔离验证要求；历史core R05由候选补为当前确定依赖冲突。给Controller相同条件或明确无Engine时的行为，实际ApplicationContextRunner验证true/false两态；当前只有静态完整依赖链，未称已运行启动失败。 |
| **MON-STD-F2 / P2** | `astral-monitor/src/main/java/com/astral/monitor/service/RequestConcurrencyCounter.java:33–42` 每次preHandle加一、最终afterCompletion减一，无request标识；`MonitorWebMvcConfig.java:23–26` 全路径注册。现真实入口 `astral-plugin/src/main/java/com/astral/ai/controller/AiAdminChatController.java:282–300` 返回SseEmitter：首次异步处理跳过afterCompletion，完成时重派发再次preHandle，最终仅减一次，正常结束每条流残留+1。 | 该计数器`:23–24` 明示成对和异步完成合同，`docs/UI_GUIDE.md:100` 资源读数表达真实状态。request所有权标记配合异步完成/错误/超时回调，避免单纯在每次dispatch增减；真实MockMvc asyncDispatch覆盖普通完成及超时/异常。不把源码推理写成已运行Spring序列证据。 |
| **MON-STD-F3 / P3** | `astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java:339–340` 使用无locale的`metric.trim().toUpperCase()`；当默认tr-TR且输入小写`client_error_count`时产生带点大写I，与 `SUPPORTED_METRICS` 的ASCII常量不等，`AlertAdminController.validateRule:419–422` 拒绝原本合法指标。 | 公共 `normalizeMetric` / supported-metric 的大小写规范化合同；采用Locale.ROOT，隔离恢复原locale的公开入口回归。历史core L02仍有当前源码证据，属于条件兼容性而非安全漏洞；测试未执行。 |
| **CFG-STD-F1 / P3** | `astral-server/src/main/resources/application.yml:242–258` 中 `logging.level` 仍在management下，console pattern则为management.pattern.console；没有根`logging.pattern.console`。因而文件声称的requestId输出格式未绑定Spring日志属性。 | `AGENTS.md:395–397` 的请求追踪合同、`docs/CODING_GUIDE.md:156–160` 根logging级别规则。移到根logging并把pattern放其下；实际Boot YAML属性加载断言。只确认配置键错误，不据此宣称当前其他profile一定输出敏感SQL。 |
| **CFG-STD-F2 / P3** | `astral-server/src/main/resources/application-database-examples.yml:10–19,126–127` 仍宣传`astral.database.*`及其高于spring.datasource的优先级，且称MySQL推荐生产。 | 与优先标准 `AGENTS.md:88,241` 的当前PostgreSQL / `spring.datasource.*` 驱动直接冲突；以受支持namespace和当前PG定位更新示例。此为历史core F06的确定文档违例，不扩大为已验证的运行期切库失败或所有方言不能使用。 |

上述均为具体行为或文档标准违例，不是Fowler个人偏好。当前没有新增必须实施的smell重构；仍按全部12类启发式检查，仓库DTO/Service层/字典与链式Wrapper的标准优先。Blog旧报告的两个possible Duplicated Code保留为低优先级判断，不上升为收口阻塞。

### 生命周期、插件、迁移和权限证据补齐

| 原项 | 当前源码、实际断言与已有日志 | 关闭边界 |
| --- | --- | --- |
| **LIFE-STD-F8 已关闭** | `AiChatOrchestrator.java:776,813,875–882` 区分是否进入provider；未进入走 `AiUsageService.releaseUnsentStage:188–226`。该事务按request→usage→quota锁序释放并标记RELEASED，仅在所有阶段均RELEASED且quotaCounted=true时由`AiQuotaService.releaseRequestCount:330–335`退唯一请求计数。`AiApprovalLifecycleTest.java:540–560`在reserve返回后取消，断言provider调用0、RELEASED、日/月token+request_count与金额总和均0。`target/takeover-ai-unsent-green.log:337–358`为26生命周期+4实际PG用量共30GREEN。 | 已知尚未发送的窗口关闭；实际已发出而未确认消费仍UNKNOWN。重复释放由状态/同一request锁保证源码幂等，本条没有声称专门重复释放交错也已实测。 |
| **E-P02全局ID证据补齐** | `PluginAndLedgerPersistenceTest.java:46`实际完整Flyway→PG→MyBatis→SequenceMetaObjectHandler/SegmentGenerator，两个pluginId并发首次保存、既有行更新、数据库拒绝传播，charge/ticket各自首ID=1000001且createTime非空。`takeover-current-migrations.log:3854–3876`该1项及其余迁移共13GREEN；`takeover-final-reactor.log:5366`再次GREEN。 | 解除原mock填充/结果待补限制；不等同生产部署执行。 |
| **E-P03与初始化顺序证据补齐** | `PluginStartupContextTest.java:48–111`以真实Boot Flyway/MyBatis/async/sequence图连续运行两个ApplicationContext；首次断言迁移无pending、sequence启用、插件状态/ID正确、真实异步历史写入，第二次保留原插件期望状态。`:93–94`实际AiSchemaInitializer fallback执行两次并验证索引及Flyway校验。`takeover-startup-knowledge.log:756–778`启动1GREEN，`:298`知识PG6GREEN；最终reactor`:5790`再次启动GREEN。 | 先前hook失败不覆盖desired的6项用例和当前真实重启证据互补；第二次上下文不是“失败hook后的真实重启”用例，不合并声称覆盖该组合。 |
| **08005–007完整Flyway证据补齐** | `takeover-current-migrations.log:1889,3562,3854,3858,3876`为空库/兼容/全局ID共13GREEN；最终reactor同三类再次通过。实际迁移包含本轮usage/tool核对字段、字典和序列水位；已读对应fixture入口确实通过Flyway全链加载，不仅手动DDL。 | 原“业务PG夹具仅执行08001/08005”的限制继续适用于该夹具；新的全链用例解除迁移门禁缺口，但不声称扫描了真实生产存量。 |
| **人工核对权限运行证据补齐** | `AiReconciliationPermissionTest.java:36–109`用真实PermissionAspect/PermissionChecker代理：只有view/approve时拒绝且业务mock零交互；专有reconcile权限放行，MockMvc负token/空note返回400，body自带operator999实际仍以request用户41调用。`takeover-ai-reconciliation-permission.log:272–291`1GREEN。 | 证明实际advice和HTTP DTO绑定，service在此为mock；既有真实PG核对流程是另一层证据。不是完整宿主认证、CSRF及任意角色组合端到端验收。 |

### 已有闭项本轮复核与覆盖边界

- **MCP/生命周期：** `McpToolExecutor.create:123–127`在错误凭据配置时返回不可用，R3-F2不再整页失败；已快照渲染headers摘要与发送的R3-F1保留。`AiChatOrchestrator:693–698`在同request锁事务内重验并创建下一助手，LIFE-STD-F7保留；Provider独立connect/TLS预算和已读流取消机制未在后续改动退回。前节F1–F7、PROV-TR-F1的原路径未复现为新代码缺陷。
- **通知/菜单/序列：** `MessageTemplateService.save:123–214`改码/scene保持既有渠道ID并检查写入结果，缓存afterCommit；`NotifyPublisher:107–143,268–306,352–377`逐渠道隔离，Redis原子预占/失败退款使用原日期key；`InappServiceImpl:132–136`失败不报成功。SysMenu更新仍仅SET提交字段；UserRoleController仍走updateAssignment通知。GeneratorFactory先统一_id类型、冲突重读后校验，evictConfigCache在afterCommit；DATABASE RETURNING与count循环修复保留。本轮没有重新运行Redis/DB。
- **历史core F01–F05：** LogSanitizer对敏感值整段固定替换；StatReportService概览/trend先normalize再同时用于key与SQL；SystemMonitorService分开主机物理内存/JVMheap并对未知返回null；notice/多规则seed经固定SQL兼容callback和完整Flyway证据关闭。原历史SQL保持不变，不能因为原文件仍有坏statement就忽略实际已接线callback而重新报缺陷。
- **知识与UI：** Spec F-N1的KnowledgeBinding现有`key={active.id}`，换版本卸载旧组件；F-N2同步完成使用仅lastSync/status/operator/time字段wrapper，PG `rebuildingPreservesConcurrentSourceDisableAndRename`断言停用和改名保留且search为空；F-N3 UsagePanel现在将显式range同时用于list/summary并提供日期控件。此处仅交叉核对原路径，不替代Spec轴的最终裁定。
- **Blog继承证据单独归因：** 对 `blog-standards-final-1fa5ace8.json` 的118行源文件清单重新计算当前SHA256，115份一致，3份漂移为Dockerfile、browser-gate、build-isolated。后两份与冻结snapshot的差异已读：增加Tab/Escape断言、显式合成公开构建URL和记录；Dockerfile与新增URL验证脚本已读。其余115份沿用原审阅归因，不伪称本轮逐行重读。浏览器脚本没有被本reviewer执行，原未接受的CDP证据仍不能转成GREEN。
- **全仓范围声明：** 本轮核对固定基线的跨模块diff/未跟踪清单及历史状态，定向读auth、system、schema、sequence、monitor、AI、CMS/QT、前端会话/API/表单/任务、部署/迁移的实质hunk；前序本reviewer存储/HTTP/SQL深审继续按各自范围归因。部分大批读取输出曾被截断，未返回部分不计fresh完整覆盖。因此这是一轮终审准备与问题收口，不宣称整个几百文件WIP已逐行复读，也不宣布最终提交Standards通过。

最终reactor日志 `target/takeover-final-reactor.log:1769,5796,5825` 为plugin586、server44零失败并BUILD SUCCESS；日志中各定向套件与早期GREEN重叠，不能相加为独立测试总数。最后提交后须核对修复hunk与新日志，保留Chrome扩展未就绪所限定的真实浏览器验收边界。Lead已要求以本节七组确认项暂收口，后续只复核已有项及最终提交差异。

## 定向修复复核：AI-REF / QT-USER / MON-STD / CFG（2026-11-08）

### 当前核心结论（覆盖上节七组待修状态）

**AI-REF-F1、QT-USER-F1、MON-STD-F1/F2/F3、CFG-STD-F1/F2 的原触发路径均已关闭；本轮限定范围没有新确认的实现缺陷。** AI 草稿、模型和路由按提交字段写入；草稿编辑与发布共用 agent→version 锁序，公开发布的校验与提交由 Controller 类级事务覆盖。QT 状态、资料和密码更新不再回放无关旧字段。监控功能开关、正常 SSE 重派发、locale 与两处配置问题均有对应源码修复。

`takeover-final-races-green.log` 中 AI3、QT3、MON4、Plugin3 分别通过，但该次总构建因旧 QtSafety 测试夹具缺少 Lambda metadata 而失败；后续 `takeover-jobs-green.log` 中 QtSafety4 通过且 BUILD SUCCESS，不能把前一份总日志改称全绿。当前 AI 并发测试源码已扩至7项，指定日志只证明原3项运行，新增4项尚待 Lead 后续日志；这属于验证边界，不是当前源码缺陷。

本轮只读限定代码、测试源与已有日志并追加本报告，未运行构建、测试、数据库、服务、网络或浏览器。HEAD 仍为固定基线 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`，提交列表为空；结论仅适用于当前 WIP 修复，不代替最终提交差异确认。无新增 Fowler 主观重构建议。

### 逐项关闭证据与边界

| 原项 | 当前源码消除原因果链 | 已有运行证据 / 标准与边界 |
| --- | --- | --- |
| **AI-REF-F1 关闭** | `astral-plugin/src/main/java/com/astral/ai/service/AiAgentService.java:368–407` 先锁版本、重验 DRAFT，再只对实际提交字段 SET；省略 route 不写旧 route。`:410–417` 在 agent 锁之后读取 `versionMapper.lock` 的当前行；`:435–472` 发布使用字段白名单。`AiAdminAgentController.java:45,230–233` 的类级 `@Transactional` 保持锁穿过 `requireValid` 与 `publish`，没有两次服务调用间提交的缺口。`AiModelService.java:78–134` / `AiRouteService.java:87–129` 也改为显式 SET；route validate `:145–170` 对主/备用模型按ID排序加锁，引用删除仍锁同一目标行后计数。 | 满足 `AGENTS.md:364–373` 和 `docs/CODING_GUIDE.md:63`。`AiAgentConcurrencyTest.java:177–208` 的真实 PG/MyBatis/Spring 事务交错断言：省略route不复活已删A、发布胜出时迟到编辑被拒、发布不回填旧route/name；`takeover-final-races-green.log:300`、`takeover-jobs-green.log:275` 均3项GREEN。当前新增4项在 `:211–269` 覆盖绑定冻结/当前版本校验/校验至发布锁保持，已读源但不以3项日志代替7项运行证据。该夹具是独立PG schema，非全宿主/global-ID验收。 |
| **QT-USER-F1 关闭** | `astral-plugin/src/main/java/com/astral/qt/controller/QtAdminController.java:127–136` 仅 SET status/time，并 WHERE id+APP、检查影响行数；`QtUserService.java:400–416` 邮件改密仅 SET password/pwdUpdateTime/time，WHERE旧password及当前email；`:466–482` 资料只写DTO字段，只有请求包含新密码才附加旧哈希相等条件。既有status及无关password不再回填。 | 同一显式字段更新规则。`QtUserConcurrentUpdateTest.java:90–127` 经真实H2/MyBatis，在读取后强制插入SQL变更，验证资料保存保留封禁+新密码、封禁保留新密码、邮件改密保留封禁；`takeover-final-races-green.log:426` 3项GREEN。这里是Mapper边界固定交错，Redis/会话为mock，不称真实Redis或多线程PG验收。旧QtSafety4错误属于夹具缺TableInfo，后续 `takeover-jobs-green.log:432,451` 4项GREEN及BUILD SUCCESS补齐。 |
| **MON-STD-F1 关闭** | `astral-monitor/src/main/java/com/astral/monitor/alert/AlertAdminController.java:55` 与 `AlertEngine.java:52` 使用相同 `astral.stat.enabled` 条件。false同时移除Controller和必需Engine。 | 恢复公开功能开关合同；`MonitorOptionalContextTest.java:18–35` 用实际ApplicationContextRunner和两实现类，true/false均正常启动并断言Bean存在性，mappers为mock。`takeover-final-races-green.log:250` 的3项中包含两态用例。不是完整宿主关闭STAT后的所有端点验收。 |
| **MON-STD-F2 关闭** | `astral-monitor/src/main/java/com/astral/monitor/service/RequestConcurrencyCounter.java:38–81` 每请求令牌只增一次，`afterCompletion` 与 Servlet `onComplete` 共用AtomicBoolean一次释放；异步重新启动重新注册listener。 | 满足该类异步计数合同和 `docs/UI_GUIDE.md:100`。`RequestConcurrencyCounterTest.java:17–29` 使用真实MockMvc/SseEmitter/asyncDispatch，断言初始1、完成后0；`takeover-final-races-green.log:256` 1项GREEN。仅验证正常完成，未把该用例扩大成所有timeout/error/容器组合验收；当前未确认这些分支有新缺陷。 |
| **MON-STD-F3 关闭** | `astral-monitor/src/main/java/com/astral/monitor/alert/AlertEngine.java:339–340` 采用 `Locale.ROOT`。 | 恢复大小写规范化合同；`MonitorOptionalContextTest.java:38–46` 在tr-TR下断言ASCII `CLIENT_ERROR_COUNT` 并finally恢复locale；上述3项GREEN包含此例。 |
| **CFG-STD-F1 关闭** | `astral-server/src/main/resources/application.yml:251–257` 现在是根 `logging.level` 与 `logging.pattern.console`，requestId占位在正确命名空间。 | 满足 `AGENTS.md:395–397` 和 `docs/CODING_GUIDE.md:156–160`；通过当前YAML层级直接核验原配置键错误消除，本轮未见独立Boot属性绑定测试日志，不伪称已运行该验收。 |
| **CFG-STD-F2 关闭** | `astral-server/src/main/resources/application-database-examples.yml:1–27` 明确当前PG运行时与 `spring.datasource.*`，删除旧namespace覆盖优先级/MySQL生产推荐；驱动、Flyway schema和基线版本与现配置一致。 | 符合优先规则 `AGENTS.md:88,241`；仅关闭文档违例，不据此声称已迁移真实存量库或验证其他数据库生产兼容。 |

此前已关闭历史项保持原证据归因。`takeover-final-races-green.log:404` 的 PluginIsolationTest3 再次通过，但本次没有扩展为新的插件全量审阅；真实全局ID/启动/Flyway证据仍以本报告前节所列独立用例为准。最终提交后须按实际hash核对本轮hunk与后续AI7运行结果。
