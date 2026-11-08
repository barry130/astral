const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');
const response = (config, data, code = 200) => ({ config, status: 200, statusText: 'OK', headers: {},
  data: { code, msg: code === 200 ? '成功' : '模拟业务失败', data, timestamp: 1 } });
function setup() { const load = createSourceLoader(); return { load, client: load('api/client.ts').default,
  api: load('api/system-admin.ts') }; }
const permission = (id) => ({ id, permissionCode: `admin:system:resource-${id}:view`, permissionName: `权限${id}`, type: 4 });

test('user CSV export uses the actual shared binary request and rejects Result JSON with its business message', async () => {
  const { client, api } = setup(); let captured;
  client.defaults.adapter = async (config) => { captured = config; return { config, status: 200, statusText: 'OK',
    headers: { 'content-type': 'text/csv;charset=UTF-8' }, data: new Blob(['id,name\n1,fixture'], { type: 'text/csv;charset=UTF-8' }) }; };
  const blob = await api.userApi.exportCsv({ username: 'fixture', userType: 'APP', status: 0, pageNum: 9 });
  assert.equal(await blob.text(), 'id,name\n1,fixture'); assert.equal(captured.responseType, 'blob');
  assert.deepEqual(captured.params, { username: 'fixture', userType: 'APP' });
  assert.ok(captured.headers.get('X-App-Ut'));
  client.defaults.adapter = async (config) => ({ config, status: 200, statusText: 'OK', headers: { 'content-type': 'application/json' },
    data: new Blob([JSON.stringify({ code: 300, msg: '导出被拒绝', data: null, timestamp: 1 })], { type: 'application/json' }) });
  await assert.rejects(api.userApi.exportCsv({}), /导出被拒绝/);
});

test('permission traversal includes rows beyond 1000 and preserves explicit page parameters', async () => {
  const { client, api } = setup(); const seen = [];
  client.defaults.adapter = async (config) => { seen.push(config.params); return response(config, { total: 1001,
    records: config.params.pageNum === 1 ? Array.from({ length: 1000 }, (_, i) => permission(i + 1)) : [permission(1001)] }); };
  const rows = await api.permissionApi.getAll();
  assert.equal(rows.length, 1001); assert.equal(rows[1000].id, 1001);
  assert.deepEqual(seen, [{ pageNum: 1, pageSize: 1000 }, { pageNum: 2, pageSize: 1000 }]);
});

test('permission traversal deduplicates overlap and rejects no-progress rather than returning partial data', async () => {
  const { client, api } = setup(); let calls = 0;
  client.defaults.adapter = async (config) => { calls++; return response(config, { total: 3,
    records: calls === 1 ? [permission(1), permission(2)] : [permission(2), permission(3)] }); };
  assert.equal((await api.permissionApi.getAll()).length, 3); assert.equal(calls, 2);
  client.defaults.adapter = async (config) => response(config, { total: 3, records: [permission(1)] });
  await assert.rejects(api.permissionApi.getAll(), /未返回完整数据/);
});

test('permission traversal refuses a missing successful page instead of fabricating an empty permission set', async () => {
  const { client, api } = setup();
  client.defaults.adapter = async (config) => response(config, null);
  await assert.rejects(api.permissionApi.getAll(), /数据缺失/);
});

test('permission grouping skips side segments, trims declared domains and excludes wildcard grants', () => {
  const { api } = setup();
  const groups = api.groupPermissionRows([permission(1), { ...permission(2), permissionCode: 'user:qt:profile:view' },
    { ...permission(3), domain: ' storage ' }, { ...permission(4), permissionCode: '*:*:*' }]);
  assert.deepEqual(groups.map((group) => group.permissionCode), ['domain:qt', 'domain:storage', 'domain:system']);
  assert.ok(groups.every((group) => group.id === null));
  assert.equal(groups.flatMap((group) => group.children).length, 3);
});

test('public role filtering is local, clamps pages and has finite bounds for malformed pagination', () => {
  const { api } = setup(); const rows = [
    { id: 1, roleCode: 'EDITOR', roleName: '编辑', description: 'write', status: 1, sort: 0 },
    { id: 2, roleCode: 'REVIEWER', roleName: '审核', description: 'review', status: 1, sort: 1 }];
  const filtered = api.paginateRoles(rows, { current: 9, pageSize: 1, keyword: ' review ' });
  assert.equal(filtered.current, 1); assert.equal(filtered.total, 1); assert.equal(filtered.records[0].id, 2);
  const bounded = api.paginateRoles(rows, { current: Number.NaN, pageSize: 0, keyword: '' });
  assert.ok(Number.isFinite(bounded.current) && bounded.current >= 1);
  assert.ok(Number.isFinite(bounded.pages) && bounded.size > 0); assert.equal(bounded.records.length, 2);
});

test('public user profile save projects writable fields and never silently changes an omitted type', async () => {
  const { client, api } = setup(); const seen = [];
  client.defaults.adapter = async (config) => { seen.push({ url: config.url, data: JSON.parse(config.data) }); return response(config, null); };
  const result = await api.saveUserProfile(7, { nickname: '测试', email: '', username: 'readonly', status: 0,
    id: 9, password: 'not-a-real-password', roleIds: [1], futureFlag: true }, 'APP', false);
  assert.equal(result.userType, 'APP'); assert.equal(result.typeChanged, false);
  assert.deepEqual(seen, [{ url: '/api/v1/admin/system/user/7', data: { nickname: '测试', email: '' } }]);
});

test('non-super user type changes fail before either public request is dispatched', async () => {
  const { client, api } = setup(); let calls = 0;
  client.defaults.adapter = async (config) => { calls++; return response(config, null); };
  await assert.rejects(api.saveUserProfile(7, { nickname: '测试', userType: 'ADMIN' }, 'APP', false), /仅限超级管理员/);
  assert.equal(calls, 0);
});

test('user type second-step failure reports the confirmed profile write, not an atomic all-or-nothing result', async () => {
  const { client, api } = setup(); const seen = [];
  client.defaults.adapter = async (config) => { seen.push({ url: config.url, data: JSON.parse(config.data) });
    return response(config, null, seen.length === 2 ? 300 : 200); };
  await assert.rejects(api.saveUserProfile(7, { nickname: '已确认', userType: 'APP' }, 'ADMIN', true),
    (error) => error instanceof api.UserPartialSaveError && error.profileSaved === true && error.userId === 7 &&
      error.profile.nickname === '已确认' && /已保存/.test(error.message) && /未确认/.test(error.message));
  assert.deepEqual(seen, [{ url: '/api/v1/admin/system/user/7', data: { nickname: '已确认' } },
    { url: '/api/v1/admin/system/user/7/type', data: { userType: 'APP' } }]);
});
