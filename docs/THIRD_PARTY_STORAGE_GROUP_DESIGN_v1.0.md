# 第三方图床存储与「组 + 策略」详细设计

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 日期 | 2026-11-05 |
| 状态 | 设计评审中（未实施） |
| 影响模块 | `astral-plugin` / `astral-server` / `astral-front` |
| 涉及 Provider | `CUSTOM_HTTP`（新增家族） |
| 首批渠道 | ImgBB、freeimage.host |

---

## 1. 背景与目标

### 1.1 问题

Astral 现有 storage 插件共 7 个 Provider（TELEGRAM / R2 / S3_COMPATIBLE / QINIU / COS / OSS / UPYUN），全部属于**浏览器直传**架构：

```
浏览器 ──PUT/POST──> 上游存储         （文件正文）
浏览器 ──POST /files/register──> Astral（仅回执，几 KB）
```

Astral 服务端**从不接触文件正文**（`astral/STORAGE_REQUIREMENTS.md` §3.1 的硬约束）。这带来两个能力缺口：

1. **无法接入只提供"服务端到服务端"HTTP API 的图床**。ImgBB、freeimage.host 这类站点虽然也接受浏览器直传，但：
   - freeimage.host **完全不返回 CORS 头**，浏览器直传物理上不可能；
   - ImgBB 虽然返回 `Access-Control-Allow-Origin: *`，但 API key 必须出现在请求中，直接给到前端即等于公开；
   - 两者的响应结构、鉴权位置、字段名、成功判定各不相同，硬编码会迅速失控。
2. **没有「组」的概念**。一个存储配置就是一个固定渠道，没有多渠道路由、没有失败转移、没有负载均衡。免费图床天然不稳定（限流、下线、清图），单渠道体验很差。

### 1.2 目标

| 编号 | 目标 | 验收方式 |
|---|---|---|
| G1 | 新增 `CUSTOM_HTTP` Provider 家族，通过**纯配置**接入任意「multipart 上传 → JSON 返回 URL」的图床，不改代码即可新增渠道 | 用两份不同的 `provider_options` 分别打通 ImgBB 与 freeimage.host |
| G2 | 差异部分（端点 / 方法 / 鉴权位置 / 请求头 / 文件字段 / 附加字段 / 响应取值路径 / 成功条件 / 大小单位）全部参数化 | 见 §6 字段规范，覆盖两家全部差异点 |
| G3 | 支持将多个渠道组成一个「存储组」，按策略调度 | 见 §8 策略实现 |
| G4 | 支持失败转移（FAILOVER）与轮询（ROUND_ROBIN）等策略 | 关停一个渠道后上传仍成功，且落库记录了实际命中的渠道 |
| G5 | 密钥仅存在于服务端，永不下发浏览器，且读接口打码 | ticket / 响应中 grep 不到明文密钥 |
| G6 | 在 1 核 2G / 1 Mbps 的部署机上可用 | 单张 300 KB 图片端到端 ≤ 5 秒，内存增量 ≤ 2 MiB |

### 1.3 非目标

- **不**支持 `tc.mofashi.ltd`（本版本按决策排除；其 `sizeUnit=KiB` 差异点仍保留在模型中，后续可加，见附录 B.3）。
- **不**实现图片删除的真实调用。ImgBB 与 freeimage.host 均**不提供可用的程序化删除接口**（实测结论见附录 B.4），故 `capabilities.delete` 恒为 `false`，删除语义降级为"标记删除 + 直链继续有效"。
- **不**做图片 CDN 回源代理。第三方渠道的图片访问**一律 302 到上游直链**，服务端不参与字节传输。
- **不**支持多实例部署下的全局并发控制（现网为单实例，见 `StorageConfigCache` 类注释），并发闸门用本地 `Semaphore` 实现。
- **不**支持非 multipart 的上传形态（如 PUT 裸字节、签名 URI），模型预留 `body.mode` 字段但本期只实现 `MULTIPART`。

---

## 2. 现状盘点

### 2.1 现有 Provider 家族

全部收敛在 `astral-plugin/src/main/java/com/astral/storage/service/ProviderSupport.java`（48 行），以 5 个布尔方法表达家族归属：

| 方法 | 语义 | 成员 |
|---|---|---|
| `isS3Family` | AWS SigV4 | R2 / S3_COMPATIBLE / QINIU |
| `isPresignedPutFamily` | 浏览器 PUT 预签名 URL | S3 家族 + COS + OSS |
| `isRegisterFamily` | 浏览器回执登记 | 预签名家族 + UPYUN |
| `isServerManagedFamily` | 服务端可直接操作对象 | `isRegisterFamily` |
| `isPermanentUrlFamily` | 支持永久公开链接 | `isRegisterFamily` |

> 类注释「新增 Provider 只需在此登记常量与家族归属」与实际不符：除本类外，还需在 `UploadTicketService`、`StorageFileService`、`StorageConfigService` 的 if/else 分支中分别登记，共 4 处。

### 2.2 关键文件与职责

| 文件 | 行数 | 职责 |
|---|---|---|
| `storage/security/UploadTicketService.java` | 430 | 签发上传凭证（ticket = base64url(payload) + "." + HMAC-SHA256） |
| `storage/service/StorageFileService.java` | 1000 | 登记、校验、删除、签名、任务 |
| `storage/service/StorageConfigService.java` | 443 | 配置 CRUD、密钥打码、连通性测试 |
| `storage/service/ProviderSupport.java` | 48 | Provider 家族归类 |
| `storage/service/StorageConfigCache.java` | 66 | Caffeine 缓存（TTL 60s，max 64） |
| `storage/service/UploadPolicyService.java` | 238 | 目录策略（大小/MIME/配额/校验级别） |
| `storage/entity/StorageConfigEntity.java` | 79 | 配置实体 |
| `storage/entity/StorageFileEntity.java` | — | 文件实体 |
| `storage/entity/StorageFolderEntity.java` | — | 目录实体 |
| `storage/controller/StorageUserController.java` | — | 用户侧端点（`/api/v1/all/storage`） |
| `storage/controller/AdminStorageController.java` | — | 管理侧端点（`/api/v1/admin/plugin/storage`） |
| `ai/tool/declarative/HttpToolExecutor.java` | 568 | **声明式 HTTP 执行器**（模板 + SSRF 守卫，本设计复用其模式） |

### 2.3 现有约束

1. **ticket payload 是浏览器可读的**：`UploadTicketService.TicketPayload`（L328）以 base64url 明文承载 `configId / folderId / chatId / maxSize / mime / uploaderId / exp / nonce / providerType / fileName / publicId / objectKey / verifyContent / adminExempt`。**任何密钥都不得写入 payload**。
2. **单实例部署**：`StorageConfigCache` 注释明确「单实例部署，无跨进程一致性问题」。
3. **`sys_storage_config.is_default` 上有唯一索引** `uk_storage_config_default`，全表最多一个 `is_default=1`。**组的默认标记不能复用该机制**，需另设字段或在组表上建独立唯一索引。
4. **现有 `maxFileSize` 默认 20 MiB**（`StorageProperties.maxFileSizeBytes = 20L * 1024 * 1024`），对服务端代传是灾难（见 §3.2），必须按渠道下沉覆盖。

---

## 3. 硬约束：部署环境

### 3.1 实测硬件与配置

部署目标为校园云服务器：**1 核 / 2 GiB / 1 Mbps 带宽 / 50 GB 云盘**。

现仓库已针对该配置调优，**无需调整 JVM**：

| 位置 | 现有配置 | 说明 |
|---|---|---|
| `astral-server/Dockerfile` | `-Xms384m -Xmx384m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC` | 注释明确「面向 1核/2GiB 学生机」 |
| `application-prod.yml:9` | `Tomcat max-threads=40, Hikari pool=5` | 注释「1核2G 上避免过度配」 |
| `application-prod.yml` | 虚拟线程已启用 | 阻塞 I/O 不占平台线程 |
| `application.yml:15-20` | `max-file-size: 50MB` / `max-request-size: 60MB` / **`resolve-lazily: true`** | 未进入 `MultipartFile` 参数则完全不解析、不落盘 |
| `application.yml:120` | `max-file-size-bytes: ${STORAGE_MAX_FILE_SIZE:20971520}` | 20 MiB 全局默认 |

### 3.2 由约束推导出的设计常量

**带宽约束**：1 Mbps ≈ 125 KB/s。一条上传要占两趟（浏览器→Astral、Astral→上游），共享同一条线路。

| 单张大小 | 单趟 | 端到端 | 判定 |
|---|---|---|---|
| 100 KB | 0.8 s | 1.6 s | 舒适 |
| 200 KB | 1.6 s | 3.2 s | 舒适 |
| 500 KB | 4 s | 8 s | 可接受 |
| **1 MiB** | **8.4 s** | **17 s** | **上限** |
| 2 MiB | 17 s | 34 s | 用户以为卡死 |
| 5 MiB | 42 s | 84 s | 不可用 |

**内存约束**：堆仅 384 MB，`UseSerialGC` 单线程。

| 并发 | 1 MiB 缓冲 | 占堆 | 20 MiB 缓冲 | 占堆 |
|---|---|---|---|---|
| 2 | 2 MiB | 0.5% | 40 MiB | 10%（会引发明显 GC 停顿） |

> **两条彼此独立的推导（带宽上限、堆占比）指向同一个数字：1 MiB。** 因此 §6 的 `limits.maxBytes` 对第三方渠道**强制**为 `1048576`，不继承全局 20 MiB。

**CPU 约束**：1 Mbps 下 TLS 开销可忽略（单核 AES-GCM 可达数百 MB/s）。真正的 CPU 风险只有两处，均须规避：

1. `ImageIO.read()` 全量解码大图（单核数百毫秒~数秒）；
2. `StorageFileService.fetchContentBytes`（L379）经 HTTP Range 回读最多 `MAX_VERIFY_BYTES = 8 MiB`。

因此第三方渠道**强制 `verifyContent = NONE`**（`UploadPolicyService.VERIFY_NONE`）。

### 3.3 一个反直觉的推论：1 Mbps 机器**必须**用第三方图床

| | 自建图床（存本地 50 GB） | 第三方图床 |
|---|---|---|
| 上传 | 1 趟带宽 | 2 趟带宽 |
| **每次访问图片** | **吃 1 Mbps** | **0**（直连上游） |
| 磁盘 | 占 50 GB | 不占 |

一张 300 KB 封面若日曝光 200 次：自建 = 60 MB/天（每天 8 分钟带宽，且是突发），第三方 = 0。

**访问是高频、上传是低频**——1 Mbps 线路最怕高频访问。因此第三方图床在本环境下不是"省事选项"，是**架构正解**。

### 3.4 结论：瓶颈不是总量，是单张延迟

每天 100 张 300 KB 图 = 30 MB ÷ 125 KB/s = **240 秒 = 一天的 0.28%**。带宽总量根本不是问题。

真正的体验问题只有一个：**那张图要等 5 秒**。故 §11 的前端压缩是本设计**不可省略**的一环。

---

## 4. 总体架构

### 4.1 数据流（新增路径）

