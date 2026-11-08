const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');
const response = (config, data) => ({ config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, msg: '成功', data, timestamp: 1 } });
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function setup() { const load = createSourceLoader(); return { load, api: load('api/system-admin.ts'), client: load('api/client.ts').default }; }

test('actual dictionary admin writes whitelist form fields and preserve numeric zero and explicit null', async () => {
  const { api, client } = setup(); const seen = [];
  client.defaults.adapter = async (config) => { seen.push({ url: config.url, body: JSON.parse(config.data) }); return response(config, null); };
  const type = { id: 80, dictCode: 'fixture', dictName: '测试', dataType: 'String', jdbcType: 'VARCHAR', dataLength: null, description: null, status: 0, createTime: 'readonly', futureFlag: true };
  await api.adminDictApi.createType(type); await api.adminDictApi.updateType(7, type);
  const typeBody = { dictCode: 'fixture', dictName: '测试', dataType: 'String', jdbcType: 'VARCHAR', dataLength: null, description: null, status: 0 };
  assert.deepEqual(seen[0], { url: '/api/v1/admin/system/dict/type', body: typeBody });
  assert.deepEqual(seen[1], { url: '/api/v1/admin/system/dict/type/7', body: typeBody });
  const data = { id: 81, dictTypeId: 7, dictLabel: '零', dictValue: '0', dictSort: 0, isDefault: 0, status: 0, cssClass: '', listClass: null, description: '', updateTime: 'readonly', children: [{ id: 99 }] };
  await api.adminDictApi.createData(data); await api.adminDictApi.updateData(8, data);
  const body = { dictTypeId: 7, dictLabel: '零', dictValue: '0', dictSort: 0, isDefault: 0, status: 0, cssClass: '', listClass: null, description: '' };
  assert.deepEqual(seen[2], { url: '/api/v1/admin/system/dict/data', body });
  assert.deepEqual(seen[3], { url: '/api/v1/admin/system/dict/data/8', body });
});

test('dictionary keyword filtering covers the complete all endpoint locally and clamps invalid pages', async () => {
  const { api, client } = setup(); const seen = [];
  const rows = Array.from({ length: 120 }, (_, index) => ({ id: index + 1, dictCode: `code-${index}`, dictName: index === 119 ? 'Needle' : '普通', status: 1 }));
  client.defaults.adapter = async (config) => { seen.push(config.url); return response(config, rows); };
  const all = await api.adminDictApi.getAllTypes();
  const page = api.paginateDictTypes(all.data, { current: 999, pageSize: 10, keyword: ' needle ' });
  assert.deepEqual(seen, ['/api/v1/admin/system/dict/type/all']);
  assert.equal(page.total, 1); assert.equal(page.current, 1); assert.equal(page.records[0].id, 120);
  const cleared = api.paginateDictTypes(all.data, { current: Number.NaN, pageSize: 0, keyword: '' });
  assert.equal(cleared.total, 120); assert.equal(cleared.current, 1); assert.equal(cleared.size, 10);
  assert.equal(rows.length, 120); assert.equal(rows[119].id, 120);
});

test('checked dictionary pages reject absent or foreign data and an absent owner before dispatch', async () => {
  const { api, client } = setup(); let seen;
  client.defaults.adapter = async (config) => { seen = config; return response(config, { records: [{ id: 21, dictTypeId: 7, dictLabel: '零', dictValue: '0', status: 0 }], current: 2, size: 20, total: 21 }); };
  const page = await api.loadDictDataPage(2, 20, 7);
  assert.equal(page.records[0].status, 0); assert.deepEqual(seen.params, { pageNum: 2, pageSize: 20, dictTypeId: 7 });
  client.defaults.adapter = async (config) => response(config, null);
  await assert.rejects(api.loadDictDataPage(1, 10, 7), /数据缺失/);
  client.defaults.adapter = async (config) => response(config, { records: [{ id: 22, dictTypeId: 8 }], total: 1 });
  await assert.rejects(api.loadDictDataPage(1, 10, 7), /所属类型/);
  let dispatched = false; client.defaults.adapter = async () => { dispatched = true; throw new Error('must not dispatch'); };
  await assert.rejects(api.loadDictDataPage(1, 10, undefined), /有效的字典类型/); assert.equal(dispatched, false);
});

test('real keyed admin caches deduplicate reads and do not let invalidated success or finally replace a fresh read', async () => {
  const { load } = setup(); const cache = load('components/admin/admin-cache.ts').createAdminCache();
  const old = deferred(), fresh = deferred(); let reads = 0;
  const first = cache.load(7, () => { reads += 1; return old.promise; });
  const duplicate = cache.load(7, () => { reads += 1; return Promise.resolve(['wrong']); });
  await Promise.resolve(); assert.equal(reads, 1); assert.equal(duplicate, first); assert.equal(cache.isBusy(7), true);
  cache.invalidate(); const second = cache.load(7, () => fresh.promise); await Promise.resolve();
  old.resolve(['stale']); assert.equal(await first, undefined); assert.equal(cache.get(7), undefined); assert.equal(cache.isBusy(7), true);
  fresh.resolve(['server-truth']); assert.deepEqual(await second, ['server-truth']); assert.deepEqual(cache.get(7), ['server-truth']); assert.equal(cache.isBusy(7), false);
  assert.deepEqual(await cache.load(7, () => { throw new Error('cached reads must not refetch'); }), ['server-truth']);
  cache.invalidate(); assert.equal(cache.get(7), undefined);
});

test('cache invalidation suppresses old failures, cancels undispatched work and permits independent keys', async () => {
  const { load } = setup(); const cache = load('components/admin/admin-cache.ts').createAdminCache();
  const failure = deferred(); const first = cache.load(1, () => failure.promise); await Promise.resolve();
  cache.invalidate(); failure.reject(new Error('old failure')); assert.equal(await first, undefined);
  let dispatched = false; const cancelled = cache.load(1, () => { dispatched = true; return Promise.resolve('wrong'); });
  cache.invalidate(); assert.equal(await cancelled, undefined); assert.equal(dispatched, false);
  const left = deferred(), right = deferred(); const a = cache.load(1, () => left.promise), b = cache.load(2, () => right.promise);
  left.resolve('left'); right.resolve('right'); assert.equal(await a, 'left'); assert.equal(await b, 'right');
  assert.equal(cache.get(1), 'left'); assert.equal(cache.get(2), 'right');
  assert.throws(() => cache.load(0, () => Promise.resolve('wrong')), /标识无效/);
});
