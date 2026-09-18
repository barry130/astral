# Astral Storage Worker（Telegram 图床分发）

Cloudflare Worker 承担所有文件流量：浏览器把文件直传 Worker，Worker 流式转发 Telegram；
下载时 Worker 验签、缓存并回源。Astral 后端只处理权限、签名和元数据，不接触文件正文。

## 架构

```text
上传：浏览器 ──ticket──> Worker ──流──> Telegram sendDocument
              ▲                    │ 回调（HMAC）
              └── ticket ── Astral ┘ （Astral 只收几 KB 元数据）

下载：浏览器 ──签名URL──> Worker ──缓存命中──> 直接返回
                          └─未命中─> Astral 元数据（HMAC）─> Telegram getFile ─> 流式返回
删除：Worker cron ─> 拉取任务 ─> Telegram deleteMessage ─> 确认 Astral
```

## 部署步骤

1. `cp wrangler.toml.example wrangler.toml`，填入 `account_id` 与 `ASTRAL_ORIGIN_BASE_URL`；
2. `npx wrangler deploy`；
3. 配置四个 Secret（与 Astral 后端环境变量值完全一致）：
   ```bash
   npx wrangler secret put TG_BOT_TOKEN
   npx wrangler secret put STORAGE_UPLOAD_TICKET_KEY
   npx wrangler secret put STORAGE_ORIGIN_SHARED_SECRET
   npx wrangler secret put STORAGE_DOWNLOAD_SIGNING_KEY_V1
   ```
4. （可选）绑定自定义域名：Dashboard → Workers → astral-storage → Settings → Domains & Routes；
5. 在 Astral 管理端「文件存储 → 存储配置」保存 Worker 地址并执行「测试连接」（走 /healthz）。

## 与 Astral 的契约

- HMAC 规范串：`METHOD\nPATH\nTIMESTAMP\nNONCE`（Worker→Astral）与
  `GET\n/f/{publicId}/{contentVersion}\n{expiresEpoch}\n{keyVersion}`（下载签名）。
  任何修改必须同步 `com.astral.storage.security.StorageHmac` 并更新固定测试向量。
- 上传凭证：`base64url(payload).sig`，payload 含 uploadId/configId/folderId/chatId/maxSize/mime/exp。
- 缓存：仅 `visibility=PUBLIC` 进共享边缘缓存（s-maxage=300）；私有文件 no-store。
  删除/转私有后的撤销窗口 = 300 秒（MVP 不做全局 purge）。
- 上传结果未知（Telegram 已接收但回调失败）不自动重传，由管理员核对频道。

## 本地开发

`npx wrangler dev` 需要 `.dev.vars`（已 gitignore）：

```text
TG_BOT_TOKEN=...
STORAGE_UPLOAD_TICKET_KEY=...
STORAGE_ORIGIN_SHARED_SECRET=...
STORAGE_DOWNLOAD_SIGNING_KEY_V1=...
ASTRAL_ORIGIN_BASE_URL=https://...
```