```
┌──────────┐   ①压缩后裸字节(≤1MiB)    ┌─────────────────┐
│ 浏览器    │ ────────────────────────> │ Astral (1核2G)  │
│ (压缩+进度)│ <──────────────────────── │                 │
└──────────┘   ⑥结果(JSON, ~1KB)        └────────┬────────┘
                                                │ ②解析组→过滤→排序
                                                │ ③内存缓冲(≤1MiB)
                                                │ ④组装 multipart 发送
                                                ▼
                                     ┌──────────────────────┐
                                     │ ImgBB / freeimage.host│
                                     └──────────┬───────────┘
                                                │ ⑤JSON 响应
                                                ▼
                                             落库（直链 + 渠道 + 组）
                                                │
┌──────────┐   ⑦图片访问：302 到上游直链  ┌───────┴────────┐
│ 任意访客  │ ──────────────────────────> │ Astral         │
└──────────┘   （字节流不经过 Astral）     └────────────────┘
```

**关键点**：第 ⑦ 步是 302 而不是代理。这是 1 Mbps 下第三方渠道的核心价值，任何情况下都不能改成服务端拉流。

### 4.2 分层

```
Controller   StorageUserController.uploadFile()        ← 新增
             AdminStorageController.*Group*()          ← 新增
                    │
Orchestrator GroupUploadOrchestrator                   ← 新增
                    │
Service      UploadStrategyResolver  ← 候选过滤/排序/计数
             CustomHttpUploadService ← 组装·发送·解析
             StorageGroupService     ← 组 CRUD
             StorageFileService      ← 落库（复用，小改）
             UploadPolicyService     ← 配额（复用）
                    │
Infra        ProviderProfileParser / UrlTemplateRenderer / ThirdPartyHealthProbe
             StorageGroupCache / SysHttpClient
```

---

## 5. 核心概念模型

| 概念 | 载体 | 说明 |
|---|---|---|
| **Provider 家族** | `sys_storage_config.provider_type = 'CUSTOM_HTTP'` | 一个家族，所有差异在 options 里 |
| **渠道（Channel）** | `sys_storage_config` 一行 | 一个具体的图床账号（一个 key = 一个配额池） |
| **组（Group）** | `sys_storage_group` 一行 | 一个命名的渠道集合 + 一条策略 |
| **组成员** | `sys_storage_group_item` | `(group_id, config_id, weight, priority)` |
| **策略（Strategy）** | `sys_storage_group.strategy` | FAILOVER / ROUND_ROBIN / WEIGHTED_RANDOM / LEAST_USED / HEALTH_FIRST |
| **命中渠道** | `sys_storage_file.storage_config_id` | 实际成功的那一个，**必须落库**，否则删除/审计无法定位 |

> **重要**：`sys_storage_file.storage_config_id` 记录的是**实际命中**的渠道，不是组。组记录在新增的 `storage_group_id` 列，仅用于统计与审计。

---

## 6. 第三方 API 参数化模型（核心）

### 6.1 差异点枚举

设计前先把"两家到底哪里不一样"穷举清楚。以下全部来自**实测**（附录 B）：

| # | 差异点 | ImgBB | freeimage.host |
|---|---|---|---|
| D1 | 端点 | `https://api.imgbb.com/1/upload` | `https://freeimage.host/api/1/upload` |
| D2 | 必需的查询参数 | — | `?format=json`（缺了返回 **HTML 页面**） |
| D3 | 鉴权位置 | **查询参数** `?key=` | **表单字段** `key` |
| D4 | 文件字段名 | `image` | `source` |
| D5 | 额外表单字段 | — | `action=upload`、`format=json` |
| D6 | 成功判定 | `success === true`（+ HTTP 200） | `status_code === 200` |
| D7 | 直链路径 | `data.url` | `image.url` |
| D8 | 缩略图路径 | `data.thumb.url` | `image.thumb.url` |
| D9 | 尺寸路径 | `data.size`（**数字字符串**） | `image.size`（数字） |
| D10 | 上游标识 | `data.id` | `image.id_encoded` |
| D11 | 删除链接 | `data.delete_url`（**是网页，不可编程**） | **无** |
| D12 | 错误信息路径 | `error.message` | `error.message` |
| D13 | 扩展名 | `data.image.extension`（`"png"`） | `image.extension`（**数字 2**），真实值在 `image.extension_name` |
| D14 | 单文件上限 | 32 MiB（15.14 MiB 实测通过） | 32 MiB |
| D15 | 删除能力 | ✗（`POST https://ibb.co/json` → 401） | ✗ |
| D16 | CORS | `*`（对本设计无意义，已走服务端） | 无 |

> D9 的数字字符串、D13 的"数字扩展名"这类坑，说明**取值函数必须做类型宽容**（见 §6.6）。

### 6.2 ProviderProfile 结构

`sys_storage_config.provider_options` 在 `provider_type = 'CUSTOM_HTTP'` 时存以下 JSON：

```jsonc
{
  "schemaVersion": 1,
  "preset": "IMGBB",                  // 可选，决定未填字段的默认值

  "endpoint": "https://api.imgbb.com/1/upload",
  "method": "POST",

  "query":    { },                    // 静态查询参数（值支持模板）
  "headers":  { },                    // 静态请求头（值支持模板）

  "auth": {
    "mode": "QUERY",                  // QUERY | HEADER | FORM | NONE
    "name": "key",
    "value": "${secret:apiKey}"
  },

  "body": {
    "mode": "MULTIPART",              // 本期仅 MULTIPART
    "fileField": "image",
    "fileNameField": null,            // 可选：把文件名单独作为一个表单字段
    "extraFields": { }                // 静态表单字段（值支持模板）
  },

  "response": {
    "successWhen": [ { "path": "success", "op": "EQ", "value": true } ],
    "urlPath": "data.url",
    "thumbPath": "data.thumb.url",
    "sizePath": "data.size",
    "sizeUnit": "BYTES",              // BYTES | KIB | KB
    "widthPath": "data.width",
    "heightPath": "data.height",
    "upstreamIdPath": "data.id",
    "deleteUrlPath": "data.delete_url",
    "errorPath": "error.message"
  },

  "limits": {
    "maxBytes": 1048576,              // 见 §3.2，强制 ≤1 MiB
    "minBytes": 0,
    "allowedMimePrefixes": ["image/"],
    "allowedMimeTypes": []
  },

  "capabilities": { "delete": false, "thumbnail": true, "permanentUrl": true },

  "timeouts": { "connectMs": 5000, "readMs": 60000 },

  "secrets": { "apiKey": "******" }   // 读接口打码；写入时传 "******" 表示保留原值
}
```

### 6.3 字段规范

#### 顶层

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `schemaVersion` | int | 否 | `1` | 用于未来兼容迁移 |
| `preset` | string | 否 | `CUSTOM` | `IMGBB` / `FREEIMAGE` / `CUSTOM`；决定未填字段的默认值，**显式字段永远优先** |
| `endpoint` | string(512) | **是** | — | 必须 `https://`（`http://` 仅在 `allowInsecure=true` 时放行，仅供内网调试） |
| `method` | string(8) | 否 | `POST` | 本期仅 `POST` |
| `query` | map | 否 | `{}` | 静态查询参数 |
| `headers` | map | 否 | `{}` | 静态请求头；`Host`/`Content-Length`/`Content-Type` 被强制剔除 |
| `auth` | object | 否 | `NONE` | 见下 |
| `body` | object | **是** | — | 见下 |
| `response` | object | **是** | — | 见下 |
| `limits` | object | 否 | `{}` | 见下 |
| `capabilities` | object | 否 | 全 `false` | 见 §6.8 |
| `timeouts` | object | 否 | `5000/60000` | 见 §6.9 |
| `secrets` | map | 否 | `{}` | 密钥容器，读时打码 |

#### `auth`

| 字段 | 类型 | 说明 |
|---|---|---|
| `mode` | enum | `QUERY` 拼进 URL 查询串；`HEADER` 作为请求头；`FORM` 作为 multipart 表单字段；`NONE` |
| `name` | string | 参数/头/字段名。`HEADER` 模式下建议用 `Authorization` |
| `value` | string | 支持模板，通常为 `${secret:apiKey}` |

> `FORM` 模式是 freeimage.host 的形态：key 必须作为**表单字段**提交，放查询串不被识别。

#### `body`

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `mode` | enum | `MULTIPART` | 本期仅此值 |
| `fileField` | string | **必填** | 文件二进制所在字段名 |
| `fileNameField` | string | `null` | 若上游要求文件名单独成字段（如某些 old-school 图床），填字段名；否则文件名写入 part 的 `filename=` |
| `extraFields` | map | `{}` | 附加表单字段，值支持模板 |

#### `response`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `successWhen` | array | 否 | AND 语义。缺省 = 仅看 HTTP 2xx。元素 `{path, op, value}`，`op ∈ {EQ, NE, EXISTS, GT, LT, MATCH}` |
| `urlPath` | string | **是** | 直链取值路径 |
| `thumbPath` | string | 否 | 缩略图 |
| `sizePath` | string | 否 | 上游回报的字节数；缺省用本地统计值 |
| `sizeUnit` | enum | `BYTES` | `BYTES` / `KIB`（÷1024）/ `KB`（÷1000） |
| `widthPath` / `heightPath` | string | 否 | 记录到 `sys_storage_file`？否——现有实体无宽高列，见 §7.6 |
| `upstreamIdPath` | string | 否 | 上游标识，写入 locator 便于日后追溯 |
| `deleteUrlPath` | string | 否 | 仅作展示，若 `capabilities.delete=false` 则不使用 |
| `errorPath` | string | 否 | 失败时提取可读消息 |

#### `limits`

| 字段 | 类型 | 说明 |
|---|---|---|
| `maxBytes` | long | **上限硬顶 1 MiB（1048576）**，配置大于此值会被 `validateAndNormalizeOptions` 夹紧并写审计 |
| `minBytes` | long | 默认 0 |
| `allowedMimePrefixes` | array | 默认 `["image/"]` |
| `allowedMimeTypes` | array | 精确白名单，优先于前缀 |

### 6.4 模板语法

**完整复用** `HttpToolExecutor` 已有的语法，不引入第二套：

| 模板 | 可用位置 | 含义 |
|---|---|---|
| `${secret:name}` | auth.value、headers、query、extraFields | 从 `secrets` 取值 |
| `${var:fileName}` | 同上 | 已净化的原始文件名 |
| `${var:contentType}` | 同上 | MIME |
| `${var:sizeBytes}` | 同上 | 字节数 |
| `${var:publicId}` | 同上 | 本次上传的 publicId（便于上游侧对账） |

实现类 `UrlTemplateRenderer`（约 90 行）：单遍扫描、遇到未知变量**抛异常**（不静默留空，避免把 `${secret:apiKey}` 原样发出去）。

> 安全要求：渲染后若结果仍含 `${`，立即中止并记审计，**绝不发送**。

### 6.5 内置预设

预设只是**默认值填充**，用户可任意覆盖。这样"绝大多数字段不用填"，但又保留"任意图床都能接"的扩展性。

