# 后端审计报告 · 存储安全 / HTTP 渠道 / 上传状态机

- 基线：`a39a3f3039ce7deafc3f547a25cc0dc4635c111a`
- 审计域：`astral-plugin` 的 `com.astral.storage` 全部主代码、测试、`sys_storage_*` 表结构 JSON
- 日期：2026-11-07
- 作者：storage-http（Team task-7）
- 证据等级：**源码全量静态审计 + 纯单测回归设计**。未执行 Maven、未连接真实数据库、未调用真实上游、未使用真实凭据；性能与时序结论均来自代码路径与超时 API 语义，不是生产实测。

---

## 0. 结论摘要

| 编号 | 问题 | 严重度 | 状态 |
|---|---|---|---|
| ST-SEC-01 | CUSTOM_HTTP 出站 URL 先做 DNS 校验，但真正连接由 HttpClient 再次解析：DNS rebinding/TOCTOU 可把已校验公网地址替换成内网地址（SSRF） | 严重 | 已修 |
| ST-SEC-02 | 上传 attempt 只配置 responseTimeout（等待/空闲超时），不是整次尝试预算；慢速或分段响应可无限拖长请求，且定时任务/连接未可靠取消 | 高 | 已修 |
| ST-SEC-03 | 仅按 `SocketTimeoutException` 统一归因，连接阶段与读取/发送/总超时混淆；可能把结果未知误判为 NOT_SENT 并允许重发 | 高 | 已修 |
| ST-SEC-04 | `sys_storage_config.provider_options` 打码只扫描顶层键、不递归嵌套对象/数组，且 `privateKey`/`apiKey` 类扩展凭据键未覆盖；解析失败时直接返回原文 | 严重 | 已修 |
| ST-SEC-05 | 管理端文件详情/可见性返回 provider locator；已删除文件仍可通过详情接口读取元数据；Worker 回源端点未限制 Telegram Provider | 高 | 已修 |
| ST-STATE-01 | 上游已创建后直接一步写 COMMITTED；进程在「上游成功—本地登记」之间崩溃会停在 SENDING，恢复任务无法区分「已创建但未登记」 | 严重 | 已修 |
| ST-STATE-02 | 上传重试时配额先算 usage 再预占：同一操作在 NOT_SENT 后换目标，`dailyLimit=1` 时被自己已 HELD 的额度阻塞成死锁 | 高 | 已修 |
| ST-STATE-03 | 配额 usage 未限定 `actorType=USER`；同名的 PLUGIN actor 与 USER actor 共用额度命名空间，互相占用/拒绝 | 中 | 已修 |
| ST-STATE-04 | `claimForReceiving` 对 sendDeadline 空值直接 NPE；目录已删除/停用时先在事务内解引用 | 中 | 已修 |
| ST-STATE-05 | 发送前只信创建时快照，未复核当前目录 enabled/version、当前操作者授权、候选资格与 config enabled/version | 严重 | 已修 |
| ST-STATE-06 | v2 路由快照解析过宽/兼容 priority=0 失败；GROUP 旧三态列已由 `V20261106003` 下线，真正缺口是 snapshot 事实源未严格解析与复用 | 高 | 已修 |
| ST-STATE-07 | 删除目录只取安全锁、不取树锁，与文件登记/权限并发可能留下孤儿文件或悬挂授权；锁顺序未固定 | 高 | 已修 |
| ST-STATE-08 | 直传票据解析只校验渠道状态，不校验目录 enabled：目录停用后仍可签发预签名 PUT，对象可上传成功但登记必然失败，留下孤儿对象 | 高 | 已修 |
| ST-STATE-09 | 故障转移的尝试预算少算当前渠道：`attemptsJson` 记录历史失败渠道后，第二次失败仍会再切第三个渠道，实际发送 3 次而契约上限为 2 次 | 高 | 已修 |
| ST-QUOTA-01 | 跨日 HELD 未退回，旧日额度永久占用；同日 HELD 重试重复检查造成自阻塞 | 中 | 已修 |
| ST-VERIFY-01 | 内容验证失败用整行 `updateById` 覆盖，可能把并发恢复写入的状态/字段回滚 | 高 | 已修 |
| ST-AUTH-01 | Worker HMAC 时间戳 `Math.abs(now-ts)` 对 `Long.MIN_VALUE` 溢出，极端时间戳可绕过窗口校验 | 低 | 已修 |
| ST-FILE-01 | 两次并发删除均可能通过前置查询并重复清理远端；删除任务/审计双成功 | 中 | 已修 |
| ST-FILE-02 | 未知 Provider 的删除 `StorageRemoteDeleteService` 静默返回并记审计成功（假成功） | 中 | 已修 |
| ST-MIME-01 | 上传 `Content-Type` 直接进入 multipart 头，CR/LF/引号可注入请求头或破坏 multipart | 高 | 已修 |
| ST-MIME-02 | magic 策略只对旧直传路径生效，两步上传发送前未做同等级 magic/SVG 检查 | 高 | 已修 |
| ST-DIR-01 | 目录策略 `maxPixels` 未接入上传图片尺寸校验；策略只能收紧不能放宽全局上限 | 中 | 已修 |
| ST-UI-01 | 删除预览：当前源代码契约没有 dry-run/preview 端点；删除是立即物理删行、远端清理异步尽力而为 | 观察项 | 非缺陷，见 §7 |

