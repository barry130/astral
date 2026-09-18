# Astral Storage Worker 部署配置教程（Telegram + Cloudflare 图床）

> 适用版本：storage 插件 MVP（2026-09-12）。全流程约 30–60 分钟。
> 安全提醒：教程中所有 `<尖括号>` 均为占位符，替换成你自己的值；**任何真实密钥不要写入本文件、聊天记录或 Git**。

---

## 一、架构回顾（你要部署什么）

```text
浏览器 ──上传凭证──> Cloudflare Worker ──文件流──> Telegram 频道
   │                      │ 回调(HMAC)
   │                      ▼
   └──────────────> Astral 后端（公网 https，只收元数据）

下载：浏览器 ──签名URL──> Worker ──缓存/回源──> Telegram
```

| 组件 | 部署位置 | 需要配置的东西 |
|---|---|---|
| Astral 后端 | 你的服务器/本机 | 3 个 HMAC 密钥环境变量 + 可被 Worker 访问的公网地址 |
| Worker | Cloudflare | 代码 + 4 个 Secret + 1 个变量 |
| Telegram Bot | 已完成 | Bot 需在频道内且拥有发消息/删消息权限 |
| 表结构 | 数据库 | V4 迁移（开发库已应用；其他环境按 migrations/README 执行） |

---

## 二、前置条件清单

- [ ] Telegram Bot 已创建，频道已建，Bot 已设为频道管理员（勾选「发布消息」「删除消息」）；
- [ ] Cloudflare 账号（免费版即可，无需绑卡）；
- [ ] 后端能编译出 `astral-server-1.0.0.jar`，数据库表由 Flyway 自动创建（已并入初始化基线脚本）；
- [ ] 本机装有 Node.js 18+（用 wrangler 部署时需要；纯 Dashboard 部署可不用）。

---

## 三、第 1 步：让 Worker 能访问到 Astral 后端

Worker 要回调 Astral（上传登记、下载回源），所以 Astral 必须有一个 **Worker 可访问的公网 https 地址**。三选一：

### 方案 A：已有云服务器（生产推荐）

把 `astral-server-1.0.0.jar` 部署到服务器，用 Nginx/Caddy 反代出 https 域名，例如 `https://api.your-domain.com`。确认外网能访问：

```bash
curl https://api.your-domain.com/actuator/health
```

### 方案 B：Cloudflare Tunnel（本机调试最快，5 分钟）

不用服务器，把本机 27000 端口映射到公网：

```bash
# 1. 下载 cloudflared（https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/）
# 2. 快速隧道（随机域名，重启会变，仅调试用）：
cloudflared tunnel --url http://localhost:27000
# 输出里会给你一个 https://xxxx-xxxx.trycloudflare.com，这就是临时公网地址
```

### 方案 C：命名隧道（本机长期使用 + 自己的域名）

```bash
cloudflared tunnel login                       # 浏览器授权
cloudflared tunnel create astral-backend
cloudflared tunnel route dns astral-backend api.your-domain.com
cloudflared tunnel run --url http://localhost:27000 astral-backend
```

> 记下最终地址，后面记作 `<ASTRAL_BASE_URL>`（如 `https://api.your-domain.com`）。

---

## 四、第 2 步：生成 3 个随机密钥

三个密钥必须互不相同，且**后端与 Worker 两边完全一致**：

```bash
# Git Bash / Linux：
openssl rand -base64 32   # 执行三次

# 或 Python：
python -c "import secrets; print(secrets.token_urlsafe(32))"   # 执行三次
```

| 密钥 | 用途 | 配置位置 |
|---|---|---|
| `STORAGE_UPLOAD_TICKET_KEY` | 上传凭证签名 | 后端 + Worker |
| `STORAGE_ORIGIN_SHARED_SECRET` | Worker→Astral 服务认证 | 后端 + Worker |
| `STORAGE_DOWNLOAD_SIGNING_KEY_V1` | 下载 URL 签名 | 后端 + Worker |
| `TG_BOT_TOKEN` | Telegram Bot Token | **仅 Worker**（后端不需要） |

---

