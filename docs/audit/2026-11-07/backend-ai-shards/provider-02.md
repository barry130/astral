# Shard provider-02 · AI 提供方 / 模型 / 路由 / 密钥与 URL 护栏（只读审计）

- 分片 ID：`backend-ai-provider-02`
- 工作区：`F:/qtMusic/astral`，基线 `a39a3f3039ce7deafc3f547a25cc0dc4635c111a`
- 机器可读副本：`docs/audit/2026-11-07/backend-ai-shards/provider-02.json`
  （SHA256 `F360CF6AAF2126BD02153F35770A891B01ADE85A45E81EEC307AA6E425171E47`，39592 字节，14 条发现 / 8 个文件）
- 边界：本分片只读。**唯一写入**是本目录下的 `provider-02.md` 与 `provider-02.json`；未改动任何生产/测试/schema/POM/锁文件/源码，未执行 Git、Maven、构建、浏览器、后端、API、DB 或真实凭据操作。
- shell 仅用于文件普查、字节数与 SHA256；无 `cat`/`rg`/私有 AST。

## 1. 读取凭证与完成度

8 个指定文件全部按 **1..N 连续读到结尾**，分块均 ≤80 行，没有任何被裁剪的中间段，因此不存在"补齐中段"的二次读取；读取前后 SHA256 逐字节相同（下表 `前=后` 均为同一值），无版本漂移、无需 version-split。

| 文件 | 行数 / 非空行 | 字节 | SHA256（前=后） | 可见 readRanges |
|---|---|---|---|---|
| `astral-plugin/src/main/java/com/astral/ai/provider/OpenAiCompatibleProvider.java` | 483 / 459 | 22414 | `99BBD672…0718D829` | 1-80, 81-160, 161-240, 241-320, 321-400, 401-483 |
| `.../service/AiProviderService.java` | 318 / 300 | 13763 | `1E13E720…B528AF7755` | 1-80, 81-160, 161-240, 241-318 |
| `.../service/AiModelService.java` | 150 / 140 | 6495 | `43B8420E…874A760AB4` | 1-80, 81-150 |
| `.../service/AiRouteService.java` | 140 / 130 | 6269 | `863E909B…429341EE5` | 1-80, 81-140 |
| `.../security/AiSecretCipher.java` | 176 / 163 | 7052 | `1DA88DCB…D1B80526F9` | 1-80, 81-176 |
| `.../security/AiUrlGuard.java` | 183 / 174 | 7205 | `41E755A3…E092E04D6AB` | 1-80, 81-183 |
| `astral-plugin/src/test/java/.../security/AiSecretCipherTest.java` | 126 / 105 | 4823 | `C322B4A4…C6E360FEF` | 1-126 |
| `astral-plugin/src/test/java/.../security/AiUrlGuardTest.java` | 112 / 95 | 4544 | `6531EE37…C80D1E543` | 1-112 |

完整哈希（前=后）：

```
99BBD672CE5D99524FF299F253859ECD4E06B0F5D3A6477B271D87CC0718D829  OpenAiCompatibleProvider.java
1E13E7202898B1E539B53A16E84DE139E4044C81589AEF51733B15B528AF7755  AiProviderService.java
43B8420E04AC6EA87E0B6E83BB939272DA9396760D3A397B63888E874A760AB4  AiModelService.java
863E909B5575EADA4B44B9E402F451A9E0511136840F114B049084A429341EE5  AiRouteService.java
1DA88DCBDFC48AA9061567CD4A6BE612DB7F75BC28FEDEE05376DBD1B80526F9  AiSecretCipher.java
41E755A33BF184E58F22E67FC158F24ED544273C98E351BBCC1DBE092E04D6AB  AiUrlGuard.java
C322B4A4C33AB352C8F67E67EDE5D02767E45056ABBF7C85095F4C8C6E360FEF  AiSecretCipherTest.java
6531EE37EA688209DEB542F09F26B1AB0901B4004DDFDBA5899F712C80D1E543  AiUrlGuardTest.java
```

