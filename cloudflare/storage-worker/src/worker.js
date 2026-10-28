/**
 * Astral Storage Worker（Telegram + Cloudflare 图床分发）
 *
 * 职责：
 *  1. POST /upload?ticket=...  校验 Astral 签发的上传凭证，把文件流式转发到 Telegram sendDocument，
 *     再回调 Astral 登记元数据。文件正文不落盘、不完整驻留内存。
 *     登记成功后把仍在内存中的字节预热进边缘缓存（prewarmCaches：/_mcache、/_tgcache，
 *     PUBLIC 文件再写 /_pcache、/_cache，PRIVATE 文件写 /_ucache），使永久/签名链接的首次访问即边缘命中。
 *  2. GET  /f/{publicId}/{contentVersion}?e&v&s  校验下载签名（与 Astral 逐字节一致的规范串），
 *     命中边缘缓存直接返回；未命中回源 Astral 取定位元数据后从 Telegram 取流。
 *     公开内容进 /_cache（7 天），私有内容进 /_ucache（15 分钟，单 PoP；对外仍声明 private）。
 *     图片业务上限 5MB，不需要分片/断点续传，因此本路径不实现 Range；速度靠命中率与 PoP 选择。
 *  3. GET  /p/{publicId}/{contentVersion}  永久公开链接：无签名，仅 PUBLIC 可见文件可访问，
 *     边缘长缓存（7 天）；可见性变更时版本递增使旧 URL 失效。
 *  4. GET  /healthz  执行 Telegram getMe，供 Astral「测试连接」。
 *  5. scheduled（cron）  拉取 Astral 远端删除任务，执行 deleteMessage 并确认。
 *  6. DELETE /cache|/pcache|/mcache/...  边缘缓存主动失效（Astral 删除文件/停用配置时调用，
 *     HMAC 鉴权同服务身份，见 handleCachePurge）；撤销延迟从边缘缓存 TTL 收敛到秒级。
 *
 * Secret（wrangler secret put，禁止写入本文件/仓库）：
 *  - TG_BOT_TOKEN                     Telegram Bot Token（仅保存在 Worker）
 *  - STORAGE_UPLOAD_TICKET_KEY        与 Astral 共享：上传凭证 HMAC
 *  - STORAGE_ORIGIN_SHARED_SECRET     与 Astral 共享：服务身份 HMAC
 *  - STORAGE_DOWNLOAD_SIGNING_KEY_V1  与 Astral 共享：下载 URL 签名
 * vars（wrangler.toml [vars]）：
 *  - ASTRAL_ORIGIN_BASE_URL           Astral 后端公网 https 地址
 *
 * HMAC 规范串（必须与 com.astral.storage.security.StorageHmac 一致）：
 *  - Worker→Astral：`METHOD\nPATH\nTIMESTAMP\nNONCE`，其中 PATH **不含 query string**
 *    （Astral 用 request.getRequestURI() 拼接，永远不带 query；带 query 的路径必须先剥离再签名）
 *  - 下载签名：      `GET\n/f/{publicId}/{contentVersion}\n{expiresEpoch}\n{keyVersion}`
 *  - 上传凭证：      sig = b64url(HMAC(ticketKey, payloadB64))，ticket = payloadB64 + "." + sig
 */

const TELEGRAM_API = 'https://api.telegram.org';
const MAX_STREAM_SIZE = 20 * 1024 * 1024; // 公共 Bot API 下载上限，双保险（业务侧另有 5MB 图片上限）
// 图片体积只有几十 KB～5MB，没有分片/断点续传的需求，所以下载路径不做 Range 切片；
// 速度全部押在「命中边缘」和「连对 PoP」两件事上，因此 TTL 比撤销窗口重要得多。
//
// 公开内容边缘缓存：撤销不依赖 TTL——可见性/内容变更会递增 contentVersion（StorageFileService.changeVisibility），
// 旧 URL 连同旧缓存键一起作废；删除/停用另有 DELETE /cache 主动失效兜底。
// 原来 3600s 意味着每小时都要重走「回源元数据→getFile→拉流」三跳，不变内容没有理由付这个成本。
const PUBLIC_CACHE_TTL = 7 * 24 * 3600;
const PERMANENT_CACHE_TTL = 7 * 24 * 3600; // 永久公开链接 /p/ 的边缘缓存（秒）；版本变化即换 URL
// 私有内容（反馈截图、私密附件）此前 `no-store` 且从不写 Cache API，等于每次翻看都要四跳回源
// （签名校验 → 广东元数据 → getFile → 拉流）。Cache API 是「单 PoP」缓存，不会把私有内容扩散到
// 其它地区，按短 TTL 缓存是安全的；对外仍声明 private，且键前缀 `_ucache` 与公开内容隔离。
const PRIVATE_CACHE_TTL = 900;
const PRIVATE_CLIENT_CACHE_TTL = 60;      // 回给浏览器的 private 缓存（签名 URL 本身 300s 过期）
// 回源元数据缓存：(publicId, contentVersion) 内字段不可变，TTL 只兜「删除/停用但主动失效失败」的窗口。
// 300s 与签名 TTL 同阶曾让每次翻页都打一跳广东，抬到 1h；撤销仍由 DELETE /mcache 收敛到秒级。
const META_CACHE_TTL = 3600;
// Telegram getFile 结果缓存：官方保证 file 链接在 getFile 后至少 1 小时有效，取 30 分钟留安全边际。
const TG_PATH_CACHE_TTL = 1800;
// 缓存副本上的内部标记：公开副本不带，私有副本带；命中时据此把 Cache-Control 改写回 private。
// （Cache API 的存储声明必须是 public，才能稳定落盘；对外口径不能因此变成 public。）
const VISIBILITY_HEADER = 'X-Astral-Cache-Visibility';