未修复但已定级的残余项见 §8。**未运行 Maven 是本报告唯一未完成的验证项**，所有测试命令已列在 §9，由 Lead 统一串行执行。

---

## 1. 覆盖清单（真实清点）

### 1.1 主代码逐文件覆盖（69 个 Java 文件）

| 包 | 文件 | 覆盖内容 | 深度 |
|---|---|---|---|
| `config` | `StorageProperties.java` | `maxFileSizeBytes=20MiB`、ticket TTL、下载 TTL、origin 窗口、CUSTOM_HTTP 开关/批准目标/并发 1-2、UploadOperation 5 MiB 上限与租约/恢复参数 | 全文件 |
| `config` | `StorageAsyncConfig.java` | 远端删除线程池 core1/max4/queue200/daemon | 全文件 |
| `config` | `StorageMapperConfig.java` | `@MapperScan("com.astral.storage.mapper")` 常驻注册 | 全文件 |
| `config` | `StorageWebConfig.java` | Worker/origin 两个精确子树注册 HMAC 拦截器，其余 `/all/storage/**` 仍走 satoken | 全文件 |
| `controller` | `AdminStorageController.java` | 概览/配置/文件夹/权限/文件/任务/审计路由与声明式权限；locator 剥离 | 全文件 |
| `controller` | `AdminStorageHttpConfigController.java` | CUSTOM_HTTP 渠道 CRUD、离线校验、真实测试、权限拆分 | 全文件 |
| `controller` | `StorageWorkerController.java` | 上传回调、回源元数据、删除任务拉取/ack；新增 Provider 门禁 | 全文件 |
| `controller` | `StorageUserController.java` | App 文件列表/详情/上传票据/可见性/删除；DELETED 预览门禁 | 全文件 |
| `controller` | `UploadOperationController.java` | 创建/查询/发送三段式接口、`readBounded` 有界读体 | 全文件 |
| `dto` | `StorageDtos.java`、`HttpChannelDtos.java`、`UploadOperationDtos.java` | 请求/响应边界、locator 可见性、版本字段 | 全文件（结构检查） |
| `entity` | `StorageConfigEntity`、`StorageFileEntity`、`StorageFolderEntity`、`StorageFolderConfigEntity`、`StorageFolderPermissionEntity`、`StoragePermissionConfigEntity`、`StorageTaskEntity`、`StorageUploadOperationEntity`、`StorageAuditEntity` | 状态/枚举常量、逻辑删除、版本/配额字段 | 全文件（常量与关键字段） |
| `http` | `ApprovedTargetPolicy.java` | 启动期解析批准目标：HTTPS、端口、无 userinfo/fragment、IDN 小写、拒绝 IP 字面量、路径规范化；空清单 fail closed；`require` 精确 origin+path；`requireOutputUrl` 归一化 | 全文件 |
| `http` | `HttpUploadProfile.java` | 画像 record、schema v1、5 MiB 硬上限、16 KiB 序列化上限、认证/请求体/响应/限额/超时结构 | 全文件 |
| `http` | `HttpProfileValidator.java` | 画像逐字段校验：endpoint 长度/路径、query/header/form 键值上限、危险头禁止、模板编译、响应条件/Pointer/单位/限额、16 KiB 上限 | 全文件 |
| `http` | `HttpTemplateEngine.java` | URL 只使用字面 endpoint + 排序 query，键值编码、URL 长度上限；header/form/render 转义 | 全文件 |
| `http` | `HttpResponseParser.java` | 2xx、严格 JSON、成功条件、输出 URL 批准 origin、尺寸/像素换算、凭据反射检测（原值/百分号/Base64/Hex） | 全文件 |
| `http` | `HttpSecretEnvelope.java` | schemaVersion=1、binding(channelId/origin/path)、凭据名 `[A-Za-z][A-Za-z0-9_]{0,31}`、单值 2 KiB、总数 8、KEEP/SET/CLEAR、View 永不含明文 | 全文件 |
| `http` | `StrictJson.java` | 重复键/未知字段/尾随 token 拒绝、深度 32、字符串 16 KiB、数值文本 32、总 256 KiB | 全文件 |
| `http` | `HttpChannelTestTransport.java` | 测试传输契约：有副作用、合成内容、不可用必须 STORAGE050，不伪造成功 | 全文件 |
| `http` | `PinnedDnsResolver.java`（新增） | 构造时校验主机非空 + 全为公网 IPv4；`resolve` 仅对规范化主机返回克隆数组；`resolveCanonicalHostname` 返回原主机且不做反查；单次 `getAllByName` | 全文件 |
| `security` | `StorageHmac.java` | HMAC-SHA256 + Base64URL、常量时间比较、规范串 | 全文件 |
| `security` | `StorageUrlSigner.java` | 下载 URL 签名/验签、TTL/keyVersion | 全文件 |
| `security` | `UploadTicketService.java` | Redis ticket 2×TTL、payload 无配置/目录版本、Telegram/UPYUN/S3/COS/OSS 预签名、CUSTOM_HTTP 拒绝 | 全文件 |
| `security` | `StorageWorkerAuthInterceptor.java` | HMAC 时间戳窗口/Nonce Redis SETNX/常量时间验签；新增溢出安全窗口比较 | 全文件 |
| `service` | `HttpUploadGateway.java` | HTTP 渠道装载/画像校验/URL+头+表单构造/multipart/发送/响应解析/错误归因/总截止时间 | 全文件 |
| `service` | `HttpStorageConfigService.java` | 渠道创建/更新/测试、版本 CAS、安全视图、凭据信封 | 全文件 |
| `service` | `StorageConfigService.java` | 配置 CRUD/测试/默认、出参脱敏、凭据边界 | 全文件 |
| `service` | `StorageConfigCache.java` | Caffeine 60s 只读缓存、写入 evictAll、仅服务端内部使用未打码行 | 全文件 |
| `service` | `StorageFolderService.java` | 目录 CRUD、绑定归一化、权限保存、targetVersion、锁顺序、子路径 | 全文件 |
| `service` | `StorageFileService.java` | 回调/浏览器登记、内容验证、可见性/删除、Provider HEAD/URL、locator 安全 | 全文件 |
| `service` | `FileManagerService.java` | 文件分页/移动/目录管理 | 全文件 |
| `service` | `StorageBatchService.java` | 1-100 批量删除/移动/可见性、逐项失败聚合 | 全文件 |
| `service` | `StorageAuditService.java` | 审计写入与分页、异常不外泄 | 全文件 |
| `service` | `UploadOperationService.java` | 创建意图/正文准入/发送/状态机/结果持久化/COMMITTED 登记/换目标/回收 | 全文件 |
| `service` | `UploadQuotaService.java` | 配额 usage/reserve、锁、HELD/CONSUMED/REFUNDED/NONE | 全文件 |
| `service` | `UploadAdmissionControl.java` | 全局/用户/inflight 计数、Permit 幂等释放 | 全文件 |
| `service` | `UploadOperationRecoveryJob.java` | RECEIVING/SENDING/REMOTE_SUCCEEDED 恢复、租约、CAS 失败处理 | 全文件 |
| `service` | `UploadPolicyService.java` | 策略解析/归一化、MIME/扩展名/大小/像素/验证等级、每日限额 | 全文件 |
| `service` | `UploadImageValidator.java` | PNG/JPEG magic、尺寸/像素上限、策略可收紧 | 全文件 |
| `service` | `StorageTargetResolver.java` | v2 快照严格解析、folder 解析、grant 解析、当前候选复核 | 全文件 |
| `service` | `TargetSelectionCursor.java` | 有界轮询游标 | 全文件 |
| `service` | `StorageTicketTargetService.java` | 票据目标选择、CUSTOM_HTTP 拒绝 | 全文件 |
| `service` | `StorageRemoteDeleteService.java` | 对象存储删除分派、未知 Provider fail closed | 全文件 |
| `service` | `WorkerCachePurgeService.java` | Worker purge HMAC、5s 请求超时、尽力而为 | 全文件 |
| `service` | `ProviderSupport.java` | Provider 家族判定 | 全文件 |
| `service` | `S3ObjectService.java` | S3/R2/QINIU 选项、SigV4 预签名、HEAD/DELETE | 全文件 |
| `service` | `CosApiService.java` | COS 选项、q-sign 预签名、HEAD/DELETE | 全文件 |
| `service` | `OssApiService.java` | OSS V1 签名、HEAD/DELETE | 全文件 |
| `service` | `UpyunApiService.java` | UPYUN 选项、FORM API、CDN token、HEAD/DELETE | 全文件 |
| `mapper` | 9 个 `Storage*Mapper.java` | 自定义锁/查询 SQL 契约 | 结构检查 |
| 根 | `StoragePlugin.java` | 插件 id、路由前缀、导航 | 全文件 |

