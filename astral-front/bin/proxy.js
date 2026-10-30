/**
 * 零依赖边缘代理：镜像的 3000 端口入口。
 *
 * 为什么需要它：
 *   Next.js 的 rewrites（next.config.js）在 `next build` 时求值，destination 以
 *   字面量序列化进 standalone 产物（实测 server.js 内 `_originalRewrites` 为
 *   "http://localhost:27000/api/:path*"，且 standalone 里没有 next.config.js），
 *   运行时改 BACKEND_URL 环境变量不会生效。要让「同一个镜像」谁都能用、换后端
 *   只改环境变量重启（不重打包），就把 /api 的转发从构建期挪到启动期：
 *
 *     浏览器 → :3000 (proxy.js) ─┬─ /api/**  → BACKEND_URL（启动时读取，默认 http://backend:27000）
 *                                └─ 其余     → 127.0.0.1:NEXT_PORT（Next standalone，默认 3001）
 *
 * 浏览器 bundle 里 baseURL 为空串（同源相对路径），因此不存在 CORS；
 * Cookie 由代理原样透传（satoken / astral_csrf 均不关心路径域名差异）。
 *
 * 设计约束：只用 node:http/node:net（runner 阶段无 npm install）；流式管道
 * （req→upstream、upstream→res），不缓冲 body，支持大文件与 SSE/WebSocket 升级。
 */
'use strict';

const http = require('node:http');
const net = require('node:net');

// ── 配置（启动时求值一次；换值 = 重启容器） ─────────────────────────────
const LISTEN_PORT = Number(process.env.PORT || 3000);
const NEXT_PORT = Number(process.env.NEXT_PORT || 3001);
const BACKEND_URL = process.env.BACKEND_URL || 'http://backend:27000';

let backendHost = 'backend';
let backendPort = 27000;
try {
  const u = new URL(BACKEND_URL);
  backendHost = u.hostname;
  backendPort = Number(u.port) || (u.protocol === 'https:' ? 443 : 80);
  // 简化实现只支持 http 后端（compose 内网与自建后端均为此形态）；
  // https 后端请在前置 nginx/网关终止 TLS 后以 http 暴露。
  if (u.protocol === 'https:') {
    console.warn(`[proxy] BACKEND_URL 是 https，本代理仅支持 http 上游：${BACKEND_URL}`);
    process.exit(1);
  }
} catch {
  console.error(`[proxy] BACKEND_URL 不是合法 URL：${BACKEND_URL}`);
  process.exit(1);
}

const NEXT_HOST = '127.0.0.1';
const HOP_BY_HOP = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade',
]);

/**
 * 收集上游响应头为 writeHead 可用的对象（Node 25 起 writeHead 之后禁止 setHeader，
 * 必须一次性传入）。去掉逐跳头（connection 由 http.Agent 重新协商）；
 * set-cookie 多条时合并为数组（writeHead 对数组值按多条发送）。
 */
function pickHeaders(src) {
  const out = {};
  const setCookies = [];
  for (let i = 0; i < src.rawHeaders.length; i += 2) {
    const name = src.rawHeaders[i];
    const lname = name.toLowerCase();
    if (HOP_BY_HOP.has(lname)) continue;
    if (lname === 'set-cookie') { setCookies.push(src.rawHeaders[i + 1]); continue; }
    out[name] = src.rawHeaders[i + 1];
  }
  if (setCookies.length) out['set-cookie'] = setCookies;
  return out;
}