// 允许读取签名 URL 的浏览器来源（逗号分隔，如 https://web.canace.cn）。
// 空 = 不回任何 Access-Control-Allow-Origin（默认最安全）。此前恒为 '*'：
// 签名 URL 一旦被记录（聊天记录、Referer、日志），任何站点都能用 JS 取回文件内容，
// 对可下载的私密文件等于绕过「只有链接持有者可见」的前提。
const ALLOWED_DOWNLOAD_ORIGINS = (env) =>
  String(env?.ALLOWED_DOWNLOAD_ORIGINS || '')
    .split(',')
    .map((v) => v.trim())
    .filter(Boolean);

/** 按请求 Origin 回显 CORS（仅命中白名单时），并把 Vary 标出来避免缓存串味 */
function downloadCorsHeaders(request, env) {
  const origin = request.headers.get('Origin');
  if (!origin || !ALLOWED_DOWNLOAD_ORIGINS(env).includes(origin)) {
    return { Vary: 'Origin' };
  }
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Range',
    Vary: 'Origin',
  };
}

/**
 * 构造写进 Cache API 的副本。
 * <p>三点与直出响应不同，都是有意的：<br>
 * 1. 不带 CORS：同一个 URL 会被多个站点引用，固化某个 Origin 的 ACAO 等于给所有来源发权限；<br>
 * 2. `Cache-Control` 恒为 public：Cache API 只有在存储声明为 public 时才稳定落盘，私有语义靠
 *    {@link VISIBILITY_HEADER} 标记、命中时再改写，不能指望把 private 塞进存储声明；<br>
 * 3. 带上 `Content-Length`：有了长度浏览器才能画进度、图片解码器也能更早分配缓冲。</p>
 */
function cacheableResponse(body, meta, contentType, contentLength, ttl, isPublic) {
  const headers = new Headers({
    ...safeContentHeaders(meta, contentType),
    ...(contentLength ? { 'Content-Length': contentLength } : {}),
    'Cache-Control': `public, max-age=${ttl}`,
  });
  if (!isPublic) headers.set(VISIBILITY_HEADER, 'PRIVATE');
  return new Response(body, { status: 200, headers });
}

/**
 * 命中边缘缓存时的出站处理：去掉内部标记、把私有副本改回 `private`、按请求 Origin 补 CORS。
 * <p>私有内容命中后浏览器端只缓 60s（签名 URL 自身 300s 过期），不把「长缓存」语义外泄。</p>
 */
function fromCache(cached, request, env) {
  const headers = new Headers(cached.headers);
  const visibility = headers.get(VISIBILITY_HEADER);
  headers.delete(VISIBILITY_HEADER);
  if (visibility === 'PRIVATE') {
    headers.set('Cache-Control', `private, max-age=${PRIVATE_CLIENT_CACHE_TTL}`);
  }
  for (const [name, value] of Object.entries(downloadCorsHeaders(request, env))) headers.set(name, value);
  return new Response(cached.body, { status: cached.status, headers });
}

/** 写入边缘缓存：不阻塞响应（否则 TG→边缘 的耗时会被算进用户 TTFB），失败只记日志 */
function storeInCache(ctx, cacheKey, response) {
  const put = caches.default.put(cacheKey, response).catch((e) => {
    console.warn(`[cache] store skipped: ${safeError(e)}`);
  });
  if (ctx) ctx.waitUntil(put);
  return put;
}

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};

// ==================== 下载内容安全头 ====================

