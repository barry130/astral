# 后端 AI 传输/声明式工具只读审阅 · **transport-04**

| 项目 | 值 |
|---|---|
| 分片 | `transport-04`（`backend-ai-shards`） |
| 日期 | 2026-11-07 |
| 基线提交 | `a39a3f3039ce7deafc3f547a25cc0dc4635c111a` |
| 范围 | `astral-plugin/src/main/java/com/astral/ai/` 下 **6 个**实际源码文件（MCP Streamable HTTP 客户端、MCP 服务器服务、声明式 HTTP/SQL/MCP 执行器） |
| 方式 | **只读静态审阅**：未启动 JVM、未发真实 HTTP、未连 MCP 服务、未连数据库、未跑 Maven、未开浏览器 |
| 计入文件计数 | **full 6 / partial 0 / split 0** |
| 确认问题 | **20 项**（High 0 · Medium 11 · Low 9） |
| 产物 | 本文件 + [`transport-04.json`](<F:/qtMusic/astral/docs/audit/2026-11-07/backend-ai-shards/transport-04.json>) |

本分片只回答一件事：**这 6 个文件里，出站请求、MCP 会话、声明式 HTTP/SQL 执行、以及由此派生的风险/权限/审批语义，在源码层面是否存在可确认的护栏缺口。** 所有结论均标注为 `confirmed-static`；不包含任何“生产已验证”“真实 socket 已验证”“mock 通过”的表述。

---

## 1. 证据方法

- 每个计入文件按 **≤80 行**分块整读，`readRanges` 必须精确覆盖 `1..总行数`；覆盖不足即不得标记 `full`。
- 每个文件阅读**前**与阅读**后**各取一次全文件 SHA256，两次一致才计入（若中途变化须重读）。
- 行数/非空行/字符/字节四种计数同时记录：字符数为 UTF-8 解码后的 `char` 数，字节数为磁盘字节数（中文字符占 3 字节，故字节数恒大于字符数）。
- 报告内直接引用代码片段总量受控（≤40 行），其余一律用 `<文件>:<行号>` 锚点引用，便于复核而不搬运源码。
- 上游/下游只需最小片段的文件（控制器门禁、编排器审批闸门、工具服务、JSON 助手、实体）只读调用点，**不计入这 6 个文件**，见 §6。

## 2. 证据台账（六文件整读，哈希前后一致）

| # | 文件（`astral-plugin/src/main/java/com/astral/ai/` 下） | 行数 | 非空行 | 字符 | 字节 | SHA256（读前 = 读后） | readRanges |
|---|---|---|---|---|---|---|---|
| 1 | `service/AiMcpServerService.java` | 285 | 263 | 11851 | 12859 | `D55CE67F…33A6655` | 1-80, 81-160, 161-240, 241-285 |
| 2 | `service/AiMcpServerFailureRecorder.java` | 43 | 39 | 1513 | 1759 | `4042A24E…1738D7C` | 1-43 |
| 3 | `tool/declarative/McpClient.java` | 427 | 397 | 17043 | 18451 | `067A8665…9E052B7` | 1-80, 81-160, 161-240, 241-320, 321-400, 401-427 |
| 4 | `tool/declarative/McpToolExecutor.java` | 197 | 181 | 8165 | 9135 | `2A099C1D…C510128E7` | 1-80, 81-160, 161-197 |
| 5 | `tool/declarative/SqlToolExecutor.java` | 441 | 414 | 17407 | 18893 | `14B919C8…6646F28F` | 1-80, 81-160, 161-240, 241-320, 321-400, 401-441 |
| 6 | `tool/declarative/HttpToolExecutor.java` | 583 | 541 | 25792 | 27970 | `85455F40…60D27264` | 1-80, 81-160, 161-240, 241-320, 321-400, 401-480, 481-560, 561-583（另复读 262-306 取 `guardHost` 原文） |

完整哈希（供机械比对，JSON 中为同值）：