```jsonc
// preset = IMGBB
{
  "endpoint": "https://api.imgbb.com/1/upload",
  "method": "POST",
  "auth": { "mode": "QUERY", "name": "key", "value": "${secret:apiKey}" },
  "body": { "mode": "MULTIPART", "fileField": "image", "extraFields": {} },
  "response": {
    "successWhen": [ { "path": "success", "op": "EQ", "value": true } ],
    "urlPath": "data.url",
    "thumbPath": "data.thumb.url",
    "sizePath": "data.size",
    "sizeUnit": "BYTES",
    "upstreamIdPath": "data.id",
    "deleteUrlPath": "data.delete_url",
    "errorPath": "error.message"
  },
  "limits": { "maxBytes": 1048576, "allowedMimePrefixes": ["image/"] },
  "capabilities": { "delete": false, "thumbnail": true, "permanentUrl": true }
}
```

```jsonc
// preset = FREEIMAGE
{
  "endpoint": "https://freeimage.host/api/1/upload",
  "method": "POST",
  "query": { "format": "json" },              // ← 缺了会返回 HTML，必须
  "auth": { "mode": "FORM", "name": "key", "value": "${secret:apiKey}" },
  "body": {
    "mode": "MULTIPART",
    "fileField": "source",
    "extraFields": { "action": "upload", "format": "json" }
  },
  "response": {
    "successWhen": [ { "path": "status_code", "op": "EQ", "value": 200 } ],
    "urlPath": "image.url",
    "thumbPath": "image.thumb.url",
    "sizePath": "image.size",
    "sizeUnit": "BYTES",
    "upstreamIdPath": "image.id_encoded",
    "errorPath": "error.message"
  },
  "limits": { "maxBytes": 1048576, "allowedMimePrefixes": ["image/"] },
  "capabilities": { "delete": false, "thumbnail": true, "permanentUrl": true }
}
```

> freeimage.host 的 `image.extension` 是数字码，真实扩展名在 `image.extension_name`。本设计**不依赖**上游的扩展名字段——文件名与扩展名以本地为准（见 §10.3），因此 D13 这个坑天然被绕开。

### 6.6 响应提取与成功判定

`ThirdPartyResponseMapper`（约 150 行）职责：

1. **解析**：响应上限 256 KiB（超出即截断并判定失败），`ObjectMapper.readTree`。
2. **判定成功**：HTTP 2xx **且** 所有 `successWhen` 条件成立。任一不成立 → 失败，提取 `errorPath` 作为消息（提取不到则用 HTTP 状态 + 响应前 200 字符）。
3. **取值 + 类型宽容**：
   - `size` 可能是 `"245678"`（字符串）或 `245678`（数字）→ 统一 `asLong()` 兜底，失败返回 -1；
   - `success` 可能是 `true` 或 `1` 或 `"true"` → 统一按布尔宽容比较；
   - 路径不存在返回 `null`，不抛异常。
4. **URL 校验**：取到的 `url` 必须是 `https?://` 且主机非私网（防上游被投毒返回内网地址）。
5. **单位换算**：`sizeUnit` 换算为字节；若与本地统计值偏差 > 20%，以**本地值**为准并记 WARN（上游单位猜错时能自愈）。

### 6.7 错误归一化

| 情形 | `errorCode` | HTTP / `body.code` | 是否触发组内换渠道 |
|---|---|---|---|
| 连接超时 / 建连失败 | `STORAGE041` | 200 / 300 | ✅ 是 |
| 上游返回 4xx（含 401/403/429） | `STORAGE042` | 200 / 300 | ✅ 是（并置该渠道 `health=DOWN`） |
| 上游返回 5xx | `STORAGE043` | 200 / 300 | ✅ 是 |
| 响应不可解析 / 缺 `urlPath` | `STORAGE044` | 200 / 300 | ✅ 是 |
| 取到的 URL 非公网 | `STORAGE045` | 200 / 300 | ✅ 是（并记安全审计） |
| 文件超出渠道上限 | `STORAGE046` | 200 / 300 | ❌ 否（换渠道无意义，直接返错） |
| 全部候选失败 | `STORAGE040` | 200 / 300 | — 终止 |

> 上表是**内部**归一化视图，供 `GroupUploadOrchestrator` 判断"要不要换渠道"。对外的 HTTP/业务码映射见 §9.1——按全仓约定，业务失败一律 **HTTP 200 + `body.code = 300`**，HTTP 状态码只表达传输层。

### 6.8 能力位

| 能力 | 语义 | 影响 |
|---|---|---|
| `delete` | 是否可程序化删除 | `false` → `requestDelete` 直接标记 `DELETED` 并写审计，**不发任何网络请求**（现有 `deleteObjectByProvider` 对未知渠道是静默 no-op，见 §10.4 改造点） |
| `thumbnail` | 上游是否返回缩略图 | `false` → 不写 `thumbPath` |
| `permanentUrl` | 是否可长期直链 | `false` → `issuePermanentUrl` 返回 `STORAGE019` |

### 6.9 安全校验

`ProviderProfileParser` 在**保存时**与**执行时**各校验一次（执行时再校验一次是为了防 DNS 重绑定）：

1. `endpoint` 必须是 `https`（或显式 `allowInsecure`）。
2. **复用** `HttpToolExecutor.guardHost()` / `requirePublicAddress()`（`HttpToolExecutor.java:268`、`:299`）：拒绝私网、环回、链路本地（含 `169.254.169.254`）、组播、全零地址。
3. **禁止跟随重定向**（`HttpClient.followRedirects(NEVER)`），收到 3xx 直接判失败。
4. 响应体上限 256 KiB。
5. 超时可配，但 `readMs` 上限 120000，`connectMs` 上限 15000。
6. `headers` 中 `Host`、`Content-Length`、`Content-Type`、`Transfer-Encoding` 一律剔除。
7. 渲染后残留 `${` 即中止。
8. `secrets` 的键名必须匹配 `^[a-zA-Z][a-zA-Z0-9_]{0,31}$`。

---

## 7. 数据库设计

### 7.1 复用与新增一览

| 表 | 动作 | 说明 |
|---|---|---|
| `sys_storage_config` | **复用 + 1 列** | 新增 `is_default_group_member`？否。仅新增 `provider_type='CUSTOM_HTTP'` 取值 |
| `sys_storage_group` | **新增** | 组定义 |
| `sys_storage_group_item` | **新增** | 组成员 |
| `sys_storage_folder` | **+1 列** | `storage_group_id` |
| `sys_storage_file` | **+1 列** | `storage_group_id`（审计用，实际渠道仍在 `storage_config_id`） |
| `sys_storage_audit` | 复用 | 新增 `group.*` / `thirdparty.*` 动作 |

### 7.2 `sys_storage_group`

```sql
CREATE TABLE IF NOT EXISTS sys_storage_group (
    id                  BIGINT       PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    strategy            VARCHAR(32)  NOT NULL DEFAULT 'FAILOVER',
    max_attempts        SMALLINT     NOT NULL DEFAULT 2,
    is_default          SMALLINT     NOT NULL DEFAULT 0,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    remark              VARCHAR(256),
    create_by           VARCHAR(64),
    create_time         TIMESTAMP,
    update_by           VARCHAR(64),
    update_time         TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_storage_group_name     ON sys_storage_group (name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_storage_group_default  ON sys_storage_group (is_default) WHERE is_default = 1;
```

> 默认组用**部分唯一索引**（`WHERE is_default = 1`），全表最多一个默认组，同时允许无限多个 `is_default = 0`。这样绕开了 `sys_storage_config` 上 `uk_storage_config_default` 那种「整列唯一导致只能存一个 0」的历史问题。

`max_attempts` 默认 2：1 Mbps 下 3 家全试一遍最坏 51 秒（见 §3.2 表）。

### 7.3 `sys_storage_group_item`

```sql
CREATE TABLE IF NOT EXISTS sys_storage_group_item (
    id                  BIGINT       PRIMARY KEY,
    group_id            BIGINT       NOT NULL,
    config_id           BIGINT       NOT NULL,
    priority            INTEGER      NOT NULL DEFAULT 0,
    weight              INTEGER      NOT NULL DEFAULT 100,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    remark              VARCHAR(256),
    create_by           VARCHAR(64),
    create_time         TIMESTAMP,
    update_by           VARCHAR(64),
    update_time         TIMESTAMP,
    CONSTRAINT uk_storage_group_item UNIQUE (group_id, config_id)
);

CREATE INDEX IF NOT EXISTS idx_storage_group_item_group ON sys_storage_group_item (group_id);
CREATE INDEX IF NOT EXISTS idx_storage_group_item_config ON sys_storage_group_item (config_id);
```

| 字段 | 语义 |
|---|---|
| `priority` | 数值**小**者优先。仅 `FAILOVER` / `HEALTH_FIRST` 的组内排序使用 |
| `weight` | 权重 ≥1。`WEIGHTED_RANDOM` 与 `ROUND_ROBIN` 的加权序列使用 |

### 7.4 `sys_storage_folder` 扩展

```sql
ALTER TABLE sys_storage_folder ADD COLUMN IF NOT EXISTS storage_group_id BIGINT;
COMMENT ON COLUMN sys_storage_folder.storage_group_id IS '绑定的存储组；非空时优先于 storage_config_id';
```

保留原 `storage_config_id` 列不动，作为**单渠道回退**。优先级见 §8.1。

### 7.5 `sys_storage_file` 扩展

```sql
ALTER TABLE sys_storage_file ADD COLUMN IF NOT EXISTS storage_group_id BIGINT;
COMMENT ON COLUMN sys_storage_file.storage_group_id IS '来源组（审计用）；实际渠道见 storage_config_id';
```

### 7.6 关于宽高

`StorageFileEntity` 现有字段集为 `id, publicId, uploadId, folderId, storageConfigId, providerType, providerLocatorJson, originalName, contentType, sizeBytes, checksum, contentVersion, visibility, status, uploaderType, uploaderId, uploaderIp, createTime, updateTime, deletedTime`。

**本期不新增 `width` / `height` 列**。上游返回的尺寸写入 `provider_locator_json`（见 §10.3），需要时读 JSON。理由：加列要连带动 schema json、DTO、前端列表、导出，收益不匹配。

`checksum` 列**本期启用**（此前从未写入，记为缺陷 F20）：因为我们**在内存里已经持有完整文件**，本地算 MD5 是零额外成本，可显著提升对账能力。

### 7.7 schema 描述文件

按仓库既有约定，新表需加 JSON 描述，供 `SchemaRegistry` / 表结构管理页使用：

- `astral-plugin/src/main/resources/schema/sys_storage_group.json`
- `astral-plugin/src/main/resources/schema/sys_storage_group_item.json`

> **位置红线**：这两张是**插件表**，必须放 `astral-plugin` 侧，**不得**放进 `astral-schema/src/main/resources/schema/` —— 否则会为插件表凭空生成宿主实体，与插件手写实体并存冲突（AGENTS.md 强约束第 2 条，qt_*.json / sys_storage_*.json 均走插件侧）。

字段清单须与 §7.2 / §7.3 的 DDL 严格一致，且每张表必须满足：

| 要求 | 说明 |
|---|---|
| `mybatisPlusIdType` | `id` 字段固定 `"INPUT"`（主键由序列填充，非自增） |
| `isPrimaryKey` | `id` 为 `true`，`isAutoIncrement` 为 `false` |
| `isAutoFill` | `createTime` → `"INSERT"`，`updateTime` → `"INSERT_UPDATE"`（触发 `SequenceMetaObjectHandler` 取号） |
| `length` / `jdbcType` | 与 DDL 一一对应（`VARCHAR(128)` → `length: 128`） |
| `indexes` | 与 DDL 一致，含两个唯一索引 |

