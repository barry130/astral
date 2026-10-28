// 下载响应头策略的静态/行为校验（node --test，无需 wrangler）
//
// 为什么值得单独测：Worker 的下载路径决定「上传的内容会不会在浏览器里被执行」。
// 历史上它一律 `Content-Disposition: inline` + 无 MIME 白名单 + `Access-Control-Allow-Origin: *`，
// 一旦 Worker 域与站点域重合就是同源存储型 XSS，签名 URL 也不再是「只有持有者可见」。
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(here, 'worker.js'), 'utf8');

test('下载不再无条件内联', () => {
  assert.ok(!/'Content-Disposition': `inline; filename/.test(source),
    '不得存在无条件 inline 的 Content-Disposition');
  assert.ok(/INLINE_SAFE_IMAGE_MIMES/.test(source), '必须有内联白名单');
  assert.ok(/attachment/.test(source), '非白名单类型必须走 attachment');
});

test('内联白名单不含 SVG', () => {
  const block = source.slice(source.indexOf('INLINE_SAFE_IMAGE_MIMES'), source.indexOf(']);', source.indexOf('INLINE_SAFE_IMAGE_MIMES')));
  assert.ok(!/svg/.test(block), 'SVG 可内联脚本，不得进内联白名单: ' + block);
  assert.ok(/image\/png/.test(block), '常见位图仍应内联（图床要能直接显示）');
});

test('下载响应带 nosniff', () => {
  assert.ok(/X-Content-Type-Options': 'nosniff'/.test(source), '缺 nosniff 时浏览器会猜类型');
});

test('签名下载 URL 的 CORS 不再是通配', () => {
  const start = source.indexOf('function downloadCorsHeaders');
  const downloadCors = source.slice(start, source.indexOf('\n}', start));
  assert.ok(!/Access-Control-Allow-Origin': '\*'/.test(downloadCors),
    '下载链路不得回显 *（签名 URL 外流即可被任意站点读取）');
  assert.ok(/ALLOWED_DOWNLOAD_ORIGINS/.test(downloadCors), '必须按白名单回显 Origin');
});

test('预热缓存副本与直出用同一套安全头', () => {
  assert.ok(/safeContentHeaders\(meta\)/.test(source),
    'prewarm 写的边缘缓存必须用同一策略，否则命中缓存就绕过安全头');
});

test('缓存写入不阻塞响应', () => {
  assert.ok(/function storeInCache\(ctx, cacheKey, response\)/.test(source), '缓存写入必须走统一封装');
  const body = source.slice(source.indexOf('function storeInCache'), source.indexOf('\n}', source.indexOf('function storeInCache')));
  assert.ok(/ctx\.waitUntil\(put\)/.test(body), '缓存落盘放进 waitUntil，别把 TG→边缘 的耗时算进 TTFB');
  assert.ok(!/await cache\.put\(cacheKey, response\.clone\(\)\)/.test(source),
    '不得再 await 整段 body 落盘后才回响应');
});

test('私有内容进单 PoP 缓存但对外声明 private', () => {
  assert.ok(/const PRIVATE_CACHE_TTL = \d+/.test(source), '私有内容要有自己的短 TTL');
  assert.ok(/X-Astral-Cache-Visibility/.test(source), '缓存副本需要可见性标记，命中后才能改回 private');
  assert.ok(/private, max-age=\$\{PRIVATE_CLIENT_CACHE_TTL\}/.test(source),
    '私有内容回给浏览器必须是 private，不得因缓存声明变成 public');
  assert.ok(!/'private, no-store'/.test(source), '私有内容不再是 no-store：那等于每次翻看都四跳回源');
});

test('公开内容边缘缓存以天计', () => {
  assert.ok(/const PUBLIC_CACHE_TTL = 7 \* 24 \* 3600/.test(source), '公开内容 TTL 至少按天，撤销靠版本递增与主动失效');
  assert.ok(/const PERMANENT_CACHE_TTL = 7 \* 24 \* 3600/.test(source), '永久链接与公开内容同量级');
});

test('下载响应透传 Content-Length', () => {
  assert.ok(/content\.headers\.get\('Content-Length'\)/.test(source), '有长度客户端才能画进度、图片更早解码');
  assert.ok(/'Content-Length': contentLength/.test(source), '长度要写进响应头');
});

test('私有内容缓存支持主动失效', () => {
  assert.ok(source.includes('mcache|ucache'), 'purge 端点必须覆盖 _ucache');
});
