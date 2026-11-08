'use strict';
// HTTP/compiled-artifact verification only; no browser or public network requests.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const fixture = path.resolve(process.argv[2] || '');
assert.ok(fixture.startsWith(path.join(root, 'tests', '.artifacts') + path.sep));
const receipt = JSON.parse(fs.readFileSync(path.join(fixture, 'build-receipt.json'), 'utf8'));
assert.equal(receipt.status, 0);
assert.equal(receipt.publicSiteUrl, 'https://blog.audit.example');
const env = { ...process.env, NEXT_PUBLIC_SITE_URL: receipt.publicSiteUrl, NEXT_PUBLIC_ADMIN_URL: receipt.publicAdminUrl };
for (const invalid of ['', 'http://localhost:3001', 'https://127.0.0.1', 'https://example.test/path', 'https://fixture:fixture@example.test', 'https://example.test/?x=1']) {
  const result = spawnSync(process.execPath, [path.join(root, 'scripts/validate-public-build-urls.mjs')], {
    env: { ...env, NEXT_PUBLIC_SITE_URL: invalid }, encoding: 'utf8', windowsHide: true,
  });
  assert.notEqual(result.status, 0, invalid);
}
assert.equal(spawnSync(process.execPath, [path.join(root, 'scripts/validate-public-build-urls.mjs')], { env, windowsHide: true }).status, 0);
const processes = [];
function start(args, cwd, extra = {}) {
  const child = spawn(process.execPath, args, { cwd, env: { ...env, ...extra }, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  let output = '';
  child.stdout.on('data', (data) => { output = (output + data).slice(-4000); });
  child.stderr.on('data', (data) => { output = (output + data).slice(-4000); });
  processes.push(child);
  return { child, output: () => output };
}
async function ready(process, url) {
  for (let attempt = 0; attempt < 60; attempt++) {
    if (process.child.exitCode !== null) throw new Error(process.output());
    try { const response = await fetch(url, { signal: AbortSignal.timeout(2000) }); if (response.ok) return; } catch { }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error('Fixture readiness failed: ' + process.output());
}
async function main() {
  try {
    const api = start([path.join(__dirname, 'fixture-api.cjs')], root);
    await ready(api, 'http://127.0.0.1:41891/_fixture/state');
    const app = start([path.join(root, 'node_modules/next/dist/bin/next'), 'start', fixture, '-p', '41890', '-H', '127.0.0.1'], fixture, {
      NODE_ENV: 'production', NEXT_TELEMETRY_DISABLED: '1', CMS_API_BASE: 'http://127.0.0.1:41891',
      NODE_OPTIONS: `--require=${path.join(__dirname, 'loopback-fetch.cjs')}`,
    });
    await ready(app, 'http://127.0.0.1:41890/robots.txt');
    const page = await (await fetch('http://127.0.0.1:41890/post/fixture-7')).text();
    assert.ok(page.includes('rel="canonical" href="https://blog.audit.example/post/fixture-7"'));
    const sitemap = await (await fetch('http://127.0.0.1:41890/sitemap.xml')).text();
    assert.ok(sitemap.includes('<loc>https://blog.audit.example/post/fixture-7</loc>'));
    assert.ok(!sitemap.includes('localhost') && !sitemap.includes('127.0.0.1'));
    const robots = await (await fetch('http://127.0.0.1:41890/robots.txt')).text();
    assert.ok(robots.includes('Sitemap: https://blog.audit.example/sitemap.xml'));
    const chunks = path.join(fixture, '.next/static');
    const jsFiles = fs.readdirSync(chunks, { recursive: true }).filter((name) => name.endsWith('.js'));
    assert.ok(jsFiles.some((name) => {
      const text = fs.readFileSync(path.join(chunks, name), 'utf8');
      return text.includes('https://admin.audit.example') && text.includes('/dashboard/cms/post');
    }), 'Public admin origin must be compiled into the actual client link');
    console.log('PASS URL validation matrix, production HTTP canonical/sitemap/robots, compiled admin link');
  } finally {
    for (const child of processes.reverse()) {
      if (child.exitCode === null) {
        const closed = new Promise((resolve) => child.once('exit', resolve));
        child.kill();
        await closed;
      }
    }
  }
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