### 1.2 表结构 JSON 覆盖（9 个）

| 文件 | 检查点 |
|---|---|
| `sys_storage_config.json` | provider/type/status/default 字段、provider_options TEXT、版本 |
| `sys_storage_folder.json` | folder 状态/可见性/策略、目标绑定、版本 |
| `sys_storage_folder_config.json` | folder→config priority 绑定唯一键 |
| `sys_storage_folder_permission.json` | 主体类型/权限位、唯一键 |
| `sys_storage_permission_config.json` | 授权→候选配置子集 |
| `sys_storage_file.json` | provider_type 非空默认 TELEGRAM、locator、upload_operation_id 唯一、软删状态 |
| `sys_storage_task.json` | 删除任务 payload/状态/重试 |
| `sys_storage_upload_operation.json` | 状态、folder_version、route_snapshot_json、send_deadline、attempts_json、quota |
| `sys_storage_audit.json` | 审计事件字段 |

### 1.3 测试覆盖（19 个测试类）

`HttpProfileValidatorTest`、`HttpResponseParserTest`、`HttpSecretEnvelopeTest`、`HttpTemplateEngineTest`、`PinnedDnsResolverTest`、`HttpUploadGatewayTest`、`StorageTargetResolverSnapshotTest`、`StorageFolderServiceTest`、`StorageFileServiceTest`、`StorageConfigServiceRedactionTest`、`StorageTicketTargetServiceTest`（新增）、`UploadOperationServiceTest`、`UploadQuotaServiceTest`、`UploadAdmissionControlTest`、`UploadImageValidatorTest`、`FileManagerServiceTest`、`StorageBatchServiceTest`、`StorageUserControllerTest`、`StorageWorkerAuthInterceptorTest`（新增）。逐项见 §9。