**完成度：精确 8/8 全量读完**（哈希一致，无部分读取、无版本分裂）。

为确认可达性与投影行为，额外做了 5 处**最小 caller/helper 读取，标注 partial，不计入上述 8 个**：

- `controller/AiAdminProviderController.java` 36-170、205-264、262-301（端点权限码 + `ProviderVo`/`RouteVo` 投影）
- `entity/AiProviderEntity.java` 1-70（`encryptedApiKey` 是否带 JSON 屏蔽注解）
- `dto/AiAdminDtos.java` 38-97（`ProviderSaveReq` 校验约束）
- `provider/AiProviderDtos.java` 40-79（`ChatRequest` 字段，含 `maxRetries`）
- `service/AiChatOrchestrator.java` 360-387（证明 `maxRetries` 只被塞进 `ChatRequest`、该处无重试循环）

未读 `backend-ai-read-recovery.json`、未读旧的全量报告、未展开 Orchestrator 其余部分与 `AdminToolProvider`。Lead 的 Orchestrator 公共 GREEN6 结论（请求者实时权限、注册/配置组独立且组内 ANY、组间 ALL、小写 REQUIRED CSV 对 HELD 通配、HIGH 恒需审批、HIGH+NONE 自动绕过为假）按既有事实采信，本分片不重新推导。

## 2. 环境限制（影响引用，不影响结论）

`web_fetch` 与 `web_search` 在本会话均不可用（`fs-quality-fetch` / `ddg` provider 未注册），因此下列两个公开文档**未在会话内抓取**，仅作为指针登记，不据其内容做任何事实主张：

- OWASP *Server-Side Request Forgery Prevention Cheat Sheet* — https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html
- OWASP *Cryptographic Storage Cheat Sheet* — https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html

## 3. 结论摘要

| 编号 | 严重度 | 主题 | 一句话 |
|---|---|---|---|
| F-01 | **HIGH** | `secret_ref` 任意环境变量读取 | 只有 `admin:ai:provider:edit` 的人可把任意环境变量（含 KEK 本身）作为 Bearer 发往自己指定的公网主机 |
| F-02 | **HIGH** | 提供方类型翻转绕过 URL 护栏 | `MOCK` 建档跳过校验 → 改回 `OPENAI_COMPATIBLE` 且不传 baseUrl 即永久保留私网/元数据地址，`/test` 触发真实请求 |
| F-03 | MEDIUM | SSRF 只在保存时做 DNS 预检 | 连接时重新解析、且 `UnknownHostException` 明确放行 → DNS rebinding 成立；无连接期复核 |
| F-04 | MEDIUM | 模型删除缺路由引用护栏 | 注释承诺"被路由引用时拒绝"，代码 `selectCount(null)` 后直接删 → 路由悬空，运行期 AI007 |
| F-05 | MEDIUM | 路由删除缺智能体版本护栏 | 同上，注释承诺未实现 |
| F-06 | MEDIUM | 路由不校验 `fallbackModelId`；模型可跨提供方迁移 | 路由保留 A 的 baseUrl、模型已属 B → 把 B 的 modelCode 发给 A；模型编码可在 update 后重复 |
| F-07 | MEDIUM | 流式无累积上限、无取消；空闲护栏并非硬时限 | 4MiB 上限只覆盖非流式；`content/reasoning` 无限累积；超时只 `cancel(true)`，`BufferedReader.close()` 可能与阻塞中的 read 争同一监视器 |
| F-08 | MEDIUM | 允许明文 `http://` | API Key 明文上线；测试用例把该行为固化为期望 |
| F-09 | LOW | `max_retries` 是死配置 | 校验 0-5、透传到 `ChatRequest`，唯一真实 provider 从不读取 |
| F-10 | LOW | AES-GCM 无 AAD、无 kid | 密文可在提供方行之间搬运仍能解密；换 KEK 无灰度轮换路径 |
| F-11 | LOW | 两处文档承诺与代码不符 | 模型 token 上限"取小值"不存在；provider 声称"整体超时都有上限" |
| F-12 | LOW | 上游错误体回显 400 字符 | 配合 F-02/F-03 变成对内网端点的小型读取原语 |
| F-13 | LOW | 白名单 `*` 关闭整个护栏 | 连元数据地址一起放行，仅由运维环境变量触发 |
| F-14 | LOW | 实体携带密文、`list()` 返回裸实体 | 当前投影干净，属性靠单点约定维持而非类型保证 |