/**
 * 视为「可内联安全」的图片类型白名单。
 * <p>不含 image/svg+xml：SVG 是 XML 文档，里面可挂 script / onload / xlink:href，
 * 一旦被浏览器按图片内联渲染就是同源存储型 XSS。Worker 域与站点域重合时后果最重。</p>
 */
const INLINE_SAFE_IMAGE_MIMES = new Set([
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'image/bmp',
  'image/avif',
]);

function normalizedMime(raw, fallback) {
  const value = String(raw || '').toLowerCase().split(';')[0].trim();
  return value || fallback;
}

/**
 * 生成下载响应的安全头。
 * <p>策略：只有「白名单内的图片」允许 {@code inline}（图床/头像要能直接显示），
 * 其它一律 {@code attachment}（浏览器下载而不是渲染），并统一加 {@code nosniff}，
 * 让浏览器不再按内容猜类型 —— 「上传 HTML 冒充图片」这条路因此走不通。</p>
 */
function safeContentHeaders(meta, contentType) {
  const mime = normalizedMime(meta?.contentType || contentType, 'application/octet-stream');
  const inline = INLINE_SAFE_IMAGE_MIMES.has(mime);
  const disposition = inline ? 'inline' : 'attachment';
  return {
    'Content-Type': mime,
    'Content-Disposition': `${disposition}; filename="${sanitizeFilename(meta?.fileName)}"`,
    'X-Content-Type-Options': 'nosniff',
    // 被直接打开（例如在标签页里访问）时也禁掉脚本与插件执行；
    // 内联图片加 sandbox 会阻止 <img> 引用正常渲染，故只对外链/附件生效。
    ...(inline ? {} : { 'Content-Security-Policy': "default-src 'none'; sandbox" }),
  };
}

// ==================== 工具 ====================