对应实体（若由生成器产出，则**不要**手改）须为：

```java
@TableName("sys_storage_group")
public class StorageGroupEntity {
    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    // ...
}
```

> `SchemaEntitySync` 每次启动会重写实体并删除手改内容，所以常量（如 `STRATEGY_FAILOVER`）**不能**写在生成的实体上——放独立常量类或枚举。

### 7.8 字典

按项目规范（AGENTS.md 强制约束第 3 条），进入前端下拉的枚举**必须**登记数据字典：

| dict_code | 项 | 说明 |
|---|---|---|
| `storage_provider_type` | 追加 `CUSTOM_HTTP`「第三方图床 API」 | 该字典已存在（`V20260914003__storage_dict.sql`），只追加项 |
| `storage_group_strategy` | `FAILOVER` / `ROUND_ROBIN` / `WEIGHTED_RANDOM` / `LEAST_USED` / `HEALTH_FIRST` | 新增类型 |
| `storage_group_status` | `ENABLED` / `DISABLED` | 新增类型 |

**禁止沿用旧的写死主键写法**。`V20260914003` 里用 `SELECT 9001, ...` 硬编码 `sys_dict_data.id`，而 AGENTS.md 已明确这是事故来源（`ON CONFLICT DO NOTHING` 会静默跳过，Flyway 仍记 success）。本迁移一律用 `V20261102004__ai_tool_executor_dict.sql` 的自增号写法：

```sql
-- ① 新增字典类型（只补缺失的，守卫用业务键 dict_code）
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('storage_group_strategy', '存储组调度策略', 'FAILOVER 失败转移 / ROUND_ROBIN 轮询 / WEIGHTED_RANDOM 加权随机 / LEAST_USED 最少使用 / HEALTH_FIRST 健康优先'),
           ('storage_group_status',   '存储组状态',     'ENABLED 启用 / DISABLED 停用')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

-- ② 新增字典项（含向已存在的 storage_provider_type 追加 CUSTOM_HTTP）
WITH seed(dict_code, dict_value, dict_label, dict_sort, description) AS (
    VALUES ('storage_provider_type',    'CUSTOM_HTTP', '第三方图床 API', 8, '服务端代传，可组队调度'),
           ('storage_group_strategy',   'FAILOVER',        '失败转移',   1, '按优先级依次尝试，首个失败自动换下一个'),
           ('storage_group_strategy',   'ROUND_ROBIN',     '轮询',       2, '按序轮流使用'),
           ('storage_group_strategy',   'WEIGHTED_RANDOM', '加权随机',   3, '按权重随机选择'),
           ('storage_group_strategy',   'LEAST_USED',      '最少使用',   4, '优先选择近期使用量最少的渠道'),
           ('storage_group_strategy',   'HEALTH_FIRST',    '健康优先',   5, '健康渠道优先，桶内轮询'),
           ('storage_group_status',     'ENABLED',         '启用',       1, NULL),
           ('storage_group_status',     'DISABLED',        '停用',       2, NULL)
), missing AS (
    SELECT t.id AS type_id, s.*
    FROM seed s JOIN sys_dict_type t ON t.dict_code = s.dict_code
    WHERE NOT EXISTS (
        SELECT 1 FROM sys_dict_data d
        WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value
    )
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_code, dict_sort),
       type_id, dict_label, dict_value, dict_sort, 1, missing.description
FROM missing;

-- ③ 抬高字典供号表高水位（否则并发写字典会撞主键）
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
```

> 前端渲染**必须**用 `fetchDictOptions([...])` / `dictApi.byCode` 按 `dict_code` 拉取（`storage/page.tsx:29` 已有同类用法），未知值回退显示原始值，不得硬编码中文文案。

### 7.9 号段

按 AGENTS.md「序列供号表」约定，首次初始化预留 `[1, 1000000]` 段，运行时 ID 从 1000001 起：

```sql
INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
VALUES ('sys_storage_group_id',      1, 1000000, 1000000, 1000),
       ('sys_storage_group_item_id', 1, 1000000, 1000000, 1000)
ON CONFLICT (biz_key) DO NOTHING;
```

> 本迁移**不写任何显式主键**（两张新表没有种子行），因此无需额外抬高 `max_value`。若将来需要预置组数据，必须同一迁移内把对应 `sequence_segment.max_value` 抬到 `MAX(id)+step` 以上。

---

## 8. 组与策略

### 8.1 渠道解析优先级

上传请求解析"用哪个渠道"的顺序（**自上而下，命中即停**）：

| 序 | 来源 | 说明 |
|---|---|---|
| 1 | `X-Storage-Group-Id` 请求头（若显式传入且用户有权） | 前端"手动选择组"场景 |
| 2 | `folder.storage_group_id` | 目录绑定的组 |
| 3 | 全局默认组（`sys_storage_group.is_default = 1` 且 `status = ENABLED`） | |
| 4 | `folder.storage_config_id` | 目录绑定的单渠道 |
| 5 | 全局默认渠道（`sys_storage_config.is_default = 1`） | 现有逻辑 `requireDefaultEnabled()` |
| 6 | — | 抛 `STORAGE019`「未配置可用存储」 |

> 第 1 项设权限校验：只有管理员可任意传，普通用户仅能传"自己可见且启用"的组，否则忽略该头（**不要报错**，静默降级到 2）。

### 8.2 候选过滤

对命中的组，取全部 `status = ENABLED` 的成员，然后过滤：

| 过滤条件 | 动作 |
|---|---|
| 渠道 `status != ENABLED` | 剔除 |
| `providerType != CUSTOM_HTTP` | 剔除（组的成员必须是本家族；配置保存时已校验） |
| `capabilities.delete` 不影响上传 | 不过滤 |
| `sizeBytes > min(渠道 limits.maxBytes, folder.maxSizeBytes, 全局 maxFileSizeBytes)` | 剔除 |
| MIME 不匹配渠道 `allowedMime*` | 剔除 |
| `healthStatus = DOWN` | **软剔除**：仅当 `HEALTH_FIRST` 或剩余候选 > 1 时才剔除；若剔除后为空则全部回填（避免误标导致完全不可用） |

过滤后为空 → `STORAGE040`「组内无可用渠道」。

### 8.3 策略实现

设过滤后候选为 `C`（`|C| = n`），`max_attempts = m`（默认 2，`m = min(m, n)`）。

#### FAILOVER（默认）

```
按 (priority ASC, weight DESC, id ASC) 排序
取前 m 个依次尝试，第一个成功即返回
```

稳定性由 `id` 兜底，保证同配置下顺序确定、可复现。

#### ROUND_ROBIN

```
k = INCR storage:group:{gid}:rr         (Redis, 无 TTL)
按 (priority ASC, id ASC) 排序后，起点 = k mod n
从起点开始环形取 m 个
```

> 为什么仍按 priority 排序？因为 priority 表达的是"运营意愿"，轮询应该在这个意愿内部轮转，而不是打乱它。

#### WEIGHTED_RANDOM

```
构建累计权重数组，用 ThreadLocalRandom 抽 1 个作起点
再按顺序补足 m 个（去重）
```

#### LEAST_USED

```
HGETALL storage:group:{gid}:usage        (哈希, TTL 1h 滑动)
选 usage 最小者；并列时按 ROUND_ROBIN 的环起点打破
成功后 HINCRBY usage {configId} 1
```

#### HEALTH_FIRST

```
分桶：UP / UNKNOWN / DOWN
桶内用 ROUND_ROBIN
跨桶取前 m 个
```

### 8.4 失败转移规则

| 规则 | 说明 |
|---|---|
| **只在"快速失败"时换渠道** | 见 §6.7：连接失败、4xx、5xx、解析失败 → 换；文件超限 → 不换 |
| **总耗时封顶** | 整个请求的总预算 = `m × readMs`，但与 nginx `proxy_read_timeout`（建议 120s）对齐；超过 90s 主动中止剩余尝试，返回 `STORAGE040` |
| **健康度即时回写** | 连续 3 次失败 → 该渠道 `healthStatus = DOWN`（计数放 Redis `storage:config:{id}:fail`，TTL 10min）；一次成功 → 计数清零，`healthStatus = UP` |
| **不重试同一渠道** | 候选去重，同一渠道在一次请求中最多试 1 次 |
| **缓冲复用** | 因为已在内存缓冲，重试**不需要重新读请求流**，这是"1 MiB 上限换失败转移"的收益（见 §10.3 步骤③） |

### 8.5 与现有健康度字段的关系

`StorageConfigEntity.healthStatus` 已有 `UP / DOWN / UNKNOWN` 与 `lastTestTime / lastTestMessage`，直接复用，不新增列。管理端「测试连通性」（`StorageConfigService.test()` L219）对 `CUSTOM_HTTP` 增加分支：发一个**极小**的探测请求（如 `POST` 一个 1×1 PNG，或有的图床支持 `HEAD`），25 秒内返回。

> 探测会真实消耗一次上游配额，UI 需提示「测试会真实上传一张 1KB 图片」。

### 8.6 Redis 键一览

| 键 | 类型 | TTL | 用途 |
|---|---|---|---|
| `storage:group:{gid}:rr` | string | 无 | 轮询计数器 |
| `storage:group:{gid}:usage` | hash | 1h | 使用量（LEAST_USED） |
| `storage:config:{cid}:fail` | string | 10min | 连续失败计数 |
| `storage:upload:{uploadId}` | string | 600s | **现有**，上传上下文（复用） |
| `storage-upload:{uploadId}` | lock | 短 | **现有**，登记锁（复用） |

---

## 9. 接口设计

### 9.1 新增：文件上传（裸字节）

```
POST /api/v1/all/storage/files/upload
```

**请求头**

| 头 | 必填 | 说明 |
|---|---|---|
| `Content-Type` | ✅ | 图片 MIME，如 `image/webp` |
| `X-File-Name` | ✅ | URL-encoded UTF-8 原始文件名 |
| `X-Folder-Id` | ✕ | 目标目录；空 = 根目录/默认 |
| `X-Storage-Group-Id` | ✕ | 显式指定组；鉴权后生效（§8.1） |

**请求体**：裸字节（`application/octet-stream` 语义），**不是 multipart**。

> 为什么裸字节？在 384 MB 堆上，multipart 解析会同时存在"解析出的字节数组"和业务副本两份；裸字节只有一份。1 MiB 差别不大，但两并发就是 4 MiB vs 2 MiB——在这个堆上看得出区别。上游的 multipart 由 Astral 自己拼。

**响应语义（遵守全仓统一响应体约定）**

全仓只有一个响应体 `com.astral.common.result.Result`，字段固定为 `{code, errorCode?, msg, data, timestamp}`。**HTTP 状态码只表达传输层，业务失败一律 HTTP 200 + `body.code`**：

```json
// 成功
{
  "code": 200,
  "msg": "success",
  "timestamp": 1762323456789,
  "data": {
    "publicId": "f_2f8a1c9e4b7d4a1e",
    "uploadId": "up_9c3f...",
    "originalName": "photo.webp",
    "contentType": "image/webp",
    "sizeBytes": 245678,
    "providerType": "CUSTOM_HTTP",
    "configId": 12,
    "groupId": 3,
    "url": "https://i.ibb.co/xxxx/photo.webp"
  }
}
```