## 五、第 3 步：配置 Astral 后端

### Windows 本机（run-backend.local.bat 追加）

```bat
set "STORAGE_UPLOAD_TICKET_KEY=<密钥1>"
set "STORAGE_ORIGIN_SHARED_SECRET=<密钥2>"
set "STORAGE_DOWNLOAD_SIGNING_KEY_V1=<密钥3>"
```

### Linux 服务器（systemd 或启动脚本）

```bash
export STORAGE_UPLOAD_TICKET_KEY="<密钥1>"
export STORAGE_ORIGIN_SHARED_SECRET="<密钥2>"
export STORAGE_DOWNLOAD_SIGNING_KEY_V1="<密钥3>"
```

重启后端。**注意：本地开发我之前生成的临时密钥（%TEMP%\storage-keys.env）只用于联调，正式部署请换新值。**

其他环境（如新数据库）无需手工操作：后端启动时 Flyway 会自动建表（已并入 `db/migration/V20260914001__init.sql` 基线），机制见该目录 README.md。

---

## 六、第 4 步：部署 Cloudflare Worker

### 方法 A：wrangler CLI（推荐，仓库里就是现成的）

```bash
cd cloudflare/storage-worker
cp wrangler.toml.example wrangler.toml
# 编辑 wrangler.toml：
#   [vars] ASTRAL_ORIGIN_BASE_URL = "<ASTRAL_BASE_URL>"（第 1 步的地址）
npx wrangler login          # 浏览器授权一次
npx wrangler deploy
# 输出会给出 https://astral-storage.<你的子域>.workers.dev，记作 <WORKER_URL>
```

cron 触发器（远端删除任务）已写在 `wrangler.toml.example` 的 `[triggers]` 里，deploy 时自动生效。

### 方法 B：纯 Dashboard（不装 Node）

1. Dashboard → **Workers & Pages → Create application → Create Worker**，名字填 `astral-storage`，模板选 Hello World，Deploy；
2. **Edit code**：把仓库 `cloudflare/storage-worker/src/worker.js` 的全部内容粘贴进去，覆盖默认代码 → **Deploy**；
3. **Settings → Variables**：
   - Type 选 **Text**（明文变量）：`ASTRAL_ORIGIN_BASE_URL` = `<ASTRAL_BASE_URL>`；
   - 4 个 Secret 见下一节（Type 选 **Secret**）；
4. **Settings → Trigger Events（Triggers）→ Cron Triggers → Add**：填 `*/5 * * * *`（每 5 分钟拉取删除任务）；
5. 回 Overview 确认 Worker 状态 Enabled，记下 `<WORKER_URL>`。

> 免费版额度：10 万请求/天、单请求 10ms CPU（网络等待不计入）、请求体上限 100MB——20MiB 图完全够用。

---

## 七、第 5 步：配置 4 个 Secret

### wrangler 方式

```bash
cd cloudflare/storage-worker
npx wrangler secret put TG_BOT_TOKEN                    # 粘贴 BotFather 给的 Token
npx wrangler secret put STORAGE_UPLOAD_TICKET_KEY       # 粘贴 <密钥1>（与后端一致）
npx wrangler secret put STORAGE_ORIGIN_SHARED_SECRET    # 粘贴 <密钥2>（与后端一致）
npx wrangler secret put STORAGE_DOWNLOAD_SIGNING_KEY_V1 # 粘贴 <密钥3>（与后端一致）
```

### Dashboard 方式

Worker → **Settings → Variables and Secrets → Add**，Name 填上面的变量名，Type 选 **Secret**，Value 粘贴对应值，保存。改完 Secret 会自动重新部署。

验证：浏览器访问 `https://<WORKER_URL>/healthz`，返回 `{"ok":true,"bot":"你的bot用户名"}` 即 Token 正确。

---

## 八、第 6 步（可选）：绑定自定义域名

1. 确认域名已托管在同一个 Cloudflare 账号（DNS 页能看到该域名）；
2. Workers & Pages → astral-storage → **Settings → Domains & Routes → Add → Custom domain**；
3. 填子域名，如 `img.your-domain.com` → Add（DNS 与证书自动配置，约 1 分钟生效）；
4. 之后 Astral 存储配置里的 Worker 地址就填 `https://img.your-domain.com`。