function b64UrlEncode(buffer) {
  const bytes = new Uint8Array(buffer);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64UrlDecode(str) {
  const normalized = str.replace(/-/g, '+').replace(/_/g, '/');
  const pad = (4 - (normalized.length % 4)) % 4;
  const bin = atob(normalized + '='.repeat(pad));
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

function hexEncode(buffer) {
  return [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function hmacKey(secret) {
  return crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}

async function hmacSignBytes(key, data) {
  return crypto.subtle.sign('HMAC', key, data);
}

async function hmacVerifyBytes(key, data, sigBuffer) {
  return crypto.subtle.verify('HMAC', key, sigBuffer, data);
}

function jsonResp(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json;charset=UTF-8', ...CORS_HEADERS, ...extraHeaders },
  });
}

function errorResp(message, status = 403) {
  return jsonResp({ ok: false, message }, status);
}

function stripTrailingSlash(url) {
  return url.endsWith('/') ? url.slice(0, -1) : url;
}

function sanitizeFilename(name) {
  const cleaned = (name || 'file').replace(/[\r\n"\\]/g, '_');
  return cleaned.length > 200 ? cleaned.slice(-200) : cleaned;
}

/** 日志里的异常文本可能含 Bot Token（Telegram URL）或凭证 query，必须先脱敏 */
function safeError(error) {
  return String(error?.message || error || 'unknown error')
    .replace(/bot[^/\s]+/g, 'bot***')
    .replace(/([?&](?:ticket|token|s|signature)=)[^&\s]+/gi, '$1***');
}

// ==================== Worker → Astral 服务身份 ====================

/**
 * 规范串里的 PATH 必须与 Astral 的 request.getRequestURI() 完全一致：**不含 query**。
 * Astral 侧 StorageWorkerAuthInterceptor 用 getRequestURI() 拼接规范串，
 * 因此带 `?limit=10` 的删除任务拉取若把 query 签进去，签名必然不匹配（STORAGE017）。
 */
function canonicalPath(path) {
  const query = path.indexOf('?');
  return query === -1 ? path : path.slice(0, query);
}

async function originHeaders(env, method, path) {
  const key = await hmacKey(env.STORAGE_ORIGIN_SHARED_SECRET);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const nonce = crypto.randomUUID();
  const canonical = `${method}\n${canonicalPath(path)}\n${timestamp}\n${nonce}`;
  const signature = b64UrlEncode(await hmacSignBytes(key, new TextEncoder().encode(canonical)));
  return {
    'X-Storage-Origin-Timestamp': timestamp,
    'X-Storage-Origin-Nonce': nonce,
    'X-Storage-Origin-Signature': signature,
    'Content-Type': 'application/json',
  };
}

async function astralFetch(env, path, init = {}) {
  const headers = await originHeaders(env, init.method || 'GET', path);
  return fetch(stripTrailingSlash(env.ASTRAL_ORIGIN_BASE_URL) + path, { ...init, headers: { ...headers, ...(init.headers || {}) } });
}

// ==================== 上传：凭证校验 + Telegram 流式转发 ====================

async function verifyTicket(env, ticket) {
  const dot = ticket.lastIndexOf('.');
  if (dot <= 0) return null;
  const payloadB64 = ticket.slice(0, dot);
  const sig = ticket.slice(dot + 1);
  const key = await hmacKey(env.STORAGE_UPLOAD_TICKET_KEY);
  const ok = await hmacVerifyBytes(key, new TextEncoder().encode(payloadB64), b64UrlDecode(sig));
  if (!ok) return null;
  let payload;
  try {
    payload = JSON.parse(new TextDecoder().decode(b64UrlDecode(payloadB64)));
  } catch {
    return null;
  }
  if (!payload.uploadId || payload.exp * 1000 < Date.now()) return null;
  return payload;
}

/** 把浏览器文件流与 multipart 边界拼接为 Telegram sendDocument 请求体（全程流式，不落内存） */
function multipartBody(chatId, fileStream, filename, contentType) {
  const boundary = '----astral' + crypto.randomUUID().replaceAll('-', '');
  const head = new TextEncoder().encode(
    `--${boundary}\r\nContent-Disposition: form-data; name="chat_id"\r\n\r\n${chatId}\r\n` +
    `--${boundary}\r\nContent-Disposition: form-data; name="document"; filename="${sanitizeFilename(filename)}"\r\n` +
    `Content-Type: ${contentType}\r\n\r\n`);
  const tail = new TextEncoder().encode(`\r\n--${boundary}--\r\n`);
  const reader = fileStream.getReader();
  let stage = 0; // 0=head 1=file 2=tail
  let sent = 0;
  const stream = new ReadableStream({
    async pull(controller) {
      if (stage === 0) {
        stage = 1;
        controller.enqueue(head);
        return;
      }
      if (stage === 1) {
        const { done, value } = await reader.read();
        if (done) {
          stage = 2;
          return;
        }
        sent += value.byteLength;
        if (sent > MAX_STREAM_SIZE) {
          controller.error(new Error('file too large'));
          return;
        }
        controller.enqueue(value);
        return;
      }
      controller.enqueue(tail);
      controller.close();
    },
    cancel(reason) {
      return reader.cancel(reason);
    },
  });
  return { body: stream, contentType: `multipart/form-data; boundary=${boundary}` };
}

async function handleUpload(request, env, ctx) {
  const url = new URL(request.url);
  const ticket = url.searchParams.get('ticket') || '';
  const payload = await verifyTicket(env, ticket);
  if (!payload) return errorResp('invalid or expired upload ticket', 401);

  const form = await request.formData();
  const file = form.get('file');
  if (!file || typeof file === 'string') return errorResp('missing file field');

  const mime = (file.type || 'application/octet-stream').toLowerCase();
  if (payload.mime && mime !== payload.mime && !mime.startsWith('image/')) {
    return errorResp('mime type not allowed', 415);
  }

  const mp = multipartBody(payload.chatId, file.stream(), file.name, mime);
  const tgResp = await fetch(`${TELEGRAM_API}/bot${env.TG_BOT_TOKEN}/sendDocument`, {
    method: 'POST',
    headers: { 'Content-Type': mp.contentType },
    body: mp.body,
  });
  const tgJson = await tgResp.json();
  if (!tgJson.ok) {
    return jsonResp({ ok: false, message: 'telegram upload failed', detail: tgJson.description }, 502);
  }
  const doc = tgJson.result.document || {};
  const callback = {
    uploadId: payload.uploadId,
    messageId: String(tgJson.result.message_id),
    telegramFileId: doc.file_id,
    fileUniqueId: doc.file_unique_id,
    sizeBytes: doc.file_size || file.size || 0,
    contentType: doc.mime_type || mime,
    fileName: file.name || '',
  };
  const cbResp = await astralFetch(env, '/api/v1/all/storage/worker/upload-callback', {
    method: 'POST',
    body: JSON.stringify(callback),
  });
  const cbJson = await cbResp.json().catch(() => ({}));
  if (cbResp.status !== 200 || cbJson.code !== 200) {
    // Telegram 已接收但登记失败：上传结果未知，管理员需核对频道（不自动重传）
    return jsonResp({ ok: false, message: 'upload registered at telegram but callback failed', uploadId: payload.uploadId }, 502);
  }
  // 上传预热：文件字节此刻仍在内存里，直接写入边缘缓存，让首次访问（永久/签名链接）
  // 呈边缘命中，不再走「回源元数据→getFile→拉流」三跳。验证失败（FAILED）不预热。
  if (ctx) {
    ctx.waitUntil(prewarmCaches(env, url.origin, file, callback, cbJson.data || {}));
  }
  return jsonResp({ ok: true, publicId: cbJson.data.publicId });
}

/**
 * 上传预热：登记响应带回 publicId/contentVersion/visibility/status（WorkerCallbackResp），
 * 按下载路由的缓存键布局写 /_mcache + /_tgcache，PUBLIC 再写 /_pcache + /_cache。
 * 内容键的 origin 必须与下载请求的 url.origin 一致（cache 键是完整 URL 字符串）。
 * 任何失败只放弃预热，不影响上传结果。
 */
async function prewarmCaches(env, origin, uploadFile, callback, registered) {
  try {
    if (registered.status && registered.status !== 'AVAILABLE') return; // 内容验证失败等状态不预热
    const publicId = registered.publicId;
    const contentVersion = String(registered.contentVersion || 1);
    const visibility = registered.visibility || 'PRIVATE';
    // 与 Astral 的 normalizeMime 对齐（去参数、小写），保证预热元数据与回源元数据一致
    const mime = (callback.contentType || 'application/octet-stream').toLowerCase().split(';')[0].trim();
    const cache = caches.default;
    const meta = {
      publicId,
      contentVersion: Number(contentVersion),
      status: 'AVAILABLE',
      visibility,
      contentType: mime,
      sizeBytes: callback.sizeBytes,
      fileName: callback.fileName,
      telegramFileId: callback.telegramFileId,
    };
    await cache.put(new URL(`/_mcache/${publicId}/${contentVersion}`, origin).toString(), new Response(JSON.stringify(meta), {
      headers: { 'Content-Type': 'application/json', 'Cache-Control': `public, max-age=${META_CACHE_TTL}` },
    }));
    await getTelegramFilePath(env, callback.telegramFileId);
    if (uploadFile) {
      const bytes = await uploadFile.arrayBuffer();
      if (visibility === 'PUBLIC') {
        // 与 handleDownload / handlePermanentDownload 用同一套策略，
        // 否则预热出来的缓存副本会绕过安全头（命中缓存直接回 inline）
        const baseHeaders = safeContentHeaders(meta);
        // /_pcache/：永久链接内容（与 handlePermanentDownload 同键同 TTL）
        await cache.put(new URL(`/_pcache/${publicId}/${contentVersion}`, origin).toString(), new Response(bytes, {
          headers: { ...baseHeaders, 'Cache-Control': `public, max-age=${PERMANENT_CACHE_TTL}, s-maxage=${PERMANENT_CACHE_TTL}` },
        }));
        // /_cache/：公开内容（与 handleDownload 同键同 TTL，跨签名 URL 复用）
        await cache.put(new URL(`/_cache/${publicId}/${contentVersion}`, origin).toString(), new Response(bytes, {
          headers: { ...baseHeaders, 'Cache-Control': `public, max-age=${PUBLIC_CACHE_TTL}, s-maxage=${PUBLIC_CACHE_TTL}` },
        }));
      } else {
        // 私有内容：字节此刻同样在内存里，顺手写一份单 PoP 短缓存，
        // 让上传者自己的回看、管理台预览也不用再走四跳回源
        await cache.put(
          new URL(`/_ucache/${publicId}/${contentVersion}`, origin).toString(),
          cacheableResponse(bytes, meta, mime, bytes.byteLength, PRIVATE_CACHE_TTL, false),
        );
      }
    }
  } catch (e) {
    console.warn(`[prewarm] skipped: ${safeError(e)}`);
  }
}

// ==================== 下载：验签 + 缓存 + 回源 ====================

async function verifyDownloadSignature(env, url) {
  const keyVersion = Number(url.searchParams.get('v') || '0');
  const expires = Number(url.searchParams.get('e') || '0');
  const sig = url.searchParams.get('s') || '';
  if (!sig || keyVersion !== 1) return false;
  if (expires * 1000 < Date.now()) return false;
  const canonical = `GET\n${url.pathname}\n${expires}\n${keyVersion}`;
  const key = await hmacKey(env.STORAGE_DOWNLOAD_SIGNING_KEY_V1);
  return hmacVerifyBytes(key, new TextEncoder().encode(canonical), b64UrlDecode(sig));
}

/** 回源元数据缓存：同 (publicId, contentVersion) 内容不可变，短 TTL 只兜删除/停用窗口 */
async function getOriginMeta(env, url, publicId, contentVersion) {
  const cacheKey = new URL(`/_mcache/${publicId}/${contentVersion}`, url.origin).toString();
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) {
    const meta = await cached.json();
    if (meta && !meta.__miss) return meta;
    return null;
  }
  const originResp = await astralFetch(env, `/api/v1/all/storage/origin/files/${publicId}/${contentVersion}`);
  const originJson = await originResp.json().catch(() => ({}));
  if (originResp.status !== 200 || originJson.code !== 200) {
    // 404（删除/版本过期）也缓存一小段，避免有效期内签名 URL 反复穿透回源；命中 __miss 直接判未找到
    await cache.put(cacheKey, new Response(JSON.stringify({ __miss: true }), {
      headers: { 'Content-Type': 'application/json', 'Cache-Control': `public, max-age=${META_CACHE_TTL}` },
    }));
    return null;
  }
  const meta = originJson.data;
  await cache.put(cacheKey, new Response(JSON.stringify(meta), {
    headers: { 'Content-Type': 'application/json', 'Cache-Control': `public, max-age=${META_CACHE_TTL}` },
  }));
  return meta;
}

/** Telegram getFile 缓存：file 链接官方保证 ≥1h 有效，缓存 30 分钟；Telegram 侧故障期间命中缓存可继续出图 */
// getFile 缓存键使用独立合成 origin（Cache API 只把 URL 字符串当键，不做 DNS）；
// put/match/delete 必须用同一字符串，purge 端点同样引用该常量。
const TG_CACHE_ORIGIN = 'https://cache.internal';

async function getTelegramFilePath(env, telegramFileId) {
  const cacheKey = new URL(`/_tgcache/${encodeURIComponent(telegramFileId)}`, TG_CACHE_ORIGIN).toString();
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) {
    const j = await cached.json();
    if (j && j.path) return j.path;
    return null;
  }
  const fileResp = await fetch(`${TELEGRAM_API}/bot${env.TG_BOT_TOKEN}/getFile?file_id=${encodeURIComponent(telegramFileId)}`);
  const fileJson = await fileResp.json();
  if (!fileJson.ok || !fileJson.result?.file_path) return null;
  await cache.put(cacheKey, new Response(JSON.stringify({ path: fileJson.result.file_path }), {
    headers: { 'Content-Type': 'application/json', 'Cache-Control': `public, max-age=${TG_PATH_CACHE_TTL}` },
  }));
  return fileJson.result.file_path;
}

async function handleDownload(request, env, ctx) {
  const url = new URL(request.url);
  if (!(await verifyDownloadSignature(env, url))) {
    return errorResp('forbidden', 403); // 签名错误/过期/无权：统一 403，不泄露存在性
  }

  const match = url.pathname.match(/^\/f\/([\w-]+)\/(\d+)$/);
  if (!match) return errorResp('not found', 404);
  const [, publicId, contentVersion] = match;

  // 可见性决定缓存键前缀，所以元数据必须先查（_mcache 自身也是边缘缓存，命中时不多一跳）
  const meta = await getOriginMeta(env, url, publicId, contentVersion);
  if (!meta) return errorResp('not found', 404);

  const isPublic = meta.visibility === 'PUBLIC';
  const cacheKey = new URL(`/_${isPublic ? 'cache' : 'ucache'}/${publicId}/${contentVersion}`, url.origin).toString();
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) return fromCache(cached, request, env);

  const filePath = await getTelegramFilePath(env, meta.telegramFileId);
  if (!filePath) return errorResp('not found', 404);
  const content = await fetch(`${TELEGRAM_API}/file/bot${env.TG_BOT_TOKEN}/${filePath}`);
  if (!content.ok || !content.body) return errorResp('not found', 404);

  const contentType = content.headers.get('Content-Type');
  const contentLength = content.headers.get('Content-Length');
  const ttl = isPublic ? PUBLIC_CACHE_TTL : PRIVATE_CACHE_TTL;
  // clone() 把上游流一分为二：一份进边缘缓存、一份回客户端，两条支路并行推进
  const stored = cacheableResponse(content.body, meta, contentType, contentLength, ttl, isPublic);
  const response = new Response(stored.clone().body, {
    status: 200,
    headers: new Headers({
      ...safeContentHeaders(meta, contentType),
      ...(contentLength ? { 'Content-Length': contentLength } : {}),
      'Cache-Control': isPublic
        ? `public, max-age=${PUBLIC_CACHE_TTL}, s-maxage=${PUBLIC_CACHE_TTL}`
        : `private, max-age=${PRIVATE_CLIENT_CACHE_TTL}`,
      ...downloadCorsHeaders(request, env),
    }),
  });
  // 公开与私有内容都落缓存（私有落在独立键前缀里，只在当前 PoP 生效）；
  // 写入不 await：等整段 body 落盘才回响应，等于把 TG→边缘 的耗时算进用户 TTFB
  storeInCache(ctx, cacheKey, stored);
  return response;
}

// ==================== 永久公开链接：/p/{publicId}/{contentVersion} ====================
// 仅 PUBLIC 可见文件可访问（回源校验可见性）；内容按 contentVersion 版本化缓存，
// 可见性/内容变更时 Astral 递增版本，旧 URL 在边缘缓存过期后自然失效。

async function handlePermanentDownload(request, env, ctx) {
  const url = new URL(request.url);
  const match = url.pathname.match(/^\/p\/([\w-]+)\/(\d+)$/);
  if (!match) return errorResp('not found', 404);
  const [, publicId, contentVersion] = match;

  const cacheKey = new URL(`/_pcache/${publicId}/${contentVersion}`, url.origin).toString();
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) return fromCache(cached, request, env);

  const meta = await getOriginMeta(env, url, publicId, contentVersion);
  if (!meta) return errorResp('not found', 404);
  if (meta.visibility !== 'PUBLIC') {
    // 私有内容不通过永久链接暴露：统一 404，不泄露存在性
    return errorResp('not found', 404);
  }

  const filePath = await getTelegramFilePath(env, meta.telegramFileId);
  if (!filePath) return errorResp('not found', 404);
  const content = await fetch(`${TELEGRAM_API}/file/bot${env.TG_BOT_TOKEN}/${filePath}`);
  if (!content.ok || !content.body) return errorResp('not found', 404);

  const contentType = content.headers.get('Content-Type');
  const contentLength = content.headers.get('Content-Length');
  const stored = cacheableResponse(content.body, meta, contentType, contentLength, PERMANENT_CACHE_TTL, true);
  const response = new Response(stored.clone().body, {
    status: 200,
    headers: new Headers({
      ...safeContentHeaders(meta, contentType),
      ...(contentLength ? { 'Content-Length': contentLength } : {}),
      'Cache-Control': `public, max-age=${PERMANENT_CACHE_TTL}, s-maxage=${PERMANENT_CACHE_TTL}`,
      ...downloadCorsHeaders(request, env),
    }),
  });
  storeInCache(ctx, cacheKey, stored);
  return response;
}

