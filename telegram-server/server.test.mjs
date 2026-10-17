import test from 'node:test';
import http from 'node:http';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createGateway, validateEnv } from './server.mjs';

const env = {
  TG_BOT_TOKEN: 'test-token', STORAGE_UPLOAD_TICKET_KEY: 'ticket-key',
  STORAGE_ORIGIN_SHARED_SECRET: 'origin-key', STORAGE_DOWNLOAD_SIGNING_KEY_V1: 'download-key',
  ASTRAL_ORIGIN_BASE_URL: 'https://astral.test',
};
const sign = (key, text) => createHmac('sha256', key).update(text).digest('base64url');

test('required configuration fails closed', () => {
  assert.throws(() => validateEnv({}), /Missing environment variable/);
  assert.throws(() => validateEnv({ ...env, ASTRAL_ORIGIN_BASE_URL: 'file:///tmp' }));
});

test('standalone HTTP gateway preserves upload, signed download, visibility and deletion contracts', async () => {
  const nativeFetch = globalThis.fetch;
  const calls = [];
  let visibility = 'PUBLIC';
  let deleteOk = true;
  globalThis.fetch = async (url, options = {}) => {
    const text = String(url);
    if (!text.startsWith('https://astral.test') && !text.startsWith('https://api.telegram.org')) {
      return nativeFetch(url, options);
    }
    calls.push(text);
    if (text.startsWith('https://astral.test')) {
      const path = text.slice('https://astral.test'.length);
      const headers = options.headers;
      // Mirror Astral: it verifies against request.getRequestURI(), which never contains the query string.
      const expected = sign('origin-key',
        `${options.method || 'GET'}\n${path.split('?')[0]}\n${headers['X-Storage-Origin-Timestamp']}\n${headers['X-Storage-Origin-Nonce']}`);
      if (headers['X-Storage-Origin-Signature'] !== expected) {
        return Response.json({ code: 400, errorCode: 'STORAGE017', message: 'signature mismatch' }, { status: 400 });
      }
      if (path.includes('upload-callback')) {
        assert.equal(JSON.parse(options.body).telegramFileId, 'file-id');
        return Response.json({ code: 200, data: { publicId: 'public-id' } });
      }
      if (path.includes('/tasks/delete')) return Response.json({ code: 200, data: [{ taskId: 1, chatId: '-1001', messageId: '7' }] });
      if (path.includes('/ack')) return Response.json({ code: 200 });
      return Response.json({ code: 200, data: { telegramFileId: 'file-id', visibility, fileName: 'test.txt' } });
    }
    if (text.includes('sendDocument')) {
      const sent = await new Response(options.body).text();
      assert.match(sent, /hello-file/);
      return Response.json({ ok: true, result: { message_id: 7, document: { file_id: 'file-id', file_unique_id: 'unique', file_size: 10 } } });
    }
    if (text.includes('/getFile')) return Response.json({ ok: true, result: { file_path: 'documents/test.txt' } });
    if (text.includes('/file/bot')) return new Response('hello-file');
    if (text.includes('/getMe')) return Response.json({ ok: true, result: { username: 'test_bot' } });
    if (text.includes('/deleteMessage')) {
      return deleteOk
        ? Response.json({ ok: true })
        : Response.json({ ok: false, description: 'Bad Request: message to delete not found' });
    }
    throw new Error('unexpected mock request');
  };
  const server = createGateway(env);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    assert.equal((await nativeFetch(`${base}/healthz`)).status, 200);
    assert.equal((await nativeFetch(`${base}/upload?ticket=bad`, { method: 'POST' })).status, 401);
    const payload = Buffer.from(JSON.stringify({ uploadId: 'upload-1', chatId: '-1001', exp: Math.floor(Date.now() / 1000) + 60 })).toString('base64url');
    const form = new FormData(); form.set('file', new Blob(['hello-file']), 'test.txt');
    const upload = await nativeFetch(`${base}/upload?ticket=${payload}.${sign('ticket-key', payload)}`, { method: 'POST', body: form });
    assert.deepEqual(await upload.json(), { ok: true, publicId: 'public-id' });
    const path = '/f/public-id/1'; const expires = Math.floor(Date.now() / 1000) + 60;
    const signature = sign('download-key', `GET\n${path}\n${expires}\n1`);
    assert.equal((await nativeFetch(`${base}${path}`)).status, 403);
    let response = await nativeFetch(`${base}${path}?e=${expires}&v=1&s=${signature}`);
    assert.equal(await response.text(), 'hello-file');
    response = await nativeFetch(`${base}/p/public-id/1`);
    assert.equal(await response.text(), 'hello-file');
    visibility = 'PRIVATE';
    assert.equal((await nativeFetch(`${base}/p/public-id/1`)).status, 404);
    response = await nativeFetch(`${base}${path}?e=${expires}&v=1&s=${signature}`);
    assert.equal(response.headers.get('cache-control'), 'private, no-store');
    await response.text();
    const worker = (await import('./worker.mjs')).default;
    const pollResult = await worker.scheduled({}, env);
    assert.equal(pollResult.ok, true);
    assert.ok(calls.some(url => url.includes('/deleteMessage')));
    assert.ok(calls.some(url => url.includes('/tasks/1/ack')));
    // A bare "10/10 failed" is undiagnosable: the Telegram reason must be carried out.
    deleteOk = false;
    const deleteFailed = await worker.scheduled({}, env);
    assert.equal(deleteFailed.failed, 1);
    assert.match(deleteFailed.firstError, /message to delete not found/);
    assert.match(deleteFailed.firstError, /taskId=1/);
    deleteOk = true;
    // Astral rejects the pull with STORAGE017 when the query string is signed; surface the reason.
    const rejected = await worker.scheduled({}, { ...env, STORAGE_ORIGIN_SHARED_SECRET: 'wrong-key' });
    assert.equal(rejected.ok, false);
    assert.match(rejected.reason, /astral pull rejected: HTTP 400/);
    // Send oversized declared length without transmitting a large body; fetch may reset
    // the socket when the server rejects a body still being transmitted.
    const oversizedStatus = await new Promise((resolve, reject) => {
      const request = http.request(`${base}/upload`, { method: 'POST',
        headers: { 'Content-Length': String(22 * 1024 * 1024) } }, response => {
        response.resume(); resolve(response.statusCode);
      });
      request.on('error', reject); request.end();
    });
    assert.equal(oversizedStatus, 413);
  } finally {
    globalThis.fetch = nativeFetch;
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});
