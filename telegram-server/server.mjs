import http from 'node:http';
import { Readable } from 'node:stream';
import { pathToFileURL } from 'node:url';
import worker from './worker.mjs';

/** Never log Bot Token (embedded in Telegram URLs) or credential-bearing query strings. */
function safeError(error) {
  return String(error?.message || error || 'unknown error')
    .replace(/bot[^/\s]+/g, 'bot***')
    .replace(/([?&](?:ticket|token|s|signature)=)[^&\s]+/gi, '$1***');
}

// Server mode deliberately has no shared edge cache. Keep the original Worker unchanged.
// Its Cache-Control headers still control public browser/proxy caching; private files are no-store.
globalThis.caches = { default: { async match() { return undefined; }, async put() {} } };

export function validateEnv(env) {
  for (const name of ['TG_BOT_TOKEN', 'STORAGE_UPLOAD_TICKET_KEY', 'STORAGE_ORIGIN_SHARED_SECRET',
    'STORAGE_DOWNLOAD_SIGNING_KEY_V1', 'ASTRAL_ORIGIN_BASE_URL']) {
    if (!env[name]?.trim()) throw new Error(`Missing environment variable: ${name}`);
  }
  const origin = new URL(env.ASTRAL_ORIGIN_BASE_URL);
  if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password || origin.search || origin.hash) {
    throw new Error('ASTRAL_ORIGIN_BASE_URL must be an HTTP(S) URL without credentials, query or fragment');
  }
}

function positiveInteger(value, fallback, name) {
  const result = value === undefined ? fallback : Number(value);
  if (!Number.isSafeInteger(result) || result <= 0) throw new Error(`Invalid ${name}`);
  return result;
}

export function createGateway(env, handler = worker) {
  validateEnv(env);
  const maxConcurrent = positiveInteger(env.MAX_CONCURRENT_REQUESTS, 8, 'MAX_CONCURRENT_REQUESTS');
  // Worker parses multipart formData before forwarding. Bound total input, including multipart overhead.
  const maxBody = 21 * 1024 * 1024;
  let active = 0;
  const server = http.createServer(async (req, res) => {
    if (active >= maxConcurrent) {
      res.writeHead(503, { 'Content-Type': 'application/json', 'Retry-After': '5', Connection: 'close' });
      res.end('{"ok":false,"message":"gateway busy"}');
      return;
    }
    active++;
    try {
      const path = req.url || '/';
      if (!path.startsWith('/') || path.startsWith('//')) {
        res.writeHead(400); res.end(); return;
      }
      const length = req.headers['content-length'];
      if (length && (!/^\d+$/.test(length) || Number(length) > maxBody)) {
        res.writeHead(413, { Connection: 'close' }); res.end(); return;
      }
      let size = 0;
      const input = Readable.toWeb(Readable.from((async function* () {
        for await (const chunk of req) {
          size += chunk.length;
          if (size > maxBody) throw new Error('request body too large');
          yield chunk;
        }
      })()));
      const method = req.method || 'GET';
      const headers = new Headers();
      for (const [name, value] of Object.entries(req.headers)) {
        if (value !== undefined) headers.set(name, Array.isArray(value) ? value.join(', ') : value);
      }
      // Fixed origin: no trust in client Host/X-Forwarded-* for security-sensitive canonical paths.
      const request = new Request(`http://gateway.internal${path}`, {
        method, headers,
        ...(!['GET', 'HEAD'].includes(method) ? { body: input, duplex: 'half' } : {}),
      });
      let response = await handler.fetch(request, env);
      // The original Worker health handler returns upstream error strings. Do not expose
      // them on the server, as network errors can contain credential-bearing URLs.
      if (new URL(request.url).pathname === '/healthz' && !response.ok) {
        response = Response.json({ ok: false, message: 'telegram health check failed' }, { status: 502 });
      }
      res.writeHead(response.status, Object.fromEntries(response.headers));
      if (!response.body || method === 'HEAD') { res.end(); return; }
      await new Promise((resolve, reject) => {
        const output = Readable.fromWeb(response.body);
        const close = () => { output.destroy(); resolve(); };
        res.once('close', close);
        output.once('error', reject);
        res.once('error', reject);
        res.once('finish', resolve);
        output.pipe(res);
      });
    } catch {
      // Never log request URLs/tickets, Bot Token or upstream exception messages.
      if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' });
      res.end('{"ok":false,"message":"gateway request failed"}');
    } finally { active--; }
  });
  server.requestTimeout = 120_000;
  server.headersTimeout = 30_000;
  return server;
}

export function startDeleteScheduler(env, handler = worker) {
  const interval = positiveInteger(env.DELETE_INTERVAL_SECONDS, 60, 'DELETE_INTERVAL_SECONDS') * 1000;
  let running = false;
  const timer = setInterval(async () => {
    if (running) return;
    running = true;
    try {
      const result = await handler.scheduled({}, env);
      // Surface rejected pulls (HMAC/clock/permission) instead of hiding them behind one generic line.
      if (result && result.ok === false) console.error(`Telegram delete task poll failed: ${result.reason}`);
      else if (result && result.failed) {
        console.error(`Telegram delete tasks failed: ${result.failed}/${result.pulled}${result.firstError ? ` — ${result.firstError}` : ''}`);
      }
    } catch (error) {
      console.error(`Telegram delete task poll failed: ${safeError(error)}`);
    } finally { running = false; }
  }, interval);
  timer.unref();
  return timer;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  // Bound all outbound API calls, including Telegram transfers and Astral callbacks.
  const nativeFetch = globalThis.fetch;
  const timeout = positiveInteger(process.env.UPSTREAM_TIMEOUT_SECONDS, 120, 'UPSTREAM_TIMEOUT_SECONDS') * 1000;
  globalThis.fetch = (url, options = {}) => nativeFetch(url, {
    ...options,
    ...(options.body instanceof ReadableStream ? { duplex: 'half' } : {}),
    signal: options.signal
      ? AbortSignal.any([options.signal, AbortSignal.timeout(timeout)]) : AbortSignal.timeout(timeout),
  });
  const server = createGateway(process.env);
  const timer = startDeleteScheduler(process.env);
  const port = positiveInteger(process.env.PORT, 8787, 'PORT');
  server.listen(port, process.env.HOST || '127.0.0.1', () => console.log(`Telegram storage gateway listening on port ${port}`));
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => {
    clearInterval(timer);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 10_000).unref();
  });
}