// ==================== 缓存主动失效（Astral 删除/停用配置时调用） ====================
// Astral 在删除文件或停用配置时逐 Worker 发 DELETE 请求清除对应边缘缓存键，
// 把撤销延迟从「边缘缓存 TTL」收敛到秒级。鉴权复用 Worker→Astral 同款服务身份
// HMAC（X-Storage-Origin-* 头，STORAGE_ORIGIN_SHARED_SECRET），不新增共享密钥。
// 支持的键（与 getOriginMeta/getTelegramFilePath/handleDownload/handlePermanentDownload 一致）：
//   DELETE /cache/{publicId}/{contentVersion}   公开内容缓存（/_cache/...）
//   DELETE /ucache/{publicId}/{contentVersion}  私有内容缓存（/_ucache/...，单 PoP 短 TTL）
//   DELETE /pcache/{publicId}/{contentVersion}  永久链接内容缓存（/_pcache/...）
//   DELETE /mcache/{publicId}/{contentVersion}  回源元数据缓存（/_mcache/...）
//   DELETE /tgcache/{telegramFileId}            Telegram getFile 结果缓存（/_tgcache/...）

const ORIGIN_TIMESTAMP_WINDOW_SECONDS = 300; // 与 Astral origin-timestamp-window-seconds 一致