### 1.4 判据

- 全部管理端接口均声明 `admin:storage:*` 权限，App/通用端声明 `all:storage:*`/`user:storage:*`；未发现控制器手写权限判断绕过 `@RequiresPermission`。
- `ApprovedTargetPolicy` 仍是唯一出站目标事实源；管理接口只读，不能扩大清单。
- 上传正文硬上限 5 MiB（`StorageProperties.UploadOperation.maxContentBytes=5242880`），旧 1 MiB 硬帽不在当前契约中。
- `V20261106003__storage_folder_target_binding.sql` 已删除 `target_mode/storage_group_id/group_id/group_version` 旧三态列；当前模型是 `folder_id + folder_version + route_snapshot_json`，报告不把旧 group 列缺失当缺陷。

---

## 2. SSRF / DNS / TLS 诊断（ST-SEC-01～03）

### 2.1 基线缺陷

- `HttpUploadGateway` 用一次 DNS 解析校验目标是否公网 IPv4，然后交给 `java.net.http.HttpClient` 以原主机名发送；HttpClient 内部再次解析，两次解析之间可被 DNS rebinding 替换。
- `HttpRequest.timeout(...)` 在 Java HttpClient 中只约束等待响应头/读取的空闲时间，不是整次 attempt 的绝对上限；恶意上游持续滴流即可拖长。
- `SocketTimeoutException` 统一映射为 `ATTEMPT_TIMEOUT`，无法区分「连接阶段确定未发出」与「可能已发出、结果未知」，错误归因可能导致 UNKNOWN 被当作 NOT_SENT 重试。

### 2.2 修复

1. **PinnedDnsResolver**（新增）：
   - 构造参数 `expectedHost` + `InetAddress[]`；拒绝空数组、非公网 IPv4、IPv6、任意/回环/链路本地/私网/保留段。
   - `resolve(host)` 仅当规范化（trim、去尾点、小写）后与 expectedHost 相同才返回克隆数组，其他主机抛 `UnknownHostException`；不发起第二次 DNS。
   - `resolveCanonicalHostname` 原样返回 expectedHost，不做 reverse DNS。
   - `resolvePublicIpv4(host)` 只执行一次 `InetAddress.getAllByName` 并校验。
2. **HttpClient5 连接绑定**：`PoolingHttpClientConnectionManagerBuilder.setDnsResolver(PinnedDnsResolver)` 让 `Socket.connect` 使用已校验 IPv4；URI 保持原 `https://主机/路径`，SNI/主机名/证书校验仍按原主机执行；不使用 IP 字面量 URL、不 TrustAll、不关闭证书校验。
3. **请求约束**：关闭 redirect、transport retry、cookie、content compression、protocol upgrade；`RequestConfig` 的 connect/connection-request 取 `min(5000, attemptMs)`，responseTimeout 仅作为读空闲；`attemptMs` 夹取 `[1000,60000]`；每个请求只 `execute` 一次。
4. **绝对总截止**：独立 daemon `DEADLINE_SCHEDULER` 在 `attemptMs` 到点调用 `HttpUriRequestBase.cancel()`；`finally` 取消 `ScheduledFuture`、关闭 client；`hardCancellationEnabled(true)`。响应体 `readNBytes(MAX_RESPONSE_BYTES)` 有界。
5. **错误归因**：
   - `ConnectTimeoutException` / `HttpHostConnectException` / `NoRouteToHostException` / `ConnectException`：仅在确认连接阶段且未到总截止时 `NOT_SENT`；到总截止则为 `UNKNOWN/ATTEMPT_TIMEOUT`。
   - 读/发送阶段 `SocketTimeoutException`、`InterruptedIOException`、总截止：`UNKNOWN`，绝不 `NOT_SENT`。
   - `SSLHandshakeException`/`SSLException`：TLS 类 `NOT_SENT`（除非总截止命中）；不弱化 TLS。
   - 其他 IO 且 cause 为 TLS：TLS 类；否则 `UNKNOWN/IO_ERROR`。
6. **无重发保证**：`send()` 每个请求只执行一次；`UploadOperationService` 仅在 `REMOTE_CREATED` 前、`NOT_SENT` 的候选上换目标；`SENDING -> UNKNOWN_OUTCOME` 后恢复任务不重发（`UploadOperationRecoveryJob` 只做状态收敛/幂等 COMMITTED 补登记）。

### 2.3 回归证据

- `HttpUploadGatewayTest.sendBindsValidatedPublicIpv4ToActualSocketConnectAndResolvesHostOnlyOnce`：用真实 `PoolingHttpClientConnectionManager` 注入 `LayeredConnectionSocketFactory`，捕获 `Socket.connect` 的 `remoteAddress` 与 `host.getHostName`；断言 resolver 仅 1 次、connect 仅 1 次、连接地址为校验过的公网 IPv4、请求主机仍是原主机名；SocketFactory 在 connect 前抛 `ConnectException`，不访问公网。
- `HttpUploadGatewayTest.sendRejectsNonPublicResolutionBeforeBuildingAnyClient`：解析到 127.0.0.1 时 client factory 零调用。
- `PinnedDnsResolverTest`：公网/私网/IPv6/保留段、主机名规范化、单次解析、canonical hostname 不反查。
- 未做真实 TLS 握手测试；SNI/证书行为由「URI 原主机 + 默认 SSL socket factory」API 语义保证，属静态/框架语义证据，不等同真实网络验证。