---

## 九、第 7 步：获取 Telegram 频道 Chat ID

1. 确认 Bot 已是频道管理员；
2. 在频道里随便发一条消息；
3. 浏览器打开（**仅自己机器上操作，勿泄露 URL，内含 Token**）：

   ```text
   https://api.telegram.org/bot<你的BotToken>/getUpdates
   ```

4. 找到 `"channel_post":{"chat":{"id":-100XXXXXXXXXX,...}}`，这个 `-100...` 数字就是 Chat ID。
   （超级群同理；找不到就把 getUpdates 再刷新一次后重新在频道发一条消息。）

---

## 十、第 8 步：在 Astral 管理端配置

登录后台 → 左侧「文件存储」：

1. **存储配置 Tab → 新建配置**：
   - 配置名称：`Telegram 图床`；
   - Chat ID：`-100XXXXXXXXXX`；
   - Worker 地址：`https://<WORKER_URL 或 img.your-domain.com>`；
   - 保存后点 **测试** —— 返回 UP（显示 Bot 用户名）说明 Worker 与 Token 正常。
   > 注意：测试连接只验证 Bot Token（getMe）；频道权限是否正确由「上传测试」第 2/3 步验证。
2. **文件夹与授权 Tab → 新建文件夹**（默认用上面的配置，可见性选私有）；
3. 点该文件夹的 **授权** → 添加一行：主体 `USER`，主体 ID 填你的用户 ID（如 `2009007`），权限勾 `READ,UPLOAD,UPDATE,DELETE` → 保存。

---

## 十一、第 9 步：上传测试（一键验证全链路）

**上传测试 Tab** → 选择测试文件夹 → **开始测试**。四个环节依次亮灯：

| 步骤 | 验证的东西 | 失败时说明 |
|---|---|---|
| 1. 凭证签发 | 后端密钥、文件夹权限、MIME/大小校验 | 见故障表 |
| 2. Worker 直传 | Worker 部署、凭证验签、Bot Token | 见故障表 |
| 3. 回调登记 | Bot 频道发消息权限、回调 HMAC | 见故障表 |
| 4. 下载验证 | 下载签名、回源、边缘缓存 | 见故障表 |

全绿即部署完成，之后就能在「文件管理」正常上传了。

**对象存储配置（R2/S3/七牛/COS/OSS/又拍云）共用此测试**：步骤文案与前置条件会按所选文件夹绑定的存储类型自动切换——
- 凭证签发 → **预签名 PUT / 表单直传**（浏览器直传对象存储，不经过 Worker）；
- 登记 → Astral HEAD 确认对象真实存在后落库；
- 下载验证 → 各 Provider 预签名下载地址（又拍云为空间绑定域名 + Token `_upt`）；
- 前置条件提示各家的桶 CORS 配置要求（允许来自管理端域名的 PUT/GET）。

文件夹下拉框会显示每个文件夹绑定的存储类型（如「图片（腾讯COS）」），未绑定配置的文件夹走默认配置。

---

## 十二、故障排查表