控制器里**只写** `return Result.success(view)`；一切失败**必须抛异常**，由 `GlobalExceptionHandler` 统一生成响应体，禁止 `return Result.fail(...)` 或手写 `response.setStatus(4xx/5xx)`。

**关键错误**

| `errorCode` | HTTP | `body.code` | 消息 | 触发 | 是否换渠道 |
|---|---|---|---|---|---|
| `STORAGE040` | 200 | 300 | 存储组内无可用渠道 | 过滤后为空 / 全部尝试失败 | — |
| `STORAGE041` | 200 | 300 | 图床连接失败 | 建连超时 | ✅ |
| `STORAGE042` | 200 | 300 | 图床拒绝请求 | 上游 4xx（含 401/403/429） | ✅ |
| `STORAGE043` | 200 | 300 | 图床服务异常 | 上游 5xx | ✅ |
| `STORAGE044` | 200 | 300 | 图床响应无法解析 | 缺 `urlPath` | ✅ |
| `STORAGE045` | 200 | 300 | 图床返回的地址不安全 | URL 非公网 | ✅ |
| `STORAGE046` | 200 | 300 | 文件超出该渠道上限 | 超 `limits.maxBytes` | ❌ |
| （无 errorCode） | 200 | **429** | 上传通道繁忙，请稍后重试 | 并发闸门拒绝 | ❌ |
| `STORAGE006` | 200 | 300 | **现有**：超出大小上限 | 复用 | ❌ |
| `STORAGE002` | 200 | **403** | **现有**：无权限 | 复用 | ❌ |
| `STORAGE019` | 200 | 300 | **现有**：未配置可用存储 | 复用 | ❌ |
| `COMMON006` | **413** | **413** | 上传超限 | 仅当请求体真被框架拦到 `MaxUploadSizeExceededException`（即突破 nginx/Spring 上限，正常路径不会到） | ❌ |

> **并发繁忙为什么要单独用 `body.code = 429`**：它属于「限流类」，按 `GlobalExceptionHandler` 的分支表应回 429 业务码，与「业务失败 300」区分开，前端才能给出"稍后重试"而不是"上传失败"。实现上 `throw BusinessException.of(429, "上传通道繁忙，请稍后重试")`。

> 现有 `STORAGE001~033` 已被占用（`001/003/010/020/022/023/027/034+` 虽为空但为保连续不占用）。新家族从 **040** 起。

**路由前缀**：该接口属"管理端与 App 端共用"，落在 `/api/v1/all/storage/**`（与现有 `StorageUserController` 的 `@RequestMapping("/api/v1/all/storage")` 一致），符合三层前缀约束。

### 9.2 新增：组管理（管理端）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/admin/plugin/storage/groups` | 列表（含成员数与健康汇总） |
| POST | `/api/v1/admin/plugin/storage/groups` | 新建 |
| PUT | `/api/v1/admin/plugin/storage/groups/{id}` | 更新（名称/策略/状态/备注） |
| DELETE | `/api/v1/admin/plugin/storage/groups/{id}` | 删除（被目录引用时拒绝） |
| POST | `/api/v1/admin/plugin/storage/groups/{id}/default` | 设为默认组 |
| GET | `/api/v1/admin/plugin/storage/groups/{id}/items` | 成员列表 |
| PUT | `/api/v1/admin/plugin/storage/groups/{id}/items` | **全量替换**成员（一次性提交 priority/weight/status） |
| POST | `/api/v1/admin/plugin/storage/groups/{id}/test` | 逐个成员探测 |

> 成员用**全量替换**而非逐条 CRUD：前端是一个列表编辑器，全量替换语义更简单，也避免并发编辑打架。

**权限标注（声明式，禁止在 Controller 里手写 `if (!hasPermission(...))`）**

沿用 `AdminStorageController` 现有的两个粒度，**不为组另建新码**（AGENTS.md：不要因为"想细"就一律建码；组管理和"配置/文件夹策略"同级，属可下放写权限）：

| 端点 | 注解 |
|---|---|
| 列表 / 成员读取 / 探测 | `@RequiresPermission(value = "admin:storage:view", name = "存储查看", description = "…")` |
| 新建 / 更新 / 删除 / 设默认 / 成员替换 | `@RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "…")` |

这两条权限已由 `AdminStorageController.java:43` / `:81` 登记并自动入库（`PermissionRegistry` 只增不改），**不需要新迁移**。

不标 `@RequiresSuper`：组管理既不能提权也不能执行代码，下放是安全的。唯一需注意的写路径是 `PUT /groups/{id}/items`——它写入的 `config_id` 必须做**白名单校验**（只能是 `provider_type='CUSTOM_HTTP'` 且存在的配置），否则持有 `admin:storage:edit` 的人可以把 `config_id` 指到任意行（mass-assignment 的同类风险）。

组写操作**不触碰**「用户→权限」映射，因此**无需** `PermissionCache.bumpVersion()`。

上传接口（`/api/v1/all/storage/files/upload`）按 `StorageUserController` 现有做法处理登录态，不额外建权限码——它走目录策略（`UploadPolicyService.PolicySpec.requireLogin`）而非权限码。

### 9.3 改造的现有接口

| 接口 | 改动 |
|---|---|
| `POST /upload-ticket` | **对 `CUSTOM_HTTP` 返回 `STORAGE019`**。该家族不走 ticket 流程，前端应改调 `/files/upload` |
| `POST /files/register` | 不变。第三方渠道不需要回执（Astral 自己就是上传者） |
| `POST /files/{publicId}/permanent-url` | 对第三方渠道返回 `provider_locator_json.url`（读取，不签名） |
| `POST /files/{publicId}/download-url` | 同上，直接返回上游直链，`expiresAt` 返回 `0`（永不过期） |
| `GET /api/v1/all/storage/folders` | 响应增加 `storageGroupId`、`groupName` |
| `PUT /admin/plugin/storage/folders/{id}` | 增加 `storageGroupId` 字段 |

---

## 10. 服务层设计

### 10.1 新增类清单

| 类 | 包 | 预估行数 | 职责 |
|---|---|---|---|
| `CustomHttpProviderProfile` | `storage.model` | 180 | 不可变 record，profile 的运行时形态 |
| `ProviderProfileParser` | `storage.service` | 220 | JSON ↔ profile，预设合并、校验、夹紧 |
| `UrlTemplateRenderer` | `storage.service` | 90 | `${...}` 求值 |
| `ThirdPartyResponseMapper` | `storage.service` | 150 | 成功判定 + 路径取值 + 类型宽容 + URL 校验 |
| `CustomHttpUploadService` | `storage.service` | 380 | 组装 multipart、发送、解析、归一化错误 |
| `StorageGroupService` | `storage.service` | 280 | 组与成员 CRUD、审计 |
| `StorageGroupCache` | `storage.service` | 70 | Caffeine（TTL 60s，max 32），写后 `evictAll` |
| `UploadStrategyResolver` | `storage.service` | 260 | 过滤 + 排序 + Redis 计数 |
| `GroupUploadOrchestrator` | `storage.service` | 300 | 串联解析→尝试→落库 |
| `ThirdPartyHealthProbe` | `storage.service` | 120 | 连通性探测 |
| `ThirdPartyUploadGate` | `storage.service` | 80 | 全局/每用户信号量 |
| `StorageGroupEntity` / `StorageGroupItemEntity` | `storage.entity` | 各 40 | |
| `StorageGroupMapper` / `StorageGroupItemMapper` | `storage.mapper` | 各 10 | |
| `StorageGroupDtos` | `storage.dto` | 120 | 请求/响应 record |
| `AdminStorageGroupController` | `storage.controller` | 160 | 管理端 |
| `SysHttpClient` | `storage.http` | 60 | 共享 `HttpClient`（连接池、禁重定向） |

合计后端新增约 **2,200 行**（含注释/Javadoc，按本仓库风格注释占比高）。

### 10.2 关键流程伪代码

#### 上传主流程

```
uploadFile(request):
    # ① 前置校验
    contentType = request.getContentType()        # 非 image/* 直接 415
    claimedSize = request.getContentLengthLong()
    if claimedSize > 1MiB: throw STORAGE006       # 快速拒绝，不读流

    user = currentUser()
    folder = resolveFolder(request.getHeader('X-Folder-Id'))
    policy = policyService.resolve(folder)
    policyService.checkDailyQuota(user, policy)   # 复用现有

    # ② 解析组
    group  = groupCache.resolve(folder, request.getHeader('X-Storage-Group-Id'), user)
    items  = groupCache.items(group.id)
    ctx    = UploadContext(contentType, fileName, claimedSize, user, folder, group)

    # ③ 内存缓冲（≤1 MiB，单次 read，带硬闸门）
    bytes = readBounded(request.getInputStream(), 1MiB)   # 超限抛 STORAGE006
    if bytes.length == 0: throw STORAGE006
    ctx.bytes = bytes; ctx.sizeBytes = bytes.length
    ctx.md5 = DigestUtils.md5Hex(bytes)                   # 顺手补 F20

    # ④ 候选与尝试
    candidates = strategyResolver.resolve(group, items, ctx)
    if candidates.isEmpty(): throw STORAGE040

    gate.acquire(user.id)                                  # 全局2/每用户1
    try:
        tried = []
        for cfg in candidates.take(group.maxAttempts):
            tried += cfg.id
            profile = profileParser.parse(cfg.providerOptions)
            try:
                result = customHttpUploadService.upload(profile, ctx)
                markHealthy(cfg.id)
                break
            catch ThirdPartyException e:
                markUnhealthy(cfg.id, e)
                if !e.retryable(): throw
                continue
        else:
            throw STORAGE040
    finally:
        gate.release(user.id)

    # ⑤ 落库
    file = StorageFileEntity(
        publicId = newPublicId(),
        providerType = 'CUSTOM_HTTP',
        storageConfigId = cfg.id,                          # 实际命中
        storageGroupId = group.id,                         # 审计
        providerLocatorJson = {
            url: result.url, thumb: result.thumb,
            upstreamId: result.upstreamId, width: ..., height: ...,
            channel: cfg.name
        },
        originalName = ctx.fileName, contentType = ctx.contentType,
        sizeBytes = ctx.sizeBytes, checksum = ctx.md5,
        visibility = folder.visibility, status = 'AVAILABLE',
        uploaderType = ..., uploaderId = ..., uploaderIp = clientIp
    )
    fileMapper.insert(file)
    auditService.record('thirdparty.upload', file.publicId, cfg.id, group.id)

    # ⑥ 不调用 verifyContentAfterRegistration（第三方强制 NONE）
    return view(file, result.url)
```

#### `CustomHttpUploadService.upload`

```
upload(profile, ctx):
    # SSRF 二次校验（防 DNS 重绑定）
    HttpToolExecutor.guardHost(hostOf(profile.endpoint), profile.allowedHosts)

    uri   = buildUri(profile, ctx)          # endpoint + query + auth(QUERY)
    body  = buildMultipart(profile, ctx)    # 字节数组 + 5 个 part
    req   = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(profile.timeouts.readMs))
                .header('Content-Type', 'multipart/form-data; boundary=' + b)
                .POST(BodyPublishers.ofByteArray(body))
    # HEADER 模式追加 auth 头；FORM 模式已进 multipart

    resp  = client.send(req, BodyHandlers.ofByteArray())
    json  = mapper.parse(resp, 256KiB)
    if !mapper.success(json, profile): throw ThirdPartyException.of(resp, json, profile)
    url   = mapper.url(json, profile)
    assertPublicUrl(url)                    # STORAGE045
    return Result(url, thumb, upstreamId, size)
```

