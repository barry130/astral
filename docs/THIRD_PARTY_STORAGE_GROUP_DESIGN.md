# 第三方图床渠道与存储组详细设计

| 项目 | 内容 |
|---|---|
| 文档版本 | **v1.1** |
| 修订日期 | 2026-11-05 |
| 状态 | 详细设计修订稿；尚未实施、迁移或部署 |
| 代码基线 | `09c9510ee599f91bd360a82cdedd081afa2cb1d3` |
| 新能力 | `CUSTOM_HTTP`、公共图片代传、渠道参数管理、存储组、上传操作状态 |
| 首批预设 | ImgBB、freeimage.host |
| 实施影响 | `astral-plugin`、`astral-server`、`astral-front`、`astral-schema`；错误资源及日志集成按本文清单同步 |

**阅读约定：**“必须”是实现和验收要求，不表示现有代码已经具备。本文的 SQL、JSON、接口和流程是设计契约，不是已执行的迁移或已编译代码。容量参数是初始保护值，不是生产性能保证。

历史依据：[v1.0 原样快照](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_DESIGN_v1.0.md>)、[针对 v1.0 的双轴评审](<F:/qtMusic/astral/docs/THIRD_PARTY_STORAGE_GROUP_REVIEW.md>)。快照 blob 为 `0df8e9fecc07a403a10a5d9aad5d47c40eef484e`；历史评审引用该快照，不能用修订后的行号反向解释旧发现。

## 1. 目标、范围与已经选定的方案

### 1.1 目标

1. 由服务端保管第三方凭据，服务器直接调用第三方上传 API，不引入 Cloudflare 中转。
2. 把请求头、认证、Query、表单字段、文件字段、响应路径等差异参数化；同一厂商的多个账号可配置为独立渠道。
3. 将渠道组成组，由组产生候选顺序；文件始终记录**实际上传成功的渠道**，读取旧文件不重新选渠道。
4. 在低配单实例上限制接收正文、出站请求及上游响应的资源占用；准确处理“远端结果未知”，不通过盲目重传掩盖失败。
5. 保留已有七类存储和已发布 Qt 客户端的直传契约。

### 1.2 本版决策表

| 决策 | 选定方案 | 原因及代价 |
|---|---|---|
| 协议范围 | HTTPS POST、multipart 文件、JSON 响应、静态凭据引用 | 可验证的小协议集合；不是万能 HTTP/脚本执行器 |
| 上传者 | 首发要求已登录主体，目录 UPLOAD 授权；不开放匿名代传 | 凭据与带宽是服务端资源；目录允许匿名不等于新能力必须匿名 |
| 客户端协议 | 先 POST 小型 JSON 创建操作，再 PUT 原始文件正文，GET 查询 | 增加一次 RTT，换取上传前稳定身份、幂等去重和可查询状态 |
| 配置 DTO | 新增独立、严格类型的 HTTP 渠道 DTO/API；旧 Map<String,String> 保留 | 不把新对象/数组塞进旧 DTO，不扩大旧七家入参权限 |
| 图床能力 | 首发 PUBLIC_ONLY、无已验证远端删除/查询/幂等能力 | 私有资产继续走原存储；不虚构上游能力 |
| 组策略 | FAILOVER 与 ROUND_ROBIN；统一优先级分层 | 权重、最少使用、健康优先独立策略延后，不留“可配但不起效”的字段 |
| 失败转移 | 只在能证明未产生远端对象时继续，最多两候选 | 结果未知立即停止；可用性不优先于避免重复副本 |
| 配额记账 | 一张上传操作表承担新上传预留/消费；旧登记接入同一短事务锁 | 不新增配额计数表，不把新文件和对应操作重复计数 |
| HTTP 客户端 | 新增隔离的 Apache HttpClient 5 执行组件，连接器使用受控 DNS 结果 | 不沿用“预查 DNS + JDK 客户端自行再解析”的漏洞窗口 |
| IP/连接范围 | 首发只连接获批目标的公共 IPv4；不回退 IPv6、不复用连接 | 范围更窄、额外 TLS 开销；优先保证连接目的地可证明 |
| 目录目标 | CONFIG / GROUP / INHERIT 三态；旧目录全部 CONFIG | 全局默认组不会覆盖已有显式绑定 |
| 索引元数据 | 扩展受控 predicate 元数据及 PostgreSQL 生成器 | 修正新组及旧配置 JSON 的部分索引表达；不修改正确的旧数据库索引 |
| 本地文件 | 应用不持久化上传正文，不引入消息队列 | 崩溃后不能自动续传正文；有可靠回执时仅恢复本地登记 |

### 1.3 非目标与边界

- 不替换现有 TELEGRAM / R2 / S3_COMPATIBLE / QINIU / COS / OSS / UPYUN 直传链。
- 不在本期为已发布 Qt 头像/封面客户端更换上传协议；仅补显式能力检查以避免空引用和误路由。
- 不做跨实例共享并发、多阶段上传、分片、断点续传、OAuth 自动刷新、动态 HMAC 签名、任意脚本、非 JSON 响应。
- 不把“本次未验证删除 API”写成“平台永远不提供删除”；本期适配器只声明自己已实现的能力。
- 公共图床不承诺永久保存、归档可靠性或可撤销访问；本地删除不会自动移除第三方副本。
- 本版不重新进行真实上传、删除探测、生产数据库检查或性能压测。

## 2. 现状与总体架构

### 2.1 仓库事实

现有接口先签发 ticket，浏览器把正文上传到上游，再回执登记。新能力是对“不经 Astral 传正文”的**限定例外**；实施时同步修改 [API 说明](<F:/qtMusic/astral/API.md>) 和 [存储需求](<F:/qtMusic/astral/STORAGE_REQUIREMENTS.md>)，不把旧家族也改为代传。

