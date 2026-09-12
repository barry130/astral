# Astral 文件上传与云存储插件详细设计文档

- **文档版本**：1.0.0
- **文档状态**：设计确认稿
- **编写日期**：2026-09-10
- **建议模块**：`astral-plugin-storage`
- **建议插件 ID**：`storage`
- **目标读者**：Astral 后端、前端、插件开发和部署维护人员

---

## 1. 设计结论与已确认决策

本插件的核心目标不是提供一个仅供后台使用的上传页面，而是为 Astral 和其他 Astral 插件提供统一的文件能力层。

### 1.1 已确认决策

| 事项 | 决策 |
|---|---|
| 权限分层 | 不做复杂的多层级权限体系，采用扁平权限集合 |
| 隔离方式 | 不引入独立多租户模型，以文件夹作为主要权限边界 |
| 上传规模 | 首期只支持小文件，不实现分片、断点续传和大文件流程 |
| 文件可见性 | 支持公开文件和私有文件 |
| 防盗链 | 公开文件必须支持防盗链；优先使用签名 URL，可结合 Referer/Origin 白名单 |
| 存储类型 | 首期支持 Local、Cloudflare R2、S3 Compatible |
| 插件调用 | 其他 Astral 插件通过 `StorageService`/SPI 调用，不直接访问 SDK、密钥和表 |
| 云端配置 | 首期由管理员手动配置 R2/S3 凭证，不自动修改 Cloudflare CORS、Worker、DNS 或 IAM |
| 内部授权 | 使用插件 ID、Scope 和文件夹范围进行授权 |
| 外部应用 | 预留 OAuth 2.1 + PKCE 和应用 Token 能力，必须与存储厂商凭证隔离 |

### 1.2 首期非目标

以下内容不进入首期实现范围：

- 多租户实体、租户层级和组织树；
- 复杂的角色继承、ABAC 策略引擎和权限表达式；
- Multipart 分片上传；
- 断点续传和大文件恢复；
- 图片压缩、视频转码、音视频封面生成；
- 病毒扫描的具体实现，但保留扩展点；
- 自动创建或修改 Cloudflare Worker、R2 CORS、DNS、IAM；
- 跨云自动迁移和多活同步；
- 其他插件直接持有 Cloudflare/S3 密钥。

---

## 2. 与现有 Astral 工程的集成边界

当前 Astral 是 Java 21 + Spring Boot 多模块 Maven 项目，前端使用 Next.js 14、React 18、Ant Design 5 和 TypeScript。

### 2.1 现有可复用能力

- `astral-plugin-api`：插件 SPI、插件前端导航扩展和插件注册接口；
- `astral-plugin`：插件启停、状态持久化、插件 API 拦截和导航聚合；
- `astral-dao`：MyBatis-Plus 实体和 Mapper；
- `astral-schema`：表结构元数据和实体生成能力；
- `astral-auth`：管理端登录、权限检查、App Bearer Token 认证；
- `astral-log`：操作日志和登录日志；
- `astral-monitor`：Micrometer 指标；
- `astral-sequence`：统一 ID/序列生成；
- `astral-front`：后台布局、API client、权限过滤和插件导航。

### 2.2 模块结构建议

建议新增以下模块：

```text
astral-plugin-storage-api      # 其他插件可依赖的稳定 SPI 和 DTO
astral-plugin-storage          # 文件业务、Provider、Controller、授权、任务
```

如果不希望新增独立 API 模块，也可以将最小接口放入 `astral-plugin-api`，但推荐独立 `astral-plugin-storage-api`，原因如下：

1. 其他插件不需要依赖文件插件的 Controller、Mapper 和实现类；
2. SPI 版本可独立演进；
3. Provider SDK、数据库实体和后台实现不会泄漏到调用方；
4. 未来禁用或替换文件插件时，依赖边界更清晰。

### 2.3 依赖关系

```text
astral-plugin-storage-api
        ↑
其他 Astral 业务插件

astral-plugin-storage
        ├── astral-plugin-storage-api
        ├── astral-plugin-api
        ├── astral-common
        ├── astral-dao
        ├── astral-auth
        ├── astral-log
        ├── astral-monitor
        └── S3 SDK（仅实现模块使用）
```

其他插件不得依赖：

- AWS SDK/S3 SDK；
- Cloudflare SDK；
- `sys_storage_*` 表；
- `StorageProvider` 的厂商实现类；
- Provider Credentials。

---

## 3. 总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│                        Astral Frontend                       │
│  存储配置 │ 文件管理 │ 文件夹权限 │ 插件授权 │ 应用授权       │
└────────────────────────────┬─────────────────────────────────┘
                             │ REST API
┌────────────────────────────▼─────────────────────────────────┐
│                  astral-plugin-storage                       │
│                                                              │
│  Controller 层                                               │
│  ├── AdminStorageController                                  │
│  ├── StorageUploadController                                 │
│  ├── StorageDownloadController                               │
│  └── StorageOAuthController                                  │
│                                                              │
│  Application Service                                          │
│  ├── StorageFileService                                      │
│  ├── StorageFolderService                                   │
│  ├── StorageUploadSessionService                             │
│  ├── StorageAuthorizationService                             │
│  └── StorageConfigService                                    │
│                                                              │
│  Domain / SPI                                                 │
│  ├── StorageProvider                                         │
│  ├── StorageProviderRegistry                                 │
│  ├── StoragePermissionChecker                                │
│  ├── PresignedUrlService                                     │
│  └── AntiHotlinkService                                      │
│                                                              │
│  Provider 实现                                               │
│  ├── LocalStorageProvider                                    │
│  ├── S3CompatibleStorageProvider                             │
│  └── CloudflareR2StorageProvider（S3 Adapter 配置）           │
└───────────┬───────────────────────────────┬──────────────────┘
            │                               │
            │ 数据库                        │ 对象存储