## 4. 逐条发现

### F-01（HIGH）`secret_ref` 是任意环境变量读取器，其值被发往管理员指定的主机

证据（当前代码）：

- `security/AiSecretCipher.java:126` — `secretRef.startsWith("env:") ? substring(4) : secretRef`，名字不做任何约束
- `security/AiSecretCipher.java:131` — `System.getenv(name)`
- `service/AiProviderService.java:268` — `cipher.resolveSecretRef(ref)` 直接作为明文密钥返回
- `service/AiProviderService.java:218` — `test()` 用 `resolveApiKey(entity)` 组装请求
- `provider/OpenAiCompatibleProvider.java:337` — `Authorization: Bearer <apiKey>`
- `dto/AiAdminDtos.java:48` — `secretRef` 只有 `@Size(max = 128)`，无 pattern/白名单
- `controller/AiAdminProviderController.java:122-123` — `POST /api/v1/admin/ai/providers/{id}/test`，权限码 `admin:ai:provider:edit`

可达路径：持 `admin:ai:provider:edit` 者 `POST /api/v1/admin/ai/providers`（`baseUrl` 指向自控公网域名，`secretRef=ASTRAL_AI_SECRET_KEY` 或任一部署变量）→ `POST /providers/{id}/test`。服务端发起真实出站连接，把该环境变量作为 Bearer 送出。这是**真实 socket**，不是预检。

影响：AI 配置管理员可外带任意环境变量；最尖锐的是 KEK 本身——拿到 KEK 即可解密全部已存提供方密钥，一次窄权限变成全部 AI 凭据失守。数据库口令等部署秘密同样可读。

修复方向：把引用名收进服务端白名单/前缀（如仅 `ASTRAL_AI_PROVIDER_SECRET_*`），显式拒绝已知敏感名，从专用映射解析而非 `System.getenv`；保持"留空=不改"的三态语义。

现有测试：无。`AiSecretCipherTest:99-109` 只证明 `env:PATH` 能解析——恰好就是这个原语。

### F-02（HIGH）提供方类型翻转完全绕过 `AiUrlGuard`

证据：

- `service/AiProviderService.java:136` — 先无条件 `setProviderType(...)`
- `service/AiProviderService.java:138-140` — 仅当 `baseUrl != null && !isBlank()` 才 `normalizeBaseUrl(...)`；更新时省略 baseUrl 即跳过校验
- `service/AiProviderService.java:313-315` — `MOCK` 直接 `return baseUrl.trim()`（或 `mock://local`），不经过 `AiUrlGuard`
- `service/AiProviderService.java:199-231` — `test()` 用实体里已存的 `baseUrl` 发起真实请求

可达路径（两步，均只需 `admin:ai:provider:edit`）：① `providerType=MOCK` + `baseUrl=http://169.254.169.254/latest/meta-data`（护栏被跳过）；② `PUT /providers/{id}` 传 `providerType=OPENAI_COMPATIBLE`、不传 `baseUrl` → 存下来的私网/元数据地址从未被校验；③ `POST /providers/{id}/test` 打到该地址。

影响：域内唯一的 SSRF 边界无需任何 DNS 技巧即可绕过，可触达 loopback/RFC1918/云元数据；响应体最多 400 字符会经 `lastHealthMessage`/`/test` 结果回流（见 F-12），构成对内网端点的小型读取原语。

修复方向：每次写都对**生效后的** `(providerType, baseUrl)` 组合校验——类型变化时必须重新校验已存 baseUrl；持久化 `urlValidated` 标记或对非 MOCK 类型无条件重新归一化；`test`/`chat` 连接前复核。