```
D55CE67F0869F02BEAD68212A23BD59DF13D6CA4AAFA972A6A9221B6336A6655  AiMcpServerService.java
4042A24EF0C8BEE4BC3717ECDFC1CEA6EB855173AF24A6E9D384471D71738D7C  AiMcpServerFailureRecorder.java
067A86655CCDACCF6DA03EC895B42278B37B59D0F7AEE7FCD1793EBB69E052B7  McpClient.java
2A099C1D2F5A2D035B56116DB94596D5D7F70DB04746CCAA9EBFAEFC510128E7  McpToolExecutor.java
14B919C8321A9BB41DE188BA1FD075D1983BBD9107680ED9764E83396646F28F  SqlToolExecutor.java
85455F40A36E4AD2DAC4E3C81436EAEBE086ABAF29C540D264C258A460D27264  HttpToolExecutor.java
```

`full 6 / partial 0 / split 0`：六个文件全部整读且哈希未变，无跳过、无拆分阅读、无“仅看元数据”占位。

## 3. 源码中确实成立的护栏（先给绿）

这些不是发现项，是本分片复核后确认**有效**的设计，避免后续把已做的防御当成缺口重复上报：

1. **不跟随重定向**：`McpClient.java:63` `followRedirects(HttpClient.Redirect.NEVER)`，`HttpToolExecutor` 自建客户端同样 NEVER —— 关闭了重定向型 SSRF 这一最常见入口。
2. **主机必须字面量**：`URL_SHAPE` / `AUTHORITY_SHAPE` 拒绝模板化 authority、拒绝 `@`（无 userinfo）、拒绝 `${`；模板只允许出现在 path/query/header/body，且必须在 `arguments_schema` 中声明，未声明的占位符直接 `AI008`。
3. **头注入防线**：header 名走 token 形状校验，header 值显式拒绝 CR/LF；path 渲染做 URL 编码（值内 `/` 被编码，抑制路径拼接穿越）。
4. **secrets 不落库**：`${secret:key}` 只从工具配置 map 或环境变量取值，绝不回写实体，序列化进 JSON body 时做 JSON 转义。
5. **MCP 端点双点校验**：保存期（create/update）与每次连接前都过 `requireEndpoint`；可选白名单 `allowed-hosts` 不匹配即失败（fail-closed）。
6. **失败记录独立事务**：`AiMcpServerFailureRecorder` 整文件 43 行，`@Transactional(REQUIRES_NEW)` + error 截断 500 + 0 行更新告警 —— 主事务回滚不会吞掉错误记录。
7. **发现阶段有硬上限**：`MAX_TOOLS=500` 与 20 页游标双保护，避免无限翻页。
8. **风险模型自洽**：服务器非只读或远端 `readOnlyHint=false` → HIGH+ADMIN_CONFIRM；通用更新入口 `riskFloor` 重算且禁止下调；`requiresApproval` 对 `risk=HIGH` 恒为 `true`。
9. **权限双组校验**：编排器同时校验“库中登记的权限”与“handler 自注册的权限”，任一不满足即返回 `rejected`，而非静默放行。
10. **SQL 保存期 + 执行期双重结构校验**：多语句、DDL/DML 关键字、`WITH … INSERT`、非 SELECT 开头一律拒绝；参数只经 `#{name}` → `PreparedStatement` 绑定；列必须显式声明；`maxRows ≤ 200`、`timeout ≤ 15s`；`finally` 必 `rollback()`。
11. **写操作下限写死**：HTTP 非 GET 强制 HIGH+ADMIN_CONFIRM，SQL 下限 READ+NONE，后台无法下调。

## 4. 确认问题（20 项）

分级为**静态审阅严重度**，不等于运行时可利用性；下文“影响”均为源码可推出的后果，未做实测。

### 4.1 出站与 SSRF

**F-01 DNS 校验与连接解析分离，存在 rebinding/TOCTOU 余量**（Medium）
`HttpToolExecutor.java:268-297` + `:378`，`McpClient.java:376`。
`guardHost` 的做法是“按主机名解析全部地址 → 逐个 `requirePublicAddress`”，但真正的连接仍交给 JDK `HttpClient` 按**主机名**再次解析：

```java
for (InetAddress address : InetAddress.getAllByName(normalized)) {
    requirePublicAddress(address);
}
```