┌───────────▼────────────┐       ┌──────────▼─────────────────┐
│ PostgreSQL              │       │ Local / R2 / S3 / MinIO    │
│ sys_storage_*           │       │ 文件对象                    │
└────────────────────────┘       └─────────────────────────────┘
```

### 3.1 设计原则

1. **文件业务与对象存储解耦**：业务服务只使用统一 Provider 接口。
2. **数据库保存元数据，不保存文件内容**：文件内容放在 Local 或对象存储中。
3. **密钥只在后端使用**：前端、其他插件和第三方应用不接触 Provider Credentials。
4. **权限先于 URL**：生成上传/下载 URL 之前必须检查身份、Scope 和文件夹权限。
5. **公开不等于无保护**：公开文件仍然可以要求签名、防盗链和访问频率控制。
6. **所有异步状态落库**：上传会话、删除任务和授权 Token 不能只保存在单节点内存。
7. **对象键由服务端生成**：用户输入的文件名仅用于展示。

---

## 4. 核心领域模型

### 4.1 StorageProvider

统一的存储提供者接口，建议定义在 `astral-plugin-storage-api` 或其稳定依赖模块中。

```java
public interface StorageProvider {

    String providerType();

    StorageCapabilities capabilities();

    ProviderHealthResult testConnection(StorageConfigView config);

    PresignedUploadResult createPresignedUpload(
            StorageConfigView config,
            PresignedUploadRequest request
    );

    PresignedDownloadResult createPresignedDownload(
            StorageConfigView config,
            PresignedDownloadRequest request
    );

    PutObjectResult putObject(
            StorageConfigView config,
            InputStream input,
            ObjectMetadata metadata
    );

    ObjectMetadata headObject(
            StorageConfigView config,
            String objectKey
    );

    void deleteObject(
            StorageConfigView config,
            String objectKey
    );
}
```

首期必需能力：

```text
PUT_OBJECT
HEAD_OBJECT
DELETE_OBJECT
PRESIGNED_UPLOAD
PRESIGNED_DOWNLOAD
TEST_CONNECTION
```

首期暂不强制：

```text
MULTIPART_UPLOAD
COPY_OBJECT
LIST_OBJECTS
OBJECT_VERSIONING
OBJECT_TAGGING
```

不支持的能力必须明确返回错误码 `STORAGE019`，不得静默执行替代操作。

### 4.2 StorageConfig

表示一套可复用的存储连接配置。

```text
configId
name
providerType
endpoint
region
bucketName
pathStyle
pathPrefix
publicBaseUrl
customDomain
credentialReference
status
isDefault
healthStatus
lastTestTime
lastTestMessage
createdBy
createdTime
updatedBy
updatedTime
version
```

### 4.3 StorageFolder

文件夹是文件权限和文件组织的主要边界。

```text
folderId
parentId
folderName
folderPath
storageConfigId
ownerType
ownerId
visibility
antiHotlinkPolicyId
inheritPermission
status
createdBy
createdTime
updatedBy
updatedTime
```

首期可支持树状文件夹，但不引入复杂的租户层级。`parentId` 只用于目录组织和可选的权限继承。

### 4.4 StorageFile

```text
fileId
folderId
storageConfigId
objectKey
originalName
extension
contentType
sizeBytes
checksum
etag
visibility
status
ownerType
ownerId
businessType
businessId
tagsJson
lastAccessTime
deletedTime
createdBy
createdTime
updatedBy
updatedTime
```

`objectKey` 示例：

```text
qt/2026/09/10/01JXYZ.../cover.webp
storage/2026/09/10/01JXYZ.../file.pdf
```

不允许使用以下内容直接作为 objectKey：

```text
../../secret.txt
C:\Windows\system.ini
用户原始文件名
未经规范化的 URL 路径
```

### 4.5 UploadSession

即使首期不做分片，也保留上传会话，用于防止客户端直接伪造完成状态，并为后续扩展留出空间。

```text
sessionId
fileId
folderId
storageConfigId
uploadMode
objectKey
expectedSize
expectedContentType
expectedChecksum
status
presignedUrl
presignedExpiresTime
expiresTime
completedTime
errorCode
errorMessage
createdBy
createdTime
updatedTime
```

状态：

```text
INITIATED -> UPLOADING -> COMPLETED
INITIATED -> EXPIRED
INITIATED -> CANCELLED
UPLOADING -> FAILED
UPLOADING -> EXPIRED
COMPLETED -> VERIFIED
```

`presignedUrl` 不建议明文保存到数据库；如果必须保存，仅保存摘要或加密密文，正常接口响应后不落日志。

---

## 5. 权限设计

### 5.1 文件夹权限

权限采用扁平集合：

```text
READ       读取元数据、生成下载地址
UPLOAD     创建上传会话、上传文件
UPDATE     修改元数据、移动文件、改变文件可见性
DELETE     删除文件
MANAGE     管理文件夹、授权主体、公开策略
```

授权关系：

```text
主体类型 + 主体 ID + 文件夹 ID + 权限集合
```

主体类型：

```text
USER
ADMIN
PLUGIN
APPLICATION
PUBLIC
```

### 5.2 权限判断顺序

所有文件操作统一执行以下顺序：

```text
1. 检查 storage 插件是否启用
2. 识别调用主体
3. 校验 Access Token / 管理员登录态 / 内部插件身份
4. 检查 Scope
5. 定位文件和文件夹
6. 检查文件夹状态和公开策略
7. 检查主体对文件夹的操作权限
8. 检查文件策略、配额和限流
9. 执行 Provider 操作
10. 写入审计日志
```

### 5.3 其他插件授权

其他插件不使用用户密码，也不共享管理员 Token。插件授权记录至少包含：

```text
pluginId
folderId
scopes
status
expiresTime
createdBy
createdTime
revokedTime
```

插件 Scope 建议：

```text
storage:file:upload
storage:file:read
storage:file:update
storage:file:delete
storage:file:list
storage:folder:manage
```

例如，音乐插件只需要：

```text
storage:file:upload
storage:file:read
```

则它不能执行删除，也不能访问其他插件的文件夹。

### 5.4 插件调用 API 的安全要求

推荐通过后端 SPI 调用：

```java
storageService.createUploadSession(
    StorageCaller.plugin("qt"),
    folderId,
    metadata
);
```

服务端必须从可信的插件注册信息中识别 `pluginId`，不能仅信任客户端提交的字符串。

如果插件需要通过 HTTP 调用，则需要使用内部调用凭证或短时内部 Token，并仍然执行 Scope 和文件夹范围校验。

---

## 6. 存储凭证与密钥管理

### 6.1 Provider Credentials

Cloudflare R2/S3 配置通常包含：

```text
Access Key ID / Access Key
Secret Access Key / Secret Key
Endpoint
Region
Bucket
```

这些凭证用于 Astral 代表系统访问对象存储。

首期需要 Provider Credentials 的场景：

- 生成预签名上传 URL；
- 生成预签名下载 URL；
- 服务端代理上传；
- 服务端代理下载；
- `HeadObject` 校验对象；
- 删除对象；
- 测试 Bucket 连接；
- 后台清理或检查对象。

客户端直传并不意味着不需要 Provider Credentials。客户端不接触凭证，但 Astral 必须使用凭证生成短时、限定 HTTP Method、对象键和 Content-Type 的预签名 URL。

### 6.2 Astral 加密主密钥

用于加密保存 Provider Credentials，不是 Cloudflare 发放的密钥。

建议配置：

```text
STORAGE_CREDENTIAL_ENCRYPTION_KEY
```

实现要求：

- 使用 AES-GCM 或等效认证加密算法；
- 数据库保存密文、Nonce/IV 和密钥版本；
- 前端只能看到脱敏值；
- 日志、异常和 SQL 日志不能打印原文；
- 支持密钥轮换；
- 密钥通过环境变量或部署密钥系统注入；
- 密钥缺失时，使用云存储的配置应处于不可用状态，而不是静默使用明文。

### 6.3 防盗链签名密钥

用于公开文件下载地址签名，例如：

```text
fileId
expires
path
signature
```

建议配置：

```text
STORAGE_PUBLIC_URL_SIGNING_SECRET
```

该密钥：

- 不具备 R2/S3 权限；
- 不能用于访问 Bucket；
- 只由下载网关验证；
- 只能由后端使用；
- 轮换时应允许短暂兼容旧版本签名。

### 6.4 第三方应用凭证

第三方应用使用：

```text
clientId
clientSecret
authorizationCode
accessToken
refreshToken
```

这些凭证只用于调用 Astral API，不用于直接访问 R2/S3。

关系如下：

```text
第三方应用 Token
    -> 调用 Astral Storage API
    -> 校验 Scope 和文件夹权限
    -> Astral 使用 Provider Credentials
    -> 访问 R2/S3