| 现象 | 原因 | 处理 |
|---|---|---|
| 步骤1 `STORAGE013` | 文件夹没授权当前用户 | 补 USER 授权，勾 UPLOAD |
| 步骤1 `STORAGE024` | 后端缺 `STORAGE_UPLOAD_TICKET_KEY` | 配置环境变量并重启 |
| 步骤1 `STORAGE005/006` | MIME 不在白名单 / 超过 20MiB | 换 jpg/png/webp/gif，控制大小 |
| 步骤2 `Worker 返回 404` | Worker 没部署 / 配置里的地址写错 | 核对 `<WORKER_URL>`，`/healthz` 先通 |
| 步骤2 `401 invalid ticket` | 两端 `STORAGE_UPLOAD_TICKET_KEY` 不一致或凭证过期 | 核对密钥；重试 |
| 步骤3 `telegram upload failed: chat not found` | Chat ID 错 / Bot 不在频道 | 核对 Chat ID 与 Bot 管理员身份 |
| 步骤3 `... not enough rights` | Bot 缺发消息权限 | 频道管理员权限勾选发布消息 |
| 步骤3 `callback failed` | 两端 `STORAGE_ORIGIN_SHARED_SECRET` 不一致 / Worker 的 ASTRAL_ORIGIN_BASE_URL 错 | 核对密钥与变量 |
| 步骤4 `403` | 两端 `STORAGE_DOWNLOAD_SIGNING_KEY_V1` 不一致 | 核对密钥 |
| 测试连接 DOWN | Worker 不可达（未部署/域名错） | 先用浏览器开 `/healthz` |
| 删除后文件没从 Telegram 消失 | cron 未配置 / Bot 缺删消息权限 | 检查 Cron Triggers 与频道权限；看「任务」Tab 的重试记录 |

---

## 十三、（增补 2026-09-13）R2 / S3 存储与永久公开链接

### 13.1 R2 / S3 兼容存储

MVP 之外的第二类 Provider：浏览器凭 Astral 签发的 **S3 SigV4 预签名 PUT 地址** 直传对象存储，完成后回 Astral 登记元数据（服务端 HEAD 桶确认对象真实存在）。全程不经过 Worker，也不经过 Astral 服务器。

**配置路径**：管理端 → 文件存储 → 存储配置 → 新建配置 → 存储类型选 `Cloudflare R2` 或 `S3 兼容存储`，填：

| 字段 | R2 示例 | AWS S3 示例 |
|---|---|---|
| S3 Endpoint | `https://<account_id>.r2.cloudflarestorage.com` | `https://s3.us-east-1.amazonaws.com` |
| Bucket | `astral-storage` | `my-bucket` |
| Region | `auto` | `us-east-1` |
| Access Key ID / Secret | R2 控制台 → Manage R2 API Tokens | IAM AccessKey |
| 公开访问域名（可选） | R2 桶公开访问的 `https://pub-xxx.r2.dev` 或自定义域名 | CloudFront / 网站端点 |

- Secret Access Key 保存后接口只返回打码 `******`，编辑时留空表示保持不变；
- 「测试连接」= HEAD 桶，通过即凭据与端点正确（R2 的 S3 API 走 path-style，无需额外设置）；
- 上传/下载 URL 均为预签名地址（下载仍为短时）；删除为同步直删；
- Telegram 配置与 R2/S3 配置可并存，文件夹按「存储配置」绑定各自通道。

### 13.1.1 （增补 2026-09-13）国内主流存储：腾讯 COS / 阿里 OSS / 七牛 / 又拍云

与 R2/S3 同一套浏览器直传架构，仅签名算法不同（均为服务端签发、浏览器直传、服务端 HEAD 校验后登记、同步删除）。

**腾讯云 COS**（存储类型 `COS`）：
| 字段 | 示例 / 说明 |
|---|---|
| Bucket | `astral-1250000000`（必须带 APPID 后缀） |
| Region | `ap-guangzhou` 等地域码 |
| SecretId / SecretKey | 访问管理 CAM → API 密钥 |
| 公开访问域名（可选） | 桶默认域名 `https://<bucket>.cos.<region>.myqcloud.com` 或自定义 CDN 域名 |

- 直传 = COS 预签名 PUT（`sign=` 单参数 URL 签名）；下载 = 预签名 GET；「测试连接」= GetBucket（max-keys=1）。

**阿里云 OSS**（存储类型 `OSS`）：
| 字段 | 示例 / 说明 |
|---|---|
| Bucket | 桶名 |
| Endpoint | `oss-cn-hangzhou.aliyuncs.com`（地域域名，不带协议/桶前缀） |
| AccessKeyId / AccessKeySecret | RAM 访问密钥 |
| 公开访问域名（可选） | 桶绑定的自定义域名或外网 endpoint 域名 |

- 签名为 OSS V1（`OSS {AK}:{Base64(HmacSHA1(SK, ...))}`）；直传 = URL 签名 PUT（Expires 绝对时间戳）；下载 = URL 签名 GET；「测试连接」= GetBucket。

