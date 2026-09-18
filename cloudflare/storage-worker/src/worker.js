/**
 * Astral Storage Worker（Telegram + Cloudflare 图床分发）
 *
 * 职责：
 *  1. POST /upload?ticket=...  校验 Astral 签发的上传凭证，把文件流式转发到 Telegram sendDocument，
 *     再回调 Astral 登记元数据。文件正文不落盘、不完整驻留内存。
 *  2. GET  /f/{publicId}/{contentVersion}?e&v&s  校验下载签名（与 Astral 逐字节一致的规范串），
 *     命中边缘缓存直接返回；未命中回源 Astral 取定位元数据后从 Telegram 取流。
 *     公开文件缓存（s-maxage=300），私有文件不进共享缓存。
 *  3. GET  /p/{publicId}/{contentVersion}  永久公开链接：无签名，仅 PUBLIC 可见文件可访问，
 *     边缘长缓存（s-maxage=86400）；可见性变更时版本递增使旧 URL 失效。
 *  4. GET  /healthz  执行 Telegram getMe，供 Astral「测试连接」。
 *  5. scheduled（cron）  拉取 Astral 远端删除任务，执行 deleteMessage 并确认。
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
 *  - Worker→Astral：`METHOD\nPATH\nTIMESTAMP\nNONCE`
 *  - 下载签名：      `GET\n/f/{publicId}/{contentVersion}\n{expiresEpoch}\n{keyVersion}`
 *  - 上传凭证：      sig = b64url(HMAC(ticketKey, payloadB64))，ticket = payloadB64 + "." + sig
 */

const TELEGRAM_API = 'https://api.telegram.org';
const MAX_STREAM_SIZE = 20 * 1024 * 1024; // 公共 Bot API 下载上限，双保险
const PUBLIC_CACHE_TTL = 300;             // 公开内容边缘缓存（秒）= MVP 撤销窗口
const PERMANENT_CACHE_TTL = 86400;        // 永久公开链接 /p/ 的边缘缓存（秒）；版本变化即换 URL

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
};

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

// ==================== Worker → Astral 服务身份 ====================