```

### 6.5 Cloudflare R2 最小权限建议

为 Astral 单独创建 R2 API Token，按实际功能授予最小权限：

```text
上传插件：PutObject
读取插件：GetObject / HeadObject
删除功能：DeleteObject
后台扫描：ListBucket
```

只允许上传的插件不能通过 Provider Credentials 获得删除权限。Provider Credentials 是存储配置级别的能力，插件权限必须在 Astral 业务层再次限制。

---

## 7. 上传设计

### 7.1 创建上传会话

请求：

```http
POST /api/v1/all/storage/upload/session
Authorization: Bearer <token>
Content-Type: application/json
```

请求体：

```json
{
  "folderId": 1001,
  "fileName": "cover.png",
  "contentType": "image/png",
  "sizeBytes": 245760,
  "checksum": "sha256:...",
  "visibility": "PRIVATE"
}
```

服务端处理：

1. 校验身份；
2. 校验调用主体的 `storage:file:upload` Scope；
3. 校验文件夹 `UPLOAD` 权限；
4. 校验文件夹是否启用；
5. 校验大小、扩展名、MIME 和文件策略；
6. 生成 File ID 和安全 objectKey；
7. 创建 UploadSession；
8. 调用 Provider 生成预签名上传 URL；
9. 返回会话信息。

响应：

```json
{
  "sessionId": "us_01JXYZ...",
  "fileId": "file_01JXYZ...",
  "uploadMode": "PRESIGNED",
  "uploadUrl": "https://...",
  "method": "PUT",
  "headers": {
    "Content-Type": "image/png"
  },
  "expiresAt": "2026-09-10T12:05:00Z"
}
```

### 7.2 完成上传

```http
POST /api/v1/all/storage/upload/session/{sessionId}/complete
Authorization: Bearer <token>
```

服务端必须：

1. 重新校验会话所属主体；
2. 校验会话未过期且状态正确；
3. 调用 `HeadObject`；
4. 校验对象存在；
5. 校验大小；
6. 校验 Content-Type；
7. 可选校验 ETag 或 SHA-256；
8. 校验 objectKey 与会话一致；
9. 更新文件状态为 `COMPLETED`；
10. 写入上传审计。

不能只相信客户端提交的“上传成功”。

### 7.3 服务端代理上传

```http
POST /api/v1/all/storage/upload
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

要求：

- 使用流式处理；
- 不一次性将文件全部加载到内存；
- 受到 Spring 请求体大小限制和插件文件策略双重限制；
- 写入对象成功后再保存最终文件记录，或使用明确的临时状态；
- 上传异常时删除临时对象并记录失败原因。

