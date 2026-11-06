# 第三方图床渠道与存储组设计评审

> 版本说明（2026-11-05）：本报告保留对 v1.0 的历史评审结论，其设计引用已指向原样快照，固定 blob 不变。当前修订见[详细设计 v1.1](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN.md>)，第20节给出逐项设计处理；下文“原设计未改”描述的是当时评审范围，不表示后续没有修订。

- 评审日期：2026-11-05
- 评审对象：[第三方存储组详细设计 v1.0](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md>)，状态为“设计评审中（未实施）”。
- 固定基线：仓库 HEAD `09c9510ee599f91bd360a82cdedd081afa2cb1d3`；设计文档 git blob `0df8e9fecc07a403a10a5d9aad5d47c40eef484e`。
- 范围：文档、仓库契约和静态调用链审查；未修改业务代码、原设计或部署配置，未连接生产数据库，未重新上传测试图片，未读取真实凭据。
- 方法：Standards（仓库规范与兼容性）和 Spec（需求、安全与可靠性）分别独立审查，再交叉核对证据。两轴不合成一个总分，也不把设计缺口称为已经上线验证的漏洞。

## 1. 总结

**总体方向合理，当前版本需要修订后再进入实施。**

值得保留的是“配置描述渠道差异、组决定使用哪个渠道、文件记录实际成功渠道”的分层，以及密钥只在服务端使用、服务端直连上游、不引入 Cloudflare 中转的边界。

目前的主要问题不是少几个参数，而是：配置尚不能进入现有 DTO；资源限制生效位置错误；失败转移没有处理远端结果未知；私有文件语义与永久公网外链冲突；现有授权、策略和 Provider 分发链没有接完整。五种调度策略却先于这些基础能力展开，实施顺序应调整。

“单张 1 MiB、全局并发 2、300 KB 五秒完成”只能作为待验证的初始部署策略，不能当作由硬件唯一推导出的事实。上一版关于总缓冲只有 2 MiB、流式发送不能重试、固定两倍上传耗时的表述需要纠正。

## 2. 可以保留的设计

| 设计点 | 评价与边界 |
|---|---|
| `CUSTOM_HTTP` 作为新的能力家族 | 合理；现有七种 Provider 不应被迫改走服务器代传。 |
| 一个渠道对应一条存储配置 | 合理；同厂商的不同账号、密钥、限额可独立启停。 |
| 请求参数、请求头、认证位置、文件字段、响应路径配置化 | 符合用户需求；应明确支持的协议范围，不能承诺所有第三方 API 都零代码接入。 |
| 组与渠道分离，成员带优先级/权重 | 合理；先解决候选过滤和安全失败转移，再扩展调度算法。 |
| 文件记录成功的 `storage_config_id` | 必须保留；组改成员后，旧文件仍按原渠道定位，不重新路由读取。 |
| 公共图片由上游提供图片正文 | 适合低带宽服务器；302、元数据和上传仍消耗 Astral 资源，不是整条业务零流量。 |
| 不承诺未经验证的远端删除 | 正确；但应描述为“本期未验证可用删除 API”，而不是证明平台永远不可删除。 |

## 3. Standards 轴：仓库规范与兼容性

本节保留独立审查的七项硬问题；这些是实现前必须补齐的契约，不是一般代码风格建议。

### S1. 嵌套配置不能通过现有 DTO 与归一化链