#### 组内解析

```
strategyResolver.resolve(group, items, ctx):
    candidates = items.filter(item ->
        item.status == ENABLED
        && cfg(item).status == ENABLED
        && cfg(item).providerType == 'CUSTOM_HTTP'
        && ctx.sizeBytes <= min(cfg.maxFileSize ?: globalMax, profile.limits.maxBytes)
        && mimeAllowed(cfg, ctx.contentType)
    )
    if candidates.isEmpty(): return []

    healthy = candidates.filter(h -> h.health != DOWN)
    if healthy.size > 0 && group.strategy == HEALTH_FIRST: candidates = healthy

    switch group.strategy:
        FAILOVER        -> sort(priority, -weight, id)
        ROUND_ROBIN     -> rotateBy(redisIncr(gid), sort(priority, id))
        WEIGHTED_RANDOM -> weightedStart(sort(priority, id))
        LEAST_USED      -> sortByUsage(sort(priority, id))
        HEALTH_FIRST    -> bucket(UP, UNKNOWN, DOWN).map(ROUND_ROBIN).flatten()
    return candidates
```

### 10.3 关键实现细节

**① multipart 边界与内存**

`buildMultipart` 一次性拼出完整 `byte[]`（文件 ≤1 MiB，加上头部约 1.01 MiB）。这是**可控的一次性分配**，无需流式拼接，代码简单。缓冲区大小受 §6.3 的 `maxBytes` 硬顶保护。

**② 文件名净化**

沿用 `StorageFileService.sanitizeName`（L977）的逻辑：去路径分隔符、去控制字符、截断 120 字符、保留扩展名。扩展名以**本地 MIME 推导**为准（`normalizeMime` L985），不信上游 —— 这样绕开 freeimage.host 的 `extension` 数字坑（D13）。

**③ 为什么不流式**

流式（`BodyPublishers.ofInputStream`）虽能省一半内存、把端到端时间从"收+发"降到"max(收,发)"，但**一旦开始发就不可重试**，与「组 + 失败转移」冲突。1 MiB 上限下缓冲开销仅 2 MiB/两并发，因此**选缓冲，换取失败转移**。20 MiB 场景才需要流式，本期不支持（§3.2 已论证其不可用）。

**④ 并发闸门**

`ThirdPartyUploadGate`：全局 `Semaphore(2)` + `ConcurrentHashMap<String, Semaphore(1)>`（按用户）。1 Mbps 下串行化不是保守而是提速——5 人同时传每人只有 1/5 带宽，都要 85 秒；串行后第一个 17 秒完成。

获取超时 3 秒，超时 `throw BusinessException.of(429, "上传通道繁忙，请稍后重试")`（`body.code = 429`），**不无限等待**。这里不用带 `errorCode` 的业务异常，是因为它属于"限流类"而非"业务失败类"，两者的 `body.code` 分支不同（见 §9.1）。

### 10.4 具体改造点清单

| 文件 | 位置 | 改动 |
|---|---|---|
| `ProviderSupport.java` | 全文 | 新增 `PROVIDER_CUSTOM_HTTP` 常量引用；新增 `isServerProxyFamily()`（仅 CUSTOM_HTTP）、`isThirdPartyFamily()`；`isKnown()` 加入该家族 |
| `StorageConfigEntity.java` | L18-25 | 新增常量 `PROVIDER_CUSTOM_HTTP = "CUSTOM_HTTP"`；扩充 `providerOptions` 注释 |
| `StorageConfigService.java` | L306 `validateAndNormalizeOptions` | 新增 `CUSTOM_HTTP` 分支：调 `ProviderProfileParser.parse`，夹紧 `limits.maxBytes ≤ 1MiB`，密钥入库 |
| `StorageConfigService.java` | L364 `secretFieldOf` | `CUSTOM_HTTP` 返回 `"secrets"`（整体打码），与现有四个单字段密钥不同 |
| `StorageConfigService.java` | L381 `optionsProbe` | 新增分支：`ThirdPartyHealthProbe.probe` |
| `StorageConfigService.java` | L402 `redactSecret` | 增加 `secrets` 对象整体打码 |
| `StorageFileService.java` | L298 `verifyContentAfterRegistration` | 若 `isThirdPartyFamily` → 立即 return（强制 NONE） |
| `StorageFileService.java` | L379 `fetchContentBytes` | 若 `isThirdPartyFamily` → 抛 `STORAGE019`（禁止回读） |
| `StorageFileService.java` | L846 `headObjectByProvider` | 新增分支：第三方返回 `locator.size`，不发网络请求 |
| `StorageFileService.java` | L863 `deleteObjectByProvider` | 新增分支：`capabilities.delete=false` → 只写审计，不发请求；`true` → 调 `delete` 块 |
| `StorageFileService.java` | L883 `publicObjectUrl` | 新增分支：第三方直接返回 `locator.url` |
| `StorageFileService.java` | L905 `locatorKey` | 第三方允许 `key` 为空（用 `url` 代替），不再抛 `STORAGE016` |
| `StorageFileService.java` | L921 `buildObjectLocator` | 新增第三方形状（见 §10.2 ⑤） |
| `StorageFileService.java` | L573 `issuePermanentUrl` | 新增分支：返回 `locator.url`，不签名 |
| `StorageUserController.java` | L119 附近 | 新增 `@PostMapping("/files/upload")`；**不声明 `MultipartFile` 参数**，用 `HttpServletRequest` |
| `application.yml` | L120 附近 | 新增 `astral.plugins.storage.third-party.{global-concurrency,per-user-concurrency,max-file-size-bytes,history-ttl-seconds}` |

---

## 11. 前端设计

### 11.1 图片压缩（不可省略）

`astral-front/src/lib/image-compress.ts`：

```
compressImage(file, {maxEdge = 1600, quality = 0.85, mime = 'image/webp'}):
    bitmap = await createImageBitmap(file)
    缩放至长边 ≤ maxEdge（保持比）
    canvas → toBlob(mime, quality)
    if blob.size > 1MiB: 逐级降 quality 到 0.6 / 缩到 1280 / 1024
    if blob.size 仍 > 1MiB: 抛"图片过大，请先裁剪"
    return blob
```

效果：手机原图 3–5 MB → **150–400 KB**，上传时间从 84 秒降到 2–5 秒。**这是 1 Mbps 下功能可用与否的分水岭。**

### 11.2 上传流程

```
选图 → 客户端压缩（显示"正在处理…"）
     → 显示"正在上传 245KB，约需 5 秒" + 真实进度
     → 成功：显示直链 + 复制按钮
     → 失败：按 errorCode 给可读文案（§9.1 表）
```

**请求出口必须走 `astral-front/src/api/client.ts` 的 axios 实例**（AGENTS.md 强约束第 4 条）。进度用 axios 自带的 `onUploadProgress`，**不要**为此改用原生 `XMLHttpRequest`，也不要 `fetch`：

```ts
const { data } = await client.post<Result<UploadView>>(
  '/all/storage/files/upload',
  blob,                                     // 裸字节，不是 FormData
  {
    headers: {
      'Content-Type': blob.type || 'application/octet-stream',
      'X-File-Name': encodeURIComponent(file.name),
      ...(folderId ? { 'X-Folder-Id': String(folderId) } : {}),
    },
    timeout: 120_000,                        // 与 nginx proxy_read_timeout 对齐
    onUploadProgress: (e) => {
      if (e.total) setPercent(Math.round((e.loaded / e.total) * 100))
    },
  },
)
```

> `client.ts` 会统一注入 satoken、`X-App-Ut` / `X-App-Version` / `X-Device` / `X-OS` 与全局加载态，并处理 401 跳登录——这些都是裸 XHR 拿不到的。AGENTS.md 里"不得添加自定义头"的限制**只针对对象存储预签名直传**（会破坏签名），本接口打到 Astral 自身，不受该限制。

**失败文案映射**（`body.code` + `errorCode`）：

| 条件 | 文案 |
|---|---|
| `errorCode = STORAGE046` | 图片超出该渠道上限（1 MB），请压缩后重试 |
| `errorCode` ∈ `STORAGE040~045` | 图床暂时不可用，请稍后重试 |
| `code = 429` | 正在上传中，请等待当前任务完成 |
| `code = 413` | 图片过大，请压缩后重试 |
| 压缩后仍 > 1 MiB | 本地直接拦截，不发请求 |

> 1 Mbps 下"没有进度条"= 用户以为卡死并重复点击，进而撞上 429。进度条与前端压缩是同一件事的两面。

### 11.3 组管理页

`astral-front/src/app/dashboard/plugin/storage/groups/page.tsx`：

- 组列表：名称 / 策略（下拉）/ 成员数 / 健康汇总（UP x / DOWN y）/ 是否默认
- 成员编辑：可拖拽排序，每行显示渠道名、provider 预设、权重输入、状态开关、健康状态灯
- 顶部提示：「组成员必须同为 `CUSTOM_HTTP` 家族」

### 11.4 渠道配置表单（动态）

`storage/page.tsx` 已有 per-provider 的分步向导（`buildTestSteps` / `testDescription` / `testPrerequisites`，约 L316-395）与 provider 色板（约 L523）。新增：

- `S3_FAMILY`（L48）、`OBJECT_PROVIDERS`（L50）旁边新增 `THIRD_PARTY_PROVIDERS = ['CUSTOM_HTTP']`
- 选中 `CUSTOM_HTTP` 后显示**预设选择器**（ImgBB / freeimage.host / 自定义），选预设即自动填好 §6.5 的全部字段
- 高级字段折叠区：端点、方法、鉴权模式、请求头、文件字段、附加字段、响应路径、成功条件、超时
- 密钥输入框旁标注「仅保存于服务端，读取时打码」

---

## 12. 配置与部署

### 12.1 nginx

在现有 `deploy/nginx/10-astral-sites.conf` 中为上传接口加独立 location（**不得复用默认 location**）：

```nginx
location ~ ^/api/v1/all/storage/files/upload$ {
    proxy_pass http://astral-backend:27000;

    proxy_request_buffering off;      # 关键：否则 nginx 先整包落盘再转发
    client_max_body_size 2m;          # 与 1 MiB 上限对齐（留 multipart 头部余量）
    proxy_read_timeout  120s;         # 默认 60s 不够：1 MiB 端到端 17s + 重试
    proxy_send_timeout  120s;
    proxy_http_version  1.1;
}
```

**必须同时修正的既有不一致**：`client_max_body_size` 在 L71 为 `8m`、L163/L233 为 `20m`，而 Java 侧 `max-file-size: 50MB`、storage 侧 20 MiB —— 三层阈值互不相同，已有 8–20 MB 文件在 nginx 被 413、Java 却以为能收的错位。应统一为：**上传专用 location 2m / 其余 20m / Java 50MB（仅兜底，业务层先行拒绝）**。

### 12.2 应用配置