两次解析之间没有绑定，OWASP SSRF 预防明确要求“校验解析后的地址，并确保客户端只连接该已校验地址”，并把“第二次未校验解析”列为 bypass 面。触发条件是**被配置的外部域名 DNS 被接管/被投毒**，管理员本身不掌控该域名——这正是该护栏要防的场景。当前唯一兜底是可选白名单 `astral.plugins.ai.http-tool.allowed-hosts`，为空时无兜底。
附带：`requirePublicAddress` 依赖 `java.net` 语义，`isSiteLocalAddress` **不覆盖** `100.64.0.0/10`（CGNAT）与 NAT64 前缀，故内网 `100.64/10` 服务不被拦截。
方向：解析一次并连接已校验 IP（保留 Host/SNI/证书校验，等价 `curl --resolve`），或把白名单改为必填并在连接层复核。

**F-06 错误文本与响应内容带出完整端点/远端正文并持久化**（Medium）
`McpClient.java:233-262`（拼 endpoint + 最多 200 字符远端正文）、`HttpToolExecutor.java:470-500`（300 字符正文）、`AiMcpServerService.java:216-232`（`last_error` 截 500 落库并经 VO 回显）、MCP 工具结果整体入 `result_json`。
远端错误页/带查询串的 URL 中的令牌、内部主机名、业务数据会进入数据库与管理端界面，并可能再次进入模型上下文。这是数据最小化与留存问题，不是越权。

### 4.2 体积与资源上限

**F-02 HTTP 体积上限在整包读入之后才判定**（Medium）
`HttpToolExecutor.java:411-413`：

```java
HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
```

随后才比较 `bytes.length > maxBytes`。配置边界（4096–1048576）给人“内存有硬上限”的印象，但字节数组分配发生在检查之前，语义是“事后拒绝”而非“边读边限”。

**F-03 MCP 通道完全没有响应体积上限**（Medium）
`McpClient.java:231` `BodyHandlers.ofString(UTF_8)` 整包读入，只有 `MAX_TOOLS=500` 与 20 页游标，**没有**单响应字节上限。同仓库 HTTP 通道至少有 `maxResponseBytes`，属通道间护栏不一致；`tools/list` 单页大小也不受 `MAX_TOOLS` 约束。

**F-17 SQL 单元格值无体积上限**（Medium）
`SqlToolExecutor.java:311-345`、`:409-435`：`byte[]` 全量 Base64、字符串原样写入 payload，只有 `maxRows ≤ 200` 与 summary 1500 字符截断。**行数受限不等于字节受限**，单行大文本/二进制可把数百 MB 灌进模型上下文与审计表。

### 4.3 MCP 传输与协议一致性

**F-04 协议一致性偏窄**（Low）
`McpClient.java:176-215`、`:273-330`：`parseEnvelope` 只按**数字 id** 匹配帧；`initialize` 的 `result.protocolVersion`/`capabilities` 未校验；后续请求只补 `Content-Type`/`Accept`/`Mcp-Session-Id`，**不带** `Mcp-Protocol-Version`；无 batch 信封支持。
后果是互操作与可诊断性问题（严格实现或升级后的服务端表现为“拿不到响应/AI008”），不构成绕过；本分片未接真实服务端，故只记为一致性风险。

**F-05 自定义头可重复协议头；受限头名抛未包装异常**（Low）
`McpClient.java:220-232`、`:265-268`、`:390-405`：自定义头先写入 builder，`send()` 再追加协议头 → 配置了 `Content-Type`/`Mcp-Session-Id` 时出现重复头（行为依赖服务端）；头名只校验形状，`Host`/`Content-Length` 等受限名会在构建/发送阶段抛 `IllegalArgumentException`，**逃出** `send()` 的 `AI008` 包装，导致 refresh 无 `last_error`（与 F-09 同一根因）。

### 4.4 MCP 生命周期与同步

**F-07 刷新成功后无法清空 `last_error`**（Medium）
`AiMcpServerService.java:236-262` 成功路径 `setLastError(null)` 后 `updateById`；`AiMcpServerEntity.java:51` 的 `lastError` **没有** `@TableField(updateStrategy=…)` 覆盖，沿用 MyBatis-Plus 默认 `NOT_NULL`（项目 `AGENTS.md` 明确该默认）→ 显式 `null` 永不进入 SET 子句。
后果：一次失败后即使后续刷新全部成功，管理端仍长期显示旧错误，数据库里的错误文本也一直保留。这是**可观测性一致性**缺陷，会让运维按陈旧错误排查。修正需要 `lambdaUpdate().set(lastError, null)` 或字段级 `ALWAYS`。
（依赖框架默认策略的推断已与实体注解、项目规范交叉核对，未做写库实测。）