/** 向上游发起请求并双向接管该次交换 */
function pipe(req, res, targetHost, targetPort, stripPrefix) {
  const headers = { ...req.headers };
  headers.host = `${targetHost}:${targetPort}`;
  headers['x-forwarded-host'] = req.headers.host || '';
  const via = req.headers['x-forwarded-for'];
  headers['x-forwarded-for'] = via ? `${via}, ${req.socket.remoteAddress}` : req.socket.remoteAddress;
  headers['x-forwarded-proto'] = req.headers['x-forwarded-proto'] || 'http';

  // WebSocket / SSE：保留逐跳头，让 upstream 自行升级
  const isUpgrade = req.headers.upgrade != null;
  const upstream = http.request(
    {
      host: targetHost,
      port: targetPort,
      method: req.method,
      path: stripPrefix ? req.url.replace(/^\/api/, '') || '/' : req.url,
      headers,
      agent: false,
    },
    isUpgrade ? undefined : (upRes) => {
      // 502 分支已 writeHead 过（响应已开始），此时不能再 setHeader，直接透传 body
      if (res.writableEnded || res.headersSent) return;
      res.writeHead(upRes.statusCode || 502, upRes.statusMessage || '', pickHeaders(upRes));
      if (typeof res.pipe === 'function' && typeof res.on === 'function') upRes.pipe(res);
      else upRes.resume(); // upgrade 场景的假 response：body 由 socket 双向接管，丢弃即可
    },
  );
  if (isUpgrade) {
    // 关键：upgrade 请求不挂 response 监听器（挂了会吞掉 'upgrade' 事件），
    // 拒绝升级时上游的普通响应从这里兜底透传
    upstream.on('response', (upRes) => {
      console.warn(`[proxy] ${targetHost}:${targetPort}${req.url} 上游拒绝升级，回 ${upRes.statusCode}`);
      res.writeHead(upRes.statusCode || 502, upRes.statusMessage || '', pickHeaders(upRes));
      upRes.pipe(res);
    });
  }
  upstream.on('upgrade', (upRes, upSocket) => {
    // WebSocket 代理：回 101 并把 socket 交给两端
    upSocket.setTimeout(0);
    upSocket.setNoDelay(true);
    const lines = [`HTTP/1.1 ${upRes.statusCode} ${upRes.statusMessage || ''}`];
    for (let i = 0; i < upRes.rawHeaders.length; i += 2) {
      if (HOP_BY_HOP.has(upRes.rawHeaders[i].toLowerCase())) continue;
      lines.push(`${upRes.rawHeaders[i]}: ${upRes.rawHeaders[i + 1]}`);
    }
    res.socket.write(lines.join('\r\n') + '\r\n\r\n');
    upSocket.pipe(res.socket);
    res.socket.pipe(upSocket);
    const teardown = () => { upSocket.destroy(); res.socket.destroy(); };
    upSocket.on('error', teardown);
    res.socket.on('error', teardown);
  });
  upstream.on('error', (err) => {
    console.error(`[proxy] ${targetHost}:${targetPort}${req.url} → ${err.message}`);
    if (res.headersSent) return res.destroy();
    res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ code: 502, msg: `Bad gateway: ${targetHost}:${targetPort} 不可达`, data: null }));
  });
  if (isUpgrade) {
    req.on('error', () => upstream.destroy());
    upstream.end(); // 必须 flush 请求头，否则上游永远收不到升级请求（无 body 可隐式 end）
    return;
  }
  req.pipe(upstream);
  req.on('error', () => upstream.destroy());
}

const server = http.createServer((req, res) => {
  const url = req.url || '/';
  // Next 的 HMR / 静态资源：静态资源在 Next 侧处理；本代理不做缓存
  if (url === '/api' || url.startsWith('/api/')) {
    // stripPrefix=false：后端路由自身带 /api 前缀（见 GlobalExceptionHandler 契约）
    pipe(req, res, backendHost, backendPort, false);
  } else {
    pipe(req, res, NEXT_HOST, NEXT_PORT, false);
  }
});

// ── Next 子进程管理 ─────────────────────────────────────────────────────
// proxy.js 是容器 1 号进程：负责拉起 standalone server.js、转发信号、
// 子进程退出时跟着退出（compose restart 策略才能接管重启）。
// 仅在 RUN_NEXT=1（镜像 CMD 默认）时启用；本地调试设 RUN_NEXT=0 即纯代理。
function startNextChild() {
  const { spawn } = require('node:child_process');
  const child = spawn('node', ['server.js'], {
    stdio: 'inherit',
    env: { ...process.env, PORT: String(NEXT_PORT), HOSTNAME: NEXT_HOST },
  });
  child.on('exit', (code, signal) => {
    console.error(`[proxy] Next 子进程退出 (code=${code} signal=${signal})，代理随之退出`);
    process.exit(code == null ? 1 : code);
  });
  return child;
}
if (process.env.RUN_NEXT !== '0') startNextChild();

// WebSocket 升级（Next HMR 用 ws）：非 /api 全部交给 Next
server.on('upgrade', (req, socket) => {
  // 必须先挂 error 监听：客户端断开时 socket 会发 ECONNRESET，未处理会打崩整个进程
  socket.on('error', () => socket.destroy());
  const url = req.url || '/';
  if (url.startsWith('/api/')) {
    // 后端若有 ws 也透传
    pipe(req, { socket, headersSent: true, destroy: () => socket.destroy(), writeHead: () => {}, setHeader: () => {}, end: () => {} },
      backendHost, backendPort, false);
    return;
  }
  pipe(req, { socket, headersSent: true, destroy: () => socket.destroy(), writeHead: () => {}, setHeader: () => {}, end: () => {} },
    NEXT_HOST, NEXT_PORT, false);
});

server.listen(LISTEN_PORT, () => {
  console.log(`[proxy] :${LISTEN_PORT} → /api → ${backendHost}:${backendPort} | 其余 → 127.0.0.1:${NEXT_PORT}`);
});

// 优雅退出：compose stop 时尽快释放端口，并让 Next 子进程同收信号
for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => {
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref();
  });
}

// 保持对 net 的引用说明（lint：未使用导入）——供未来 TCP 探活扩展
void net;
