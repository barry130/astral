const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

test('explicit AI content reads use the separately permission-gated endpoint', async () => {
  const load = createSourceLoader();
  const api = load('api/client.ts');
  const { aiRequestApi } = load('api/ai.ts');
  const sent = [];
  api.setCsrfToken('audit-csrf-fixture');
  api.default.defaults.adapter = async (config) => {
    sent.push(config);
    return {
      config,
      status: 200,
      statusText: 'OK',
      headers: {},
      data: {
        code: 200,
        msg: '成功',
        data: { request: { id: 101 }, toolCalls: [], events: [], messages: [] },
        timestamp: 1,
      },
    };
  };

  const detail = await aiRequestApi.detail(101, true);

  assert.equal(detail.data.request.id, 101);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].url, '/api/v1/admin/ai/requests/101/content');
  assert.equal(sent[0].params?.content, undefined, 'a query flag must not stand in for permission');
});