**F-08 `refresh` 在事务内做最长 60 秒网络 I/O，失败再占第二条连接**（Medium）
`AiMcpServerService.java:236-262`（`@Transactional` 内 `connect`/`listTools`，超时受 `[3,60]` 约束）+ `AiMcpServerFailureRecorder`（`REQUIRES_NEW` 独立事务）。
并发刷新或远端超时会长期占用连接池（每次刷新 1 条，失败时 2 条），可拖垮同池其他业务请求。方向：探测移出事务，落库用短事务，并对 refresh 加互斥/限流。

**F-09 只捕获 `AiException`，非预期运行时异常不写失败记录**（Low）
`AiMcpServerService.java:246-262` + `McpClient.java:265-268`：只有 `catch (AiException)` 才 `failureRecorder.record`。头渲染/请求构建阶段的 `IllegalArgumentException` 等不在此列，表现为 500 且 `last_error` 保持旧值。

**F-10 同步增量语义有缺口：冲突中断整次刷新、下架工具不会自动恢复**（Medium）
`AiMcpServerService.java:170-215` + `McpToolExecutor.java:120-145`：按远端 `toolName` upsert，`toolCodeOf` 截断到 64 字符；远端缺失项**只置 DISABLED**，已存在的 DISABLED 行不会再被启用。
后果：远端两个长名工具截断后同码 → 唯一键冲突使整次 refresh 回滚；工具临时消失后再出现时保持停用，模型侧静默缺少该工具。根因是把“远端缺失”与“人工停用”合并成同一状态。

**F-11 `toolsOfServer` 全表扫描内存过滤；`delete` 在删除后才统计**（Low）
`AiMcpServerService.java:255-268`、`:280-300`：取全部 MCP 工具再按 `serverId` 过滤（`serverId` 埋在 `executor_config` JSON 里，无索引）；`delete` 先停用工具、删服务器，随后 `countOfServer(id)` 仅用于日志，删除后计数已无意义（不影响数据正确性）。

**F-12 更新服务器时 `serverCode` 被静默忽略**（Low）
`AiMcpServerService.java:110-150` + `AiAdminMcpController.java:71-78`：`update` 只应用 name/url/headers/readOnly/status 等字段；接口注释称“编码不可改”，但请求体仍带该字段且返回成功。调用方以为改码成功，错误被推迟到后续按新码查询时暴露。不可变字段应显式拒绝。

### 4.5 声明式 SQL

**F-13 SELECT-only 关键字门禁挡不住有状态函数**（Medium）
`SqlToolExecutor.java:60-92`（`FORBIDDEN_WORDS`）、`:162-200`（`requireValidShape` + `words()`）、`:255-264`。
`words()` 按非字母数字下划线切词，因此黑名单里的 `SET` **不会命中** `set_config`；黑名单也不表达函数级写语义。可确认可达的非事务副作用包括：`nextval()`（**不随回滚撤销**）、`set_config()`（改会话/函数级配置）、advisory lock、`pg_terminate_backend`、`dblink`/外部连接函数。
关键点：**回滚只保护事务内数据修改，不保护非事务性副作用**，所以“只看首词是不是 SELECT”不可能等价于“只读语义”。

**F-14 SQL 工具复用应用主数据源与主库账号，只读靠驱动标志 + 回滚**（Medium）
`AiToolService.java:270-272`（注入容器内 `JdbcTemplate`，即应用主 `DataSource`）+ `SqlToolExecutor.java:306-337`（`setReadOnly(true)` → `setAutoCommit(false)` → `finally rollback()`）。
一旦驱动忽略 `readOnly`，或 SQL 触发上述非事务副作用，写操作可达生产库；且同账号权限等于应用全权限，缺少凭据级隔离。属纵深防御缺口：护栏集中在代码层，未在数据库角色/连接层收口。

**F-15 结果截断对模型不可见**（Low）
`SqlToolExecutor.java:322-344`：`setMaxRows(maxRows+1)`，但循环条件 `rows.size() < maxRows`，payload 只写 `rowCount = rows.size()`。查询命中 200 行上限时模型得到“恰好 200 行”的结论，可能据此做错误统计；多取的一行完全未被使用，说明设计意图未落地。