async function handleCachePurge(request, env) {
  const url = new URL(request.url);
  const timestamp = request.headers.get('X-Storage-Origin-Timestamp') || '';
  const nonce = request.headers.get('X-Storage-Origin-Nonce') || '';
  const signature = request.headers.get('X-Storage-Origin-Signature') || '';
  if (!env.STORAGE_ORIGIN_SHARED_SECRET || !timestamp || !nonce || !signature) {
    return errorResp('forbidden', 403);
  }
  const ts = Number(timestamp);
  if (!Number.isFinite(ts) || Math.abs(Date.now() / 1000 - ts) > ORIGIN_TIMESTAMP_WINDOW_SECONDS) {
    return errorResp('forbidden', 403);
  }
  const canonical = `DELETE\n${canonicalPath(url.pathname)}\n${timestamp}\n${nonce}`;
  const key = await hmacKey(env.STORAGE_ORIGIN_SHARED_SECRET);
  const ok = await hmacVerifyBytes(key, new TextEncoder().encode(canonical), b64UrlDecode(signature));
  if (!ok) return errorResp('forbidden', 403);

  const metaMatch = url.pathname.match(/^\/(cache|pcache|mcache|ucache)\/([\w-]+)\/(\d+)$/);
  const tgMatch = url.pathname.match(/^\/tgcache\/(.+)$/);
  if (!metaMatch && !tgMatch) return errorResp('not found', 404);

  const purged = metaMatch
    ? await caches.default.delete(new URL(`/_${metaMatch[1]}/${metaMatch[2]}/${metaMatch[3]}`, url.origin).toString())
    : await caches.default.delete(new URL(`/_tgcache/${encodeURIComponent(tgMatch[1])}`, TG_CACHE_ORIGIN).toString());
  return jsonResp({ ok: true, purged });
}