**七牛云**（存储类型 `QINIU`）：走七牛的 **S3 兼容网关**，存储类型选 `七牛云（S3 网关）`，按 S3 填写——
- Endpoint：`https://s3.cn-east-1.qiniu.com`（华东）/ `https://s3.cn-north-1.qiniu.com`（华北）等 S3 区域端点（七牛控制台 → 对象存储 → S3 高可用域名）；
- Bucket / Region：七牛空间名与对应 S3 区域（如 `cn-east-1`）；
- Access Key / Secret Key：七牛个人密钥对。
签名与 R2/S3 完全一致（SigV4），无需单独适配代码。

**又拍云**（存储类型 `UPYUN`）：
| 字段 | 示例 / 说明 |
|---|---|
| 服务名（Bucket） | 又拍云的服务名 |
| 操作员名 / 操作员密码 | 服务的操作员账号（密码参与签名：HMAC-SHA1 密钥 = md5(密码)） |
| API 线路域名（可选） | 默认 `v0.api.upyun.com`（智能选路） |
| 空间绑定域名 | 必填——下载/永久链接走该域名（REST 不支持 URL 预签名） |
| Token 防盗链密钥（可选） | 控制台「防盗链 → Token 防盗链」密钥；配置后下载 URL 附 `_upt` 签名 |

- 直传 = **表单 API**（multipart POST `policy` + `authorization` + `file`，凭证随 upload-ticket 下发）；HEAD/删除走 REST Header 签名（`UPYUN {op}:{Base64(HmacSHA1(md5(pwd), "METHOD&URI&DATE"))}`）；「测试连接」= GET `/{bucket}/?usage`。
- 下载 URL：配置了 tokenKey 时为 `{域名}/{key}?_upt={md5(key&etime&path) 中间8位}{etime}`（有时效）；未配置时为裸公开 URL（空间需允许公开访问）。
- 注意：又拍云的私有空间如未开 Token 防盗链，下载链接等于公开直链，请在又拍云控制台开启 Token 防盗链并填入密钥。

**通用行为**（四家与 R2/S3 一致）：
- 所有密钥（secretAccessKey/secretKey/accessKeySecret/password/tokenKey）接口返回时打码 `******`，编辑留空保持不变；
- 公开文件永久链接 = `{publicBaseUrl}/{对象键}`；对象键形如 `astral/{publicId}/v1/{文件名}`，publicId 全局唯一不会覆盖；
- 删除均为服务端同步直删（对象不存在视为成功）；TELEGRAM 配置可与以上任意配置并存，文件夹按绑定路由。

### 13.2 永久公开链接（/p/）

- 管理端「文件管理」→ 每个文件新增 **永久** 按钮：仅 `PUBLIC` 可见文件可生成，私有点击会提示先转公开；
- **TELEGRAM 文件**：`{Worker域名}/p/{publicId}/{contentVersion}`——无签名、任何人可访问；Worker 回源校验可见性后从 Telegram 取流并写边缘缓存（1 天）；文件转私有或删除时 contentVersion 递增，旧链接在边缘缓存过期后失效（最长 1 天窗口）；
- **R2/S3 及国内对象存储（COS/OSS/七牛/又拍云）文件**：`{publicBaseUrl}/{对象键}` 直链（需配置 publicBaseUrl 且桶开启公开访问）；
- Worker 需要**重新部署**才有 `/p/` 路由（`npx wrangler deploy`）。

---

## 十四、安全须知

- 4 个值（3 密钥 + Bot Token）**永远不进 Git、聊天、截图**；只存在于后端环境变量与 Worker Secret；
- Worker Secret 与后端环境变量必须成对一致，轮换时两端同步换；
- `getUpdates` 链接内含 Token，只在自己浏览器临时打开；
- 私有文件不进边缘缓存；公开文件缓存 300 秒，删除/转私有后最多 5 分钟全球失效；
- Telegram 不是带 SLA 的存储，重要文件请另行备份（后续可做 R2 双写）。