---

## 3. 上传状态机 / 配额（ST-STATE-01～09、ST-QUOTA-01）

### 3.1 缺陷与修复

- **ST-STATE-01 两步提交**：`sendContent` 收到 `REMOTE_CREATED` 后，先 `persistRemoteSucceeded` 在独立事务把 SENDING→REMOTE_SUCCEEDED 并保存 `remoteUrl`；再由 `commitRemoteSucceeded` 在另一事务登记文件并 CAS COMMITTED。若进程在两步间崩溃，恢复任务从 REMOTE_SUCCEEDED 幂等补登记，不会永久停在 SENDING，也不会重复上传。
- **ST-STATE-02 配额死锁**：`reserve` 先按 `usage()` 统计本操作已 HELD 的额度，`dailyLimit=1` 时第二次进入必然 `used>=limit`。修复：同一操作同 day 的 HELD 直接复用；跨日 HELD 先 CAS 置 REFUNDED，再统计并预占。
- **ST-STATE-03 actor 命名空间**：`usage()` 只按 `actorId` 统计，PLUGIN 与 USER 同 id 会互相占额；修复为 `.eq(actorType, ACTOR_USER)`。
- **ST-STATE-04 空值/停用目录**：`claimForReceiving` 的 `sendDeadline` 为空直接 NPE（旧数据/恢复路径）；`folder == null` 与 `status != ENABLED` 先解引用后判空。修复为前置判空 + `STORAGE016/025`；`create` 也补目录缺失/停用检查。
- **ST-STATE-05 快照复核**：`claimForReceiving` 与 `resolveNextCandidate` 在读取意图快照后，仍以当前目录/当前 config/当前授权复核：目录 id/version 精确匹配、目录 enabled、候选 config 仍 enabled 且 version 匹配、操作者当前仍具 UPLOAD 授权（或 unrestricted grant）；不通过 fail closed，不临时 resolve 新候选。
- **ST-STATE-06 v2 快照**：`StorageTargetResolver.parseSnapshot` 严格校验 `v=2`、folderId/folderVersion、strategy/source、tiers 1..32、priority 0..10000（修正旧版把 0 当非法导致持久化/回放自相矛盾）、candidates 1..128、configId>0、configVersion>=0、provider 可识别、重复 id 拒绝；损坏/超长/版本不符 `STORAGE047`。`resolveForUpload` 对 unrestricted grant（零配置行）保留完整目录解析，否则按授权子集过滤且不接受 live 新候选。
- **ST-STATE-07 目录删除锁**：`StorageFolderService.delete` 固定先 `storage-folder-tree` 再 `storage-folder-security:{id}`，与 `update` 的「树锁→安全锁」一致；此前只取安全锁，与文件登记/权限保存并发可能把文件挂到已删目录（`sys_storage_file.folder_id` 无 FK）。
- **ST-STATE-08 停用目录直传票据**：`StorageTicketTargetService.resolveUploadConfig` 此前只校验渠道 enabled，`resolveForUpload` 的目录检查也只判 null。目录停用后仍能签发预签名 PUT，浏览器可把对象传入桶/存储，但 `registerFromBrowser` 必然以 `STORAGE025` 拒绝登记，形成孤儿对象且给出错误成功预期。修复为解析前校验 `folder != null && status == ENABLED`，统一返回 `STORAGE025` 且不再解析候选。
- **ST-STATE-09 故障转移次数**：`resolveNextCandidate` 的 `attempts` 只统计 `attemptsJson` 中已写回的历史渠道，当前正在失败的渠道尚未计入；第二次失败时仍满足 `attempts >= 2` 的旧判断，可再切到第三个渠道。修复为 `attempts + 1 >= MAX_ATTEMPTS`，保证含首次发送总次数不超过 2；`attemptsJson` 仍只记录历史失败渠道。
- **ST-QUOTA-01**：跨日 HELD 退回、同日 HELD 复用，见 ST-STATE-02。

### 3.2 GROUP 迁移结论（重要）

- `V20261106003__storage_folder_target_binding.sql` 明确下线 `target_mode/storage_group_id/group_id/group_version`；`sys_storage_upload_operation` 只保留 `folder_id/folder_version/route_snapshot_json`。
- 因此「旧 group 列缺失」是迁移后的正确状态，不是缺陷。真正缺口在 `StorageTargetResolver`：基线对 `route_snapshot_json` 解析不严格、候选顺序/priority/version 可能回退到实时 resolve；现已按上面的 v2 契约修正。
- `TargetSelectionCursor` 和 `StorageTicketTargetService` 只读当前 config 首候选，不参与两步上传快照；直接上传票据没有 `configVersion/folderVersion`，属 §8 残余。

### 3.3 回归证据