// ==================== 健康检查与远端删除任务 ====================

async function handleHealth(env) {
  try {
    const resp = await fetch(`${TELEGRAM_API}/bot${env.TG_BOT_TOKEN}/getMe`);
    const json = await resp.json();
    if (!json.ok) return jsonResp({ ok: false }, 502);
    return jsonResp({ ok: true, bot: json.result.username });
  } catch (e) {
    return jsonResp({ ok: false, message: String(e) }, 502);
  }
}

/** 拉取删除任务 → deleteMessage → 确认 */
async function processDeleteTasks(env) {
  const resp = await astralFetch(env, '/api/v1/all/storage/worker/tasks/delete?limit=10');
  // 先取原始文本再解析：resp.json() 遇到非 JSON 会抛异常，被 catch 成 {} 后
  // 只剩「HTTP 200 code n/a」这种查不出原因的日志（ASTRAL_ORIGIN_BASE_URL 指错、
  // 被前置代理/静态站点接走返回 HTML 时就是这样）。
  const raw = await resp.text().catch(() => '');
  let json = {};
  try {
    json = JSON.parse(raw);
  } catch {
    // 非 JSON 响应，json 保持 {}，下面把 content-type 和正文片段带进原因里
  }
  if (resp.status !== 200 || json.code !== 200 || !Array.isArray(json.data)) {
    // 静默 return 曾让签名不匹配（STORAGE017）在线上完全不可见，必须留下原因
    const contentType = resp.headers.get('content-type') || 'n/a';
    const preview = safeError(raw).replace(/\s+/g, ' ').slice(0, 200) || '<empty>';
    return { ok: false, reason: `astral pull rejected: HTTP ${resp.status} code ${json.code ?? 'n/a'}${json.message ? ' ' + json.message : ''} content-type=${contentType} body=${preview}` };
  }
  let deleted = 0;
  let failed = 0;
  // 只报「10/10 失败」等于没报：Telegram 拒绝删除的原因（机器人不是频道管理员、
  // 消息超过 48 小时、chat_id/message_id 不对）全在 description 里，必须带出来。
  let firstError = null;
  for (const task of json.data) {
    let success = false;
    let errorMessage = null;
    try {
      const delResp = await fetch(`${TELEGRAM_API}/bot${env.TG_BOT_TOKEN}/deleteMessage`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chat_id: task.chatId, message_id: Number(task.messageId) }),
      });
      const delJson = await delResp.json();
      success = Boolean(delJson.ok);
      if (!success) errorMessage = delJson.description || 'telegram delete failed';
    } catch (e) {
      errorMessage = safeError(e);
    }
    await astralFetch(env, `/api/v1/all/storage/worker/tasks/${task.taskId}/ack`, {
      method: 'POST',
      body: JSON.stringify({ success, errorMessage }),
    });
    if (success) deleted++;
    else {
      failed++;
      if (!firstError) firstError = `taskId=${task.taskId} chatId=${task.chatId} messageId=${task.messageId} ${errorMessage}`;
    }
  }
  return { ok: true, pulled: json.data.length, deleted, failed, firstError };
}

