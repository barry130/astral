const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');
const response = (config, data) => ({ config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, msg: '成功', data, timestamp: 1 } });
function setup() {
  const source = createSourceLoader();
  // Optional witness executes the unchanged public generic reader, not copied implementation.
  const reader = process.env.SYSTEM_DICTIONARY_BASELINE === '1' ? source('api/dict.ts').fetchDictOptions : source('components/admin/admin-dictionaries.ts').loadAdminDictOptions;
  return { reader, client: source('api/client.ts').default };
}

test('actual dictionary option reader preserves dictionary labels, raw zero and color', async () => {
  const { reader, client } = setup(); let query;
  client.defaults.adapter = async (config) => { query = config; return response(config, [{ id: 7, dictTypeId: 8, dictLabel: '否', dictValue: '0', dictSort: 0, status: 1, listClass: 'gray' }]); };
  const values = await reader(['qt_yes_no']);
  assert.deepEqual(values, { qt_yes_no: [{ value: '0', label: '否', color: 'gray' }] });
  assert.equal(query.url, '/api/v1/admin/system/dict/data/byCode'); assert.deepEqual(query.params, { code: 'qt_yes_no' });
});

test('HTTP 200 dictionary business failure cannot resolve as a successful empty option set', async () => {
  const { reader, client } = setup();
  client.defaults.adapter = async (config) => ({ ...response(config, []), data: { code: 300, msg: '字典业务拒绝', data: [] } });
  await assert.rejects(reader(['enable_status']), /字典业务拒绝/);
});

test('required dictionary options reject a missing success payload', async () => {
  const { reader, client } = setup(); client.defaults.adapter = async (config) => response(config, null);
  await assert.rejects(reader(['enable_status']), /选项缺失/);
});

test('required dictionary options reject an empty dictionary instead of enabling dependent writes', async () => {
  const { reader, client } = setup(); client.defaults.adapter = async (config) => response(config, []);
  await assert.rejects(reader(['enable_status']), /选项缺失/);
});