- `UploadOperationServiceTest`：创建/准入/发送/结果持久化/换目标/恢复；新增配额重试与 actor 过滤断言；`failoverStopsAfterMaxAttemptsIncludingFirstSend` 证明首次失败可切第二候选、第二次失败不再切第三候选（总发送上限 2）。
- `UploadQuotaServiceTest`：同日 HELD 复用、跨日退回、USER/PLUGIN 隔离、`dailyLimit` 边界。
- `StorageTargetResolverSnapshotTest`：v2 正常/损坏/版本不符/priority 0/候选上限/重复 id/空候选。
- `StorageFolderServiceTest.deleteTakesTreeAndSecurityLocksInOrderBeforeReadingFolder`：`InOrder` 断言 tree lock → security lock → selectById → delete。
- `StorageFileServiceTest.verificationFailureUsesAvailableCasInsteadOfWholeRowUpdate`：断言 `mapper.update(null, LambdaUpdateWrapper)` 一次、`updateById` 零次、`taskMapper.insert` 零次（CUSTOM_HTTP 无远端删除任务）。

---

## 4. 目录组授权 / 凭据边界（ST-SEC-04、ST-SEC-05）

### 4.1 目录组授权

- `StorageFolderService.savePermissions`：只允许 USER 主体；重复 subject 拒绝；权限子集校验 `requireSubset`；`targetVersion+1` 使所有旧上传意图立即失效；`rows==null` 明确视为「清空」并 bump 版本，不再静默返回。
- `StorageTargetResolver.requireCurrentCandidate`：每次发送前验证目录 id/version、目录状态、config 存在且 enabled、当前操作者授权/候选资格；非 owner 且无 UPLOAD grant 时 `STORAGE013`；unrestricted grant 允许全部当前候选；有界 grant 只允许其配置子集。快照不绕过撤权/停用。
- `StorageFolderService.delete`：删除前拒绝子目录/非 DELETED 文件；删除 grant/permission/folder binding/folder；审计。
- PLUGIN 授权：`StorageFolderPermissionEntity` 明确拒绝非 USER 主体（`COMMON002`），无消费链；`StorageTicketTargetService` 不读取 PLUGIN 授权。App 兼容契约保持：App 端只声明 `all:storage:*`，不引入管理端权限。

### 4.2 凭据边界

- `StorageConfigService.safeForOutput`：CUSTOM_HTTP 只返回脱敏副本，不包含 `provider_options`；其他 Provider 的顶层敏感键统一 `******`。
- `redactSecret` 修复：
  - 解析失败不再回退原始 JSON，而是清空 `provider_options`（fail closed）。
  - 递归/逐层扫描所有扩展键；敏感判定包含 `secret`/`credential`、`password`、`token` 结尾、`tokenKey`；`secretId` 等以 `id` 结尾的公开标识保留可见。
  - 编辑回传 `******` 时沿用存量值，不把掩码当真实凭据落库。
- `HttpSecretEnvelope`：`Credential.toString()` 不输出值；`SecretView` 只含 `configured/updatedAt`；绑定 channelId/origin/path 不能由客户端改写；KEEP/SET/CLEAR 变更判别；单值/数量/名称严格上限。
- `StorageUserController`：详情接口在读 userId 前先拒绝 DELETED，避免已删文件元数据通过 App 预览。
- 管理端 `AdminStorageController.changeVisibility` 返回前置空 `providerLocatorJson`；文件分页 DTO 不返回 locator；`StorageFileService` 的 `page` locator null。

---

## 5. 删除预览与删除链路（ST-UI-01、ST-FILE-01/02）

- **无 dry-run/preview 契约**：全仓库 `com.astral.storage` 没有 `preview`/`dry-run`/删除影响面接口；`SPEC.md` 也未要求。当前 `requestDelete` 语义是「立即删行 + 远端异步尽力清理」，不提供撤销。按 Lead「不新增无授权 feature」，本轮不新增预览端点，记录为观察项。
- **ST-FILE-01**：`deleteRowOrConflict` 以 `deleteById` 影响行数 CAS，两个并发删除只有一个成功；TELEGRAM 任务在事务提交后仍独立存在（`sys_storage_task.file_id` 无 FK），Worker ack 不依赖文件行。
- **ST-FILE-02**：`StorageRemoteDeleteService.deleteObject` 对未知 Provider 由静默返回改为抛 `IllegalStateException`，避免记录「删除成功」而远端对象仍在。
- `requestDelete` 的 `providerTypeOf(file)` 仍对 null 回退 TELEGRAM；`sys_storage_file.provider_type` 为 NOT NULL default TELEGRAM，属历史数据防御，报告列为残余观察项。

---

## 6. HTTP 渠道 / MIME / 图片校验（ST-MIME-01/02、ST-DIR-01）

- **ST-MIME-01**：`UploadOperationService.normalizeMime` 只接受 RFC 9110 `token/token`（长度 1..255），拒绝 CR/LF/引号/空格；`HttpUploadGateway` 用该值构造 `Content-Type`，阻断头注入与 multipart 破坏。
- **ST-MIME-02**：`UploadOperationService.validateContent` 对 magic 策略执行与注册后验证同级门槛：拒绝声明 `image/svg*` 或内容 `looksLikeSvg`；声明图片但实际 magic 非 JPEG/PNG 拒绝；声明 MIME 与实际不符拒绝。避免把任意文本推到上游图床。
- **ST-DIR-01**：`UploadImageValidator.validate(content, declaredMime, maxPixels)` 支持目录策略像素上限；取 `min(policyMaxPixels, MAX_PIXELS)`，策略只能收紧，不能放宽全局 4096 边/1M 像素限制。
- `HttpProfileValidator` 仍禁止 `Host/Content-Length/Transfer-Encoding/Connection/...` 与 `proxy-*`/`x-app-*`/`sec-*`/`x-forwarded-*` 头；`HttpResponseParser` 的凭据反射检测覆盖 URL/thumb/remoteId 与原始 body。
- `HttpTemplateEngine.buildUrl` 只使用 literal `profile.endpoint()`，不把 endpoint 当模板，避免把用户输入拼入 host/path。