### 7.4 文件校验

最少校验：

```text
文件大小
扩展名白名单
Content-Type 白名单
文件名长度
文件名控制字符
magic bytes（可逐步增加）
```

文件名只用于展示，统一进行 Unicode 规范化和危险字符处理。

---

## 8. 公开/私有访问与防盗链

### 8.1 私有文件

私有文件是默认模式：

```text
visibility = PRIVATE
```

访问流程：

```text
请求下载地址
  -> 身份认证
  -> Scope 检查
  -> 文件夹 READ 权限
  -> 文件状态检查
  -> 生成短时效预签名 URL
  -> 返回 URL 或代理下载
```

私有文件不允许通过公开 URL 直接访问。

### 8.2 公开文件

公开文件可以通过公开地址访问，但仍需应用防盗链策略。

建议不暴露：

```text
https://account.r2.cloudflarestorage.com/bucket/raw-object-key
```

推荐使用：

```text
https://files.example.com/f/{fileId}
```

由 Astral 下载网关、CDN 或 Cloudflare Worker 负责：

- 解析文件 ID；
- 校验签名和过期时间；
- 检查 Referer/Origin；
- 检查访问频率；
- 再重定向到短时效对象 URL，或由网关代理响应。

### 8.3 防盗链模式

```text
NONE
REFERER
SIGNATURE
REFERER_AND_SIGNATURE
```

推荐：

- 内部测试：`NONE` 或 `REFERER`；
- 普通公开文件：`SIGNATURE`；
- 高价值公开资源：`REFERER_AND_SIGNATURE`。

Referer 不能作为唯一安全凭证，因为客户端可能不发送或伪造 Referer。私有文件应始终依赖身份、Scope、文件夹权限和短时效 URL。

### 8.4 签名 URL 设计

签名内容建议包含：

```text
HTTP method
normalized path
fileId
expiresAt
optional client binding
```

建议：

- URL TTL 默认 300 秒；
- 最大 TTL 不超过系统配置上限；
- 签名使用 HMAC-SHA256 或同等级算法；
- 访问失败统一返回 403，不泄露文件是否存在；
- 不把完整签名 URL 写入普通日志；
- 支持签名密钥版本号，便于轮换。

### 8.5 下载网关接口

```http
GET /api/v1/all/storage/files/{fileId}/download
```

或公开网关：

```http
GET /f/{fileId}
```

网关需要支持：

- Range 请求；
- Content-Disposition；
- Content-Type；
- ETag；
- Cache-Control；
- 防盗链失败 403；
- 限流；
- 下载审计或聚合计数。

---

## 9. 文件夹和文件管理 API

### 9.1 管理端 API

```text
GET    /api/v1/admin/plugin/storage/overview
GET    /api/v1/admin/plugin/storage/configs
GET    /api/v1/admin/plugin/storage/configs/{id}
POST   /api/v1/admin/plugin/storage/configs
PUT    /api/v1/admin/plugin/storage/configs/{id}
DELETE /api/v1/admin/plugin/storage/configs/{id}
POST   /api/v1/admin/plugin/storage/configs/{id}/test
POST   /api/v1/admin/plugin/storage/configs/{id}/enable
POST   /api/v1/admin/plugin/storage/configs/{id}/disable
POST   /api/v1/admin/plugin/storage/configs/{id}/default

GET    /api/v1/admin/plugin/storage/folders
POST   /api/v1/admin/plugin/storage/folders
PUT    /api/v1/admin/plugin/storage/folders/{id}
DELETE /api/v1/admin/plugin/storage/folders/{id}
GET    /api/v1/admin/plugin/storage/folders/{id}/permissions
PUT    /api/v1/admin/plugin/storage/folders/{id}/permissions

GET    /api/v1/admin/plugin/storage/files
GET    /api/v1/admin/plugin/storage/files/{id}
POST   /api/v1/admin/plugin/storage/files/{id}/download-url
PUT    /api/v1/admin/plugin/storage/files/{id}/visibility
DELETE /api/v1/admin/plugin/storage/files/{id}
POST   /api/v1/admin/plugin/storage/files/{id}/restore
POST   /api/v1/admin/plugin/storage/files/batch-delete

GET    /api/v1/admin/plugin/storage/tasks
POST   /api/v1/admin/plugin/storage/tasks/{id}/retry
GET    /api/v1/admin/plugin/storage/audit
```

### 9.2 业务和插件 API

```text
POST   /api/v1/all/storage/upload/session
POST   /api/v1/all/storage/upload/session/{sessionId}/complete
POST   /api/v1/all/storage/upload/session/{sessionId}/cancel
GET    /api/v1/all/storage/upload/session/{sessionId}
POST   /api/v1/all/storage/files/{id}/download-url
GET    /api/v1/all/storage/files/{id}
DELETE /api/v1/all/storage/files/{id}
GET    /api/v1/all/storage/folders/{id}/files
```

### 9.3 外部应用授权 API

外部应用不是首期文件 Provider 的凭证持有者。外部应用只获得 Astral API 访问令牌。

```text
POST   /api/v1/admin/plugin/storage/apps
GET    /api/v1/admin/plugin/storage/apps
PUT    /api/v1/admin/plugin/storage/apps/{id}
DELETE /api/v1/admin/plugin/storage/apps/{id}
POST   /api/v1/admin/plugin/storage/apps/{id}/revoke

GET    /api/v1/all/storage/oauth/authorize
POST   /api/v1/all/storage/oauth/token
POST   /api/v1/all/storage/oauth/revoke
```

建议采用授权码 + PKCE：

1. 应用跳转到授权页面；
2. 用户登录 Astral；
3. 用户查看应用名称、请求 Scope 和文件夹范围；
4. 用户同意或拒绝；
5. Astral 返回一次性授权码；
6. 应用使用 `code_verifier` 换取短时 Access Token；
7. Refresh Token 轮换、可撤销并绑定应用。