**F-16 同名列静默覆盖、带引号别名大小写被折叠**（Low）
`SqlToolExecutor.java:380-407`：`declaredColumns` 与 label 都 `toLowerCase` 后写入 `ObjectNode`，重复 label 后者覆盖前者。JOIN 两侧同名列时只保留一列，数据看似完整实则缺列，模型无任何提示。

### 4.6 权限码与审批

**F-18 三个执行器用 `splitCsv` 构造 `permissionCodes`（会大写），与权限码小写契约冲突**（Low）
`HttpToolExecutor.java:339-340`、`SqlToolExecutor.java:287-288`、`McpToolExecutor.java:104-105`；对照 `AiJson.java:131-159`（同文件注释已写明“权限码使用小写；不得复用会大写客户端类型的 splitCsv”）。
**当前不构成越权**：判定处 `AiChatOrchestrator.java:568-587` 会对 handler 自注册的权限码再走 `splitPermissionCsv`（`toLowerCase` + 严格正则），仍按小写比较。但管理端 VO 直接展示大写码（`AiAdminToolController.java:288`），与库中存储值不一致；任何新增消费者按原样比较即失配。
修正方向只有一条：三处改用 `splitPermissionCsv`。**绝不**放宽它的严格性——required 解析器必须保持“小写 + 四段式 + 不接受 HELD 通配/历史短码”，这条是当前唯一的收紧点。

**F-19 审批按 `toolCode` 重解析工具，存在“先审批后改配置”的快照缺口**（Medium）
`AiAdminToolController.java:229-245` + `AiChatOrchestrator.java:560-616` + `AiToolService.java:325-346`。
`approve()` 在确认时用 `resolve(versionId, clientType, toolCode)` **重新取**当前生效工具，再以 `approved=true` 调 `invoke`（此时不再校验 `requiresApproval`）。若等待确认期间工具的 `executor_config`/风险被修改，管理员批准的是旧语义、执行的是新配置，且不会要求新的审批（需具备 `admin:ai:tool:edit` 才能造成）。审批对象应绑定“当时快照”（配置摘要/版本），而非可变的 `toolCode`。

**F-20 `test-run` 以 `approved=true` 直接执行且不写工具调用审计行**（Low）
`AiToolService.java:348-383` + `AiAdminToolController.java:178-186`：注释明确“等同人工确认”“不写 `ai_tool_call`”，`arguments` 也不做 schema 校验（未知占位符仍会 `AI008`）。
设计上把管理员手动触发视为确认，属可接受取舍；残余风险是审计粒度不足（仅 `OperateLog`）与参数未校验。方向：test-run 也写审计行（标 `source=TEST`）并复用执行期 schema 校验。

## 5. 明确不成立的推断（防止误报扩散）

写下这些是为了让下游分片不必重复论证：

- **`HIGH + NONE` 不是审批绕过。** `requiresApproval` 对 `risk=HIGH` 恒返回 `true`（`AiChatOrchestrator.java:611-615`），与 `approvalPolicy` 取值无关；且 `riskFloor` 在通用更新与声明式更新两处都会重算并禁止下调（`AiToolService.java:204-227`、`:441-468`）。
- **大写 `permissionCodes` 当前不产生提权。** 判定点在原始请求人上、且额外做一次小写化严格解析；缺陷在契约与展示层（F-18），不在判定层。
- **`splitPermissionCsv` 的严格性是正确设计，不要“修”。** 它拒绝 HELD 通配与历史短码是预期的收紧；不得为了兼容旧数据放宽正则或改成大小写不敏感。
- **MCP 只读下限不由远端“自证”决定。** 远端 `readOnlyHint` 仅在与服务器 `readOnly` **同时**为真时才允许 READ+NONE，任一为假即落到 HIGH+ADMIN_CONFIRM —— 方向是保守的。
- **`testRun` 的 `approved=true` 属已记录设计取舍**，不是本分片新发现的绕过；其残余问题已收在 F-20。
- 记录型观察（不计入 20 项）：`approve/reject` 把 `ToolCallStatus.REJECTED` 写进审批状态列，属枚举混用，不影响判定；属 UI 语义瑕疵。

