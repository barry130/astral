'use strict';
// Build the real authored app, not a Vite/static imitation. Never copy real .env files.
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { randomUUID } = require('node:crypto');
const root = path.resolve(__dirname, '..');
const artifacts = path.join(__dirname, '.artifacts');
const fixture = path.join(artifacts, `production-${randomUUID()}`);
if (!fixture.startsWith(root + path.sep)) throw new Error('Fixture escaped blog scope');
fs.mkdirSync(fixture, { recursive: true });
for (const name of [
  'package.json',
  'pnpm-lock.yaml',
  'next.config.ts',
  'tsconfig.json',
  'postcss.config.mjs',
]) {
  fs.copyFileSync(path.join(root, name), path.join(fixture, name));
}
for (const name of ['src', 'public']) {
  const source = path.join(root, name),
    destination = path.join(fixture, name);
  if (fs.existsSync(source))
    fs.cpSync(source, destination, {
      recursive: true,
      filter: (entry) => !path.basename(entry).startsWith('.env'),
    });
  else fs.mkdirSync(destination);
}
fs.symlinkSync(path.join(root, 'node_modules'), path.join(fixture, 'node_modules'), 'junction');
const temp = path.join(fixture, 'temp');
fs.mkdirSync(temp);
const env = {
  ...process.env,
  NODE_ENV: 'production',
  NEXT_TELEMETRY_DISABLED: '1',
  NEXT_PUBLIC_SITE_URL: process.env.AUDIT_PUBLIC_BUILD === 'true' ? 'https://blog.audit.example' : 'http://127.0.0.1:41890',
  NEXT_PUBLIC_ADMIN_URL: process.env.AUDIT_PUBLIC_BUILD === 'true' ? 'https://admin.audit.example' : 'http://127.0.0.1:41890',
  CMS_API_BASE: 'http://127.0.0.1:41891',
  BLOG_TRUST_PROXY: 'false',
  BLOG_COMMENT_LOCAL_LIMIT: 'false',
  TEMP: temp,
  TMP: temp,
  TMPDIR: temp,
};
fs.writeFileSync(
  path.join(fixture, '.env.production.local'),
  `NEXT_PUBLIC_SITE_URL=${env.NEXT_PUBLIC_SITE_URL}\nNEXT_PUBLIC_ADMIN_URL=${env.NEXT_PUBLIC_ADMIN_URL}\nCMS_API_BASE=http://127.0.0.1:41891\nNEXT_TELEMETRY_DISABLED=1\n`,
);
if (process.env.AUDIT_PUBLIC_BUILD === 'true') {
  const validation = spawnSync(process.execPath, [path.join(root, 'scripts/validate-public-build-urls.mjs')], { env, stdio: 'inherit' });
  if (validation.status !== 0) throw new Error('Public build URL validation failed');
}
// Guard the app's actual fetch boundary even if Next attempts an optional prerender read.
const preload = path.join(__dirname, 'loopback-fetch.cjs');
env.NODE_OPTIONS = `${process.env.NODE_OPTIONS || ''} --require=${preload}`.trim();
const command = [path.join(root, 'node_modules', 'next', 'dist', 'bin', 'next'), 'build', fixture];
console.log(`ISOLATED_FIXTURE=${fixture}`);
const build = spawnSync(process.execPath, command, { cwd: fixture, env, stdio: 'inherit' });
if (build.error) throw build.error;
const manifest = require('./source-manifest.cjs').authoredManifest(fixture);
const receipt = {
  status: build.status,
  fixture,
  node: process.version,
  next: require(path.join(root, 'node_modules/next/package.json')).version,
  react: require(path.join(root, 'node_modules/react/package.json')).version,
  realEnvCopied: false,
  publicSiteUrl: env.NEXT_PUBLIC_SITE_URL,
  publicAdminUrl: env.NEXT_PUBLIC_ADMIN_URL,
  authoredManifest: manifest,
  command,
};
const receiptPath = path.join(fixture, 'build-receipt.json');
fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + '\n');
console.log(`BUILD_RECEIPT=${receiptPath}`);
process.exitCode = build.status ?? 1;