现有测试：无。`AiUrlGuardTest` 完全不碰 service 更新路径，因此该绕过能通过全部现有测试。

### F-03（MEDIUM）URL 校验只是保存期 DNS 预检，连接期重新解析且解析失败放行

证据：

- `security/AiUrlGuard.java:61` — 注释自称"DNS rebinding 的基础防护"
- `security/AiUrlGuard.java:63` — 保存时 `InetAddress.getAllByName(host)`
- `security/AiUrlGuard.java:70-73` — `UnknownHostException` 明确不拦截，"交给实际请求阶段"
- `service/AiProviderService.java:316` — `validateBaseUrl` 在全模块**唯一**调用点（写路径）；读/请求路径从不复核
- `provider/OpenAiCompatibleProvider.java:331` — `URI.create(url)` 交给 JDK `HttpClient`，连接时重新解析

可达路径：保存时填入一个当时解析不出（或解析到公网）的域名——护栏显式允许——在 `test`/`chat`/`stream` 真正连接时让它解析到 `127.0.0.1`/`169.254.169.254`。重定向不是向量（`:256` `followRedirects(NEVER)`），重新解析才是。

影响：护栏声称的 rebinding 防护对真实 socket 不成立；因为解析失败是 fail-open，攻击者甚至不需要维护保存期的 DNS 记录。

修复方向：解析一次并**钉住**（连 IP + 覆写 Host/SNI），或在自定义 `HttpClient` resolver 内对解析结果复核；保存期解析失败应阻塞或标记"未验证"并要求显式确认。

置信度：结构性确定；本分片未做运行时验证。

现有测试：无"域名解析到私网"用例；测试文件 18-20 行明确规避 DNS。

### F-04（MEDIUM）`AiModelService.delete` 没有路由引用护栏，`used` 计数毫无意义

证据：

- `service/AiModelService.java:110` — 注释"删除模型（被路由引用时拒绝）"
- `service/AiModelService.java:114` — `Long used = modelMapper.selectCount(null);`（统计全表）
- `service/AiModelService.java:115` — 直接 `deleteById`
- `service/AiRouteService.java:57-60` — 运行期 `requireEnabled` 遇模型缺失/禁用抛 `AI007`

可达路径：`admin:ai:model:edit` 调 `DELETE /api/v1/admin/ai/models/{id}`，即使 `ai_provider_route.model_id` / `fallback_model_id` 仍在引用。代码从不检查这两列。

影响：路由悬空后，所有走该路由的智能体调用与测试都报 "AI007 模型路由关联的模型不可用"——契约承诺的"删除即拒绝"完全缺失，故障被推迟到运行期。`selectCount(null)` 恒非空，导致每次删除都会打印"已删除模型"，日志不含任何真实引用信息。

修复方向：`selectCount(wrapper.eq(model_id,id).or().eq(fallback_model_id,id))` 并拒绝；删掉伪计数、改为输出真实引用数。

现有测试：无（该模块测试树中没有任何 provider/model/route service 测试）。

### F-05（MEDIUM）`AiRouteService.delete` 缺"被智能体版本引用"护栏

证据：

- `service/AiRouteService.java:113` — 注释"删除路由（被智能体版本引用时拒绝）"
- `service/AiRouteService.java:116-117` — `require(id); routeMapper.deleteById(id);`

可达路径：`admin:ai:route:edit` 调 `DELETE /api/v1/admin/ai/routes/{id}`，删除仍被已发布智能体版本引用的路由。

影响：版本继续持有失效 `routeId`，原本可用的智能体在路由解析处失败（AI007），删除时零提示。

修复方向：删除前查询版本引用并列出拒绝，或同事务内解绑/禁用。

### F-06（MEDIUM）路由不校验 `fallbackModelId`，模型还能跨提供方迁移

证据：