首期 Scope：

```text
storage:file:upload
storage:file:read
storage:file:update
storage:file:delete
storage:file:list
```

高风险权限需要单独确认：

```text
storage:file:delete
storage:folder:manage
```

---

## 10. 数据库设计

所有表名、字段类型和公共字段应遵循现有 Astral/PostgreSQL 规范，并纳入 `astral-schema` 元数据。

### 10.1 `sys_storage_config`

| 字段 | 说明 |
|---|---|
| `id` | 主键 |
| `name` | 配置名称 |
| `provider_type` | `LOCAL`、`S3`、`R2` |
| `endpoint` | Endpoint，必要时脱敏返回 |
| `region` | Region |
| `bucket_name` | Bucket |
| `path_style` | 是否使用 Path Style |
| `path_prefix` | 对象前缀 |
| `public_base_url` | 公共基础地址 |
| `custom_domain` | 自定义域名 |
| `credentials_ciphertext` | 凭证密文 |
| `credentials_nonce` | 加密 Nonce |
| `credential_key_version` | 加密密钥版本 |
| `status` | `ENABLED`、`DISABLED`、`ARCHIVED` |
| `is_default` | 默认配置标记 |
| `health_status` | `UP`、`DOWN`、`UNKNOWN` |
| `last_test_time` | 最近测试时间 |
| `last_test_message` | 测试结果摘要，不含密钥 |
| `created_by` | 创建人 |
| `created_time` | 创建时间 |
| `updated_by` | 更新人 |
| `updated_time` | 更新时间 |
| `version` | 乐观锁版本 |

约束：

- 同时只能有一个默认配置；
- 被文件引用的配置不能直接物理删除；
- Secret 更新时不回显旧值；
- 配置测试失败不能标记为健康。

### 10.2 `sys_storage_folder`

| 字段 | 说明 |
|---|---|
| `id` | 文件夹 ID |
| `parent_id` | 父文件夹 |
| `folder_name` | 名称 |
| `folder_path` | 展示路径 |
| `storage_config_id` | 使用的存储配置 |
| `owner_type` | 所有者类型 |
| `owner_id` | 所有者 ID |
| `visibility` | 文件夹默认可见性 |
| `anti_hotlink_policy_id` | 防盗链策略 |
| `inherit_permission` | 是否继承父级授权 |
| `status` | 状态 |
| `created_time` | 创建时间 |
| `updated_time` | 更新时间 |

### 10.3 `sys_storage_folder_permission`

| 字段 | 说明 |
|---|---|
| `id` | 主键 |
| `folder_id` | 文件夹 |
| `subject_type` | `USER`、`PLUGIN`、`APPLICATION` |
| `subject_id` | 主体 ID |
| `permissions_json` | 权限集合 |
| `expires_time` | 可选过期时间 |
| `created_by` | 授权人 |
| `created_time` | 创建时间 |
| `revoked_time` | 撤销时间 |

建议对：

```text
folder_id + subject_type + subject_id
```

建立唯一约束。

### 10.4 `sys_storage_file`

| 字段 | 说明 |
|---|---|
| `id` | 文件 ID |
| `folder_id` | 文件夹 |
| `storage_config_id` | 存储配置 |
| `object_key` | 对象键 |
| `original_name` | 原始文件名 |
| `extension` | 扩展名 |
| `content_type` | MIME |
| `size_bytes` | 字节数 |
| `checksum` | 校验和 |
| `etag` | Provider ETag |
| `visibility` | `PUBLIC`、`PRIVATE` |
| `status` | `UPLOADING`、`COMPLETED`、`FAILED`、`DELETED` |
| `owner_type` | 所有者类型 |
| `owner_id` | 所有者 |
| `business_type` | 业务类型 |
| `business_id` | 业务 ID |
| `tags_json` | 标签 |
| `last_access_time` | 最近访问 |
| `deleted_time` | 软删除时间 |
| `created_time` | 创建时间 |
| `updated_time` | 更新时间 |

### 10.5 `sys_storage_upload_session`

记录普通小文件上传会话，不包含分片字段也可以，但建议保留可扩展结构：

```text
id
session_id
file_id
folder_id
storage_config_id
upload_mode
object_key
expected_size
expected_content_type
expected_checksum
status
expires_time
completed_time
error_code
error_message
created_by
created_time
updated_time
```

### 10.6 `sys_storage_task`

用于删除失败、清理临时对象和后续异步任务：

```text
id
task_type
file_id
session_id
retry_count
next_retry_time
status
error_code
error_message
started_time
finished_time
created_time
updated_time
```

### 10.7 `sys_storage_policy`

```text
id
name
max_file_size
allowed_extensions_json
allowed_mime_types_json
url_ttl_seconds
public_url_ttl_seconds
rate_limit_json
retention_days
status
created_time
updated_time
```

### 10.8 `sys_storage_app`、`sys_storage_grant`、`sys_storage_token`

外部应用授权表分别保存：

- 应用基础信息和 Client ID；
- 应用对用户、Scope 和文件夹范围的授权；
- Access Token/Refresh Token 摘要、签发、过期、撤销和最后使用信息。

Token 建议只存哈希或加密密文，不在日志中记录原文。

### 10.9 `sys_storage_audit`

记录：

```text
配置创建、更新、删除、启停、测试
文件上传、完成、下载、删除、恢复
文件公开/私有状态变更
文件夹授权和撤销
插件授权和应用授权
Token 签发和撤销
防盗链失败
```

---

## 11. 插件 SPI 设计

### 11.1 调用方上下文