```yaml
astral:
  plugins:
    storage:
      third-party:
        global-concurrency: 2          # §10.3④
        per-user-concurrency: 1
        max-file-size-bytes: 1048576   # §3.2，硬顶
        gate-acquire-timeout-ms: 3000
        response-max-bytes: 262144
        total-budget-ms: 90000
```

### 12.3 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| （无新增） | — | 第三方渠道的密钥存在**数据库**的 `provider_options.secrets` 里，不走环境变量 |

> 与 TELEGRAM 不同：TELEGRAM 的 Bot Token 在 Worker Secret 里；本设计没有 Worker，密钥只能落库。**落库形态是明文 JSON**（与现有 R2/COS/OSS/UPYUN 的 `secretAccessKey` 等一致）。读接口打码，缓存仅在服务端内存。若要求加密，需单独引入（如 Jasypt），但会与现有 7 个渠道形成两套密钥管理路径——**不建议只给新渠道加密**。

### 12.4 JVM

**不改**。现有 `-Xmx384m / Metaspace 256m / SerialGC` 对本设计的增量（≤2 MiB 缓冲 + profile 缓存 + 组缓存）足够。组缓存 `maximumSize(32)`、`TTL 60s`，与现有 `StorageConfigCache` 规模同量级。

---

## 13. 安全设计

| 风险 | 处置 | 位置 |
|---|---|---|
| **SSRF**（端点被配成内网） | 保存时 + 执行时各校验一次；拒绝私网/环回/链路本地/`169.254.169.254`/组播/全零；禁重定向 | `ProviderProfileParser` + 执行前 |
| **DNS 重绑定** | 执行时重新解析并校验（`HttpToolExecutor.requirePublicAddress`） | 同左 |
| **上游被投毒返回内网 URL** | 取到的 `url` 必须 `https?://` 且主机公网，否则 `STORAGE045` + 安全审计 | `ThirdPartyResponseMapper` |
| **密钥泄漏到前端** | ① 不进 ticket payload（payload 是 base64 明文）② 读接口整体打码 ③ 上传接口响应不含任何密钥 | `UploadTicketService` / `StorageConfigService` |
| **密钥泄漏到日志** | 沿用 `worker.js` 的脱敏思路：日志中 `key=`、`token=`、`authorization` 一律替换为 `***` | 日志切面 |
| **模板注入** | 未知变量抛异常；渲染后残留 `${` 即中止 | `UrlTemplateRenderer` |
| **请求头注入** | `Host`/`Content-Length`/`Content-Type`/`Transfer-Encoding` 强制剔除；值内 CR/LF 拒绝 | `ProviderProfileParser` |
| **并发打爆上游** | 全局 2 / 每用户 1；429 快速失败 | `ThirdPartyUploadGate` |
| **配额滥用** | 复用 `policyService.checkDailyQuota`（`registerFromBrowser` 已有同款逻辑） | `GroupUploadOrchestrator` |
| **SVG 上传 XSS** | 沿用现有：SVG MIME 无条件拒绝 | `UploadTicketService` |
| **响应体炸弹** | 256 KiB 上限，超出截断判失败 | `ThirdPartyResponseMapper` |

---

## 14. 可观测性

### 日志

| 事件 | 级别 | 字段（**均不含密钥**） |
|---|---|---|
| 上传开始 | INFO | `publicId, groupId, sizeBytes, contentType, uploader` |
| 渠道尝试 | INFO | `groupId, configId, preset, attempt, elapsedMs, outcome` |
| 渠道失败 | WARN | 同上 + `errorCode, upstreamStatus, message(截断200)` |
| 全部失败 | ERROR | `groupId, triedConfigIds, totalElapsedMs` |
| 健康度变化 | INFO | `configId, old, new, reason` |

### 审计（`sys_storage_audit`）

| action | 说明 |
|---|---|
| `group.create` / `group.update` / `group.delete` / `group.default` | 组变更 |
| `group.items.replace` | 成员变更（记录前后 id 列表） |
| `config.secret.rotate` | 密钥轮换（不记值） |
| `thirdparty.upload` | 成功上传（记 configId + groupId） |
| `thirdparty.delete.skip` | 因 `capabilities.delete=false` 跳过的删除 |

### 指标（Micrometer，若仓库已接入）

- `storage.upload.thirdparty.duration`（tag: group, config, outcome）
- `storage.upload.thirdparty.bytes`
- `storage.upload.thirdparty.failover`（计数器：发生换渠道的次数）
- `storage.gate.rejected`

---

## 15. 兼容与迁移

| 项 | 影响 |
|---|---|
| 现有 7 个 Provider | **零影响**。`ProviderSupport` 只新增方法，原方法逻辑不变 |
| `ProviderSupport.isKnown` | 加入 `CUSTOM_HTTP`，用于配置保存校验 |
| `sys_storage_config` | 仅新增一种 `provider_type` 取值，不加列 |
| `sys_storage_folder` / `sys_storage_file` | 各加一列，**可空**，旧数据不受影响 |
| `uk_storage_config_default` | 不动；组默认用独立表 + 部分唯一索引 |
| 现有 `maxFileSize` 语义 | 组内渠道上限取 `min(渠道 maxFileSize, profile.limits.maxBytes, folder, 全局)`，全局 20 MiB 仍然生效但被渠道值夹紧 |
| Flyway | 新增 `V20261105001__storage_group.sql`（建表 + 加列 + 字典 + 号段，单文件原子） |

**回滚**：删除两张新表、两列可留（可空）或一并 drop；代码回退后 `provider_type='CUSTOM_HTTP'` 的配置行会因 `isKnown=false` 而在保存时被拒，但不影响启动。

---

## 16. 测试计划

### 单元测试

| 类 | 用例 |
|---|---|
| `ProviderProfileParser` | 预设合并；缺 `urlPath` 报错；`maxBytes` 夹紧到 1 MiB；`headers` 危险键剔除；`http://` 拒绝 |
| `UrlTemplateRenderer` | `${secret:x}`；未知变量抛异常；`${var:fileName}`；残留 `${` 中止 |
| `ThirdPartyResponseMapper` | ImgBB 真实响应；freeimage 真实响应；`size` 为字符串/数字；`success` 为 `true/1/"true"`；成功条件不满足；错误路径提取；非公网 URL 拒绝 |
| `UploadStrategyResolver` | 5 种策略的排序确定性；大小过滤；MIME 过滤；DOWN 软剔除与回填 |

### 集成测试（需真实端点，标记 `@Tag("network")`）

- ImgBB 上传 → 断言响应 URL 可访问 HEAD 200
- freeimage.host 上传 → 断言 `status_code=200`
- 组 `FAILOVER`：第一个渠道 key 故意写错 → 断言自动切第二个且落库 `storage_config_id` 为第二个
- 超限文件 → 断言 `body.code = 300` 且 `errorCode = STORAGE006`，并**未发起上游请求**（用 mock counter 验证）。注意**不是** HTTP 413——413 只在突破框架上限时出现

> 现有 `astral-plugin/src/test/java/com/astral/storage/service/` 只有 4 个测试文件、399 行，**零 per-provider 覆盖**。本设计新增测试应达到 **≥ 400 行**，并首次为 Provider 家族建立用例基线。

### 手工验证矩阵

| 场景 | 预期 |
|---|---|
| 推送 300 KB WebP | 端到端 ≤5 s（本机 1 Mbps 实测） |
| 推送 1 MiB JPEG | 端到端 ≤17 s |
| 推送 2 MiB | 前端压缩后应 <1 MiB；强行推送 → `body.code 300` + `errorCode STORAGE006`（HTTP 仍是 200） |
| 关停组内唯一渠道 | `STORAGE040`，且审计有失败记录 |
| 两用户同时上传 | 第二个排队（每用户 1），不互相拖慢 |
| 图片访问 | `302` 到 `i.ibb.co`，`Response` 无字节体 |
| 删除图片 | 标记 `DELETED`，审计 `thirdparty.delete.skip`，直链仍可访问（预期行为） |
| 配置读取 | 响应中 `secrets` 全部为 `******` |

---

## 17. 实施阶段

| 阶段 | 内容 | 验收 | 预估 |
|---|---|---|---|
| **P0** | `CUSTOM_HTTP` 家族 + profile 解析 + `CustomHttpUploadService` + `/files/upload` + ImgBB 预设 + 前端压缩上传 | 单渠道端到端可用，本机实测 ≤5 s | 后端 ~900 行 / 前端 ~250 行 |
| **P1** | 组表 + `StorageGroupService` + `GroupUploadOrchestrator` + `FAILOVER` | 两渠道组，关停一个仍成功 | 后端 ~750 行 |
| **P2** | `ROUND_ROBIN` / `WEIGHTED_RANDOM` / `LEAST_USED` / `HEALTH_FIRST` + 健康度回写 | 5 策略可切换 | 后端 ~300 行 |
| **P3** | 组管理页 + 渠道配置动态表单 + 预设选择器 | 管理端可自助加渠道/建组 | 前端 ~600 行 |
| **P4** | 并发闸门 / 审计 / 指标 / 文档 | 压测通过 | ~250 行 |

**建议节奏**：P0 先在真机跑通再动 P1。1 Mbps 的体感是关键变量——如果 300 KB 压缩图的 5 秒体验不可接受，P1 以后的设计需要重估（例如改 720p 上限、或引入付费图床）。

---

## 18. 风险与已知限制

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | **两家均无可用删除 API**（附录 B.4） | 误传/换图后旧图永久留存；无迁移能力 | 前端删除确认文案改为"仅从列表移除，外链仍有效"；不要承诺可删 |
| R2 | 免费图床随时清图/关停 | 历史图片批量 404 | 健康探测 + 图片 `onerror` 兜底占位；重要图片建议用 R2 类可控渠道 |
| R3 | 1 Mbps 下单张延迟高 | 体验差 | 前端压缩（关键）；进度条；异步上传队列 |
| R4 | 上游响应结构变更 | 解析失败 | profile 可热改（无需改代码）；解析失败率监控 |
| R5 | `provider_options` 明文落库 | DB 泄露即密钥泄露 | 与现有 7 渠道一致；打码 + 日志脱敏；建议定期轮换 |
| R6 | 组内成员同质化 | 一起挂 | 建议组内混合不同厂商 |
| R7 | 单实例并发闸门 | 无法水平扩展 | 现网单实例，可接受；扩展时改 Redis 令牌桶 |
| R8 | ImgBB 的 `delete_url` 易被误认为"可删" | 用户误解 | UI 不展示该字段 |

---

## 19. 与仓库强制规范的对应

AGENTS.md 的五条强制约束逐条核对（实施时按此自检，评审拦截图）：

| # | 规范 | 本设计的落点 | 结论 |
|---|---|---|---|
| 1 | 接口路径必须 `admin / app / all` 三层 | 上传 `POST /api/v1/all/storage/files/upload`（沿用现有 `StorageUserController` 类级前缀）；组管理 `/api/v1/admin/plugin/storage/groups/**`（沿用 `AdminStorageController` 类级前缀） | ✅ |
| 2 | 新建表必须纳入表结构管理 + 配 JSON | §7.7：两份 JSON 放 `astral-plugin/src/main/resources/schema/`；建表走 `V20261105001__storage_group.sql`；**不写死种子主键** | ✅ |
| 3 | 枚举必须走数据字典 | §7.8：`storage_group_strategy` / `storage_group_status` 新建，`storage_provider_type` 追加 `CUSTOM_HTTP`；前端用 `fetchDictOptions` | ✅ |
| 4 | 前端 shadcn/ui + 请求走 `api/client.ts` | §11：新增页用 shadcn/ui；压缩模块纯 TS 无 UI 依赖；上传走共享 axios + `onUploadProgress`；改界面先读 `docs/UI_GUIDE.md` | ✅ |
| 5 | 权限声明式标注 | §9.2：复用 `admin:storage:view` / `admin:storage:edit`，不新建码；上传接口沿用目录策略而非权限码 | ✅ |