// ==================== 入口 ====================

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }
    const url = new URL(request.url);
    try {
      if (request.method === 'POST' && url.pathname === '/upload') {
        return await handleUpload(request, env, ctx);
      }
      if (request.method === 'GET' && url.pathname.startsWith('/f/')) {
        return await handleDownload(request, env, ctx);
      }
      if (request.method === 'GET' && url.pathname.startsWith('/p/')) {
        return await handlePermanentDownload(request, env, ctx);
      }
      if (request.method === 'GET' && url.pathname === '/healthz') {
        return await handleHealth(env);
      }
      if (request.method === 'DELETE') {
        return await handleCachePurge(request, env);
      }
      return errorResp('not found', 404);
    } catch (e) {
      return jsonResp({ ok: false, message: 'internal error' }, 500);
    }
  },

  async scheduled(event, env) {
    const result = await processDeleteTasks(env);
    if (result && result.ok === false) {
      // wrangler tail 可见：拉取被拒（如 STORAGE017 签名不匹配）不再静默
      console.error(`Telegram delete task poll failed: ${result.reason}`);
    } else if (result && result.failed) {
      console.error(`Telegram delete tasks failed: ${result.failed}/${result.pulled}${result.firstError ? ` — ${result.firstError}` : ''}`);
    }
  },
};