```java
public record StorageCaller(
        CallerType type,
        String callerId,
        String requestId
) {
    public static StorageCaller plugin(String pluginId) {
        return new StorageCaller(CallerType.PLUGIN, pluginId, null);
    }
}
```

### 11.2 公共服务接口

```java
public interface StorageService {

    UploadSessionResult createUploadSession(
            StorageCaller caller,
            Long folderId,
            UploadRequest request
    );

    FileObjectView completeUpload(
            StorageCaller caller,
            String sessionId
    );

    FileObjectView getFile(
            StorageCaller caller,
            Long fileId
    );

    DownloadUrlResult createDownloadUrl(
            StorageCaller caller,
            Long fileId,
            DownloadRequest request
    );

    void deleteFile(
            StorageCaller caller,
            Long fileId
    );

    boolean hasPermission(
            StorageCaller caller,
            Long folderId,
            StoragePermission permission
    );
}
```

### 11.3 调用约束

其他插件：

- 只传递业务需要的文件元数据；
- 不能自行指定任意 StorageConfig；
- 不能自行传入 objectKey；
- 不能跳过文件夹权限；
- 不能访问原始凭证；
- 不能绕过审计；
- 插件禁用后，新调用立即失败。

### 11.4 插件生命周期

`AstralPlugin` 实现：

```text
pluginId: storage
pluginName: 文件存储
apiPrefixes:
  /api/v1/admin/plugin/storage
  /api/v1/all/storage
  /api/v1/app/storage
```

启用时：

- 注册 Provider；
- 校验加密主密钥配置；
- 加载存储配置；
- 注册服务和指标；
- 启动必要的任务调度。

禁用时：

- 拒绝新的上传会话；
- 拒绝新的私有下载 URL；
- 拒绝其他插件的写入/删除调用；
- 已生成且仍有效的公开签名 URL可按策略继续处理；
- 正在进行的服务端上传应取消或标记失败；
- 不删除任何文件和配置。

---

## 12. 管理后台设计

建议使用插件导航扩展挂载：

```text
/dashboard/plugin/storage
/dashboard/plugin/storage/configs
/dashboard/plugin/storage/folders
/dashboard/plugin/storage/files
/dashboard/plugin/storage/authorizations
/dashboard/plugin/storage/policies
/dashboard/plugin/storage/audit
```

### 12.1 插件概览

展示：

- 插件启用状态；
- 默认存储；
- Provider 健康状态；
- 文件数量和总容量；
- 最近上传；
- 失败任务；
- 最近授权变更；
- 防盗链配置摘要。

### 12.2 存储配置页面

字段按 Provider 动态显示：

```text
配置名称
Provider 类型
Endpoint
Region
Bucket
Path Style
Path Prefix
Public Base URL
Custom Domain
Access Key
Secret Key
```

要求：

- Secret 默认不回显；
- 编辑时空值表示保持原值；
- 支持测试连接；
- 支持保存草稿；
- 支持启用/禁用；
- 支持设为默认；
- 显示健康状态和最后测试信息；
- 不能通过列表 API 获取明文凭证。

### 12.3 文件夹页面

支持：

- 创建、编辑、停用文件夹；
- 设置默认存储配置；
- 设置文件夹默认公开/私有；
- 设置防盗链策略；
- 管理用户、插件和应用授权；
- 查看文件数量和容量；
- 查看最近操作。

### 12.4 文件页面

列表展示：

```text
文件名
File ID
文件夹
MIME
大小
公开/私有
Provider
状态
所有者
创建时间
```

操作：

```text
预览
生成下载地址
复制地址
修改可见性
移动文件
软删除
恢复
彻底删除
查看审计
```

### 12.5 插件和应用授权页面

分别展示：

- 已授权插件；
- 插件可访问的文件夹；
- 插件 Scope；
- 外部应用；
- 已授予 Scope；
- 已授权文件夹；
- Token 最后使用时间；
- 撤销操作。

删除、文件夹管理和 Token 撤销属于高风险操作，需要二次确认。

---

## 13. 错误码

```text
STORAGE001  文件存储插件未启用
STORAGE002  存储配置不存在或已禁用
STORAGE003  存储连接测试失败
STORAGE004  默认存储未配置
STORAGE005  文件类型不允许
STORAGE006  文件大小超过限制
STORAGE007  文件配额不足
STORAGE008  上传会话不存在、过期或状态错误
STORAGE009  远端对象校验失败
STORAGE010  预签名 URL 生成失败
STORAGE011  远端上传失败
STORAGE012  远端删除失败，已进入重试
STORAGE013  无文件夹或文件访问权限
STORAGE014  调用主体未授权
STORAGE015  Scope 不足
STORAGE016  授权回调地址不匹配
STORAGE017  授权码或 Token 无效/已过期
STORAGE018  应用、插件或授权已禁用
STORAGE019  Provider 不支持该能力
STORAGE020  Endpoint 不安全或不在白名单
STORAGE021  文件已删除
STORAGE022  防盗链校验失败
STORAGE023  签名 URL 已过期
STORAGE024  存储凭证未配置或无法解密
STORAGE025  上传会话与调用主体不匹配
```

错误响应继续使用 Astral 现有 `Result` 和全局异常处理规范，不向客户端返回 Provider Secret、完整 Endpoint 凭证信息或底层异常堆栈。

---

## 14. 安全设计

### 14.1 文件安全

- objectKey 必须由服务端生成；
- 防止路径穿越和绝对路径；
- 校验扩展名、MIME 和文件头；
- 限制文件大小、文件名长度和请求体大小；
- 默认私有；
- 公开状态必须显式设置；
- 下载接口不能根据 objectKey 直接访问；
- 删除默认软删除并保留审计。

### 14.2 Endpoint 安全

自定义 S3 Endpoint 可能成为 SSRF 入口，必须：