- `service/AiRouteService.java:73` — `fallbackModelId` 原样落库
- `service/AiRouteService.java:120-139` — `validate()` 校验提供方存在、模型存在、`model.providerId == provider.id`、temperature/topP 范围；**不含 fallback 校验**
- `service/AiModelService.java:78-81` — 允许改 `providerId`，只校验新提供方存在，不检查是否有路由正在引用该模型
- `service/AiModelService.java:82-84` — `modelCode` 更新时不做重复校验（create 在 63-68 有）

可达路径：`PUT /api/v1/admin/ai/models/{id}` 改 `providerId`；或在路由写入时传一个属于别的提供方/根本不存在的 `fallbackModelId`。

影响：路由保留 A 的 baseUrl，模型已归 B → 把 B 的 modelCode 发给 A，得到看起来像"提供方故障"的上游 4xx；update 后同提供方内 `modelCode` 可重复，模型解析产生歧义。

修复方向：`validate()` 内补 fallback 校验（存在、同提供方、启用）；模型 `providerId` 变更时若被路由引用则拒绝或级联校验；update 补唯一性校验。

### F-07（MEDIUM）流式无累积上限、无取消通道；空闲护栏不是硬时限

证据：

- `provider/OpenAiCompatibleProvider.java:112-113` — 两个 `StringBuilder` 无任何上限
- `provider/OpenAiCompatibleProvider.java:124-125` — `try (BufferedReader reader = new BufferedReader(...))`，tswr 在调用线程上 `close()`
- `provider/OpenAiCompatibleProvider.java:462-465` — javadoc："超时就关掉响应流（让阻塞的读立刻抛异常退出）"
- `provider/OpenAiCompatibleProvider.java:472-474` — 实际只 `future.cancel(true)` 后抛异常；**此处不关流**
- `provider/OpenAiCompatibleProvider.java:52` / `:359` — `MAX_RESPONSE_BYTES = 4 MiB` 只作用于非流式 `readBody`

可达路径：任何"保持连接但要么无限吐 delta、要么中途卡住"的上游。无调用方取消标志、无字节/token 上限、无总时长上限。

影响两类：

1. **无界增长**：每个 delta 追加进 `content`/`reasoning`，流式路径完全不受 4MiB 约束，恶意或自环上游可耗尽堆内存。
2. **空闲护栏仍可能挂住调用线程**：超时只取消 worker Future，随后 tswr 在同一线程执行 `close()`，而 worker 可能仍阻塞在 `reader.readLine()` 内。`BufferedReader` 的读与关共用同一内部监视器，`close()` 会等读释放；socket 读本身不响应 `Thread.interrupt`。结果正是该护栏 javadoc（`:460`）声称要消除的现象——"生成中"永久悬挂。

修复方向：加累积字节/字符上限并超限即失败；保留显式取消标志逐行检查，并由取消线程关闭 socket；避免在并发读未退出时 close `BufferedReader`（改用自有缓冲读原始流，或设置 socket 读超时），让空闲超时成为真实上限；另加单请求总时长上限。

置信度：无界累积与无取消 = 代码确定；`close()` 与阻塞读争监视器 = 依 JDK `BufferedReader` 语义高置信，但本分片未运行时验证。

现有测试：**零**——`OpenAiCompatibleProvider` 在测试树中没有任何测试。

### F-08（MEDIUM）允许明文 `http://`，API Key 明文上线

- `security/AiUrlGuard.java:43-46` — 只要求 scheme ∈ {http, https}
- `test/.../AiUrlGuardTest.java:24-27` — `allowsPlainHttp()` 断言 http 合法
- `provider/OpenAiCompatibleProvider.java:336-338` — Bearer 头

任何非 MOCK 提供方配成公网 `http://`，每次 chat/stream/test 都明文送密钥。测试把该行为固化，说明是设计选择而非疏漏，但仍是未加密的密钥通道。建议：非私网主机强制 https，http 仅限私网白名单（本机 Ollama），并在管理端/审计日志提示。

### F-09（LOW）`max_retries` 是死配置