- 设计依据：[嵌套 profile](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L251-L301>)、[配置服务改造清单](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1083-L1086>)。
- 仓库证据：[StorageDtos:16–21](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/dto/StorageDtos.java#L16-L21>) 的入参仍是 `Map<String,String>`；[StorageConfigService:306–338](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageConfigService.java#L306-L338>) 也按字符串合并；[前端配置类型](<F:/qtMusic/astral/astral-front/src/api/storage.ts#L115-L181>) 未支持新结构。
- 后果：直接提交对象/数组会在 profile parser 执行前发生绑定失败；仅把 `secretFieldOf` 改成 `secrets` 也无法逐项保留或脱敏。
- 修订要求：明确 Java DTO、TS 类型、序列化、缓存与读出视图的完整迁移；旧 Provider 继续经过各自严格校验。定义每个 secret 的“未提交/保留、替换、明确清除”语义，新建时不得把 `******` 当有效凭据。
- 安全补充：[现有脱敏失败分支](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageConfigService.java#L401-L418>) 会原样返回。新模型不能沿用该兜底：解析失败应隐藏敏感配置或报错，不回传原文；脱敏应作用于独立出参副本，不污染服务端原始缓存。

### S2. 上传入口未完整接回目录授权、策略与文件归属

- 设计依据：[主流程及文件登记](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L941-L1006>)。
- 仓库证据：[原上传入口](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/controller/StorageUserController.java#L86-L105>) 显式检查目录 `UPLOAD` 权限；[配额方法](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/UploadPolicyService.java#L202-L210>) 只计数，不负责鉴权；[文件表 DDL](<F:/qtMusic/astral/astral-server/src/main/resources/db/migration/V20260914001__init.sql#L1605-L1610>) 要求 `folder_id` 非空。
- 后果：伪代码只解析目录/查配额，未列 ACL；创建文件还未填写 `folderId`。按清单照搬可能先消费上游，再因非空约束登记失败。
- 修订要求：把“根目录/默认目录”解析为确定的、有上传权限的目录；显式保留登录、ACL、管理员豁免边界和策略执行；文件登记必须写已授权的 `folderId`。与 Spec 的授权问题有关，但这里强调现有接口和数据库契约。

### S3. Provider 目标行为与具体分发改造清单未闭合

- 设计依据：[改造清单](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1081-L1094>)、[旧家族判断不变的承诺](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1280-L1287>)。
- 仓库证据：[ProviderSupport:35–41](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/ProviderSupport.java#L35-L41>) 不把新家族归入服务端管理；[删除入口](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java#L662-L687>) 在其外会走 Telegram 的 `DELETE_REMOTE` 任务；[下载入口](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java#L534-L550>) 的兜底也是 Worker signer。
- 后果：只补低层 `deleteObjectByProvider` 或 URL helper，新分支未必可达；可能生成错误的 Worker 删除任务或下载链接。
- 修订要求：逐项列出上传、上传结果查询、下载、永久链接、可见性切换、删除、测试和批处理入口的行为矩阵，显式加分支或委托对应适配器。
- 表述边界：原稿已经要求本地删除，并提到 `issuePermanentUrl` 新分支；问题是这些目标没有全部进入具体改造清单，不能一概说文稿完全没考虑它们。

### S4. axios 示例与真实 API 路径、类型及错误对象不一致

- 设计依据：[上传与错误处理示例](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1127-L1156>)。
- 仓库证据：[client:5–6](<F:/qtMusic/astral/astral-front/src/api/client.ts#L5-L6>) 默认 baseURL 为空；[响应拦截器](<F:/qtMusic/astral/astral-front/src/api/client.ts#L187-L203>) 返回完整 `ApiResult`；[request 包装](<F:/qtMusic/astral/astral-front/src/api/client.ts#L249-L254>) 提供正确的泛型接口；[业务 Error](<F:/qtMusic/astral/astral-front/src/api/client.ts#L139-L146>) 目前只有消息和 `errorCode`。
- 后果：`/all/storage/files/upload` 缺 `/api/v1`，默认部署会请求错误路径；裸 axios 泛型与拦截后的运行时结构不一致；设计又想按 `code=429` 区分繁忙，但当前 catch 对象不保留这个业务码。
- 修订要求：通过现有 `request.post<UploadView>('/api/v1/all/storage/files/upload', ...)` 封装上传；统一保留 `businessCode` 等必要错误元信息，避免每个页面各做一次解析。
- 表述边界：原例的 `const { data }` 在运行时可以拿到 `UploadView`；不能误判成已经发生双重解包。问题是路径、静态类型和错误契约。

### S5. 复用错误码的含义写错

- 设计依据：[错误表](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L845-L858>)。
- 仓库证据：[错误资源](<F:/qtMusic/astral/astral-common/src/main/resources/error-codes.properties#L130-L149>)、[业务码映射](<F:/qtMusic/astral/astral-server/src/main/java/com/astral/server/exception/GlobalExceptionHandler.java#L91-L104>)。
- 应修正为：`STORAGE002` = 配置不存在/禁用，通常 body.code 300；`STORAGE004` = 默认存储未配置；`STORAGE013` = 目录/文件无访问权限，映射 403；`STORAGE019` = Provider 不支持该能力。
- `STORAGE006` 的大小超限复用合理。新增 040 起的细分错误应登记资源，并与统一响应体规则一致，不能为了新功能改写已有错误码的含义。

### S6. nginx 样例不能直接用于现有部署

- 设计依据：[部署样例](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1182-L1198>)。
- 仓库证据：[管理端代理](<F:/qtMusic/astral/deploy/nginx/10-astral-sites.conf#L168-L180>) 使用 `127.0.0.1:3000`；[API 域代理](<F:/qtMusic/astral/deploy/nginx/10-astral-sites.conf#L238-L249>) 使用 `127.0.0.1:27000` 并带转发头、限流；[博客阈值](<F:/qtMusic/astral/deploy/nginx/10-astral-sites.conf#L58-L71>) 单独为 8m。
- 后果：宿主 nginx 未必能解析 `astral-backend`；新 location 不继承兄弟 location 的转发头/限流。8m 与 20m 属于不同站点，不是已证实的同一上传链冲突。
- 修订要求：明确在哪个 server 添加规则，沿用真实上游、可信代理头和限流；同时核对同源前端代理与跨域直连两条路径的超时，不顺手放宽博客阈值。
- 裸字节请求没有浏览器 multipart 头部，2m 不能以“预留 multipart 余量”解释。nginx 体积限制是兜底；raw-body 流式读取上限仍由应用负责，Spring multipart 上限不会自动覆盖该接口。

### S7. schema 同步清单与部分索引表达未闭合

- 设计依据：[旧表加列及 schema 清单](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L552-L593>)。
- 仓库证据：[规范要求](<F:/qtMusic/astral/AGENTS.md#L266-L283>) 加列必须同步 JSON；[IndexSchema](<F:/qtMusic/astral/astral-schema/src/main/java/com/astral/schema/IndexSchema.java#L17-L23>) 没有 WHERE 谓词；[生成器](<F:/qtMusic/astral/astral-schema/src/main/java/com/astral/schema/SchemaCodeGenerator.java#L394-L402>) 只生成全列索引。
- 修订要求：除新表 JSON，还要同步 [folder schema](<F:/qtMusic/astral/astral-plugin/src/main/resources/schema/sys_storage_folder.json>) 和 [file schema](<F:/qtMusic/astral/astral-plugin/src/main/resources/schema/sys_storage_file.json>)。部分唯一索引不能直接伪装成全列唯一索引。
- 可以选择扩展受控的部分索引元数据/生成能力；或明确暂由 Flyway 独占维护该特殊索引，并防止管理工具生成错误的全列唯一索引。无论选哪种，都不能声称当前工具已经能与 DDL 完全对齐。

**Standards 事实修正：**[原稿对旧唯一索引的批评](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L517-L521>) 不准确。[运行迁移基线](<F:/qtMusic/astral/astral-server/src/main/resources/db/migration/V20260914001__init.sql#L1559-L1560>) 已使用 `WHERE is_default = 1`。这不证明生产库当前结构，却足以否定“仓库迁移就是整列唯一”的说法；不要因此擅自修改旧索引。

## 4. Spec 轴：需求、安全与可靠性

本节为独立 Spec 审查：7 项 P1、1 项 P2。P1 表示按该设计实施前必须解决；P2 表示应在修订版明确解决的行为问题。它们不与 Standards 轴合并评级。

### R1 · P1：可重放字节不等于可以安全重试

- 证据：[失败转移规则](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L768-L776>)、[上传后登记顺序](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L974-L1006>)、[现有 uploadId 幂等登记](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java#L214-L227>)。
- 场景 A：上游 A 已存图，但响应超时、JSON 缺字段或 URL 被安全检查拒绝；随后切 B，生成第二份图片，A 的副本无法登记/清理。
- 场景 B：远端已成功，本地插入/审计失败或客户端断线；用户再传一次，重复上传而不是补登记。
- 修订：在上游请求前生成稳定 `uploadId/publicId`，绑定主体、目录和文件摘要；持久化上传操作及尝试状态。区分 `DEFINITE_FAILURE`、`UNKNOWN_OUTCOME`、`REMOTE_SUCCEEDED` 和 `COMMITTED`。结果未知默认不盲目切换；已知远端成功时只重试本地登记。
- 必须承认：不支持上游幂等键/查询的服务无法保证跨系统 exactly-once。即使记录尝试，也只能收敛风险、保留待对账状态，不能消除远端成功而本地尚未持久化之间的崩溃窗口。

### R2 · P1：并发许可取得太晚，不能约束内存

- 证据：[先 readBounded、后 gate.acquire](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L957-L984>)、[multipart 完整复制](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1056-L1069>)。
- 后果：N 个请求能先各自持有一份图片，只有上游发送阶段受“并发 2”限制；等待者数量和缓冲总量无硬界限。两条活跃请求还各有原图与完整 multipart 副本，仅主要数组就约 4 MiB，而非 2 MiB，尚未含扩容、HTTP/TLS 等开销。
- 修订：鉴权后、读取正文前获取受控容量许可；限制等待者、读取时间及总请求预算；过载尽早返回。持有资源期间统一 finally 释放；不能在配额/本地登记仍未完成时过早释放同一用户的串行约束。
- 优化：保留一份受限原图缓冲，multipart 使用“前缀 + 原图缓冲 + 后缀”的可重放 publisher，不再拼第二份完整 byte array。非可重放的浏览器输入流与基于已保留数据的流式发送是两回事。

### R3 · P1：响应大小限制必须在读取时生效

- 证据：[ofByteArray 后才 mapper 校验](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1012-L1028>)。
- 后果：上游返回超大响应或持续 chunked 数据时，`BodyHandlers.ofByteArray()` 已经分配全部内容，随后检查 256 KiB 来不及保护堆。
- 修订：采用有界订阅器或流式读取，累计达到上限后取消/关闭；错误响应也受同样限制。对缺失/伪造 Content-Length、慢响应、超时和响应压缩后的体积边界给出一致规则，不能只依赖响应头。

### R4 · P1：永久公网 URL 无法提供 PRIVATE 语义

- 证据：[直接返回直链且 expiresAt=0](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L900-L905>)、[继承目录 visibility](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L987-L1006>)；现有 [下载授权](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java#L514-L550>) 与 [仅 PUBLIC 可发永久链接](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java#L573-L587>)。
- 后果：标为 PRIVATE 的文件实际可被拿到 URL 的任何人持续访问；对 Astral 的鉴权、302 或本地删除都不能撤销已经公开的上游地址。
- 修订建议：本期公共图床明确 `visibilityMode=PUBLIC_ONLY`；发送前根据 `forceVisibility` 和目录默认值计算最终可见性，私有目标直接拒绝并保留原私有存储路径。禁止把此类已公开资源“改成 PRIVATE”后宣称已撤权。
- 本地删除应区分“从 Astral 移除”与“已从上游删除”，不把 `expiresAt=0` 解释成上游承诺永久保存。公共图床不是可靠归档服务。

### R5 · P1：二次 DNS 检查未封闭 SSRF 重绑定窗口

- 证据：[保存/执行检查的安全承诺](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L474-L477>)、[执行伪代码](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1013-L1024>)；[HttpToolExecutor:268–305](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java#L268-L305>)。
- 后果：检查时解析出的公网地址与 HTTP 客户端真正建连时解析出的地址不一定相同。执行前重查 DNS 本身不能证明不会连入内网。
- 修订：HTTPS、受信目标 origin/端口白名单、禁重定向只是基础；还需在实际连接/出站网络层约束目的 IP，同时保留正确 TLS 主机校验。若当前客户端栈无法固定已验证地址，应采用受控出站方案或收紧到审定预设，不能把缺口标为已解决。
- 工程边界：`guardHost` 是包级 static，`requirePublicAddress` 是 private，不能跨包直接调用。需要抽取公共安全组件，而不是存储模块依赖 AI 执行器的内部实现。

### R6 · P1：传输配置权限与凭据信任边界未定义

- 证据：[预设字段任意覆盖](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L383-L385>)、[复用脱敏/保留旧 secret](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1083-L1086>)、[现有配置编辑与测试权限](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/controller/AdminStorageController.java#L88-L107>)。
- 风险条件：如果配置编辑权被下放给“可使用但不可查看已有凭据”的运营人员，保留 `******` 再把 endpoint 改为其控制的公网地址，就能让服务端把旧 key 发过去。仅校验公网地址/HTTPS 和读接口打码不能阻止它。
- 修订：明确配置编辑者是否属于完全可信的密钥管理员；若不是，普通组运营与出站地址/凭据管理应分权。凭据绑定获批 origin，改变目标不得静默沿用旧凭据；需要重新授权目标或明确替换凭据。
- 对任意命名密钥及上游反射错误采用结构化脱敏：不要原样记录响应前 200 字符，不让异常消息、探测结果、直链查询参数、审计或操作日志带回密钥。
- 表述边界：本条不是宣称所有拥有配置管理权的人都是攻击者，而是指出文稿把“可安全下放”与“可将已有密钥发送到任意公网端点”同时成立，却没有说明信任模型。

### R7 · P1：不走 ticket 不代表原策略会自动执行

- 证据：[新主流程](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L941-L960>)、[声称沿用 ticket 拒绝 SVG](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1239-L1243>)；[原 ticket 策略执行](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/security/UploadTicketService.java#L105-L126>)、[forceVisibility 落点](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java#L269-L277>)。
- 后果：登录、目录允许的 MIME、最小/最大尺寸、强制可见性等可能失去执行点。仅看 `Content-Type` 以 `image/` 开头不能拒绝伪造图片，也包括 `image/svg+xml`。
- 修订：抽出共享授权/上传校验组件，旧 ticket 路径与新 raw-body 路径都显式调用。读取后对真实字节做 magic/MIME 一致性检查和必要的尺寸校验；授权、大小、目的地能力必须在向外发送前确定。
- **无需为了性能强制 NONE。**[现有像素读取](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java#L419-L433>) 使用 ImageReader 获取宽高，不是完整 `ImageIO.read()` 解码。应“复用已在内存中的字节做本地验证、跳过再次远端下载”，而不是整体关闭验证。政策值实际是小写 `none/magic/full`。

### R8 · P2：全局默认组不应覆盖目录显式绑定

- 证据：[解析优先级](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L692-L705>)、[既有目录绑定选择](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/controller/StorageUserController.java#L99-L105>)。
- 后果：已有目录明确绑定 R2，仅新增全局默认图床组就改变它后续上传目的地，与“旧行为零影响”不一致。无权指定的组头被静默忽略，也会让调用者误以为按所选目标上传成功。
- 修订：合法的显式请求选择（若本期支持）优先；其次目录显式组/单渠道；只有目录明确选择“继承默认”才使用全局默认。目标非法、禁用或无权限时失败关闭，不悄悄换目的地；故障转移仅限已授权组内候选。
- 存量目录 `storage_config_id` 非空，因此新默认组不会自然接管旧目录，应设计显式迁移/重新绑定流程，而不是用优先级绕过旧绑定。

## 5. 补充校核：必须更正的事实与可简化的设计

### 5.1 参数模型保留，但应限制成可验证的配置协议

1. 支持范围写为“本期：HTTPS POST + multipart 文件 + JSON 响应 + 静态凭据引用”。OAuth 刷新、HMAC 动态签名、二进制响应、异步任务、多阶段上传不自动属于零代码接入范围；未来通过受控适配器扩展，不引入任意脚本执行。
2. 冻结版本化 profile；预设按明确版本展开成最终有效配置，不让服务升级后隐式改变已保存渠道。列出字段数量、长度、未知字段、请求头大小写去重及 auth/query/form 同名冲突规则。
3. 请求头值拒绝 CR/LF，危险保留头宜报配置错误而非无声剔除；文件名和 multipart 字段也必须规范化，模板按 URL/query/header/form 各自上下文正确编码。
4. `${secret:name}` 保留统一语义。[原稿的“完整复用”及 `${var:...}`](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L367-L381>) 应明确为语法扩展或收敛到统一语法；`publicId` 必须在模板渲染前生成。
5. 首发成功条件建议只保留带类型的 `EQ/EXISTS`；非必要的任意 `MATCH` 正则延后，避免把轻量配置系统变成表达式执行器。数字字符串转换只允许已声明字段，不能把任意真值当成功。
6. [sizeUnit 换算](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L348-L352>) 写反：上游 KIB → 字节应乘 1024，KB → 字节应乘 1000。上传字节数与本地摘要以实际发送内容为准，上游回报大小单独保存，不能靠差异小于 20% 的“自愈”猜测单位。上游若转码，本地摘要不应被冒称为远端对象摘要。
7. 不把删除授权 URL 当普通展示字段；本期无已验证删除能力时不采集/展示敏感删除凭据。普通下载 URL 也要校验并防止凭据反射。

### 5.2 组策略：分开“选择、重试、健康”，首发做少而正确

[当前算法](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L724-L774>) 有三类语义不一致：

- 先按 priority 排序再对所有候选轮询，并不会保留优先级层级；而成员表又声称权重参与 ROUND_ROBIN，算法没有实现加权。
- `HGETALL` 累计成功数加一个持续续期的 1h TTL 不是“最近一小时使用量”；只要持续有上传，老计数就不会被逐小时剔除，也没有计算在途占用。
- 全 DOWN 时全部回填、4xx 立即 DOWN、连续三次失败才 DOWN 同时存在；401、429、非法图片、解析失败也不应使用同一健康状态转换。

建议首发只提供主备顺序和一种分散策略（轮询或加权随机），其余延后。将以下三件事独立表达：

- **选择**：如何产生去重后的候选顺序；priority 是分层还是排序字段，权重仅用于哪些策略。
- **重试**：仅确定没有产生远端对象的失败才能自动切换；次数硬上限、剩余总预算与上游能力共同约束。
- **健康**：启用状态、凭据无效、限流冷却、暂时故障分别处理；按 Retry-After/退避冷却，必要时只允许一个半开探测，而不是把所有 DOWN 都重新放行。

明确 Redis 不可用时的行为；对本期单实例可采用有界本地退化，不为了五种算法先引入分布式队列。渠道配置/成员列表按一次上传的快照执行，保存配置版本用于对账。

### 5.3 资源和时延应采用实测模型，不承诺固定倍数

对于“先完整接收，再发送上游”的主流程，粗略下界为：

```text
T ≈ 文件字节数 / 浏览器到服务器的有效吞吐
  + 文件字节数 / 服务器到上游的有效吞吐
  + 排队、TLS、上游处理和本地登记时间
```

1 Mbps 的理想裸吞吐是 125,000 B/s：1 MiB 仅出站传输约 8.39 秒；300,000 B 仅出站约 2.4 秒。只有两段都被限制为 1 Mbps 且串行时，纯传输才约为两倍；不能从“服务器带宽 1 Mbps”直接断言入站也是同一上限。

因此，[300 KB ≤5 秒、1 MiB ≤17 秒的验收](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1314-L1322>) 应改为目标机上测量 p50/p95、成功率、并发时吞吐、峰值堆和慢请求拒绝情况，而不是先宣布必达。同样，两用户共享 1 Mbps 出口时可能互相拖慢；“全局 2、每用户 1”也不意味着第二个不同用户一定排队。

建议从小图优先、全局 1 或 2 的可配置值开始压测后选择，不改现有 JVM 参数作为前提，但也不能仅凭理论缓冲占比保证现有堆足够。1 MiB 可以保留为初始保护上限，不是内存和带宽独立证明的唯一答案。

每次请求只有一个绝对 deadline；每次尝试按 `min(单次预算, 剩余预算)` 执行，读取、排队和登记也计入总预算。`proxy_read_timeout` 是代理读取空闲超时，不等于业务端到端总 deadline。

### 5.4 管理操作、权限与并发更新

- [组测试权限表](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L875-L892>) 把真实上传探测赋给 view，而[现有配置测试](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/controller/AdminStorageController.java#L103-L107>) 需要 edit。建议区分离线配置校验与真实上传测试：后者需要相应写/测试权限、确认有外部副作用，并走相同体积/并发/日志限制，不作为无成本的定时心跳。
- `admin:storage:view/edit` 是存量三段码，新规范要求 `端:域:资源:操作`。不得简单宣称全部符合最新规范，也不应只改注解造成旧角色失权。下一版应写明兼容例外，或提供代码与权限数据同批迁移方案。
- [全量替换成员](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L873-L879>) 不会自动解决并发编辑。用版本号/ETag 做乐观并发校验，成员替换、默认切换与版本更新保持事务一致，提交后再失效缓存。
- 配置删除/禁用要处理组成员引用；删除组要考虑目录引用和历史文件审计。选择服务校验或外键策略，但不能留下可执行的悬挂成员。成员权重、优先级、状态、尝试次数都需范围校验。
- 目录双绑定要有明确的有效目标模式：CONFIG、GROUP 或 INHERIT，存量映射为 CONFIG；不要让两个外键的偶然非空状态暗中决定业务。物理字段可复用，但须满足既有非空约束和新建目录流程。

### 5.5 前端与验证范围

- `onUploadProgress` 只表示浏览器到 Astral 的传输进度，不能当作完整链路进度。达到 100% 后显示“上游处理中”，收到业务成功才显示完成。
- 不把所有图片无提示转为有损 WebP：动态 GIF、透明图、已很小的图片以及压缩失败要定义保留/拒绝策略；重编码后同步真实 MIME 与文件名扩展名，释放 ImageBitmap 等资源。
- 客户端压缩不构成服务端安全边界；外部客户端可以完全绕过。
- [测试代码 ≥400 行](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1294-L1312>) 不是有效验收标准。先用本地 mock 上游覆盖失败、限额、未知结果和并发；真实网络用例必须显式开启，承认图片副作用与未验证删除能力。
- [排除对象仍留在附录](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1462-L1527>) 与用户要求不符，应从本设计移除；历史探测事实可以独立保留，不再参与本期方案。

## 6. 建议的收敛架构

建议采用三个配置层和一条上传执行链，不引入新的重型工作流平台。

### 6.1 三个配置层

| 层 | 负责什么 | 不负责什么 |
|---|---|---|
| 协议模板/Profile | 固定版本的请求构造、响应解析、能力声明、允许的模板语法 | 用户权限、组轮询、任意代码执行 |
| 渠道/Channel | 某个已批准端点、账号凭据、限额、状态和模板版本 | 动态改变文件原有归属 |
| 组/Group | 允许使用的渠道集合、选择策略、安全重试限制 | 代替目录授权、替上游保证隐私或删除 |

模板首先可以是版本化的内置预设与严格校验后的 JSON，不必为“模板管理”再建一套独立 CRUD/市场。新家族的 capability 必须由已支持协议和验证过的适配能力约束，不能用户勾一个 `delete=true` 就视为已实现删除。

### 6.2 上传执行链

```text
请求/幂等键
  → 身份、目录 UPLOAD ACL、有效目标与策略解析
  → 读取正文前的容量准入（有界等待、总 deadline）
  → 有界接收一份图片；本地 MIME/magic/尺寸校验与摘要
  → 生成稳定 uploadId/publicId；落上传操作与候选快照
  → 公共/私有能力、实际体积、配置版本、剩余配额复核
  → 可重放 multipart 发送；实际出站目的地限制
  → 在读取阶段限制上游响应大小
  → 确定失败：按安全规则尝试下一候选
  → 结果未知：持久化 UNKNOWN_OUTCOME，停止盲切
  → 已知远端成功：记录结果，再提交文件登记
  → 返回结果；失败重连按幂等键/上传 ID 查询；finally 释放资源
```

幂等键需在请求开始时按主体登记/去重；正文摘要用于阻止同一键提交不同内容。状态查询必须校验同一主体/目录权限，不因知道 ID 就能读取他人 URL。`publicId` 只要求在模板渲染和上游发送前稳定生成，不需要重新启用含秘密信息的旧 ticket。

可增加一张轻量上传操作表保存状态及受限的尝试记录，文件表仍表示最终资产；也可在明确状态兼容后复用现有模型，下一版应选定一种。Redis 600 秒上下文和普通成功审计不能代替持久化状态。无需引入消息队列，也不在等待上游时持有数据库事务/行锁和稀缺连接。

上传操作记录不意味着必须持久化图片正文：本期可仍然只在内存保留小图；进程崩溃后已知结果可以补登记，无法确认的尝试保持 UNKNOWN，不能假装可无损恢复继续上传。

### 6.3 重试结果分类

| 结果 | 默认动作 |
|---|---|
| 发送前的本地参数/权限/图片校验失败 | 直接失败，不请求任何上游。 |
| 能确认尚未提交请求的建连失败 | 在剩余预算内尝试下一授权候选。 |
| 经该 Provider 契约确认未创建对象的拒绝 | 可按错误类别换渠道；凭据错误标配置不可用，429 按契约冷却。 |
| 已发送后超时、断线、5xx、缺字段、超大响应、安全 URL 校验失败 | 可能已创建对象，默认 UNKNOWN，不自动再传。HTTP 状态本身不足证明安全重试。 |
| 远端成功、本地登记失败 | 重试/恢复本地登记；无法持久化的崩溃窗口明确保留为未知，不重新上传掩盖它。 |
| 已登记，同键重放 | 返回同一结果，校验主体与内容一致性，不再消费上游。 |

若未来希望“宁可重复也尽量成功”，必须作为用户明确接受的策略及告警语义，而非默认无副作用行为。

## 7. 建议调整实施顺序

[原阶段安排](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md#L1329-L1339>) 把并发闸门、审计放到 P4，不适合资源受限的真实上传服务。

| 阶段 | 建议交付 | 完成标准 |
|---|---|---|
| D0：修订契约 | 本报告的两轴问题收口；确定公共资产边界、目标选择、profile 类型、幂等和未知状态 | 不再有“目标行为有、改造路径无”的关键缺口。 |
| D1：安全的单渠道纵切 | DTO/脱敏、ACL/本地验证、前置准入、受控出站、有界响应、状态登记、mock 上游 | 不靠真实图床也能验证失败/过载/恢复；这些不是后期补丁。 |
| D2：最小组能力 | 组表/成员/版本控制、目录显式绑定、主备顺序、安全失败转移 | 两个模拟渠道，确定失败可切换，未知结果不盲切，旧目录不改变目标。 |
| D3：运营闭环 | 配置表单、组管理、进度分阶段、明确副作用的测试、必要监控 | 无真实密钥回传，普通读权限不能触发真实上传，删除提示准确。 |
| D4：按指标扩展 | 视需求增加轮询或加权随机，再评估最少使用/健康优先 | 以业务负载、稳定性和可解释性证明需要，不以算法数量验收。 |

回滚优先关闭新上传入口/新组使用并保留历史配置、文件和上传操作记录；不要把“删除新表”当常规回滚。旧版本不识别 `CUSTOM_HTTP`，即使能启动也不代表能读旧的新家族文件，应预先验证回滚期间的读取/管理兼容路径。

## 8. 修订版应补充的验收用例

以下都是后续应执行的验收，不代表本轮已经运行通过。

1. 新旧配置往返：七种旧 Provider 不回归；嵌套 profile 成功保存；逐密钥更新/清除；损坏 JSON 不回传秘密；origin 改变不偷带旧密钥。
2. 目录与策略：无权限目录、缺失/禁用目标、forceVisibility=PRIVATE、MIME 伪造、SVG、空文件、超尺寸/超像素均在发送前拒绝；管理员豁免不绕过硬安全上限。
3. 输入资源：伪造/缺失 Content-Length、chunked 超限、慢速发送、大量并发、超时取消；验证读正文前准入、许可无泄漏、缓冲总量有界。
4. 上游资源：超大成功/错误响应、无长度持续输出、慢响应；在读取过程中终止，内存不随响应无限增长。
5. 不确定结果：上游写入后断线/超时/返回坏 JSON，后续候选不被自动调用；保留 UNKNOWN；用户重放可查同一操作。
6. 幂等和登记：并发同键只执行一次操作；不同主体不能复用查询；同键不同内容拒绝；远端成功后 DB/审计失败不重新上传；进程故障窗口明确。
7. 组语义：默认组不覆盖旧目录；未经授权的显式目标拒绝；两次并发成员编辑不静默覆盖；默认切换原子；被引用渠道删除行为确定。
8. 出站安全：非 HTTPS、私网/IPv6 特殊地址、重定向、DNS 变更、凭据反射、头注入等由本地可控测试覆盖，不以“保存时测过”代替运行约束。
9. 端到端分发：上传、查询、下载、永久链接、可见性切换、删除、批处理均命中正确家族；不生成新家族的 Telegram 任务。
10. 部署与响应：实际两条代理路径、API 前缀、CSRF、繁忙 body.code、nginx 413、总 deadline 都符合统一契约；不修改不相关站点限制。
11. 目标机性能：明确带宽限制在哪个方向；分别测单用户与双用户 p50/p95、峰值堆、吞吐、其他 API 延迟；压缩失败/动态图片体验同时验证。
12. 真实上游冒烟：仅显式授权时执行最小次数；记录实际契约与残留对象，不把一次探测推广为永久可用、无限流或不可删除的证明。

## 9. 评审结论与边界

**建议保留核心架构，先修订 v1.1，再实施；不建议直接照 v1.0 编码。**

首发目标应是“小而可靠的公共图片渠道组”，不是“支持五种策略的万能 HTTP 图床引擎”。参数化解决厂商差异，前置准入解决资源边界，状态与幂等解决不确定结果，能力模型解决隐私/删除承诺——四者缺一不可。

本轮只形成评审与优化建议；原设计保持不变。生产网络表现、删除能力、最终容量参数以及迁移执行结果均未在本轮重新验证。