async function originHeaders(env, method, path) {
  const key = await hmacKey(env.STORAGE_ORIGIN_SHARED_SECRET);
  const timestamp = String(Math.floor(Date.now() / 1000));
  const nonce = crypto.randomUUID();
  const canonical = `${method}\n${path}\n${timestamp}\n${nonce}`;
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

async function handleUpload(request, env) {
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
  return jsonResp({ ok: true, publicId: cbJson.data.publicId });
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

async function handleDownload(request, env) {
  const url = new URL(request.url);
  if (!(await verifyDownloadSignature(env, url))) {
    return errorResp('forbidden', 403); // 签名错误/过期/无权：统一 403，不泄露存在性
  }

  const match = url.pathname.match(/^\/f\/([\w-]+)\/(\d+)$/);
  if (!match) return errorResp('not found', 404);
  const [, publicId, contentVersion] = match;

  const cacheKey = new URL(`/_cache/${publicId}/${contentVersion}`, url.origin).toString();
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) return cached;

  const originResp = await astralFetch(env, `/api/v1/all/storage/origin/files/${publicId}/${contentVersion}`);
  const originJson = await originResp.json().catch(() => ({}));
  if (originResp.status !== 200 || originJson.code !== 200) {
    return errorResp('not found', 404);
  }
  const meta = originJson.data;

  const fileResp = await fetch(`${TELEGRAM_API}/bot${env.TG_BOT_TOKEN}/getFile?file_id=${encodeURIComponent(meta.telegramFileId)}`);
  const fileJson = await fileResp.json();
  if (!fileJson.ok || !fileJson.result?.file_path) return errorResp('not found', 404);
  const content = await fetch(`${TELEGRAM_API}/file/bot${env.TG_BOT_TOKEN}/${fileJson.result.file_path}`);
  if (!content.ok || !content.body) return errorResp('not found', 404);

  const isPublic = meta.visibility === 'PUBLIC';
  const headers = new Headers({
    'Content-Type': meta.contentType || content.headers.get('Content-Type') || 'application/octet-stream',
    'Content-Disposition': `inline; filename="${sanitizeFilename(meta.fileName)}"`,
    'X-Content-Type-Options': 'nosniff',
    'Cache-Control': isPublic
      ? `public, max-age=${PUBLIC_CACHE_TTL}, s-maxage=${PUBLIC_CACHE_TTL}`
      : 'private, no-store',
    ...CORS_HEADERS,
  });
  const response = new Response(content.body, { status: 200, headers });
  if (isPublic) {
    // 仅公开内容进入共享边缘缓存；私有内容绝不缓存
    await cache.put(cacheKey, response.clone());
  }
  return response;
}

// ==================== 永久公开链接：/p/{publicId}/{contentVersion} ====================
// 仅 PUBLIC 可见文件可访问（回源校验可见性）；内容按 contentVersion 版本化缓存，
// 可见性/内容变更时 Astral 递增版本，旧 URL 在边缘缓存过期后自然失效。

async function handlePermanentDownload(request, env) {
  const url = new URL(request.url);
  const match = url.pathname.match(/^\/p\/([\w-]+)\/(\d+)$/);
  if (!match) return errorResp('not found', 404);
  const [, publicId, contentVersion] = match;

  const cacheKey = new URL(`/_pcache/${publicId}/${contentVersion}`, url.origin).toString();
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) return cached;

  const originResp = await astralFetch(env, `/api/v1/all/storage/origin/files/${publicId}/${contentVersion}`);
  const originJson = await originResp.json().catch(() => ({}));
  if (originResp.status !== 200 || originJson.code !== 200) {
    return errorResp('not found', 404);
  }
  const meta = originJson.data;
  if (meta.visibility !== 'PUBLIC') {
    // 私有内容不通过永久链接暴露：统一 404，不泄露存在性
    return errorResp('not found', 404);
  }

  const fileResp = await fetch(`${TELEGRAM_API}/bot${env.TG_BOT_TOKEN}/getFile?file_id=${encodeURIComponent(meta.telegramFileId)}`);
  const fileJson = await fileResp.json();
  if (!fileJson.ok || !fileJson.result?.file_path) return errorResp('not found', 404);
  const content = await fetch(`${TELEGRAM_API}/file/bot${env.TG_BOT_TOKEN}/${fileJson.result.file_path}`);
  if (!content.ok || !content.body) return errorResp('not found', 404);

  const headers = new Headers({
    'Content-Type': meta.contentType || content.headers.get('Content-Type') || 'application/octet-stream',
    'Content-Disposition': `inline; filename="${sanitizeFilename(meta.fileName)}"`,
    'X-Content-Type-Options': 'nosniff',
    'Cache-Control': `public, max-age=${PERMANENT_CACHE_TTL}, s-maxage=${PERMANENT_CACHE_TTL}`,
    ...CORS_HEADERS,
  });
  const response = new Response(content.body, { status: 200, headers });
  await cache.put(cacheKey, response.clone());
  return response;
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
  const json = await resp.json().catch(() => ({}));
  if (resp.status !== 200 || json.code !== 200 || !Array.isArray(json.data)) return;
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
      errorMessage = String(e);
    }
    await astralFetch(env, `/api/v1/all/storage/worker/tasks/${task.taskId}/ack`, {
      method: 'POST',
      body: JSON.stringify({ success, errorMessage }),
    });
  }
}

// ==================== 入口 ====================

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }
    const url = new URL(request.url);
    try {
      if (request.method === 'POST' && url.pathname === '/upload') {
        return await handleUpload(request, env);
      }
      if (request.method === 'GET' && url.pathname.startsWith('/f/')) {
        return await handleDownload(request, env);
      }
      if (request.method === 'GET' && url.pathname.startsWith('/p/')) {
        return await handlePermanentDownload(request, env);
      }
      if (request.method === 'GET' && url.pathname === '/healthz') {
        return await handleHealth(env);
      }
      return errorResp('not found', 404);
    } catch (e) {
      return jsonResp({ ok: false, message: 'internal error' }, 500);
    }
  },

  async scheduled(event, env) {
    await processDeleteTasks(env);
  },
};