- `dto/AiAdminDtos.java:52` — `@Min(0) @Max(5) Integer maxRetries`
- `service/AiChatOrchestrator.java:370-372` — 仅塞进 `ChatRequest`
- `provider/AiProviderDtos.java:59` — record 字段
- `provider/OpenAiCompatibleProvider.java:82` — 单次 `client.send(...)`，全文件从不读取该字段

设 `maxRetries=3`，一次 429/5xx/连接重置仍只有一次尝试并立刻 AI006。要么实现（仅对幂等、首个 delta 之前的失败重试，尊重 `Retry-After`），要么从 API 移除，避免"可调但无效"的可靠性假象。

### F-10（LOW）AES-GCM 密文未绑定提供方/字段，且无密钥版本

- `security/AiSecretCipher.java:67`、`:108` — `cipher.init(...)` 均无 AAD
- `security/AiSecretCipher.java:146-150` — 直接 `SHA-256(KEK)` 当 AES key
- `security/AiSecretCipher.java:32-35` — `v1:` 前缀 + 12 字节 IV + 128 位 tag

任何能写密文列的路径（DB 层、未来导入/恢复、批量赋值缺陷）都可把 A 的密文搬到 B 行且解密成功 → 一方静默使用另一方凭据；`describe()` 只显示 ENCRYPTED，难以察觉。无 kid 意味着换 KEK 会一次性作废全部已存密钥，无灰度轮换。建议 AAD = 提供方 id + 字段名 + 信封版本，前缀带 kid 并支持旧钥解密过渡期；同时明确 KEK 必须是高熵随机值（无拉伸的 SHA-256 仅对随机 32 字节秘密可接受）。

原语本身正确（AES-256-GCM、每次随机 12 字节 IV、tag 128），问题在信封绑定与轮换。

### F-11（LOW）两处文档承诺在代码中不存在

- `service/AiModelService.java:17-18` 称"上下文与输出上限会与全局硬上限取小值"；`:121-149` `normalize()` 只处理 kind/flags/currency/prices，`:96-99` 原样拷贝 token 上限——**从不夹取**
- `provider/OpenAiCompatibleProvider.java:39` 称"连接/读取/整体超时都有上限"；实际流式只有基于空闲的护栏，且没有总时长上限（见 F-07）

影响：管理员可把 `maxOutputTokens` 配到任意大并原样进请求体；与 F-09（重试失效）、F-07（流式无界）叠加后，成本/DoS 姿态弱于文档承诺。

### F-12（LOW）上游错误体回显（400 字符截断）

- `provider/OpenAiCompatibleProvider.java:85` — `"上游返回 " + status + ": " + truncate(body)`
- `provider/OpenAiCompatibleProvider.java:412-418` — 压空白 + `MAX_ERROR_CHARS = 400`
- `service/AiProviderService.java:292` — `lastHealthMessage` 同样截 400
- `provider/OpenAiCompatibleProvider.java:102` — `log.warn(... e.getMessage())`

仅管理端可见，严重度低；但与 F-02/F-03 组合即成为对内网端点的小型读取原语。建议：完整体只进服务端日志，API/健康列回"状态码 + 安全码"，另设调试开关。

### F-13（LOW）白名单 `*` 关闭整个护栏（含元数据地址）

- `security/AiUrlGuard.java:91-93` — `candidate.equals("*")` 直接放行
- `security/AiUrlGuard.java:52` — 元数据检查同样走 `isAllowed`
- `test/.../AiUrlGuardTest.java:88-91` — `wildcardWhitelistDisablesTheGuard()` 断言 `127.0.0.1` 与 `169.254.169.254` 在 `"*"` 下被接受

不可从管理端触发（`AiProviderService.java:283-285` 读系统属性/环境变量），属运维脚枪。建议取消 `*`，或至少在 `*` 生效时打印显著启动告警；`*` 绝不能授权元数据地址。

### F-14（LOW）实体携带密文、`list()` 返回裸实体