- 只允许 `https`，Local Provider 单独允许本地路径；
- 拒绝 `localhost`、回环地址、内网地址和云元数据地址；
- 对 DNS 解析结果进行校验；
- 可配置域名白名单；
- 禁止任意协议和任意端口；
- 连接测试和实际 Provider 请求使用同一套校验。

### 14.3 URL 安全

- 预签名 URL 短时效；
- 限定 HTTP Method；
- 限定 objectKey；
- 限定 Content-Type 和大小条件；
- 不在日志打印完整 URL；
- 私有下载 URL 默认只返回一次使用或短时有效地址；
- 防盗链失败不泄露对象存在性。

### 14.4 权限安全

- 菜单权限不能代替文件夹资源权限；
- 只知道 File ID 不能访问文件；
- 插件不能伪造 pluginId 绕过授权；
- Token 只存哈希或密文；
- Client Secret 创建时只展示一次；
- 删除、授权、凭证更新必须审计；
- 插件禁用后，新的业务调用必须拒绝。

### 14.5 日志脱敏

允许记录：

```text
requestId
providerType
storageConfigId
folderId
fileId
sessionId
callerType
callerId
操作结果
耗时
错误码
```

禁止记录：

```text
Secret Key
Access Token 原文
Refresh Token 原文
Client Secret 原文
完整预签名 URL
完整对象存储授权请求
```

---

## 15. 可观测性与任务处理

### 15.1 Micrometer 指标

```text
storage_upload_total
storage_upload_failed_total
storage_upload_bytes_total
storage_download_url_total
storage_download_denied_total
storage_delete_total
storage_delete_failed_total
storage_provider_latency
storage_provider_error_total
storage_upload_session_active
storage_task_retry_total
storage_oauth_token_issued_total
storage_antihotlink_denied_total
```

指标标签应控制基数，不使用原始文件名、完整 URL 或用户输入作为标签。

### 15.2 异步任务

首期任务类型：

```text
DELETE_REMOTE_OBJECT
CLEANUP_EXPIRED_SESSION
CLEANUP_FAILED_OBJECT
RETRY_PROVIDER_OPERATION
```

任务要求：

- 指数退避；
- 最大重试次数；
- 可区分可重试和不可重试错误；
- 管理端可查看和手动重试；
- 任务状态落库；
- 集群环境不能重复执行造成数据损坏；
- 任务执行写入审计。

---

## 16. 配置项设计

建议在 `application.yml` 或系统配置中提供：

```yaml
astral:
  plugins:
    storage:
      enabled: true
      max-file-size: 20MB
      upload-session-ttl-seconds: 600
      private-download-url-ttl-seconds: 300
      public-download-url-ttl-seconds: 300
      public-signing-secret: ${STORAGE_PUBLIC_URL_SIGNING_SECRET:}
      credential-encryption-key: ${STORAGE_CREDENTIAL_ENCRYPTION_KEY:}
      endpoint-allow-list: []
      endpoint-deny-private-network: true
      default-visibility: PRIVATE
      default-anti-hotlink-mode: SIGNATURE
      enable-provider-health-check: true
      health-check-interval-seconds: 300
```

敏感配置建议只通过环境变量或外部 Secret 注入，不进入前端构建产物。

---

## 17. 测试设计

### 17.1 单元测试

必须覆盖：

1. ProviderRegistry 按 Provider 类型选择实现；
2. R2 配置正确映射到 S3 Client；
3. Endpoint 白名单和 SSRF 防护；
4. objectKey 生成；
5. 文件扩展名、MIME、大小和文件名校验；
6. 文件夹权限判断；
7. Plugin Scope 判断；
8. 上传会话状态转换；
9. 重复 complete 的幂等性；
10. 预签名 URL TTL 和方法限制；
11. 防盗链签名生成和校验；
12. 密钥加密、解密和脱敏；
13. Token 撤销后不能继续调用；
14. 插件禁用后调用被拒绝。

### 17.2 集成测试

建议使用 Testcontainers MinIO：

1. Local Provider 上传、读取、删除；
2. S3 Compatible Provider 上传、HeadObject、删除；
3. PostgreSQL 表结构和唯一约束；
4. 并发创建上传会话；
5. 删除失败进入任务表；
6. 文件夹权限和插件授权；
7. 管理端 API 权限；
8. OAuth 授权码只能使用一次；
9. Access Token 过期和撤销；
10. Plugin API Interceptor 与插件启停联动。

### 17.3 验收测试

1. 配置 Cloudflare R2 后可以连接测试；
2. 小文件可以通过服务端上传；
3. 小文件可以通过预签名 URL 直传；
4. 客户端始终无法获得 R2 Secret；
5. 完成上传时服务端会校验远端对象；
6. 私有文件没有文件夹 READ 权限时无法获取下载 URL；
7. 公开文件的防盗链签名错误时返回 403；
8. 签名 URL 过期后不能访问；
9. 其他插件只能访问被授权文件夹；
10. 其他插件没有 DELETE Scope 时无法删除文件；
11. 删除远端失败时管理端可以看到失败任务并重试；
12. 禁用插件后新的上传、下载地址和删除请求被拒绝；
13. 文件公开/私有切换和授权变更均有审计记录。

---

## 18. 实施拆分

### 阶段 1：API 与 SPI

- 建立 `astral-plugin-storage-api`；
- 定义 `StorageService`、`StorageProvider`、DTO 和错误码；
- 定义文件、文件夹和上传会话状态；
- 确定 Provider capability；
- 确定权限和 Scope。

### 阶段 2：插件骨架和 Local Provider

- 新增 Maven 模块；
- 注册 `AstralPlugin`；
- 接入插件启停；
- 实现 Local Provider；
- 完成文件夹、文件元数据、上传会话；
- 完成基础 REST API。

### 阶段 3：R2/S3 Provider

