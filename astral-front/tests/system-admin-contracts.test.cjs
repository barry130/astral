const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

function captureRequests() {
  const load = createSourceLoader();
  const client = load('api/client.ts').default;
  const requests = [];
  client.defaults.adapter = async (config) => {
    requests.push({ url: config.url, method: config.method,
      data: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
      params: config.params });
    return { config, status: 200, statusText: 'OK', headers: {},
      data: { code: 200, msg: '成功', data: null, timestamp: 1 } };
  };
  return { load, requests };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

test('the production admin request lane ignores older success and older finally', async () => {
  const { createAdminTask } = createSourceLoader()('components/admin/admin-task.ts');
  const task = createAdminTask();
  const first = deferred();
  const second = deferred();
  const seen = [];
  const callbacks = (name) => ({ onSuccess: (value) => seen.push(value),
    onError: () => seen.push(`${name}:error`), onSettled: () => seen.push(`${name}:settled`) });
  const older = task.run(() => first.promise, callbacks('first'));
  const latest = task.run(() => second.promise, callbacks('second'));
  first.resolve('stale rows');
  await older;
  assert.deepEqual(seen, []);
  second.resolve('latest rows');
  await latest;
  assert.deepEqual(seen, ['latest rows', 'second:settled']);
});

test('public role API updates only metadata and cannot submit a super-role grant', async () => {
  const { load, requests } = captureRequests();
  const { roleApi } = load('api/system-admin.ts');
  await roleApi.update(4, { id: 99, roleCode: 'IMMUTABLE', roleName: '新名', status: 0,
    isSuper: 1, permissions: ['*:*:*'] });
  assert.deepEqual(requests[0].data, { roleName: '新名', status: 0 });
});

test('numeric Switch form bindings render zero as false and serialize booleans as numbers', () => {
  const load = createSourceLoader();
  const React = require('react');
  const { renderToStaticMarkup } = require('react-dom/server');
  const { Form, createFormInstance } = load('components/antd-compat/Form.tsx');
  const { numericSwitchBinding } = load('components/admin/admin-task.ts');
  const form = createFormInstance();
  form.setFieldsValue({ status: 0 });
  let control;
  const Capture = (props) => { control = props; return React.createElement('span'); };
  renderToStaticMarkup(React.createElement(Form, { form }, React.createElement(Form.Item,
    { name: 'status', ...numericSwitchBinding }, React.createElement(Capture))));
  assert.equal(control.checked, false);
  control.onChange(true);
  assert.equal(form.getFieldValue('status'), 1);
  control.onChange(false);
  assert.equal(form.getFieldValue('status'), 0);
});

test('admin queries distinguish clearing a filter from preserving an omitted filter', () => {
  const { createAdminQuery } = createSourceLoader()('components/admin/admin-task.ts');
  const query = createAdminQuery({ current: 3, pageSize: 20, userId: 5, status: 0 });
  const before = query.read();
  const cleared = query.read({ current: 1, userId: undefined });
  assert.equal(cleared.userId, undefined);
  assert.equal(cleared.status, 0);
  assert.equal(query.read({ current: 2 }).status, 0);
  assert.equal(before.current, 3, 'captured request snapshots never mutate');
});

test('invalidating an admin request suppresses success, errors, and finalizers', async () => {
  const { createAdminTask } = createSourceLoader()('components/admin/admin-task.ts');
  for (const failed of [false, true]) {
    const task = createAdminTask();
    const pending = deferred();
    const seen = [];
    const operation = task.run(() => pending.promise, { onSuccess: () => seen.push('success'),
      onError: () => seen.push('error'), onSettled: () => seen.push('finally') });
    task.invalidate();
    if (failed) pending.reject(new Error('late error')); else pending.resolve('late data');
    await operation;
    assert.deepEqual(seen, []);
  }
});

test('mutation lanes acquire their lock synchronously before validation and release on failure', async () => {
  const { createAdminTask } = createSourceLoader()('components/admin/admin-task.ts');
  const task = createAdminTask('exclusive');
  const validation = deferred();
  let requests = 0;
  const first = task.run(async () => { await validation.promise; requests += 1; });
  assert.equal(await task.run(async () => { requests += 1; }), false);
  validation.reject({ errorFields: [{ name: ['name'] }] });
  await first;
  assert.equal(requests, 0);
  await task.run(async () => { requests += 1; });
  assert.equal(requests, 1);
});

test('SMS edits omit blank secrets and server fields', async () => {
  const { load, requests } = captureRequests();
  const { smsApi } = load('api/sms.ts');
  await smsApi.providerUpdate(8, { id: 55, providerName: '测试供应商', accessSecret: '  ',
    enabled: 0, createTime: 'server-owned', unknown: 'no' });
  assert.deepEqual(requests[0].data, { providerName: '测试供应商', enabled: 0 });
});

test('mail edits omit blank credentials and server fields while preserving disabled state', async () => {
  const { load, requests } = captureRequests();
  const { mailApi } = load('api/mail.ts');
  await mailApi.accountUpdate(7, { id: 99, accountName: '测试账户', password: '   ',
    enabled: 0, createTime: 'server-owned', updateTime: 'server-owned', futureSecret: 'unknown' });
  assert.deepEqual(requests[0].data, { accountName: '测试账户', enabled: 0 });
});

test('menu updates send only writable fields, never entity identity or a child tree', async () => {
  const { load, requests } = captureRequests();
  const { menuApi } = load('api/menu.ts');
  const { createFormInstance } = load('components/antd-compat/Form.tsx');
  const form = createFormInstance();
  form.setFieldsValue({ id: 17, name: '更新名称', visible: 0,
    children: [{ id: 18, name: '不得覆盖' }], createTime: 'server-owned', futureFlag: 'unknown' });
  await menuApi.update(17, form.getFieldsValue());
  assert.deepEqual(requests[0], { url: '/api/v1/admin/system/menu/17', method: 'put',
    data: { name: '更新名称', visible: 0 }, params: undefined });
});