### 19.1 实施时必须注意的工程细节

| 项 | 要求 | 原因 |
|---|---|---|
| **Jackson 3.x** | 用 `tools.jackson.databind.ObjectMapper` / `tools.jackson.core.type.TypeReference`；注解仍是 `com.fasterxml.jackson.annotation.*` | 仓库已切 Jackson 3，混用 Jackson 2 的 `com.fasterxml.jackson.databind.*` 会编译不过/行为不一致 |
| **不声明 `MultipartFile`** | 控制器签名用 `HttpServletRequest` + `getInputStream()`，**绝不**写 `@RequestPart` / `MultipartFile` | 声明即触发 Spring multipart 解析，会先落临时盘再回读，1 Mbps 下平白多一次磁盘 IO 与一份副本；`resolve-lazily: true` 只有在完全没声明时才生效 |
| **无 star import** | 新增 Java 文件逐条显式导入 | Checkstyle / Spotless 规范 |
| **文件末尾换行** | 所有新增文件 | Spotless `endWithNewline` |
| **不用 `System.out` / `printStackTrace`** | 一律 SLF4J | SpotBugs 会报 |
| **空 catch 必须写日志或注释** | 尤其是"健康度回写失败不影响主流程"这类吞异常 | SpotBugs + 评审要求 |
| **`ObjectMapper` 复用** | 通过构造注入，不要每次 `new` | 384 MB 堆上对象分配要克制 |
| **提交前跑** | `mvn spotless:check` + 所在模块 `mvn compile`；前端 `npx eslint src --fix` + `npx tsc --noEmit` | AGENTS.md「代码质量工具链」 |

### 19.2 与 API.md 的冲突处理

`API.md` §10（442–530 行）当前明确写着「**不新增浏览器文件正文上传到 Astral 的接口**」。本设计**正是要推翻这一条**，因此实施 P0 时必须同批修改文档，否则文档与代码背道而驰：

| 文件 | 改动 |
|---|---|
| `API.md` §10 | 删除「不新增…」的表述；新增 `POST /files/upload` 契约（§9.1）；说明 `CUSTOM_HTTP` 家族不走 ticket 流程 |
| `astral/STORAGE_REQUIREMENTS.md` §3.1 | 将该硬约束改写为「**默认**浏览器直传；`CUSTOM_HTTP` 家族因上游不支持 CORS 或无公开密钥隔离能力，例外允许服务端代传，并受 1 MiB / 并发 2 / 强制 `verifyContent=NONE` 约束」 |
| `docs/THIRD_PARTY_STORAGE_GROUP_DESIGN.md`（本文） | 引用的行号在实施后可能漂移，改动代码时同步更新 §10.4 |

> 这是本设计**唯一的破坏性架构变更**。`STORAGE_REQUIREMENTS.md` §3.1 是当初用来防止"文件正文流经 Astral"的安全条款，绕开它必须留下明确的例外边界，而不是悄悄改代码。

---

## 附录 A：完整 `provider_options` 示例

### A.1 ImgBB（推荐首发的渠道）

```json
{
  "schemaVersion": 1,
  "preset": "IMGBB",
  "endpoint": "https://api.imgbb.com/1/upload",
  "method": "POST",
  "headers": { "User-Agent": "Astral-Storage/1.0" },
  "auth": { "mode": "QUERY", "name": "key", "value": "${secret:apiKey}" },
  "body": { "mode": "MULTIPART", "fileField": "image", "extraFields": {} },
  "response": {
    "successWhen": [ { "path": "success", "op": "EQ", "value": true } ],
    "urlPath": "data.url",
    "thumbPath": "data.thumb.url",
    "sizePath": "data.size",
    "sizeUnit": "BYTES",
    "widthPath": "data.width",
    "heightPath": "data.height",
    "upstreamIdPath": "data.id",
    "errorPath": "error.message"
  },
  "limits": { "maxBytes": 1048576, "minBytes": 1, "allowedMimePrefixes": ["image/"] },
  "capabilities": { "delete": false, "thumbnail": true, "permanentUrl": true },
  "timeouts": { "connectMs": 5000, "readMs": 60000 },
  "secrets": { "apiKey": "******" }
}
```

### A.2 freeimage.host

```json
{
  "schemaVersion": 1,
  "preset": "FREEIMAGE",
  "endpoint": "https://freeimage.host/api/1/upload",
  "method": "POST",
  "query": { "format": "json" },
  "headers": { "User-Agent": "Astral-Storage/1.0", "Accept": "application/json" },
  "auth": { "mode": "FORM", "name": "key", "value": "${secret:apiKey}" },
  "body": {
    "mode": "MULTIPART",
    "fileField": "source",
    "extraFields": { "action": "upload", "format": "json" }
  },
  "response": {
    "successWhen": [ { "path": "status_code", "op": "EQ", "value": 200 } ],
    "urlPath": "image.url",
    "thumbPath": "image.thumb.url",
    "sizePath": "image.size",
    "sizeUnit": "BYTES",
    "widthPath": "image.width",
    "heightPath": "image.height",
    "upstreamIdPath": "image.id_encoded",
    "errorPath": "error.message"
  },
  "limits": { "maxBytes": 1048576, "minBytes": 1, "allowedMimePrefixes": ["image/"] },
  "capabilities": { "delete": false, "thumbnail": true, "permanentUrl": true },
  "timeouts": { "connectMs": 5000, "readMs": 60000 },
  "secrets": { "apiKey": "******" }
}
```

### A.3 未来接入其他图床的最小改动

只需新增一段 JSON，**不改任何代码**。例如需要 `sizeUnit: KIB` 的图床（tc.mofashi 即此类，本期排除）：

```json
"response": { "sizePath": "data.size", "sizeUnit": "KIB", ... }
```

需要把文件名单独成字段的：

```json
"body": { "fileField": "file", "fileNameField": "filename", "extraFields": {...} }
```

需要 Bearer 头的：

```json
"auth": { "mode": "HEADER", "name": "Authorization", "value": "Bearer ${secret:token}" }
```

---

## 附录 B：实测契约记录（2026-11-05）

> 所有数据来自真实网络探测。测试产生的图片**均无法删除**（三家都不提供可用删除接口），已向用户说明。

### B.1 ImgBB

| 项 | 值 |
|---|---|
| 端点 | `POST https://api.imgbb.com/1/upload?key=<K>` |
| 文件字段 | `image` |
| CORS | `Access-Control-Allow-Origin: *` |
| 成功响应 | `{data:{id,url,display_url,url_viewer,thumb:{url},width,height,size,image:{filename,name,mime,extension,url}},success:true,status:200}` |
| 直链形态 | `https://i.ibb.co/<hash>/<原名>.png` |
| 非图片 | `415 Unsupported or unrecognized file format` |
| 错误形态 | `{status_code,error:{message,code}}` |
| 大小 | 15.14 MiB 通过；未测到上限 |
| 限流 | 8 次快速上传无限流 |
| 吞吐（国内网络） | 2.07 MiB → 3484 ms ≈ **0.59 MiB/s** |
| chunked（无 Content-Length） | **接受**（1.03 MiB，2576 ms，成功） |

### B.2 freeimage.host

| 项 | 值 |
|---|---|
| 端点 | `POST https://freeimage.host/api/1/upload?format=json` |
| 表单字段 | `key`、`action=upload`、`format=json`、`source`（文件） |
| CORS | **无任何 CORS 头** |
| 成功响应 | `{status_code:200,success:{message,code},image:{name,extension:2,extension_name:"png",width,height,size,...},status_txt:"OK"}` |
| 直链形态 | `https://iili.io/<id>.png` |
| 非图片 | `310 Can't get target upload source info` |
| 删除链接 | **响应中无 `delete_url`** |
| 大小 | 15.14 MiB 通过 |
| 限流 | 5 次快速上传无限流 |
| 吞吐 | 2.07 MiB → 3758 ms ≈ **0.55 MiB/s** |
| 坑 | 缺 `format=json` 时返回 **HTML 页面**（HTTP 200，`text/html`） |
| 坑 | `image.extension` 是数字码，真扩展名在 `image.extension_name` |

### B.3 tc.mofashi.ltd（本期排除，仅留档）

- 匿名上传可用；token 上传返回 `42209 账户未验证`
- 硬上限 **5,242,880 B**（5 MiB），5,242,881 B → `42204`
- 成功响应 `data.size` 单位是 **KiB 浮点**（不是字节）→ 这就是 `sizeUnit` 字段存在的原因
- 响应含 `md5` / `sha1`，可用于填充 `checksum`
- 删除需 sanctum token；匿名 401

### B.4 删除能力实测（关键结论）

| 渠道 | 尝试 | 结果 |
|---|---|---|
| ImgBB | `POST https://ibb.co/json` | `400 {"error":{"message":"Request denied","code":401}}`；`delete_url` 是网页且无 csrf meta、无 Set-Cookie | **不可编程删除** |
| freeimage.host | 响应无 `delete_url`，无公开删除 API | **不可删除** |

因此 `capabilities.delete` 对本版本**恒为 `false`**，删除语义降级为"标记删除 + 直链继续有效"，且 UI 必须如实告知。

### B.5 吞吐实测（1 Mbps 推导依据）

| 目标 | 大小 | 耗时 | 速率 |
|---|---|---|---|
| ImgBB | 2.07 MiB | 3484 ms | 0.59 MiB/s |
| freeimage.host | 2.07 MiB | 3758 ms | 0.55 MiB/s |
| ImgBB | 1.03 MiB | 2576 ms | 0.40 MiB/s |
| freeimage.host | 1.03 MiB | 3011 ms | 0.34 MiB/s |

> 上表为测试机网络；**部署机为 1 Mbps，故实际速率为 0.122 MiB/s**，本文 §3.2 的表格按此计算。

---

## 附录 C：与现有声明式 HTTP 执行器的关系

`astral-plugin/src/main/java/com/astral/ai/tool/declarative/HttpToolExecutor.java`（568 行）已经实现了一套同构能力（模板、SSRF 守卫、超时、响应上限）。本设计**不直接复用该类**，理由：

1. 它的职责是"通用 HTTP 工具调用"，返回结构为工具结果，无文件语义；
2. 它的配置模型（`executor_config` + `arguments_schema`）面向 LLM 参数化调用，与本设计的"一个 profile 固定描述一个图床"不同构。

但**复用其中两块**：

| 复用项 | 方式 |
|---|---|
| `guardHost()` / `requirePublicAddress()`（L268 / L299） | 直接 static 调用，避免重复实现 SSRF 守卫 |
| `${secret:...}` 模板语法 | `UrlTemplateRenderer` 遵循同一语法，保持全仓一致性 |

这样既避免了一处新的安全事故面，也不强迫两套模型合并。