- 引入 S3 SDK，仅放在实现模块；
- 实现 R2/S3 配置；
- 实现预签名上传/下载；
- 实现 HeadObject、删除和连接测试；
- 使用 MinIO 完成集成测试；
- 增加 Cloudflare R2 配置文档。

### 阶段 4：权限、公开访问和防盗链

- 实现文件夹权限；
- 实现插件 Scope；
- 实现公开/私有文件；
- 实现签名 URL；
- 实现 Referer/Origin 白名单；
- 实现下载网关和限流；
- 接入审计。

### 阶段 5：管理端

- 存储配置；
- 文件夹和权限；
- 文件列表；
- 失败任务；
- 审计查询；
- 插件导航和前端权限。

### 阶段 6：外部应用授权

- 应用注册；
- 授权确认页；
- OAuth 授权码 + PKCE；
- Scope 和文件夹授权；
- Token 轮换、撤销和审计。

### 阶段 7：稳定性和安全加固

- SSRF 防护；
- 密钥轮换；
- Provider 健康检查；
- 任务重试；
- 集群并发；
- 限流和指标；
- 部署文档和故障恢复文档。

---

## 19. 部署与 Cloudflare R2 配置说明

部署前需要准备：

```text
R2 Account Endpoint
R2 Bucket
R2 API Token
R2 Access Key ID
R2 Secret Access Key
Astral Credential Encryption Key
Astral Public URL Signing Secret（启用公开防盗链时）
```

建议生产配置步骤：

1. 在 Cloudflare R2 创建专用 Bucket；
2. 创建只用于 Astral 的 API Token；
3. 按功能授予最小 Bucket 权限；
4. 将凭证通过部署 Secret 注入 Astral；
5. 在 Astral 控制台创建 StorageConfig；
6. 执行连接测试；
7. 设置默认存储；
8. 创建文件夹；
9. 为其他插件授予文件夹和 Scope；
10. 配置自定义域名和防盗链模式；
11. 使用测试文件执行公开、私有、删除和失败重试验收。

首期不自动操作 Cloudflare 控制台，因此：

- R2 Bucket CORS 由部署人员手动配置；
- 自定义域名由部署人员手动配置；
- Cloudflare Worker/CDN 规则由部署人员手动配置；
- Astral 只负责保存和执行自身的访问策略。

---

## 20. 风险与处理方案

| 风险 | 处理方案 |
|---|---|
| 客户端伪造上传完成 | complete 时调用 HeadObject 二次校验 |
| R2 凭证泄露 | 加密保存、最小权限、前端不可见、日志脱敏 |
| 公开文件被盗链 | 签名 URL、Referer/Origin 校验、CDN/网关限流 |
| 文件 ID 枚举 | 使用不可预测 ID，并执行文件夹权限校验 |
| 自定义 Endpoint SSRF | HTTPS、域名白名单、私网拒绝、解析结果校验 |
| 远端删除失败 | 软删除 + 任务重试 + 管理端人工处理 |
| 插件越权访问 | 插件身份可信识别 + Scope + 文件夹范围三重校验 |
| 单节点状态丢失 | 上传会话、Token 和任务全部落库 |
| Provider API 差异 | Provider Capability，不支持时返回明确错误 |
| 公开 URL 永久有效 | 默认短 TTL，避免直接暴露原始 Bucket 地址 |
| 文件恶意内容 | 首期预留扫描接口，后续接入杀毒或内容审核 |

---

## 21. 第一版验收标准

当以下条件全部满足时，认为首版设计实现完成：

1. Astral 可以独立启用和禁用 `storage` 插件；
2. 管理员可以配置 Local、Cloudflare R2 和 S3 Compatible 存储；
3. 存储凭证不会以明文出现在数据库、API、前端和日志中；
4. 管理员可以测试存储连接并查看健康状态；
5. 管理员可以创建文件夹并授予用户、插件或应用权限；
6. 其他 Astral 插件可以通过 `StorageService` 创建小文件上传会话；
7. 浏览器或插件可以通过预签名 URL 完成直传；
8. Astral complete 接口会校验远端对象；
9. 文件可以设置为公开或私有；
10. 私有文件必须经过文件夹权限检查；
11. 公开文件支持签名防盗链；
12. Referer/Origin 白名单可以作为附加防盗链条件；
13. 其他插件不能访问未授权文件夹；
14. 文件删除失败会进入可重试任务；
15. 关键操作全部写入审计日志；
16. 禁用插件后新的上传、私有下载和删除调用会被拒绝；
17. 其他模块不需要直接依赖 R2/S3 SDK；
18. 使用 MinIO 的集成测试通过；
19. 文档中提供 R2 所需环境变量、权限和部署步骤；
20. 首期没有引入多租户实体、复杂权限分层、分片上传和大文件流程。

---

## 22. 待编码前最终确认项

以下事项不影响整体架构，但编码前需要确定默认值：

1. 默认小文件上限选择 `10 MiB` 还是 `20 MiB`；
2. 首期文件夹是否支持多级目录，还是仅支持一级目录；
3. 公开文件默认防盗链模式选择 `SIGNATURE` 还是 `REFERER_AND_SIGNATURE`；
4. 是否首期就实现外部应用 OAuth 2.1 + PKCE，还是先实现内部插件授权；
5. 是否所有文件默认私有；
6. 软删除保留时间；
7. R2 Endpoint 是否只允许 Cloudflare R2 域名，还是开放经过白名单校验的 S3 Compatible Endpoint；
8. 是否在首期实现真正的下载代理，还是只返回预签名下载 URL；
9. Secret 加密主密钥的具体注入方式；
10. 文件夹权限是否默认继承父文件夹权限。

在这些默认值确定后，可以进入模块骨架、表结构和 API 契约编码阶段。