---

## 7. 逐文件证据索引（关键行/方法）

| 证据 ID | 文件 | 方法/位置 | 结论 |
|---|---|---|---|
| E-01 | `http/PinnedDnsResolver.java` | 构造器、`resolve`、`resolveCanonicalHostname`、`resolvePublicIpv4` | 单次解析、公网 IPv4、返回原主机 |
| E-02 | `service/HttpUploadGateway.java` | `send()`、`attemptMs`、`DEADLINE_SCHEDULER`、`classify` | 连接绑定、总截止、错误归因、单次发送 |
| E-03 | `service/HttpUploadGateway.java` | `loadChannel`、`Channel` record | 信封 binding 与 channel 一致、origin/path 精确校验 |
| E-04 | `service/UploadOperationService.java` | `create`/`claimForReceiving`/`sendContent`/`persistRemoteSucceeded`/`commitRemoteSucceeded`/`switchToNextTarget`/`resolveNextCandidate` | 状态机两阶段、快照复核、UNKNOWN 不重发 |
| E-05 | `service/UploadQuotaService.java` | `usage`/`reserve` | actorType、HELD 复用/跨日退回 |
| E-06 | `service/StorageTargetResolver.java` | `parseSnapshot`/`resolveForUpload`/`requireCurrentCandidate` | v2 严格快照、授权子集、发送前复核 |
| E-07 | `service/StorageFolderService.java` | `delete`/`savePermissions`/`normalizeBindings`/`replaceBindings` | 锁顺序、授权边界、版本 bump |
| E-08 | `service/StorageFileService.java` | `verifyContentAfterRegistration`/`markVerificationFailed`/`requestDelete`/`changeVisibility` | CAS、删除、locator、内容验证 |
| E-09 | `service/StorageConfigService.java` | `safeForOutput`/`redactSecret`/`isSensitiveOption` | 脱敏 fail closed、扩展键 |
| E-10 | `http/HttpSecretEnvelope.java` | `parse`/`write`/`requireValid`/`SecretView` | 凭据信封边界 |
| E-11 | `http/HttpResponseParser.java` | `parse`/`reflectsCredentialMaterial`/`originAllowed` | 响应解析与凭据反射 |
| E-12 | `http/HttpProfileValidator.java` | 构造器、`validate`、`FORBIDDEN_HEADERS` | 画像与头注入防护 |
| E-13 | `service/UploadImageValidator.java` | `validate(..., maxPixels)` | 像素上限可收紧 |
| E-14 | `service/UploadOperationRecoveryJob.java` | `recoverReceiving`/`recoverSending`/`recoverRemoteSucceeded` | 恢复不重发、CAS |
| E-15 | `security/StorageWorkerAuthInterceptor.java` | `preHandle` 时间窗口 | 溢出安全、Nonce 先验签后写 |
| E-16 | `controller/StorageWorkerController.java` | `originFile` | 仅 Telegram 回源 |
| E-17 | `service/StorageRemoteDeleteService.java` | `deleteObject` | 未知 Provider fail closed |
| E-18 | `V20261106003__storage_folder_target_binding.sql` | `ALTER TABLE ... DROP COLUMN` | 旧 group 三态列已下线，当前模型一致 |

---

## 8. 未修复 / 环境依赖残余（已定级，不冒充已修）