- [StorageDtos](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/dto/StorageDtos.java#L16-L21>) 的 providerOptions 仍为字符串 Map；新家族需要新类型链。
- [ProviderSupport](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/ProviderSupport.java>) 只是家族判断，不是可自动接通全部调用的 SPI。
- [上传入口](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/controller/StorageUserController.java#L86-L105>) 显式检查目录权限，并读取目录绑定的配置。
- [配额实现](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/UploadPolicyService.java#L183-L210>) 当前只查文件行，没有原子预留。
- [默认索引的运行迁移](<F:/qtMusic/astral/astral-server/src/main/resources/db/migration/V20260914001__init.sql#L1559-L1560>) 已是 `WHERE is_default=1`；不能据旧稿擅自“修复”数据库索引。
- 运行环境采用 Java 25 / Spring Boot 4.1、Jackson 3 `tools.jackson.*`、PostgreSQL、MyBatis-Plus；前端使用共享 axios、npm、shadcn/ui。

### 2.2 三个配置层与一个执行器

```text
版本化 Profile：如何构造请求、解析响应、声明实际支持能力
        ↓ 固化为渠道的有效配置，不在升级时隐式改变
Channel：获批端点 + 账号凭据 + 限额 + 配置版本
        ↓ 组只引用已有且获批的渠道
Group：成员 + 优先级 + 选择策略 + 安全尝试上限
        ↓
UploadOperationService：授权、准入、校验、记账、执行、结果与恢复
```

- Profile 首发为内置版本化预设和经过严格校验的自定义 JSON，不另建模板市场/脚本平台。
- 一个渠道仍占用一条 `sys_storage_config`，`provider_type=CUSTOM_HTTP`。
- 组成员首发只允许 CUSTOM_HTTP；混合直传和代传意味着不同客户端协议，不以一次组选择偷偷切换协议。
- `HttpUploadExecutor` 不负责用户权限或组算法；只执行已经授权、已固定目标的单次调用，并返回明确的结果分类。
- 缓存中的执行配置是服务端不可变对象；读接口单独组装安全 View，不通过修改缓存对象来脱敏。

## 3. 参数模型与严格校验

### 3.1 Profile 结构

配置协议的 `schemaVersion=1` 与本文版本 v1.1 是两个概念。旧稿未实施，不存在需要迁移的已上线 CUSTOM_HTTP schema v1。

| 字段 | 类型/限制 | 语义 |
|---|---|---|
| schemaVersion | 整数，目前只能 1 | 不认识的版本直接拒绝 |
| presetId / presetVersion | 可空字符串 / 正整数 | 成对出现，表示展开来源；执行以固化后的 profile 为准 |
| endpoint | HTTPS URL，≤2048 字符 | 仅 origin + 路径，不含 Query/userinfo/fragment；不能模板化 host/port/path |
| method | POST 常量 | 其他方法不接受 |
| query | Map<String,Template>，≤16 项 | 独立 Query 参数，统一编码一次 |
| headers | Map<String,Template>，≤16 项 | 按大小写不敏感键去重，不能改路由/连接控制头 |
| auth | 下述判别联合 | NONE / HEADER / QUERY / FORM / BASIC |
| body.mode | MULTIPART 常量 | 正文由程序构造 boundary，不允许调用者传完整 multipart |
| body.fileField | 1–64 字符 | 文件字段名，首发采用安全 ASCII 字母/数字/下划线/短横线 |
| body.fileNameTemplate | 固定允许的模板 | 默认 `${publicId}.${extension}`，不向上游暴露原始本地路径 |
| body.fields | Map<String,Template>，≤16 项 | 普通表单字段；不得与文件字段或 auth FORM 字段冲突 |
| response.success | 1–8 条条件，全部满足 | 每条由 JSON Pointer、EQ/EXISTS、类型化预期值组成 |
| response.urlPointer | JSON Pointer，必填 | 必须提取一个非空字符串直链 |
| response.thumbPointer / idPointer | 可空 JSON Pointer | 缩略图、上游 ID；不采集删除令牌 |
| response.sizePointer / sizeUnit | 可空 Pointer / BYTES、KB、KIB | 上游报告大小，不覆盖实际上传字节数 |
| response.widthPointer / heightPointer | 可空 Pointer | 辅助元数据，不替代本地验证 |
| response.numericStringPointers | ≤8 个 Pointer | 只对明确列出的非成功条件数值字段允许数字字符串 |
| response.allowedUrlOrigins | ≤8 个精确 HTTPS origin | 必须是部署侧批准集合的子集 |
| limits.maxBytes | 1–5242880，默认5242880 | 只能收紧应用硬上限 |
| limits.allowedMimes | MIME 精确集合 | 首发公共图片支持集的子集，不接受 `image/*` |
| timeouts.attemptMs | 1000–60000 | 单次上游尝试预算，还受总剩余时间限制 |

Profile 序列化后上限 16 KiB；凭据另算且总量有界。Map 键最长64、普通模板最长1024、各区渲染后文本合计最多8 KiB。未知字段、重复 JSON 键、嵌套超限、非法类型均拒绝，不静默忽略拼错的安全设置。JSON 解析器开启严格重复键检测和深度/字符串/数字长度限制。

### 3.2 认证模型

| mode | 必需字段 | 构造行为 |
|---|---|---|
| NONE | 无 | 仅用于确实不需凭据且获批的上游；本期不开放匿名调用者 |
| HEADER | name、valueTemplate | 例如 Authorization 的 Bearer 值或 X-Api-Key |
| QUERY | name、valueTemplate | 只由请求构造器添加到 Query；禁止请求完整 URL 日志 |
| FORM | name、valueTemplate | 添加普通 multipart 字段 |
| BASIC | usernameSecret、passwordSecret | 执行器按协议生成 Basic 头，不允许配置任意变换脚本 |

认证模板通常引用 `${secret:apiKey}`。需要多份静态凭据时，额外头/字段也可使用命名 secret 引用，但同名参数只能由一个来源定义。

- HEADER auth 与 headers 大小写不敏感同名冲突时拒绝；QUERY/FORM auth 与对应字段同名同样拒绝。
- BASIC 占用 Authorization，不能再配置另一份 Authorization。
- 禁止 headers 覆盖 Host、Content-Length、Transfer-Encoding、Connection、Trailer、Expect、Proxy-*、Accept-Encoding、Content-Type，以及向上游转发 Astral 的 satoken、Cookie、X-App-* 等身份头。
- Accept 默认为 application/json；User-Agent 由服务端固定为公开产品标识，不包含账号或设备信息。
- 禁用外部 cookie jar，不继承浏览器 Cookie 或服务端环境代理凭据。

### 3.3 模板、编码及文件名

只支持以下变量，不执行表达式、不读环境变量、不支持循环或任意函数：

| 变量 | 来源 |
|---|---|
| `${secret:name}` | 当前渠道获批绑定的命名凭据 |
| `${uploadId}` / `${publicId}` | 创建操作时生成并持久化的稳定 ID |
| `${fileName}` | 已规范化的用户文件显示名；使用者须认识其可控性 |
| `${extension}` / `${mimeType}` | 服务端本地验证后的 MIME 映射，不信任原扩展名 |
| `${sizeBytes}` | 实际接收且与元数据匹配的大小 |

与既有 HTTP 工具类似的语法不等于直接复用其私有实现。存储建立受限模板编译器；不引入另一套 `${var:...}` 别名。创建配置时编译模板和检查变量，发请求前再验证渲染结果。

- Query 通过 URI builder 对键和值各编码一次；禁止拼接 `?key=` 后再整体 URL 编码。
- Header 渲染结果拒绝 CR/LF/NUL 和控制字符；不能以转义控制字符代替拒绝。
- multipart 字段按安全名称生成 Content-Disposition；文件名首发使用 publicId + 已验证扩展名，仅 ASCII，不采纳路径分隔符或引号。
- 普通 form 值是 UTF-8 文本，不做 HTML 转义；长度在编码成字节后复核。
- 同一 key 跨 Query 与 Form 可同时存在（如 format=json）；同一容器内不得重复。

### 3.4 响应解析

1. HTTP 成功状态首发要求 200–299，然后再检查所有 success 条件；HTTP 200 本身不足以判定成功。
2. Pointer 使用 RFC 6901 JSON Pointer，如 `/data/url`、`/image/size`；最多12层、128字符，不支持 JSONPath 通配、脚本或正则。
3. EQ 严格区分字符串、数字、布尔；`true`、`1`、`"true"` 不互相替换。EXISTS 要求存在且非 null。成功条件不做隐式数值字符串转换。
4. JSON 总正文≤256 KiB、深度≤32、单字符串≤16 KiB、数值文本≤32字符；解析与遍历不得无限递归。
5. 上游大小单位换算：KB ×1000，KIB ×1024。使用有界十进制数并检查溢出；换算结果必须为合法非负字节数，否则标元数据无效。绝不按“与本地相差20%”猜单位。
6. `sizeBytes/sha256` 记录本地实际发送的内容；`upstreamReportedSize` 单独记录。上游转码后，不把本地摘要宣称为远端对象摘要。
7. 主 URL 验证失败时为 UNKNOWN_OUTCOME，不把有害 URL 返回给用户，也不自动换渠道。可选缩略图不合法时丢弃该字段并记安全原因，不因此重传。
8. URL 只接受批准的 HTTPS origin、正常路径；拒绝 userinfo、fragment、控制字符。首发禁止 Query，后续确需 CDN 查询参数须由部署策略逐键批准。
9. 以白名单字段生成安全回执；上游错误正文、响应前200字符、删除网页和删除令牌均不进入普通日志/API。
10. 对回传 URL/ID 检查已知凭据及已渲染认证材料的原值/常见编码反射，命中则隔离。该检查只是纵深防护，不能证明不可信上游不会以任意编码泄密；接收凭据的第三方本身必须属于信任边界。

## 4. 渠道配置、密钥与权限

### 4.1 独立类型链，保持旧 Provider 兼容

新增 `HttpConfigCreateReq`、`HttpConfigUpdateReq`、`HttpUploadProfile`、`HttpConfigView` 以及匹配的 TypeScript 类型；不使用前端 any 或无约束 Map 透传作为执行配置。

- 旧 [配置 DTO](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/dto/StorageDtos.java>) 和旧 providerOptions 字符串 Map 保持原契约。
- 新管理接口 `/api/v1/admin/plugin/storage/http-configs` 接收嵌套 profile 及 secretChanges。
- 新服务在严格验证后将内部 envelope 写入原 `provider_options` TEXT；包含 schemaVersion、有效 profile、服务端 credential entries。独立 View 永不序列化 credential entries。
- 普通存储配置列表对 CUSTOM_HTTP 只返回摘要、id/name/type/status/version；不让旧实体直出机制返回其 providerOptions 原文。
- 旧配置 CRUD/test/default 的服务层明确拒绝 CUSTOM_HTTP；禁止新建时从旧路由传新类型，也禁止把旧配置更新成新类型绕过权限。
- CUSTOM_HTTP 渠道永不成为旧 `is_default` 配置，包括“首个配置自动默认”的逻辑。只有旧七类参与该默认机制。

### 4.2 每个 secret 的三态

命名规则 `[A-Za-z][A-Za-z0-9_]{0,31}`，每渠道≤8个、每个≤2048 UTF-8字节；不接受无引用的多余 secret。更新采用显式判别联合：

```ts
type SecretChange =
  | { op: 'KEEP' }
  | { op: 'SET'; value: string }
  | { op: 'CLEAR' }

interface HttpConfigUpdate {
  expectedVersion: number
  profile: HttpUploadProfile
  secretChanges?: Record<string, SecretChange>
}
```

- 缺失该 key 或 KEEP：保留当前值，只能用于已有凭据且目标绑定未变。
- SET：显式替换；空串、纯空白、`******` 一律拒绝。值只允许经受信管理表单/API发送。
- CLEAR：明确清除；若仍为 profile 必需凭据，渠道只能以 DISABLED 状态保存，不能带缺失凭据启用。
- 新建渠道不能通过 KEEP/掩码创建“看似已配置”的账号。
- 读出仅给 `configuredSecrets: [{name, configured, updatedAt}]`，没有查看/复制现有明文按钮。
- profile、secretChanges 和 status 在同一版本校验事务保存；凭据不得在普通导入/导出文件中出现。

本期按现有服务器侧存储模式，将凭据保存在数据库配置字段，数据库/备份管理员属于可信主体；这不是“明文存储天然安全”。限制数据库角色、备份访问和日志，数据库连接采用现有安全部署要求。KMS/列加密可后续引入，不把新的加密子系统作为本期已存在事实，也不把密钥写入仓库、ticket、操作快照或浏览器存储。

### 4.3 目标批准与凭据绑定

部署侧维护只读的目标清单，**配置管理 API 不能扩大清单**。每项精确给出 HTTPS origin、上传路径和允许的输出 URL origins；空清单意味着不能出站。

- 禁止通配域名、IP 字面量、非443端口、userinfo、fragment、动态 host、编码后的路径跳转；统一小写域名、IDNA、默认端口后再精确匹配，拒绝尾点、模糊端口/分隔符写法。
- 初始目标只包含两份已选预设；接入其他目标需要运维明确批准，再由凭据管理员配置参数。
- 每份凭据由服务端绑定 channelId + origin + 上传路径。客户端不得提交/改写绑定证明。
- 改变 origin 或上传路径必须 CLEAR/SET 所有旧凭据并重新核验批准目标，不能 KEEP 搬运；更新后版本递增、禁用旧编译缓存。
- 允许同 origin 不等于允许任意路径接收既有密钥；路径也被约束。普通组运营不能修改这些字段。
- 正在发送的请求不能被配置编辑“收回”；其事实仍登记，后续请求使用新版本。尚未发送且版本不一致的操作拒绝，不偷偷换新账号。

### 4.4 声明式权限与旁路封闭

| 管理操作 | 新权限 |
|---|---|
| HTTP 渠道摘要/安全详情/离线校验 | `admin:storage:channel:view` |
| HTTP 渠道创建、参数/凭据/状态修改、删除 | `admin:storage:channel:edit` |
| 真实渠道上传测试 | `admin:storage:channel:test` |
| 组查看、离线检查 | `admin:storage:group:view` |
| 组/成员/默认设置 | `admin:storage:group:edit` |
| 真实组上传测试 | `admin:storage:group:test` |
| 操作诊断、已知回执的本地补登记触发 | `admin:storage:upload:view` / `admin:storage:upload:recover` |

全部使用 `@RequiresPermission` 标注；权限授予仍走现有超管权限管理，不因普通写操作一律新增 RequiresSuper。现存 `admin:storage:view/edit` 作为旧七家兼容例外保留，本期不全库重命名、不自动把旧编辑者升级成新凭据管理员。

只给组 edit 的运营人员只能选择获批渠道、更改组白名单字段；不能通过旧 configs 路由更改新渠道，也不能借导入、复制、状态、test、批处理端点执行任意端点请求。真实测试不能仅凭 view 触发。权限映射变更沿用 `PermissionCache.bumpVersion()`。

## 5. 数据模型与数据库约束

### 5.1 存量表变更

| 表 | 变更 |
|---|---|
| sys_storage_config | 新增 `config_version BIGINT NOT NULL DEFAULT 1`；新增 provider 字典值；provider_options 仍为 TEXT |
| sys_storage_folder | 新增 `target_mode VARCHAR(16) NOT NULL DEFAULT 'CONFIG'`、`storage_group_id BIGINT NULL`、`target_version BIGINT NOT NULL DEFAULT 1`；storage_config_id 改可空 |
| sys_storage_file | 新增 `upload_operation_id BIGINT NULL` 唯一、`storage_group_id BIGINT NULL`；storage_config_id 与 folder_id 继续非空 |

目录三态必须由 CHECK 和服务层共同约束：

```sql
CHECK (
  (target_mode = 'CONFIG' AND storage_config_id IS NOT NULL AND storage_group_id IS NULL)
  OR (target_mode = 'GROUP' AND storage_config_id IS NULL AND storage_group_id IS NOT NULL)
  OR (target_mode = 'INHERIT' AND storage_config_id IS NULL AND storage_group_id IS NULL)
)
```

存量目录一律 CONFIG，原 configId 原样保留。不新增“两个外键都填时猜谁优先”的隐含规则。切换模式使用白名单 UPDATE 显式 SET 另一个外键为 NULL，并比较 target_version；不能依赖 MyBatis-Plus `updateById` 忽略 null 的行为。

文件的 groupId 仅表示上传来源，不用于下载路由。`upload_operation_id` 与操作一对一，用于登记唯一性和配额排除；历史文件为 NULL，不批量捏造操作。新增文件记录仍填写原 uploader、folder、visibility、status、时间与审计字段。

### 5.2 sys_storage_group

| 字段 | 类型/约束 | 说明 |
|---|---|---|
| id | BIGINT PK，全局序列 | 业务键 sys_storage_group_id |
| group_code | VARCHAR(64) UNIQUE NOT NULL | 稳定业务标识，创建后不可改 |
| group_name | VARCHAR(128) NOT NULL | 显示名 |
| strategy | VARCHAR(32) NOT NULL | FAILOVER / ROUND_ROBIN |
| status | VARCHAR(16) NOT NULL | ENABLED / DISABLED |
| is_default | SMALLINT NOT NULL DEFAULT 0 | 只允许0/1，部分唯一索引保证最多一个1 |
| max_attempts | SMALLINT NOT NULL DEFAULT 2 | 1–2，包括第一次尝试，不是额外重试数 |
| version | BIGINT NOT NULL DEFAULT 1 | 乐观并发控制 |
| remark | VARCHAR(512) NULL | 不允许放秘密 |
| create_time / update_time | TIMESTAMP | 自动填充 |
| create_by / update_by | VARCHAR(64) | 操作主体 |

```sql
CREATE UNIQUE INDEX uk_storage_group_default
ON sys_storage_group (is_default) WHERE is_default = 1;
```

默认组必须 ENABLED 且至少有一个静态可用成员；动态故障不自动改默认指向。禁用默认组必须在同一事务先取消默认或切换默认，不能悄悄回落旧默认配置。

默认切换请求同时提交目标expectedVersion与客户端看到的expectedDefaultGroupId/expectedDefaultVersion（无默认时明确为null）。服务端取得固定的默认组事务咨询锁，核对旧默认快照，白名单更新旧/新组的is_default并递增对应版本，提交后统一失效默认缓存。仅对目标组CAS不足以阻止两个管理员从不同目标组同时覆盖全局默认。

### 5.3 sys_storage_group_member

| 字段 | 类型/约束 | 说明 |
|---|---|---|
| id | BIGINT PK，全局序列 | sys_storage_group_member_id |
| group_id | BIGINT NOT NULL | FK 到组，删除组前先处理成员 |
| storage_config_id | BIGINT NOT NULL | FK 到配置，禁止悬挂成员 |
| priority | INTEGER NOT NULL DEFAULT 100 | 0–10000，数字小的层级先使用 |
| enabled | SMALLINT NOT NULL DEFAULT 1 | 只允许0/1 |
| create_time / update_time | TIMESTAMP | 自动填充 |

唯一 `(group_id, storage_config_id)`；每组最多16个成员。首发没有 weight 字段，ROUND_ROBIN 不是加权轮询。替换成员与组 version 更新在同一事务，提交后再失效缓存。

### 5.4 sys_storage_upload_operation

选择**一张轻量操作表 + 有界尝试 JSON**，不另外建立工作流平台或配额计数表。

| 字段组 | 必需字段与约束 |
|---|---|
| 主键/幂等 | id BIGINT（sys_storage_upload_operation_id）；upload_id VARCHAR(64) UNIQUE；public_id VARCHAR(64) UNIQUE；actor_type VARCHAR(16)、actor_id VARCHAR(64)、idempotency_key_hash CHAR(64)，三者唯一 |
| 请求指纹 | intent_fingerprint CHAR(64)；original_name VARCHAR(255)；expected_mime VARCHAR(64)；expected_size BIGINT；expected_sha256 CHAR(64) |
| 授权目标 | folder_id BIGINT；folder_version BIGINT；target_mode VARCHAR(16)；group_id BIGINT NULL；group_version BIGINT NULL；安全的 route_snapshot_json TEXT≤16KiB |
| 真实选择 | chosen_config_id BIGINT NULL；chosen_config_version BIGINT NULL；chosen_preset_version INTEGER NULL |
| 状态/CAS | state VARCHAR(32)；version BIGINT；lease_token VARCHAR(64) NULL；lease_until TIMESTAMP NULL；send_deadline TIMESTAMP NULL；intent_expires_at TIMESTAMP |
| 配额 | quota_day DATE NULL；quota_charge VARCHAR(16)：NONE / HELD / CONSUMED；quota_exempt SMALLINT；计费目录/用户在准入后不可改 |
| 执行事实 | actual_size BIGINT NULL；actual_sha256 CHAR(64) NULL；attempts_json TEXT≤8KiB（最多2项）；safe_result_json TEXT≤8KiB；failure_code VARCHAR(64) NULL |
| 完成/审计 | file_id BIGINT NULL；purpose VARCHAR(16)：UPLOAD / PROBE；audit_pending SMALLINT DEFAULT 0；audit_event_key VARCHAR(128) NULL（uploadId+事件类型）；create_time/update_time/completed_at |

索引：主体+幂等键唯一、upload_id唯一、public_id唯一、`(folder_id,actor_id,quota_day,quota_charge)`、`(state,lease_until)`。同一upload_operation_id至多对应一行文件。文件表已由[既有增量迁移](<F:/qtMusic/astral/astral-server/src/main/resources/db/migration/V20261007001__storage_file_upload_id_unique.sql#L22-L36>)清理旧重复并建立uq_storage_file_upload(upload_id)唯一索引；本期保留它，仅新增upload_operation_id唯一约束，不重复清理历史文件、不重建正确的既有索引。

- 快照只存目标 ID/版本、能力、参数结构的非秘密摘要、固定策略，不存 token、认证头、带 key 的 URL 或原始响应。
- 凭据仅存在于当前服务端执行配置；CREATED 操作等待期间若配置版本变化，PUT 拒绝该旧意图，客户端经确认创建新意图。
- 回执保存已验证的 URL/上游 ID、上传大小和本地校验信息；不存完整第三方正文。
- 操作中的历史 group/config/folder 标识用于审计，不以 ON DELETE CASCADE 抹除历史。活动及 UNKNOWN/REMOTE_SUCCEEDED 引用由服务层禁止硬删，并与目录/配置删除使用相同短事务锁；已终结的无文件操作可保留历史标识。
- 幂等墓碑保留：不因普通日志清理删除主体、键指纹、双ID、状态、计费日、结果引用。首发不自动物理清理操作行；后续归档只能裁剪终态的非必要快照，UNKNOWN 与待登记回执不得丢失。

### 5.5 schema、序列、字典和部分索引

全部新增业务实体采用 `@TableId(INPUT)` + INSERT fill，由 SequenceMetaObjectHandler 按表名_id填充；不在业务服务显式取号，不用数据库自增。

新增三张表各自的插件 schema JSON；同时更新 [配置 schema](<F:/qtMusic/astral/astral-plugin/src/main/resources/schema/sys_storage_config.json>)、[目录 schema](<F:/qtMusic/astral/astral-plugin/src/main/resources/schema/sys_storage_folder.json>)、[文件 schema](<F:/qtMusic/astral/astral-plugin/src/main/resources/schema/sys_storage_file.json>)。不得放入宿主 schema 目录造成重复实体。

明确扩展 [IndexSchema](<F:/qtMusic/astral/astral-schema/src/main/java/com/astral/schema/IndexSchema.java>) 与 [SchemaCodeGenerator](<F:/qtMusic/astral/astral-schema/src/main/java/com/astral/schema/SchemaCodeGenerator.java>)，新增受控字段：

```json
{
  "indexName": "uk_storage_group_default",
  "columns": ["is_default"],
  "isUnique": true,
  "predicate": {"column": "is_default", "operator": "EQ", "value": 1}
}
```

- 同步消除isUnique的JavaBean绑定歧义：保留JSON规范字段isUnique，将Java成员规范为unique并显式使用 `@JsonProperty("isUnique")`、读入兼容 `@JsonAlias("unique")`，保留isUnique()/setUnique()访问器契约；导出只输出isUnique。现有Lombok boolean isUnique与默认Jackson映射不能被假定已经正确读入该字段。实施测试必须走真实SchemaRegistry反序列化链，断言索引同时保留UNIQUE和WHERE，而不是只测拼接字符串或JSON能否解析。
- predicate 只允许本表已声明列、EQ 和类型化常量，生成器引用标识符、正确编码常量，禁止任意 SQL 字符串。
- PostgreSQL 生成部分索引；MySQL 导出遇到此特性明确拒绝并说明原因，不能默默生成全列唯一索引。
- 同步修正旧配置 JSON 中 uk_storage_config_default 的 predicate。旧运行迁移已正确，不改历史迁移、不重建正确的旧索引。
- 新 CHECK/FK 等复杂约束由增量 Flyway 维护；schema 管理明确显示受迁移管理的约束，不能宣传其通用生成输出等同完整迁移。
- 字典至少登记 provider、目标模式、组策略、组状态、认证模式、大小单位、用户可见操作状态/健康状态。前端通过字典 API 取文案，内部状态转移仍用常量。
- 增量迁移采用发布时唯一的 `V{yyyyMMddNNN}` 名称；字典/权限种子按业务键幂等和安全取号规范，不写死 ID；涉及已越过百万的序列供号表时同迁移抬高水位。

## 6. 目录、默认与已发布客户端兼容

### 6.1 目标解析

首发普通上传意图**只接收 folderId**，不支持 X-Storage-Group-Id 或请求级任意 config/group 覆盖。出现这些字段/头时明确报参数不支持，不静默忽略。管理员真实测试使用专用路由，但也须匹配测试目录的目标。

```text
CONFIG  → 使用该目录明确绑定的配置
GROUP   → 使用该目录明确绑定的组
INHERIT → 平台默认组（如存在），否则原默认配置
```

INHERIT 本期仅指平台默认，不递归查询父目录。选中了禁用/缺失/不可用的显式目标或默认组，就失败关闭，不借机换另一组或回落旧默认渠道。组内安全失败转移与跨组回退不是一回事。

- 存量目录 CONFIG 保持不变；新增默认组不接管旧目录。
- 老的目录创建请求没有target对象时，**先保留并校验显式旧configId**，绑定为CONFIG；仅configId也缺省时才快照旧默认配置。不能把“没有新target字段”解释成“忽略旧configId”。
- 新请求使用判别联合target：CONFIG必须configId、GROUP必须groupId、INHERIT两者均无；target与非空的顶层旧configId同时出现即拒绝歧义。更新未提供任何目标字段表示保持绑定，不等于自动改INHERIT。
- 新管理界面只有显式选 INHERIT 才会随平台默认变化；选择 GROUP 清空 configId，反之亦然。
- 用户子目录复制父目录完整三元组（mode/configId/groupId）、可见性及原继承策略；不能只复制可空 configId 再触发默认回落。
- 目录返回安全的 `uploadMode: DIRECT_TICKET | SERVER_HTTP` 及有效大小/MIME约束，前端据此选择协议。状态失效时返回明确原因；不把禁用目标展示成另一种可用协议。

### 6.2 文件移动与历史访问

移动只是元数据操作，不重新上传对象，也不按组算法重新选择渠道。沿用现有源目录 UPDATE 与目标目录 UPLOAD 权限；目标解析后必须包含文件实际 configId，且当前可见性/能力相容，否则拒绝跨渠道移动。公共图床文件不能移动成“声称已私有”的资产。

组改名、成员移除、默认改变，不重写旧文件 configId/URL。渠道停用阻止新上传，不自动使其已有公共图片无法读取。读取仍执行 Astral 当前 READ/状态检查，再从文件安全 locator 中取 URL。

### 6.3 Qt 媒体链保护

[QtMediaService](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/qt/service/QtMediaService.java#L179-L189>) 直接读取 folder.configId；[媒体树创建](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/qt/service/QtMediaService.java#L243-L274>) 使用旧默认配置。必须：

1. Qt 的系统媒体目录维持 CONFIG + 旧七家；其创建/修改目标校验拒绝 CUSTOM_HTTP、GROUP、INHERIT。
2. 原默认配置选择器永远排除 CUSTOM_HTTP；没有旧默认时继续明确报未配置，不能用默认组冒充 ticket 后端。
3. Qt issueTicket 在读取 configId 前显式检查模式及家族，不因字段可空而 NPE。
4. 不修改已发布客户端的 ticket/complete 路径、字段或正文协议；将来升级另立方案。
5. 管理端及通用 upload-ticket 也检查目标协议，遇到 SERVER_HTTP 返回 STORAGE019 并引导使用新操作接口，不能签出含秘密的 ticket。

## 7. 两步上传 API

共用操作入口继续由现有认证链识别管理端/App主体，从已验证的request属性userId取得规范化用户ID；不接收客户端uploaderId/actorId作为授权依据。actor_type首发固定为USER，管理端/App设备不拆成可逃逸配额的不同用户。客户端IP沿用ClientIp.resolve和可信代理配置，不直接信任任意X-Forwarded-For。

新增可达性权限 `all:storage:upload:create`（POST与PUT）和 `all:storage:upload:view`（本人GET），由@RequiresPermission声明；目录ACL、当前身份和操作归属是额外的数据授权，不能被接口码代替。这两个all权限只向获准使用代传的角色显式授予，不误以为APP_USER自动拥有all前缀，也不授予all通配。真实测试创建同时要求对应channel/group:test与all:storage:upload:create（Mode.ALL）；使用者需具有view才显示后续查询入口。普通角色不会因旧storage:edit而自动获准写新渠道凭据。

异步读取/执行使用服务端建立的不可变主体上下文并正确传播/清理requestId；后续ACL复核显式传入主体和会话依据，不假设Servlet线程本地身份自动传到ReadListener/工作线程。持久化只存主体ID及授权版本，不保存登录token。

### 7.1 端点

| 方法/路径（均以 /api/v1 开头） | 用途 | 权限 |
|---|---|---|
| POST /all/storage/upload-operations | 创建上传意图 | 登录 + 目录 UPLOAD；当前有效目标为SERVER_HTTP |
| PUT /all/storage/upload-operations/{uploadId}/content | 原始正文上传 | 同一主体 + 当前 UPLOAD；CAS/准入/配额 |
| GET /all/storage/upload-operations/{uploadId} | 查询操作状态 | 同一主体可读最小状态；URL另需当前READ；管理诊断走专用权限 |
| POST /admin/plugin/storage/http-configs/validate | 离线语法/模板/批准清单校验 | channel:view；不发 DNS/HTTP |
| GET/POST/PUT/DELETE /admin/plugin/storage/http-configs[/{id}] | HTTP 渠道管理 | channel:view/edit |
| GET/POST/PUT/DELETE /admin/plugin/storage/groups[/{id}] | 组与成员管理 | group:view/edit |
| PUT /admin/plugin/storage/groups/{id}/default | 设置/取消默认 | group:edit + expectedVersion |
| POST /admin/plugin/storage/http-configs/{id}/test-operations | 创建真实测试意图 | channel:test + 匹配测试目录的UPLOAD |
| POST /admin/plugin/storage/groups/{id}/test-operations | 创建组测试意图 | group:test + 匹配测试目录的UPLOAD |
| GET /admin/plugin/storage/upload-operations | 分页诊断 | upload:view；安全字段 |
| POST /admin/plugin/storage/upload-operations/{uploadId}/recover | 对已知回执补本地登记 | upload:recover；绝不发送文件 |

`[/{id}]` 只用于上表表示集合/单项，不是字面路由。没有 DELETE 操作接口假装可以撤回已发送图片。

> **实施注记（v1.1 交付收敛）**：① 渠道真实测试（`POST /admin/plugin/storage/http-configs/{id}/test-operations`）落地时未叠加 `all:storage:upload:create`（Mode.ALL），仅要求 `channel:test`——管理端测试意图的配额/准入由服务端以固定 actor 类型统一约束；② 上表末两行的管理端诊断端点（分页诊断 `GET /admin/plugin/storage/upload-operations` 与 `POST .../{uploadId}/recover`）**尚未实现**，当前以 `UploadOperationRecoveryJob` 自动恢复为准，人工诊断接口留待后续需要时再立项。

真实测试同样使用两步协议、全局许可和配额；只测试本次选中的渠道，不能一次遍历16个成员。测试目录必须 PUBLIC 且绑定对应渠道/组；不能创建系统隐藏目录绕过用户授权。purpose=PROBE仅供审计，不代表免费、不计费。生成小型测试图须用户确认，测试结果告知上游可能残留。

### 7.2 创建操作

Header：`Idempotency-Key`，客户端生成 UUID；最多128 ASCII字符。JSON 请求上限8 KiB，字段如下：

```json
{
  "folderId": 1000001,
  "fileName": "avatar.png",
  "mimeType": "image/png",
  "sizeBytes": 102400,
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

上面摘要仅是格式示例，不代表任何真实图片；客户端必须计算最终上传 Blob 的实际 SHA-256。folderId 也只是契约示例，不是种子数据或已存在对象承诺。

服务端验证登录、目录/策略/公共能力、静态目标及元数据边界，沿用现有无分隔符UUID格式生成 uploadId/publicId，保存意图指纹。指纹覆盖主体、目录、文件名规范化结果、MIME、大小、摘要和首次固定的目标快照；同主体同键、不同请求元数据返回幂等冲突，不能覆盖旧操作。查询已有键先于重新选择目标：重复POST只把请求字段与原快照组合校验，不用当前配置版本重算另一份目标指纹。服务端配置后来改变不妨碍查询旧结果；尚未发送的旧意图仍按版本规则拒绝执行。

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "uploadId": "0123456789abcdef0123456789abcdef",
    "publicId": "fedcba9876543210fedcba9876543210",
    "state": "CREATED",
    "canSendContent": true,
    "contentPath": "/api/v1/all/storage/upload-operations/0123456789abcdef0123456789abcdef/content",
    "maxBytes": 5242880,
    "intentTtlSeconds": 600
  },
  "timestamp": 0
}
```

重复 POST 返回原操作，终态/过期也不新建。不存在“网络超时就换一个幂等键”的自动逻辑。新建意图不占传输许可、暂不预留配额；每用户最多2个未开始且未过期意图，全局最多64个，创建接口另有限流。

### 7.3 PUT 正文与重复提交

- Content-Type 为元数据声明的精确 MIME；正文是最终图片字节，不是 multipart、base64 或 JSON。
- 标准身份/CSRF/客户端系统头由共享 axios 注入；不由业务页面重造另一套认证出口。
- 有 Content-Length 时必须与 expectedSize 相同；无长度可读，但用实际累计字节数约束。禁止请求 Content-Encoding，禁止同时模糊的长度/分块组合。
- 在正文读取之前检查状态、身份、目标版本，取得全局和每用户许可，并原子预留配额、CAS CREATED→RECEIVING。
- 只有 CAS 成功的一个请求读正文。重复 PUT 返回原状态并 `contentAccepted=false`，不读其正文、不再调用上游，也**不声称核验了未读取的重复内容**；客户端转 GET。
- 初次接收字节必须 size/hash 精确匹配，才可进入发送。客户端无法用同一键、同一声称摘要实际替换已接收图片。
- 同键不同元数据、操作过期、内容不匹配均需用户明确重新开始；未向上游发送的正常接收失败可在原操作许可规则内重试，见状态机。

### 7.4 返回状态不是上传成功的同义词

统一返回 `Result<UploadOperationView>`。`body.code=200` 表示操作请求被正确处理，**文件只有 state=COMMITTED 才上传完成**。UNKNOWN_OUTCOME、REMOTE_SUCCEEDED 可作为可查询的业务状态返回；不会因为 HTTP 200 给 UI 显示“上传成功”。

View 包含 uploadId、state、canSendContent、contentAccepted、safeFailureCode、可选 file/publicId。非完成状态不提供未登记 URL；身份/当前READ不足时只返回允许的最小状态，不泄露目录名、渠道内部信息或链接。知道随机ID不构成查询权限。

### 7.5 错误契约

控制器只返回 Result.success；验证失败抛异常，经统一处理器生成 `{code,errorCode,msg,data,timestamp}`。不得返回字段 message 替代 msg，不手写 HTTP 状态。基础设施超限由相应异常处理器/代理返回真实413，普通业务错误仍HTTP200。

| 场景 | 细分码/业务码 |
|---|---|
| 配置不存在/禁用 | STORAGE002 / 300 |
| 未配置全局默认 | STORAGE004 / 300 |
| 文件大小超过限制 | STORAGE006 / 300；代理/框架正文硬限可为HTTP413 |
| 目录/文件无权限 | STORAGE013 / 403 |
| Provider/目标协议不支持 | STORAGE019 / 300 |
| 每日配额耗尽 | STORAGE032，保留当前映射并在 UI 展示配额原因 |
| 业务参数错误（BusinessException） | COMMON002 / 300，保持现有映射 |
| Bean Validation参数校验失败 | 沿用统一校验异常处理器的body.code 320，不把所有COMMON002改映射 |
| 拟新增：配置或模板非法 | STORAGE040 / 300 |
| 拟新增：无可用成员 | STORAGE041 / 300 |
| 拟新增：容量繁忙 | STORAGE042 / 429 |
| 拟新增：幂等元数据冲突 | STORAGE043 / 300 |
| 拟新增：意图过期/不再可发送 | STORAGE044 / 300 |
| 拟新增：实际内容不匹配 | STORAGE045 / 300 |
| 拟新增：结果未知（故障展示原因） | STORAGE046 / 300 |
| 拟新增：版本冲突 | STORAGE047 / 300 |
| 拟新增：目标或可见性能力冲突 | STORAGE048 / 300 |
| 拟新增：远端已成功、登记待恢复 | STORAGE049 / 300 |

新增编号在实施时核对最新 [错误资源](<F:/qtMusic/astral/astral-common/src/main/resources/error-codes.properties>) 无冲突后登记；尤其 STORAGE042 要同步 [异常映射](<F:/qtMusic/astral/astral-server/src/main/java/com/astral/server/exception/GlobalExceptionHandler.java>)，不能只增加文案。GET 查询中的诊断原因是 View 字段，不要求故意把正常查询抛成异常。

## 8. 执行流程与状态机

### 8.1 完整流程

```text
POST JSON：登录/ACL/元数据/有效目标 → 主体+幂等键去重 → CREATED
PUT headers：同主体/ACL/版本 → 无等待容量许可 → 配额短事务预留 + CAS
    → RECEIVING：异步有界读取单份正文，校验size/hash/magic/像素
    → 生成一次候选顺序；按当前有效版本复核第一候选
    → 短事务记 SENDING 和尝试ID，再执行外部请求
        确定未生效失败 → 短事务记 NOT_SENT → 如预算允许选下一候选
        结果未知       → UNKNOWN_OUTCOME，停止
        可靠成功回执   → REMOTE_SUCCEEDED 持久化
    → 短事务：文件登记 + COMMITTED/CONSUMED + 审计待投递标记
    → 返回可见结果；关闭I/O；释放全部许可和内存
GET：查询同一操作；已知回执可触发/等待本地登记恢复，不会上传正文
```

每次操作从第一次 PUT 被准入时建立一个90秒绝对 deadline并持久化；意图等待的600秒是另一回事。接收失败后原意图重收、所有后续候选均共享这份剩余预算，不能重置90秒。回CREATED只允许在意图TTL与执行deadline都尚未过期时；预算耗尽则EXPIRED。运行中用单调时钟计时，重启后的持久时间只用于过期/状态恢复，不据此自动恢复发送。

### 8.2 状态与恢复规则

| 状态 | 是否可能已有远端对象 | 正常去向 | 进程恢复/租约处理 |
|---|---|---|---|
| CREATED | 否 | RECEIVING、EXPIRED | TTL到期仅过期；不占配额/传输槽 |
| RECEIVING | 否，前提是发送必须先持久化SENDING | SENDING、FAILED、回CREATED | 确认旧接收者已终止且未提交SENDING后可释放HELD；TTL内回CREATED，否则EXPIRED |
| SENDING | 是/未知 | 下次SENDING、UNKNOWN_OUTCOME、REMOTE_SUCCEEDED、FAILED | 无可靠结果则UNKNOWN，绝不仅因租约过期重传 |
| REMOTE_SUCCEEDED | 是，可靠回执已存 | COMMITTED | 只补本地登记；保留HELD，不需要图片正文或原密钥 |
| COMMITTED | 是，文件已登记 | 不再执行上传 | 同键返回同一资产；文件后来删除则返回已删除事实 |
| UNKNOWN_OUTCOME | 可能 | 管理诊断/明确证据下结案 | 不回CREATED，不自动释放HELD，不自动换键/渠道 |
| FAILED | 明确无远端对象 | 终态 | 同键仍返回原失败；用户可明确新建意图 |
| EXPIRED | 未发送 | 终态 | 不恢复发送；保留幂等墓碑 |

SENDING→下一候选前，必须先持久化该次“确定未发送/确定未创建对象”的证据；如果此写入失败就停止，不能在内存里决定再试。

### 8.3 租约、取消和并发

- 每次接收/执行 claim 使用新的 lease_token 与 version；写 SENDING 必须同时匹配旧 token、状态、未过期条件。恢复者不能让旧工作线程继续发送。
- 单实例内每个 uploadId 一个执行句柄；取消令牌、I/O终止和许可释放必须幂等。超时任务不能先释放容量、让尚在发送的旧请求与新请求叠加。
- 对 RECEIVING 的租约回收须先取消且确认本进程执行句柄结束；重启后没有旧本地工作者才可安全恢复。未来多实例需 fencing/分布式准入，不能复用本期假设。
- 用户关闭页面不等于撤回第三方上传：发送前取消可安全终止；发送后取消可能UNKNOWN。客户端先查原 uploadId，不能默认新建。
- 用持久化状态承接故障：即使写UNKNOWN失败，之前已提交的SENDING仍是恢复依据；不能先清状态/配额再“尽力记日志”。

### 8.4 远端成功后的本地一致性

分五个短事务：创建意图、配额预留/接收claim、发送意图、可靠回执、文件登记。**正文读取、DNS/TCP/TLS、上游上传和响应解析均在事务外**。

- 保存回执失败：停止后续上传；已持久化SENDING保留，崩溃恢复按UNKNOWN，不承诺能找回只在内存的URL。
- 回执已保存、插入文件失败：保持REMOTE_SUCCEEDED；小批量恢复任务只补登记。
- 文件插入、操作COMMITTED/CONSUMED与唯一 operation_id 在一个事务，避免“双文件/没有计费”的中间态。
- 一般统计/操作日志失败不能导致重新上传。事务内记一个安全的 audit_pending 状态/事件摘要，由现有审计适配器幂等补写；不新增消息队列。
- 恢复任务单工作者、小批量、指数退避；数据库不可用时暂停新外部上传，不能靠Redis成功日志冒充持久回执。

发送意图提交是**目录授权与路由配置**的线性化点：在该短事务复核最新目录ACL、目标/配置版本与最终PUBLIC能力。为兑现这一点，不能只增加一次普通SELECT：

- SENDING复核与旧/新目录授权写入共用 `storage-folder-security:{folderId}` 事务咨询锁；[savePermissions现有入口](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFolderService.java#L250-L279>)也须接线。授权全量替换、清空、目录status/visibility/uploadPolicy/target改变，都在同锁内递增target_version，不能因null列表早返回跳过版本更新。
- target_version在本版代表目录上传目标及授权策略版本，不仅是外键版本。发送前直接核对权威版本/授权，不以可能陈旧的执行缓存代替；相关group/config行持短时共享/更新锁直到SENDING提交，使版本变更不能从校验与提交之间穿过。
- 涉及同批资源时统一顺序：原目录树锁（确需时）→目录安全锁（ID升序）→group/config行（各自ID升序）→额度锁→uploadId/operation锁。旧登记、授权、删除与新执行按同序接入，网络操作不在锁内。没有额度变更的发送资格检查可跳过额度锁。
- 全局登录会话/接口权限继续遵循现有认证与权限版本机制并在发送前复核；不宣称Redis鉴权状态与PostgreSQL提交具有跨存储原子撤销能力。发生在最后一次有效授权之后的撤权，不能保证取消已授权在途I/O。

此后撤权或停用渠道，仍保存已授权在途操作的事实并按原归属登记；但返回/查询URL必须按当前权限。禁止因撤权丢弃已产生的对象事实、释放费用或偷偷重传。

**保证边界：**没有上游幂等/查询支持，就不能保证跨系统 exactly-once。远端写入与本地保存回执之间的崩溃窗口仍可能留下UNKNOWN和孤儿副本；本设计保证不把这种不确定性自动扩大为另一轮上传。

## 9. 配额预留与旧链共同记账

### 9.1 统计口径

保留“目录 + uploader + 服务器业务自然日”的次数限制，软删不返还。服务器业务时区沿用现有 LocalDate 口径，实施时固定并测试时区，不凭浏览器时区决定计费日。

```text
有效用量(folder,user,day)
  = 原文件行中 upload_operation_id IS NULL 且 status != FAILED 的当日数量
  + 新 operation 在该计费日 quota_charge ∈ {HELD, CONSUMED} 的数量
```

新文件关联operation后**不再在第一项重复计数**。新操作从HELD转CONSUMED，额度仍只占一份；不会在文件插入与释放预留之间出现窗口。

- 新操作 quota_day 固定为 PUT 准入日；跨午夜才登记仍归该日。
- 旧无operation直传继续按登记日计数，保留既有日期语义。
- 新操作计费目录固定于上传准入，不随之后的文件移动改变；文件数统计与上传配额统计应在界面区分。
- 明确未发送的失败释放HELD；UNKNOWN/REMOTE_SUCCEEDED保持HELD。时间过了不是“已证明没有对象”，不能自动返还。
- 超管按既有规则可豁免业务配额；quota_exempt明确记录。豁免不取消登录、硬体积、SSRF、PUBLIC_ONLY或并发边界。

### 9.2 原子步骤

使用 PostgreSQL 事务级咨询锁，键为规范化 folderId+actorId 的稳定命名空间哈希；与 operation 行CAS按统一锁顺序执行，事务结束自动释放。哈希碰撞最多额外串行，不改变授权。

1. 新 PUT：取得内存许可后，短事务锁定额度范围，读取统一用量，检查当前限额，写HELD/quota_day并CAS接收状态。
2. 旧签票检查改读统一用量，避免忽略新操作预留。
3. **旧登记**也必须在同一额度锁内重查统一用量并插入文件。仅给新操作加预留、旧路径不改，不能防超卖。
4. [当前登记方法](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java#L214-L253>) 在事务内做HEAD；需要把远端HEAD/内容检查与短事务登记边界分离，保留原对象核验与幂等语义，不能在持锁时等待网络。
5. 既有 uploadId 幂等锁与新额度锁采用一致顺序，重复登记不重复计数。

本期不宣称消除了旧浏览器直传“已上传到对象存储，但登记时配额已满”的窗口；要消除此窗口需要把旧ticket也改为原子预留，属于额外协议/生命周期改造。当前保证的是共享登记额度不因两套记账互相忽略而超卖。

## 10. 组选择、失败转移与健康状态

### 10.1 候选快照

候选在正文校验后只生成一次，记录组版本、成员顺序及渠道版本；GET、重复POST/PUT不推进轮询游标。过滤条件包括成员/渠道启用、获批目标、凭据完整、PUBLIC能力、实际MIME/大小、冷却/禁用状态。

普通“没有候选”在出站前失败；所有渠道DOWN时不把它们全部重新放回。用户本次失败不会跨出目录授权的组。

### 10.2 两种策略

**FAILOVER：**按 priority 升序分层，同层按稳定 memberId 排序；生成唯一configId候选序列。只在本次失败被分类为可安全继续时取下一项。

**ROUND_ROBIN：**同样先分priority层；对每层的合格成员按本地原子游标轮换首项，再把各层顺序拼接。先耗尽高优先级层的候选，才轮到低优先级。游标键为groupId+version+priority，配置变化可重置；首项被选中才推进一次，不按每次尝试反复推进。

权重不参与这两种算法。最多尝试2个不同渠道；若第一层有多成员而上限已耗尽，不为了“必须碰到备层”突破上限。单实例本地状态足够；Redis中断不改变算法正确性，多实例不在本期承诺内。

### 10.3 结果分类与可重试性

| 结果类别 | 示例 | 本次动作 |
|---|---|---|
| LOCAL_REJECTED | 无权限、私有目标、摘要不符、配置未获批 | 不出站，失败；不自动换目标掩盖问题 |
| NOT_SENT | DNS/目标校验失败；传输层能证明尚未提交HTTP请求的建连失败 | 记录安全原因；允许在同组和剩余预算内下一候选 |
| DEFINITE_REJECTED | 经过版本化适配器契约证明未创建对象的拒绝 | 按错误类别决定下一候选；不是通用HTTP状态规则 |
| UNKNOWN_OUTCOME | 请求可能送达后的超时/断线/5xx、坏JSON、缺主URL、超大响应、主URL被拒绝 | 持久化UNKNOWN、停止，不能重传 |
| REMOTE_SUCCEEDED | 完整有界响应满足成功条件与URL验证 | 保存回执，仅继续本地登记 |

首发两家没有经本设计确认的通用“安全拒绝码表”，因此不提供给管理员随意勾选 retryOn4xx/5xx 的开关。默认只有可靠NOT_SENT证据支持失败转移；后续某预设要增加 DEFINITE_REJECTED，必须附供应商契约与故障测试证据。HTTP客户端内部重试始终关闭。

### 10.4 健康与配额不混为一谈

健康状态是过滤提示，不决定已经发生的远端结果：

- AUTH_INVALID：经可信错误结构确认凭据问题，阻止新请求直到配置版本变化或管理员明确处理。
- COOLDOWN：429的Retry-After或暂时网络故障触发；解析秒数/HTTP-date并限制为最多15分钟，无头时采用30秒起的有界退避。
- 图片不合法/目录额度不足不把整个渠道标DOWN。
- 连续网络故障阈值初始3次；达到后冷却，期限后只允许一个真实用户请求半开试用，不自动制造测试图片。
- 半开失败重新冷却，其余请求选择其他候选或明确无可用渠道；不执行“全DOWN全部放开”。

健康状态、游标和熔断令牌本地有界缓存；重启清空只影响调度提示，不清除持久化UNKNOWN/配额事实。

## 11. 有界接收、图片校验与出站传输

### 11.1 初始保护参数

| 参数 | 默认 | 硬边界/说明 |
|---|---|---|
| 单图大小 | 5 MiB = 5242880 B | 渠道/目录可收紧，超管不可突破 |
| 全局传输并发 | 1 | 本版配置只允许1或2；目标机压测后再选2 |
| 单用户并发 | 1 | 从读取前持有到I/O结束及状态/配额已可靠承接 |
| 排队 | 不排队 | tryAcquire失败立即繁忙，不堆积持有正文的等待者 |
| JSON意图大小 | 8 KiB | 与图片正文上限分开 |
| HTTP配置请求大小 | 64 KiB | profile与有界secret更新；入口单独限制 |
| 接收正文绝对预算 | 20秒 | 还受90秒总deadline限制 |
| 单次上游预算 | 60秒 | min(profile预算, 总剩余预算减登记保留时间) |
| DNS/TCP/TLS阶段 | 5秒初始上限 | 必须可取消/停止后续连接，不是只在日志记超时 |
| 响应读取空闲预算 | 10秒 | 与绝对deadline同时生效 |
| 总deadline | 90秒 | 包含接收、校验、所有尝试、登记；不含创建后等待PUT |
| 登记保留预算 | 5秒 | 出站最晚在总deadline前5秒结束/取消 |
| 上游响应正文 | 256 KiB | 成功和错误响应都边读边限 |
| multipart普通字段与封装 | 16 KiB | 除图片外的总字节预算；逐项限制再总计 |

这些值是本期初始策略；不是硬件唯一推导，也不是生产实测通过。

### 11.2 入口与内存

- 不用 `@RequestBody byte[]`、MultipartFile 或任何会在准入前读完整正文的参数绑定。
- 上传内容端点使用 Spring MVC 异步请求，接收层在 Servlet async 已启动后使用非阻塞 ReadListener；由异步请求生命周期和单调时钟deadline取消读入，失败交给统一异常处理，不在业务Controller手写状态。
- 实现必须证明慢客户端不会永远卡在一次阻塞read里；仅在read调用前后检查时间不合格。Servlet集成需嵌入式Tomcat慢流测试，不以MockMvc通过代替。
- 取得容量许可与配额预留后，才分配 expectedSize 的固定byte[]；读取到 expectedSize 后再探测至多1字节判断额外正文。少读、多读、断线均不出站。
- 分块更新SHA-256，复用单份正文；禁止无上限ByteArrayOutputStream扩容和toByteArray复制。
- 所有 finally/取消路径关闭流、释放内存引用和许可；定时器不能与主处理者重复释放。

### 11.3 本地图片验证

最终约束是应用硬上限、目录策略、所选渠道能力的交集。明确计算 `finalVisibility = policy.forceVisibility ?? folder.visibility`，本期请求不能覆盖；非PUBLIC在向外发送之前拒绝，并将PUBLIC结果写入操作快照及最终文件。非豁免主体仍执行目录min/maxSize、精确allowedMimes、requireLogin和配额；新代传总是要求登录。管理员豁免业务策略时也不能绕过最终PUBLIC能力和下面的安全底线：

1. 首发允许 JPEG、PNG、GIF、WebP 的可验证子集；SVG、HTML、任意 `image/*`、未知魔数全部拒绝。
2. 魔数、声明MIME和实际结构一致；先限制文件字节，再解析有限头部。
3. 宽高为正整数，单边初始上限16384、总像素初始上限2000万；使用long安全乘法。目录maxPixels只能进一步收紧。
4. 不完整解码位图做缩放；使用受限头部/尺寸读取器。显式 `MemoryCacheImageInputStream` 或字节头解析，不能因为输入是内存就默认 ImageIO 不落临时文件。
5. WebP 若当前运行时无可靠读取器，需实现经过测试的受限头解析，否则从有效允许集合移除并在上传前说明；不能读取失败返回-1后悄悄跳过像素上限。
6. GIF不强制转静态图，不全量解码帧；字节/逻辑画布上限必须检查，复杂动画支持范围在UI说明。
7. 复用目录 `none/magic/full` 的业务意图，但新代传始终执行硬安全验证；需要full而没有相应验证能力时拒绝/收紧MIME，不能自动降级到none。

[既有验证 helper](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java#L419-L438>) 使用尺寸读取而非完整ImageIO.read；新实现可抽取思想，但须补失败关闭和内存缓存边界。验收不是“为了省性能关掉所有验证”。

### 11.4 可重放 multipart

使用“少量前缀/字段字节 + retained image buffer + 后缀”的可重复读取entity；每次创建新流视图，不拼第二个完整multipart数组。可以计算总Content-Length；也可在适配器验证后使用chunked，但不依赖此前一次探测作为所有上游的保证。

重放能力只说明本地还能读相同字节，不授予自动重试的业务权利。分配、图片验证、字段编码、网络缓冲等都计入峰值，不宣称两并发总内存只有2 MiB。

### 11.5 安全 HTTP 执行组件

在storage内部建立独立 HC5 适配层；依赖经项目BOM/依赖树确定兼容版本并锁定。本文不声称某个HC5具体builder API已在本仓编译通过。

- **实际连接绑定：**受控DnsResolver只向连接器返回核验后的公共IPv4地址；TCP用这些地址，TLS/SNI/Host仍用原批准hostname。禁止客户端自行二次DNS解析或切IPv6。
- 排除回环、RFC1918、CGNAT、链路本地、0/8、组播、文档、基准测试、保留段及云元数据地址；不能只调用 isSiteLocalAddress。CIDR清单及规范化有单元测试。
- 默认拒绝IP字面量，AAAA仅不能成为回退路径；若仅IPv6则明确不支持。以后开放IPv6须增加映射/转换/特殊范围及实际socket目的地测试。
- DNS本身使用有超时、固定小并发且无无限队列的解析组件；不能让Future超时后未终止的解析工作无限累积。解析晚到或deadline已过时不得再连接。
- 首发每次尝试使用独立连接并明确不复用；禁止系统代理/环境代理、重定向、自动重试、自动Cookie及认证协商重放。不要全局修改影响旧Provider的HTTP客户端。
- 保持正常证书链与主机名校验；不使用trust-all或关闭hostname verifier来实现IP固定。
- 总deadline到达时显式取消exchange并关闭连接/响应。阶段timeout和proxy idle timeout不等价于总deadline；只中断一个Future但底层仍发送不合格。
- 显式请求identity，关闭自动解压；上游返回非identity Content-Encoding时停止并按UNKNOWN承接，不在解压后才发现无限膨胀。
- 在读取成功/错误响应时以≤8KiB小块计数，超过256KiB立即取消/关闭；不使用无界ofByteArray再做事后检查。响应头总量/单行也要在客户端限制，初始16KiB/8KiB。
- 所有执行端点、TLS/DNS/重定向与故障用例用注入的测试transport/解析器模拟；测试私网例外只能存在测试装配，不暴露生产 `allowPrivate=true` 后门。

## 12. 文件能力与完整 Provider 分发

首发由适配器固定 `PUBLIC_ONLY / remoteDelete=false / remoteLookup=false / upstreamIdempotency=false`；管理页面不能勾选未实现能力。

| 入口/能力 | CUSTOM_HTTP 行为 | 旧七家 |
|---|---|---|
| upload-ticket | 明确STORAGE019，不生成含秘密ticket | 原直传 |
| 新upload-operations | 本文两步协议 | 不偷偷代传，拒绝协议不匹配 |
| 浏览器files/register | 不作为新家族上传入口 | 原回执；共享配额检查接线 |
| operation GET | 状态/安全结果 | 不改变旧uploads GET |
| 旧uploads GET | COMMITTED后可按原授权查文件；未完成不伪装文件 | 原契约 |
| download-url | READ及文件状态检查后返回已验证公网URL；标明非私有签名 | 原签名逻辑 |
| permanent-url | 仅PUBLIC，返回locator直链 | 原逻辑 |
| 可见性修改 | 禁止改PRIVATE，说明无法撤权；不靠改数据库字段伪装 | 原逻辑 |
| requestDelete | 本地标DELETED，记录remoteDeletePerformed=false；绝不发Telegram任务 | 原真实删除/Worker流程 |
| 批量删除/下载 | 逐项调用同一能力分支并返回准确结果 | 原批处理 |
| 移动/重命名 | 不移动远端对象；按第6节校验 | 原相同配置规则兼容 |
| verifyContentAfterRegistration | 使用操作中的本地已验证证据，不重新公网下载 | 原对象校验 |
| HEAD/对象存在性 | 未实现，不用-1假装存在 | 原HEAD |
| config test | 专用有权限的两步测试 | 原test |

文件locator使用版本化结构，仅保存已验证publicUrl、可选thumbnail、remoteId、capability snapshot及本地校验摘要；删除URL/令牌不采集。读取不依赖组当前成员，也不依赖当前密钥仍有效。

`expiresAt=0` 若为兼容现有View保留，只表示Astral没有签名过期时间，不代表图床承诺永久有效；新增安全说明 `accessMode=PUBLIC_EXTERNAL`。首发不新建开放重定向服务或图片字节代理，直接返回验证后的URL；若复用既有302入口，仍必须从已授权文件locator取得，禁止用户提供任意目标。

## 13. 管理端与上传体验

界面遵循 [UI_GUIDE](<F:/qtMusic/astral/docs/UI_GUIDE.md>)，新增页面使用shadcn/ui/Tailwind及PageHeader、SectionCard、DataTable等现有业务组件；不引入antd或另造主题色。弹层沿用已修复的Dialog内portal行为。

### 13.1 渠道管理

- 基本信息：名称、状态、预设及版本；预设升级是显式操作，显示差异后保存新版本，不随服务发布偷偷升级。
- 请求：已批准端点下拉、认证模式、Query/Header/Form键值编辑、文件字段；端点批准清单只读。
- 凭据：显示“已配置/未配置”，操作只有保留、替换、清除；替换输入不预填旧值，不存localStorage，不随普通JSON导出。
- 响应：成功条件、Pointer、单位、输出域名；离线预览用合成响应，禁止粘贴真实密钥响应到共享日志。
- 校验：离线检查不联网；真实测试单独按钮、写权限、明确确认副作用。结果显示实际渠道/操作ID，不显示认证URL或原始响应。

### 13.2 组管理

成员列表支持优先级分层、启停、顺序预览；不展示无效weight输入。显示算法、maxAttempts、健康/冷却原因、安全失败转移说明。保存必须带expectedVersion；冲突保留用户未保存草稿并提示重新读取，不静默覆盖另一个管理员。

目录目标为三个互斥选项；已有CONFIG标明“固定绑定，不受默认组改变”。Qt媒体目录锁定为兼容模式，并解释原因。

### 13.3 前端上传阶段

```text
选图 → 可选压缩/确认 → 计算最终SHA-256 → 创建操作
    → 传到Astral（可测百分比） → 上游处理中（不伪造百分比）
    → 本地登记中 → 已完成 / 待恢复 / 结果未知
```

- 浏览器onUploadProgress只代表第一段传输；达到100%不能显示整个任务完成。
- 静态JPEG/PNG等可提供可选缩放压缩；透明图保持透明，GIF默认保留动画，已小图不重复有损压缩。
- 压缩后重新计算大小、MIME、扩展名和摘要；压缩失败保留原图仅在仍满足上限时可继续，否则明确拒绝。释放ImageBitmap/ObjectURL等资源。
- 图片验证和体积限制以服务端为准，外部客户端绕过压缩也不能突破。
- 所有Astral请求经 [共享client](<F:/qtMusic/astral/astral-front/src/api/client.ts>) 的request封装；完整路径包含 `/api/v1`。新API函数返回 `Promise<ApiResult<UploadOperationView>>`，不依赖裸axios错误泛型。
- 仅正文PUT通过共享request的单请求config显式设置 `timeout: 120000`，给90秒应用deadline留出网络与响应余量；不得沿用默认30秒而把合法慢上传提前断开，也不把全局普通请求改成120秒。元数据POST/GET仍用普通超时，客户端取消后按原操作查询，浏览器不以加长超时为由自动重传。
- 统一错误类型增加 `businessCode`、`errorCode`、requestId等安全信息；catch用unknown收窄。当前拦截器返回完整ApiResult，取其data一次即可，不误称原行为“双重解包”。
- 超时/刷新/断网先GET或同键POST恢复原操作；不自动新建键。UNKNOWN明确提示“可能已上传，请勿直接重复”，并提供查询/联系管理员入口。
- 只有纯元数据请求可由统一客户端自动重放；正文PUT因既有状态机不会重复执行上游，但UI仍应避免通用重试插件自动再次发送大正文。CSRF刷新后的动作须按操作状态决定，不无限重放。

## 14. 部署、反向代理与资源模型

### 14.1 初始环境

用户给出的目标为1 CPU、2 GiB、1 Mbps、50 GB；[后端Dockerfile](<F:/qtMusic/astral/astral-server/Dockerfile>) 当前堆上限384 MiB。入站是否与出站共享同一1 Mbps限制尚未确认，不把它当既定事实。

```text
缓冲后顺序传输：
T ≈ S / 浏览器到Astral吞吐 + S / Astral到上游吞吐
  + 校验、TLS、上游处理、数据库登记开销
```

1 Mbps理想裸吞吐为125000 B/s；5 MiB仅出站约42秒，300000 B仅出站约2.4秒。两用户共享出口会互相影响。峰值内存不只是图片byte[]，还包括头解析、multipart小段、HTTP/TLS、响应树、线程和并发请求元数据。

默认并发1，小图优先；在目标机记录p50/p95、峰值堆、其他API延迟后决定是否允许2。无“300KB必定五秒”或“总缓冲必定10MiB”的验收承诺。

### 14.2 nginx实际链路

现有 [站点配置](<F:/qtMusic/astral/deploy/nginx/10-astral-sites.conf>) 为宿主nginx：web普通API代理到127.0.0.1:3000，API域代理到127.0.0.1:27000。不得复制一个未证实可解析的容器名上游。

**选定部署方式：**在API域和web域两个server内，为新的 upload-operations 前缀增加专用location，直接代理同一后端27000，避免正文再经Next代理。其他web API继续原Next路径，不改变博客站点8m规则。开发环境的 [Next rewrites](<F:/qtMusic/astral/astral-front/next.config.js#L25-L31>) 仍可用，但慢流验证要核对其代理行为；生产验收针对实际两条专用路由。

下例仅表达两个server都应具有的内容；实施时复用已有include方式，不新增替代服务器：

```nginx
location ^~ /api/v1/all/storage/upload-operations {
    limit_req zone=astral_api burst=10 nodelay;
    client_max_body_size 5m;
    client_body_timeout 20s;
    proxy_request_buffering off;
    proxy_buffering off;
    proxy_http_version 1.1;
    proxy_pass http://127.0.0.1:27000;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 110s;
    proxy_send_timeout 110s;
}
```

- 5m与应用5MiB一致；客户端发raw body，不以“浏览器multipart余量”解释额外放大。JSON意图仍由应用单独8KiB限制。
- 新location不会继承兄弟location的转发头、限流和安全设置；完整带回。代理超时是空闲超时，90秒总deadline仍由应用实现。
- 生产关闭该入口request buffering，避免nginx先收完整正文到临时文件；需验证chunked也不缓冲及中间代理行为。不将应用“不持久化”误称为整个操作系统绝无任何磁盘页/代理缓存。
- 保留后端CORS单一来源和可信代理解析；跨域直连需允许Idempotency-Key及PUT等必要头/方法，不在nginx重复加另一组CORS。
- Spring multipart大小配置不保护raw-body端点；日志过滤器/监控中间件也不得预读或复制其正文。
- 管理渠道请求64KiB上限与真实测试内容入口分开；测试PUT仍走同一content端点。

### 14.3 应用开关与故障降级

拟新增 `astral.storage.custom-http` 配置域，字段包括enabled、maxBytes、globalConcurrency、perUserConcurrency、totalTimeoutMs、receiveTimeoutMs、responseMaxBytes及批准目标集合。首次部署enabled=false，完成本地测试与目录/权限配置后启用；配置绑定校验拒绝越界值。

- PostgreSQL无法可靠创建/推进操作：不发送上游。
- Redis失败：新上传的正确性仍由PG和本地准入保证；限流采用有界本地兜底或明确拒绝，不能无限放行。不得干扰原认证基础设施自己的故障策略。
- 新入口关闭：停止新创建/新发送，不删文件/配置/操作；保留旧公共文件读取和操作诊断/已知回执恢复。
- 关闭中已有请求按状态安全终止或完成登记，不能把已发送请求强行标FAILED。
- 不默认启动后台真实上传心跳；健康信息来自真实请求与人工确认测试。

## 15. 日志、监控与审计

安全日志按结构白名单输出：requestId、uploadId、actorId、folderId、groupId/configId及版本、attemptNo、阶段耗时、字节数、结果分类、内部安全错误码。指标标签不放用户ID、文件名、完整URL，避免敏感信息和高基数。

- 新HTTP配置路由禁止自动记录请求/响应正文；secretChanges、provider_options、认证材料不进操作日志、异常字符串或审计remark。现有 [OperateLog注解](<F:/qtMusic/astral/astral-log/src/main/java/com/astral/log/annotation/OperateLog.java>) 尚无逐方法正文开关；本期为注解和Aspect新增recordParams/recordResult（默认保留旧行为），这些敏感方法设为false并只记安全摘要。请求统计、错误日志及其他会读取参数的过滤器也识别同一敏感路由策略，不只修改AOP一处。
- 上传content路由不记录/缓存正文。DTO的toString、参数绑定错误、校验异常也必须脱敏，不以@JsonProperty WRITE_ONLY作为全部日志保护。
- 关闭新客户端wire/debug日志，不记录query-auth完整URI；错误原因用固定安全码，不原样拼第三方message。
- 对一般上游HTTP错误仅记录状态、内部分类、长度和有限的预设错误码；没有“响应前200字符”兜底。
- 新凭据读接口解析坏JSON时隐藏敏感配置并报状态，绝不沿用 [现有原文返回兜底](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageConfigService.java#L401-L418>)。
- 登记审计事件键为uploadId+事件类型，重复恢复不重复记一次成功上传；审计不可用不触发再次出站。

初始指标：接收/出站活跃数、准入拒绝、各阶段时延、上游响应超限、NOT_SENT/UNKNOWN/成功计数、REMOTE_SUCCEEDED待恢复数量及最老年龄、配置/组版本冲突、DNS/目标拒绝、剩余配额。告警对持续UNKNOWN、待登记积压、凭据失效和持续繁忙给出可操作提示，不包含秘密。

## 16. 后端、前端及规范改造清单

下表是实施清单，不表示本次已经修改这些文件。

| 位置 | 必须覆盖的改动 |
|---|---|
| [StorageDtos](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/dto/StorageDtos.java>) | 保持旧配置入参；扩展目录target/View；新HTTP/operation专用DTO另建 |
| [StorageConfigService](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageConfigService.java>) | 新家族安全摘要、旧路由拒绝、禁止首配置自动设CUSTOM默认、version、坏JSON不回原文 |
| [StorageConfigCache](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageConfigCache.java>) | 编译配置与原始秘密只供服务器；安全View分离，事务提交后按版本失效 |
| [StorageFolderService](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFolderService.java>) | 三态互斥、CAS、显式NULL、旧创建兼容、活动操作删除保护、Qt目录目标限制 |
| [FileManagerService](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/FileManagerService.java>) | 子目录完整目标继承、移动按实际渠道匹配、不只判可空configId |
| [StorageUserController](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/controller/StorageUserController.java>) | targetResolver、旧ticket协议拒绝、有效目标View、新接口另建受控Controller |
| [AdminStorageController](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/controller/AdminStorageController.java>) | 旧configs所有写/test/default旁路封闭，管理端ticket同样检查模式 |
| [UploadTicketService](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/security/UploadTicketService.java>) | 旧家族保持，显式拒绝CUSTOM；共用安全策略/统一用量，不在ticket放秘密 |
| [UploadPolicyService](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/UploadPolicyService.java>) | 共享策略解析；新配额统计与旧签票/登记集成，区分业务豁免与硬上限 |
| [StorageFileService](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/StorageFileService.java>) | requestDelete、download、permanent、visibility、登记/HEAD事务边界、operation唯一性及验证证据，不能只改低层helper |
| [ProviderSupport](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/storage/service/ProviderSupport.java>) | 增加明确CUSTOM判断；不把它塞进旧register/Worker家族图省事 |
| [QtMediaService](<F:/qtMusic/astral/astral-plugin/src/main/java/com/astral/qt/service/QtMediaService.java>) | 保持已发布协议，模式/旧默认保护，无空引用和隐式改目标 |
| [前端storage API](<F:/qtMusic/astral/astral-front/src/api/storage.ts>)、[client](<F:/qtMusic/astral/astral-front/src/api/client.ts>) | 新类型/API、业务错误元信息、完整前缀、两阶段进度/状态查询，不改变旧直传出口 |
| [IndexSchema](<F:/qtMusic/astral/astral-schema/src/main/java/com/astral/schema/IndexSchema.java>)、[生成器](<F:/qtMusic/astral/astral-schema/src/main/java/com/astral/schema/SchemaCodeGenerator.java>) | 受控predicate、PG导出/MySQL拒绝、旧config及新group元数据对齐 |
| [站点代理](<F:/qtMusic/astral/deploy/nginx/10-astral-sites.conf>) | 两个server的新专用入口、真实上游、头/限流/缓冲/超时 |
| [API](<F:/qtMusic/astral/API.md>)、[存储需求](<F:/qtMusic/astral/STORAGE_REQUIREMENTS.md>) | 限定正文例外、PUBLIC_ONLY、两步API、私有/删除/回滚边界 |

拟新增主要类：HttpStorageConfigService、HttpProfileCompiler、HttpUploadExecutor、ApprovedTargetPolicy、PublicIpv4Resolver、StorageTargetResolver、StorageGroupService/Selector、UploadOperationService/Recovery、UploadAdmission、SharedUploadValidator、UploadQuotaCoordinator，以及三张表的entity/mapper与对应Controller。新增类仍在现有模块内，不创建新Maven业务模块。

StorageBatchService、Worker回调登记、文件夹详情/管理UI及任何直接读取folder.storageConfigId的调用者必须通过搜索清单逐一核对；不是只修改上表已列入口就宣告零回归。Worker回调不能替新家族登记，旧回调登记要进入共享配额/幂等边界。

## 17. 预设完整示例与历史证据边界

以下JSON是**不含密钥值的有效Profile**，不是完整创建请求；新建渠道仍须在受信表单通过SET提供对应secret。默认capability由执行器固定，不允许JSON自己宣称delete=true。

### 17.1 ImgBB v1

```json
{
  "schemaVersion": 1,
  "presetId": "IMGBB",
  "presetVersion": 1,
  "endpoint": "https://api.imgbb.com/1/upload",
  "method": "POST",
  "query": {},
  "headers": {},
  "auth": {"mode": "QUERY", "name": "key", "valueTemplate": "${secret:apiKey}"},
  "body": {
    "mode": "MULTIPART",
    "fileField": "image",
    "fileNameTemplate": "${publicId}.${extension}",
    "fields": {}
  },
  "response": {
    "success": [
      {"pointer": "/success", "op": "EQ", "expected": true},
      {"pointer": "/status", "op": "EQ", "expected": 200}
    ],
    "urlPointer": "/data/url",
    "thumbPointer": "/data/thumb/url",
    "idPointer": "/data/id",
    "sizePointer": "/data/size",
    "sizeUnit": "BYTES",
    "widthPointer": "/data/width",
    "heightPointer": "/data/height",
    "numericStringPointers": ["/data/size", "/data/width", "/data/height"],
    "allowedUrlOrigins": ["https://i.ibb.co"]
  },
  "limits": {"maxBytes": 5242880, "allowedMimes": ["image/jpeg", "image/png", "image/gif", "image/webp"]},
  "timeouts": {"attemptMs": 60000}
}
```

### 17.2 freeimage.host v1

```json
{
  "schemaVersion": 1,
  "presetId": "FREEIMAGE",
  "presetVersion": 1,
  "endpoint": "https://freeimage.host/api/1/upload",
  "method": "POST",
  "query": {"format": "json"},
  "headers": {},
  "auth": {"mode": "FORM", "name": "key", "valueTemplate": "${secret:apiKey}"},
  "body": {
    "mode": "MULTIPART",
    "fileField": "source",
    "fileNameTemplate": "${publicId}.${extension}",
    "fields": {"action": "upload", "format": "json"}
  },
  "response": {
    "success": [{"pointer": "/status_code", "op": "EQ", "expected": 200}],
    "urlPointer": "/image/url",
    "thumbPointer": "/image/thumb/url",
    "idPointer": "/image/id_encoded",
    "sizePointer": "/image/size",
    "sizeUnit": "BYTES",
    "widthPointer": "/image/width",
    "heightPointer": "/image/height",
    "numericStringPointers": ["/image/size", "/image/width", "/image/height"],
    "allowedUrlOrigins": ["https://iili.io"]
  },
  "limits": {"maxBytes": 5242880, "allowedMimes": ["image/jpeg", "image/png", "image/gif", "image/webp"]},
  "timeouts": {"attemptMs": 60000}
}
```

Preset v1可用MIME仍须与本地验证器已实现集合取交集；不能因示例列了WebP就声称运行时已经支持验证。可选元数据Pointer未返回时仅缺省，不猜其他字段，也不把数值extension码当真实后缀。

### 17.3 可扩展参数示例

```json
{"mode":"HEADER","name":"Authorization","valueTemplate":"Bearer ${secret:token}"}
```

```json
{"mode":"HEADER","name":"X-Api-Key","valueTemplate":"${secret:apiKey}"}
```

这两段只是auth子对象示例，不能误当完整profile保存。需要HMAC/OAuth刷新或多请求握手的供应商须新增受控适配器，不通过模板偷渡脚本。

### 17.4 既有观测，不作性能/能力承诺

| 项 | 2026-11-05既有观测 | 正确解释 |
|---|---|---|
| ImgBB请求 | Query key、文件字段image，响应success/status/data | 用于预设字段初稿，生产仍须授权冒烟 |
| freeimage请求 | Form key/action/format、文件source；缺format可返回HTML200 | 必须固定JSON格式；HTTP200不等于成功 |
| CORS | ImgBB曾回通配；freeimage当次未见CORS头 | 是当次观察；本架构基于不向浏览器交付密钥，不依赖CORS现状 |
| 较大文件/chunked | 两家过去接受过较大图片和chunked | 不证明统一32MiB上限、永久支持或当前可用 |
| 短时突发 | 当次未触发限流 | 不证明无限流或无日配额 |
| 时延 | 来自测试机网络，不是1Mbps目标机 | 不用于本期SLA/内存验收 |
| 删除 | 未验证出本期可用程序化删除链 | remoteDelete=false，不推导平台永久无此功能 |

本次修订不增加这些探测的副作用；历史残留对象不视为已清理。当前实施范围仅上述两家，不再用范围外平台填补参数示例。

## 18. 验收矩阵与故障注入

以下为**实施后必须执行**的验收，不代表本文档阶段已运行通过。单元/集成测试默认使用本地可控transport、DNS和数据库fixture；真实网络用例显式开关及人工授权，不能随普通测试自动上传。

| 编号 | 场景 | 必须观察到的结果 |
|---|---|---|
| T01 | 旧七家配置保存、读取、ticket/register | 原契约保持；旧入口不能接受/修改CUSTOM |
| T02 | 嵌套profile往返、未知字段、重复键、模板错误 | 严格拒绝错误；未破坏旧字符串Map |
| T03 | 多secret KEEP/SET/CLEAR、新建掩码、损坏JSON | 没有明文出参/日志；不能用掩码建立凭据 |
| T04 | 换origin/path并KEEP旧密钥，组运营调用旧configs | 拒绝；不能把旧密钥发往新目标 |
| T05 | 禁用/缺失组，旧R2目录新增全局组，子目录创建 | 明确失败或保持原绑定；不静默回落 |
| T06 | Qt首配置/默认/ticket与管理员目录修改 | CUSTOM不进入Qt直传；configId可空不会导致NPE |
| T07 | 多管理员改成员/默认/目录模式 | CAS冲突，无丢更新；默认最多一个；NULL清理正确 |
| T08 | 无UPLOAD、匿名、PRIVATE/forcePRIVATE、超管超硬限 | 出站次数为0；业务豁免不绕过安全底线 |
| T09 | 伪MIME/SVG/超像素/未知尺寸/摘要不符 | 发送前拒绝；无磁盘图片缓存，无失败即跳过验证 |
| T10 | 缺长度/chunked/伪长度/慢正文、大量并发 | 准入前不缓存正文、缓冲有界、慢流按deadline结束、许可无泄漏 |
| T11 | 同键并发POST、同键不同元数据、重复PUT | 一操作一执行；不读重复正文、不宣称核验它 |
| T12 | 接收中取消/崩溃、旧lease晚到 | 旧处理者不能发送；安全重收只发生于未提交SENDING |
| T13 | A建连确定未发送、B成功；priority/RR/尝试上限 | 只在授权组内按稳定序列，最多2个唯一候选 |
| T14 | A写入后断线/5xx/坏JSON/无URL/恶意URL | UNKNOWN，B出站次数为0，客户端不自动换键 |
| T15 | 上游成功/错误无界响应、压缩炸弹、慢响应 | 边读边限/主动取消，内存不随响应无限增长 |
| T16 | DNS重绑定、私网/保留A、AAAA回退、代理、302 | 实际socket目的地受控；无代理/重定向/IPv6旁路；正常TLS校验 |
| T17 | 第一次SENDING提交前/后崩溃，回执保存失败 | 未发送可安全失败；已SENDING无证据即UNKNOWN，不恢复上传 |
| T18 | 回执已存、文件/审计写失败、恢复并发 | 只补本地登记，同operation一文件一计费，不重复远端上传 |
| T19 | 新旧链并发占最后一份额度、跨午夜、软删/移动 | 统一用量无超卖、无双计，新计费日和归属固定 |
| T20 | 发送前/后撤权、配置轮换、目录硬删 | 前者阻止发送；后者保存事实且不给越权URL；活动引用阻止硬删 |
| T21 | 删除/批删/download/permanent/visibility | 新家族不生成Telegram任务；PRIVATE拒绝；上游残留说明准确 |
| T22 | 日志、DTO异常、URL反射、导出 | 合成秘密哨兵不出现在任一日志/响应/普通导出；不使用真实凭据测试 |
| T23 | PG schema与predicate生成/MySQL导出 | 两个is_default=0可存、第二个1失败；MySQL明确拒绝；无旧索引破坏 |
| T24 | API域与web域专用代理、CORS/CSRF、繁忙/413 | 路径/头/响应语义一致，不把业务429当普通成功 |
| T25 | 上游成功但下游断线、刷新浏览器 | 查询原操作收敛到同一结果，不重传 |
| T26 | feature关闭、Redis/PG故障、恢复任务积压 | 不无限放行、不丢状态、旧文件可读、数据库失败前不出站 |
| T27 | 目标机单/双并发与普通API并行 | 记录p50/p95、峰值堆、吞吐/成功率/其他API延迟后再定容量 |
| T28 | 明确授权的最小真实冒烟 | 校验字段/URL/限流/残留；不把一次成功推广为永久可用或可删除 |

T20必须包含旧savePermissions清空授权与SENDING提交的并发交错，断言共锁/版本规则有效；T23必须经真实SchemaRegistry读取isUnique与predicate后生成DDL，不只验证JSON语法；T25另覆盖30–90秒内合法完成的上游响应，确认content PUT不再被共享client默认30秒超时提前取消。

Java实施按仓库规范跑格式检查和模块编译/测试；TS/TSX实施跑ESLint、tsc，界面/路由改动再跑npm build。文档修订本身不要求启动服务器、访问生产或伪造应用测试已通过。测试数量/代码行数不能替代上述断言。

## 19. 实施顺序、发布及回滚

| 阶段 | 交付范围 | 进入下一阶段的门槛 |
|---|---|---|
| D0 设计与契约 | 本文、类型/状态/权限/目录/配额契约、schema predicate | 无悬空关键选择；既有调用者清单完整 |
| D1 安全单渠道 | 专用DTO/脱敏、两步API、ACL/本地验证、前置准入、HC5绑定、持久状态/配额、mock故障 | T01–T04、T08–T12、T14–T20、T22通过；不靠真实图床证明正确性 |
| D2 最小组 | 三表/schema、CONFIG/GROUP/INHERIT、FAILOVER/RR、CAS/健康/默认、Qt保护 | T05–T07、T13、T19、T23通过 |
| D3 全链运营 | 全Provider入口矩阵、前端进度、权限/测试/恢复、监控、两个代理入口 | T21、T24–T26，端到端和旧客户端兼容通过 |
| D4 授权验证 | 目标机压力/最小真实冒烟、保留与回滚演练 | T27–T28及待登记/UNKNOWN处理预案；参数经测量确认 |

日志、准入、幂等、配额和出站安全属于D1，不作为D4补丁。D2之前不对外宣传组路由已经可用；实现期间可按feature flag封闭未完成路径。

发布前：

1. 预检数据/新索引名/字典码/序列水位，备份元数据；不读取或打印明文密钥作为“检查”。
2. 以新增迁移和同版代码部署新schema、predicate生成器、旧入口限制，feature关闭。
3. 运维批准目标；管理员配置渠道/组及PUBLIC测试目录，显式授予新权限。
4. 先本地故障矩阵，再经授权目标机/真实冒烟；逐步开放新UI入口。
5. 公告公共外链、结果未知和本地删除边界；保留旧目录固定绑定。

回滚优先关闭新上传而非删除新表：保留operation、配置、文件和安全回执，继续恢复已知成功登记。**不能直接回到完全不识别CUSTOM_HTTP及目录三态的旧二进制**并宣称可用；须保留读取/诊断兼容补丁，或在维护窗口完成经过验证的兼容回退。DDL不反向删除历史数据，旧客户端链仍通过回归验证。

## 20. 对 v1.0 评审的闭环与剩余验证

本表表示设计文本已给出处理方案，不代表对应实现缺陷已经修好；原问题排序保留，两轴不合并评分。

| 评审编号 | 本版设计处理 |
|---|---|
| S1 嵌套DTO/脱敏 | 第3–4节：独立新DTO/API与执行profile，旧Map保持；逐secret操作和安全View |
| S2 目录授权/归属 | 第6–9节：必填目录、显式ACL、稳定归属、操作/文件记录与配额 |
| S3 Provider分发 | 第12、16节：顶层全矩阵及Qt/批处理/移动接线 |
| S4 axios路径/错误 | 第7、13节：完整/api/v1、统一ApiResult、businessCode和状态判定 |
| S5 错误码 | 第7.5节：现有码含义纠正、新码登记与映射 |
| S6 nginx | 第14节：两server真实27000上游、转发头、缓冲/限流/超时 |
| S7 schema/部分索引 | 第5.5节：新旧JSON、受控predicate，旧正确索引不动 |
| R1 结果未知/重复上传 | 第7–10节：稳定操作、SENDING前持久化、UNKNOWN停重试、已知回执只补登记 |
| R2 前置准入/缓冲 | 第11节：准入先于正文、固定单缓冲、零等待、生命周期许可 |
| R3 响应限制位置 | 第11.5节：读取阶段计数、取消、禁自动解压 |
| R4 公共/私有矛盾 | 第1、6、12节：PUBLIC_ONLY，不能伪造撤权/远端删除 |
| R5 DNS重绑定 | 第4.3、11.5节：批准目标、解析结果交真实连接器、公共IPv4/TLS/禁旁路 |
| R6 凭据信任边界 | 第4、15节：origin/path绑定、分权、旧路由封闭、日志/反射防护 |
| R7 原策略未执行 | 第8–9、11节：共享校验、原子额度、本地MIME/像素，不强制none |
| R8 默认覆盖显式绑定 | 第6节：旧CONFIG不变、显式INHERIT、删除请求级组头 |

其他纠正：不保留无效weight/最近一小时伪统计；不把streaming一概认作不能重放；KB/KIB换算方向正确；不承诺未测时延/总堆；不将范围外供应商纳入当前设计；不用测试代码行数验收。

**尚需在实施阶段验证的不是新的开放设计选项，而是工程证据：**HC5实际版本/API与socket约束、Servlet异步慢流取消、图片格式验证器、PG迁移/权限数据、代理路由和目标机容量。任一关键安全边界无法通过测试，则该功能保持关闭，而不是静默降低安全要求。第三方真实契约、服务条款、账号限额、可用性和删除能力仍须在明确授权下单独核实。

## 21. 已知限制与后续迭代（2026-11-06 两轴审阅结论）

本版实现已完成 §20 之外的两轴审阅（Standards/Spec）并修复全部可就地收口的发现；以下为**已确认、但属结构级改动、显式延后**的项，避免与审阅修复混在同一提交：

| 编号 | 限制 | 现状与影响 | 后续方向 |
|---|---|---|---|
| L1 | DNS 解析 TOCTOU（§11.5 出站传输） | `rejectNonPublicHost` 预查走 `InetAddress.getAllByName`，JDK HttpClient 在 `CLIENT.send` 连接时重新解析，窗口内 DNS 重绑定可绕过公共 IPv4 校验。缓解边界：渠道须管理员在 approved-targets 白名单内显式批准，攻击面限于已批准域名被其 DNS 控制方（或中间人）操纵 | 迁移到 HostCustomizationResolver（HC5）把预查通过的 IP 锁进自定义 DnsResolver，消除二次解析；随 JDK 版本验证后实施 |
| L2 | REMOTE_SUCCEEDED 状态实际不可达 | 回执落库与文件登记同事务：插文件失败即整体回滚，状态永远无法独立停留。操作实际只经历 CREATED→RECEIVING→SENDING→COMMITTED/FAILED/EXPIRED/UNKNOWN_OUTCOME | 要么拆分「回执持久化」与「登记」为两段事务让状态真实可达，要么收敛状态机删除该状态与对应恢复分支；二选一，不维持死状态 |
| L3 | 旧上传链路额度未并入操作表记账 | 旧直传（presigned）不走 storage_upload_operation，日额度只统计两步上传路径 | 旧路径写入同构 quota_charge 行或在 sys_storage_file 侧补计 |
| L4 | 接收正文为同步阻塞读 | 无 20s 预算的阻塞 `readLine/readBody` 慢连接会占住 Tomcat 工作线程（并发由准入控制上限约束，不会无界膨胀） | Servlet 3.0 异步 + ReadListener，预算内未完成即取消 |
| L5 | 上传操作无独立审计事件 | 操作表自带状态与 failure_code，但未接审计日志模块（§15 的 upload 审计事件未实施） | 按 §15 定义补 audit 事件发布 |
| L6 | GIF/WebP 暂不开放 | 本地 magic 校验器仅实现 JPEG/PNG，画像 allowedMimes 已同步收窄（§11.3(5)），避免「画像放行、正文拒绝」死路 | 补齐两种格式的头校验后再放开画像选项 |

修复记录（就地收口）：PUBLIC_ONLY 目录门禁（STORAGE059，创建与接收双点校验）、EXPIRED 过期即退 HELD 配额、failover 排除已试渠道、上游大小不匹配不再误判 UNKNOWN、BASIC 认证字典行（V20261106004）、恢复任务补本进程 inflight 守卫、公网 IPv4 排除段补齐文档/基准段、前端幂等键改为文件绑定派生键。