- `entity/AiProviderEntity.java:41` — `private String encryptedApiKey;`（无 `@JsonIgnore`）
- `service/AiProviderService.java:46-48` — `list()` 返回 `List<AiProviderEntity>`
- `controller/AiAdminProviderController.java:209-231` — `toVo()` 显式投影，用 `credentialSource(...)` 替代密文
- `controller/AiAdminProviderController.java:32` — 注释"密钥永不出现在任何响应里"

当前**没有泄露**：唯一使用 `list()` 的控制器投影到 `ProviderVo`，不含密文。风险是潜在的——任何新端点、漏注解的序列化或调试接口返回实体就会暴露密文，届时 F-10 的搬运问题立刻可用。建议给字段加 `@JsonIgnore`，或让 service 返回窄类型，使投影无法被遗忘。

## 5. 测试真实性评估

两个测试都是**真实单元测试，无 mock、无私有状态反射**，不是"绿色剧场"：

- `AiSecretCipherTest`：真 JCE（AES/GCM）+ 真 `AiProperties`。覆盖往返、每次加密 IV 随机、无 KEK 时拒绝加密明文、null/空白处理、格式损坏返回 null、KEK 变更/移除后 fail-closed、`describe()` 来源标签、环境变量解析、常量时间比较、正确的 HMAC-SHA256 参考向量（`f7bc83f4…2d1a3cd8`）。
- `AiUrlGuardTest`：调用真实静态护栏，含真实 `InetAddress` 查询。覆盖 scheme 白名单、空白/畸形 URL、元数据字面量、几乎所有私网段、边界公网地址（172.32/198.20/100.128）、单主机白名单、大小写不敏感、空项/无关项、`*` 通配。

**覆盖缺口（与发现对应）**：

1. 没有"域名解析到私网"用例 → `AiUrlGuard:60-74` 的 rebinding 拒绝分支未被证明；测试文件 18-20 行明确规避 DNS。
2. 没有"解析失败放行"的用例 → `:70-73` 的 fail-open 无断言。
3. 没有任何用例走 `AiProviderService` 的 update 路径 → **F-02 的绕过能通过全部现有测试**。
4. 没有密文篡改/AAD/搬运用例；`:64-73` 只覆盖格式错误，未覆盖"格式正确但认证失败"。
5. `OpenAiCompatibleProvider` **零测试**：流式空闲护栏、响应体上限、错误截断、超时与取消全部无覆盖。
6. `AiSecretCipherTest:106-108` 依赖运行环境的 `PATH`——真实但环境敏感。
7. `AiUrlGuardTest:26` 把 http 合法化、`:88-91` 把"通配即全放行"断言为期望，即两种不安全行为被固化。

本分片**未执行**任何 Maven/测试，故以上均为静态结论，不声称任何用例曾在本轮运行。

## 6. 明确不声明

- 不声明 task-14 或 AI 域全量审计完成；其他文件属于其他分片。
- 不声明全项目完成、不声明"脚手架/构建可绿"。
- 不重新推导 Orchestrator 护栏；Lead 的 GREEN6 按其既有结论采信。
- 未读 recovery JSON 与旧全量报告，历史覆盖账本仍由 Lead 持有。
- 本分片未做运行时验证（无构建、无测试、无真实请求），F-03/F-07 中标出的"高置信但未运行验证"须按此理解。

## 7. 建议处置顺序（给 Lead）

1. **F-01、F-02**：都只需一个已下放权限 `admin:ai:provider:edit`，且分别击穿"密钥不外泄"与"SSRF 边界"两条域级承诺；建议作为同一批安全修复，并各自补 service 层回归（当前完全无覆盖）。
2. **F-04 / F-05 / F-06**：把运行期失败前移到 API 边界的引用完整性修复，风险低、收益直接。
3. **F-07**：流式上限 + 真取消，需要新建 `OpenAiCompatibleProvider` 测试（今日零覆盖）。
4. **F-08 / F-03**：公网强制 https；连接期校验/钉址。
5. **F-09～F-14**：正确性与加固；其中 **F-10 会改变密文信封格式**，必须先给存量行的迁移/兼容方案（kid + 旧钥解密过渡），不能直接改。