## 6. 未覆盖与未验证（局限，必须随结论一起读）

1. **纯静态**：未启动 JVM、未建真实 socket、未验证 TLS/SNI/证书链/代理、未跑任何 MCP 真实服务端、未连数据库。凡涉及“运行时实际行为”的表述都只是源码推断。
2. **无测试证据**：未执行 Maven、未运行任何单测；本分片不引用 mock/单测结果作为安全证明，也不对全量 AI 域或 task-14 做任何声明。
3. **上下文文件只读片段**（不计入 6 个文件）：`AiChatOrchestrator.java`（1805 行，仅 462-501 / 552-596 / 609-630）、`AiToolService.java`（仅 6 段调用点）、`AiAdminToolController.java`（100-179 / 200-259）、`AiAdminMcpController.java`（28-97）、`AiJson.java`（36-115 / 120-179）、`AiMcpServerEntity`（字段注解 grep）。因此**控制器门禁只到“部分可见”**，不能据此宣称整条管理端链路已验证。
4. **`AdminToolProvider`（1165 行）未读**：代码注册类工具的风险声明下限（weak handler floors）不在本分片结论内，仍为开放项。
5. **F-07 依赖框架默认字段策略**（已与实体注解、`AGENTS.md` 交叉核对），未做写库验证；若未来给该字段加了 `updateStrategy`，此结论需重测。
6. **F-04/F-05 依赖远端实现**：协议版本回显、SSE 分帧、session 语义、重复头处理均未与真实服务端对拍。
7. `requirePublicAddress` 对 `100.64.0.0/10`、NAT64 等特殊段无覆盖，已在 F-01 中作为附带项记录；未穷举所有非公网段。

## 7. 结论

六个文件的传输/声明式执行面整体呈“**窄口设计 + 多层护栏**”形态：主机字面量、禁重定向、禁 userinfo、模板白名单、CR/LF 拒绝、secrets 不落库、风险下限不可下调、权限双组校验、SQL 结构校验 + 参数绑定 + 强制回滚，这些都是实的，不是文档承诺。

**未发现 High 级、可在无管理员权限前提下直接利用的缺陷。** 确认的 20 项集中在三类：

| 类别 | 条目 | 共性 |
|---|---|---|
| 护栏深度不足 | F-01, F-02, F-03, F-13, F-14, F-17 | 校验与执行分离（DNS/IP、体积、SQL 函数语义），兜底偏软 |
| 生命周期一致性 | F-07, F-08, F-09, F-10, F-11, F-12 | 状态字段/同步语义/事务边界与真实系统状态不同步 |
| 可观测与审计 | F-06, F-15, F-16, F-19, F-20, F-18 | 错误与结果外溢、截断不可见、审批快照缺失、权限码展示失真 |

优先级建议（按“修一次收益最大”排序，不是按严重度）：**F-01 → F-13/F-14 → F-02/F-03 → F-07 → F-19**。其中 F-01 与 F-13/F-14 指向同一件事：当前的“只读/内网”判定都是**代码层语义**，没有在连接层（IP 绑定、只读账号）落成硬约束。

## 8. 外部参考

- **OWASP · Server-Side Request Forgery Prevention Cheat Sheet**
  <https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html>
  本地留档：`target/audit-reference-owasp-ssrf-20261107-1045.html`（5402 行，本地副本 5124-5129 行已逐字核对）。
  引用要点：必须校验**解析后**的地址并让客户端**只连接该已校验地址**；第二次未校验的 DNS 解析即构成 bypass；连接已校验 IP 时须保留 Host/SNI/证书校验（否则等于关掉证书验证）。该要点直接支撑 F-01 的判定与修正方向。

## 9. 交付哈希

| 产物 | 字节 | 完整 SHA256 |
|---|---|---|
| `transport-04.json` | 29632 | `8D9BE48773A1F903D43CA5D8F440BDBFD6DE57B04A52E1DB071675CC035B3763` |
| `transport-04.md`（本文件） | — | 自哈希不可内嵌，最终字节的 SHA256 在交付消息中给出（两者互不为对方内容所依赖，可独立复算） |

复算方式：在仓库根目录对两个路径直接 `SHA256`（如 `Get-FileHash -Algorithm SHA256 <path>` 或 `sha256sum <path>`）。