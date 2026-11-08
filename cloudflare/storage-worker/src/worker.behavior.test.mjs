import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import worker from './worker.js';

const environment = {
  TG_BOT_TOKEN: 'fixture-bot', STORAGE_UPLOAD_TICKET_KEY: 'fixture-upload',
  'STORAGE_ORIGIN_SHARED_SECRET': 'fixture-origin', STORAGE_DOWNLOAD_SIGNING_KEY_V1: 'fixture-download',
  ASTRAL_ORIGIN_BASE_URL: 'https://origin.test',
};

class PopCache {
  entries = new Map();
  async put(key, response) {
    const ttl = Number(response.headers.get('Cache-Control')?.match(/max-age=(\d+)/)?.[1] || 0);
    this.entries.set(String(key), { response: response.clone(), expires: Date.now() + ttl * 1000 });
  }
  async match(key) {
    const entry = this.entries.get(String(key));
    return entry && entry.expires > Date.now() ? entry.response.clone() : undefined;
  }
  async delete(key) { return this.entries.delete(String(key)); }
}

function context() {
  const jobs = [];
  return { waitUntil(job) { jobs.push(job); }, async settle() { await Promise.all(jobs); } };
}

function ticket() {
  const body = Buffer.from(JSON.stringify({ uploadId: 'up-fixture', chatId: 'fixture', maxSize: 100,
    mime: 'image/png', exp: Math.floor(Date.now() / 1000) + 300 })).toString('base64url');
  return body + '.' + createHmac('sha256', environment.STORAGE_UPLOAD_TICKET_KEY).update(body).digest('base64url');
}

test('repeated ticket cannot overwrite the first registered public URL with different bytes', async (t) => {
  const cache = new PopCache();
  t.mock.method(globalThis, 'fetch', async (input) => {
    const url = String(input);
    if (url.includes('/sendDocument')) return Response.json({ ok: true, result: { message_id: 2,
      document: { file_id: 'second', file_unique_id: 'second', file_size: 4, mime_type: 'image/png' } } });
    if (url.includes('/upload-callback')) return Response.json({ code: 200, data: {
      publicId: 'public-fixture', status: 'AVAILABLE', contentVersion: 1, visibility: 'PUBLIC', contentMatches: false,
    } });
    if (url.includes('/getFile')) return Response.json({ ok: true, result: { file_path: 'second' } });
    throw new Error('unexpected fixture URL');
  });
  const oldCaches = globalThis.caches;
  globalThis.caches = { default: cache };
  t.after(() => { globalThis.caches = oldCaches; });
  await cache.put('https://edge.test/_pcache/public-fixture/1', new Response('original', { headers: { 'Cache-Control': 'public,max-age=1000' } }));
  const form = new FormData();
  form.set('file', new File(['next'], 'fixture.png', { type: 'image/png' }));
  const ctx = context();
  const result = await worker.fetch(new Request('https://edge.test/upload?ticket=' + ticket(), { method: 'POST', body: form }), environment, ctx);
  assert.equal(result.status, 200);
  await ctx.settle();
  assert.equal(await (await cache.match('https://edge.test/_pcache/public-fixture/1')).text(), 'original');
});

test('another PoP rechecks visibility within 300 seconds even when its body was not purged', async (t) => {
  let clock = 1_800_000_000_000;
  t.mock.method(Date, 'now', () => clock);
  let available = true;
  let originCalls = 0;
  t.mock.method(globalThis, 'fetch', async (input) => {
    const url = String(input);
    if (url.includes('/origin/files/')) {
      originCalls++;
      return Response.json(available ? { code: 200, data: { publicId: 'public-fixture', contentVersion: 1,
        visibility: 'PUBLIC', status: 'AVAILABLE', telegramFileId: 'file', contentType: 'image/png', fileName: 'fixture.png' } }
        : { code: 300 });
    }
    if (url.includes('/getFile')) return Response.json({ ok: true, result: { file_path: 'file' } });
    if (url.includes('/file/bot')) return new Response('fixture');
    throw new Error('unexpected fixture URL');
  });
  const oldCaches = globalThis.caches;
  t.after(() => { globalThis.caches = oldCaches; });
  const pops = [new PopCache(), new PopCache()];
  const request = new Request('https://edge.test/p/public-fixture/1');
  for (const pop of pops) {
    globalThis.caches = { default: pop };
    const ctx = context();
    assert.equal((await worker.fetch(request, environment, ctx)).status, 200);
    await ctx.settle();
  }
  available = false;
  pops[0].entries.clear(); // An active purge reaches only one PoP.
  globalThis.caches = { default: pops[1] };
  clock += 250_000;
  const remaining = await worker.fetch(request, environment, context());
  assert.ok(Number(remaining.headers.get('Cache-Control').match(/max-age=(\d+)/)[1]) <= 50);
  clock += 51_000;
  assert.equal((await worker.fetch(request, environment, context())).status, 404);
  assert.equal(originCalls, 3);
});

test('oversized or differently typed upload is rejected before any Telegram request', async (t) => {
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async () => { calls++; throw new Error('must not send'); });
  for (const [bytes, type, expected] of [['x'.repeat(101), 'image/png', 413], ['jpeg', 'image/jpeg', 415]]) {
    const form = new FormData();
    form.set('file', new File([bytes], 'fixture.png', { type }));
    const result = await worker.fetch(new Request('https://edge.test/upload?ticket=' + ticket(), { method: 'POST', body: form }), environment, context());
    assert.equal(result.status, expected);
  }
  assert.equal(calls, 0);
});