1. **Provider HEAD/DELETE 无请求级超时**：`S3ObjectService`、`CosApiService`、`OssApiService`、`UpyunApiService` 的 Java `HttpClient` 只有 5s connect timeout，没有 per-request timeout；Provider 停止响应时注册/异步删除线程可长期挂起。建议统一设置 request timeout（需跨 Provider 回归，Lead 可决定是否本轮修）。
2. **直接上传票据无 config/folder 版本**：`UploadTicketService.TicketPayload` 缺 `configVersion/folderVersion`；票据有效期内配置/权限变更后，`registerFromBrowser` 以当前 config HEAD 失败关闭但可能留下已上传对象成为孤儿（可后续用孤儿清理/票据版本字段）。
3. **`StorageTicketTargetService` 只取 first candidate**：不校验 folder/config 版本快照；与两步上传不同，属旧直传路径残余。
4. **Worker 回源/ack 的全局 HMAC 身份**：Worker 可 ack 任意 taskId（服务级共享密钥模型），无租户隔离；部署侧可信 Worker 假设下接受。
5. **CUSTOM_HTTP 无远端删除接口**：验证失败/删除只删库行，上游对象可能残留（设计限制，已在文档写明）。
6. **`StorageFolderService.delete` 与文件登记并发**：已加树锁，但文件登记若不走树锁仍可能产生孤儿（`folder_id` 无 FK）；需要把文件登记也纳入同一锁键或外键，属跨服务改动。
7. **`StorageConfigService.test` 的 workerBaseUrl**：管理端可配置 URL 由 Java HttpClient 请求，理论存在管理员 SSRF；该配置是部署/管理面，需产品确认是否限制到内网/白名单。
8. **目录策略每日配额 check-then-act**：`UploadPolicyService.checkDailyQuota` 在准入后、事务外检查，存在并发窗口；当前由 `UploadAdmissionControl` 的全局/用户并发上限兜底，不适合作为强一致计数。
9. **`ackDeleteTask` 状态竞争**：Worker 重复 ack 由 CAS/状态判断处理；若 ack 与人工删除并发，审计可能重复，不造成数据丢失。
10. **`StorageConfigService.setDefault` 允许 disabled 配置**：默认配置缓存只取 `status=ENABLED`，disabled default 不会被使用，但管理界面可能显示矛盾；属 UX/一致性残余。
11. **`StorageConfigService.redactSecret` 原地修改 mapper 实体**：同一请求内后续读可能看到打码值；目前 `safeForOutput` 返回副本用于出参，但共享缓存/实体复用路径应改为复制后修改（残余）。
12. **`StorageWorkerAuthInterceptor` 时间窗口比较已修**，但 `window` 为负配置时区间反转会导致全部拒绝（安全侧 fail closed），不单独报缺陷。
13. **成功路径不清理历史 `failureCode`**：`UploadOperationService.switchToNextTarget` 会把前一次候选的安全码写入数据库；之后换目标成功并提交时 `commitRemoteSucceeded` 未清空该列，`toView` 仍会带出旧失败码，前端可能显示「成功 + 失败原因」。建议提交事务内 `.set(failureCode, null)` 并补状态机回归。
14. **意图并发预算 check-then-act 竞态**：`enforceIntentBudget` 先 `selectCount` 再 insert，两个并发请求可同时通过每用户 2/全局 64 的门限；没有数据库锁/唯一约束兜底。当前只影响并发意图数量，不影响单次上传安全。
15. **`registerFromCallback`/`registerFromBrowser` 幂等返回不检查 DELETED**：同 uploadId 的已删除行会被当作成功回执原样返回（含元数据）；虽不恢复文件，但会让客户端误判成功。建议 DELETED 返回 `STORAGE021`，并补幂等回归。
16. **`StorageFileService.changeVisibility` 未检查 update 影响行数**：并发删除与改可见性竞争时，更新 0 行仍会继续记审计/返回旧对象；应像 `deleteRowOrConflict` 一样按影响行数 CAS 失败关闭。
17. **DNS 查询本身没有独立超时**：`PinnedDnsResolver.resolvePublicIpv4` 的 `InetAddress.getAllByName` 在连接前同步执行，`attemptMs` 截止任务尚未建立，DNS 卡住时无法由请求级超时/取消兜底；建议用有界 DNS 解析器或独立 executor + 超时。
18. **Probe 错误摘要只替换原始凭据字面量**：`upstreamReason` 对上游错误体做 140 字截断并替换原始 secret，但未覆盖 URL 编码/Base64 变形；受限管理端可见，建议复用 `HttpResponseParser` 的反射检测或不做错误体透传。

---

## 9. 聚焦回归命令（Lead 串行执行）

在 `F:/qtMusic/astral`，JDK 25：

```text
mvn -pl astral-plugin -Dtest=PinnedDnsResolverTest,HttpUploadGatewayTest,StorageTargetResolverSnapshotTest -DfailIfNoTests=false test
mvn -pl astral-plugin -Dtest=UploadOperationServiceTest,UploadQuotaServiceTest,UploadAdmissionControlTest,UploadImageValidatorTest,StorageTicketTargetServiceTest -DfailIfNoTests=false test
mvn -pl astral-plugin -Dtest=StorageFolderServiceTest,StorageFileServiceTest,StorageConfigServiceRedactionTest,StorageWorkerAuthInterceptorTest,StorageUserControllerTest,FileManagerServiceTest,StorageBatchServiceTest -DfailIfNoTests=false test
```

`UploadOperationRecoveryJobTest` 不存在，恢复任务由 `UploadOperationServiceTest` 的 `failoverStopsAfterMaxAttemptsIncludingFirstSend` 及现有状态机用例覆盖；所有测试只使用 Mockito/内存替身，不连数据库/外网。

**尚未执行**：本地无 Maven 运行授权，以上命令待 Lead 统一执行并回报结果；本报告不声称测试已通过。

---

## 10. 交付结论

- 本域 69 个主代码文件、9 个 schema JSON、19 个测试类已完成覆盖清点；已确认并修复 22 项缺陷（含 3 项严重：SSRF、凭据边界、不可恢复状态机；12 项高严重度：超时归因、快照复核、锁/直传票据/内容校验等）。
- 关键安全边界保持不放宽：HTTPS-only 批准目标、公网 IPv4-only、DNS 单次解析并绑定真实连接、原主机名 SNI/证书、无 redirect/retry、5 MiB 正文上限、UNKNOWN_OUTCOME 不重发。
- GROUP 旧三态列已由 `V20261106003` 正式下线，报告明确排除「旧 group 列缺失」假设；真正修复的是 v2 `route_snapshot_json` 事实源与发送前当前授权复核。
- 删除预览：源代码无该契约，本轮按不新增 feature 处理并记录；删除链路已做并发 CAS、未知 Provider fail closed、Telegram 任务解耦。
- 残余项见 §8，不把未验证/未修复项描述为已解决。
