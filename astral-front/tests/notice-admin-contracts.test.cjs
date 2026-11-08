const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');
const response = (config, data) => ({ config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, msg: '成功', data, timestamp: 1 } });
const deferred = () => { let resolve; const promise = new Promise((yes) => { resolve = yes; }); return { promise, resolve }; };
function setup() { const load = createSourceLoader(); return { load, api: load('api/notice.ts'), client: load('api/client.ts').default }; }

test('actual notice writes and page filters whitelist editable fields and preserve numeric zero and explicit clearing', async () => {
  const { api, client } = setup(); const seen = [];
  client.defaults.adapter = async (config) => { seen.push({ url: config.url, body: config.data ? JSON.parse(config.data) : undefined, params: config.params }); return response(config, null); };
  const values = { id: 99, title: '测试通知', channel: 'all', noticeType: 'announce', display: 4, isShow: 0, isTop: 0, dialogClosable: 0, readSeconds: null, termsVersion: null, effectiveStart: null, effectiveEnd: null, versionMin: 0, versionMax: null, userId: null, scene: 'readonly-event', feedbackId: 77, createTime: 'readonly', firstLaunchPopup: 1, futureField: true };
  await api.noticeAdminApi.create(values); await api.noticeAdminApi.update(7, values);
  const body = { title: '测试通知', channel: 'all', noticeType: 'announce', display: 4, isShow: 0, isTop: 0, dialogClosable: 0, readSeconds: null, termsVersion: null, effectiveStart: null, effectiveEnd: null, versionMin: 0, versionMax: null, userId: null };
  assert.deepEqual(seen[0].body, body); assert.deepEqual(seen[1].body, body); assert.equal(seen[1].url, '/api/v1/admin/message/7');
  await api.noticeAdminApi.page({ pageNum: 1, pageSize: 20, channel: 'web', noticeType: 'notice', keyword: '', startDate: '2026-11-07', endDate: undefined, audience: 'ALL', futureField: true });
  assert.deepEqual(seen[2].params, { pageNum: 1, pageSize: 20, channel: 'web', noticeType: 'notice', keyword: '', startDate: '2026-11-07' });
});

test('actual notice form projection preserves mixed legacy platforms, unknown enums, future display bits and one-sided dates', () => {
  const { load } = setup(); const form = load('components/admin/notice-form.ts');
  const record = { id: 8, title: '测试', channel: 'app,web,pc,app-windows,future-platform', noticeType: 'future-type', audience: 'future-audience', display: 2 ** 33 + 7, isShow: 0, isTop: 0, dialogClosable: 0, effectiveStart: '2026-11-07 08:00:00', effectiveEnd: null, versionMin: 0, versionMax: null, feedbackId: 88, createTime: 'readonly' };
  const values = form.noticeEditorValues(record);
  assert.deepEqual(values.channel, ['app-android', 'app-ios', 'web', 'app-windows', 'future-platform']);
  assert.equal(values.noticeType, 'future-type'); assert.equal(values.audience, 'future-audience'); assert.deepEqual(values.display, [1, 2, 4, 2 ** 33]);
  assert.equal(Object.hasOwn(values, 'id'), false); assert.equal(Object.hasOwn(values, 'feedbackId'), false); assert.equal(Object.hasOwn(values, 'effectiveStart'), false);
  const payload = form.noticeEditorPayload(values, { feedbackReply: false, defaultBatch: '2026.11' });
  assert.equal(payload.display, record.display); assert.equal(payload.channel, values.channel.join(',')); assert.equal(payload.dialogClosable, 0);
  assert.equal(payload.effectiveStart, record.effectiveStart); assert.equal(payload.effectiveEnd, null); assert.equal(payload.versionMin, 0); assert.equal(payload.termsVersion, null); assert.equal(payload.readSeconds, null);
  assert.deepEqual(form.toggleNoticeChannels(['web', 'all'], ['web']), ['all']); assert.deepEqual(form.toggleNoticeChannels(['all', 'web'], ['all']), ['web']);
  assert.equal(form.joinNoticeChannels(['web', 'all']), 'all'); assert.deepEqual(form.splitNoticeDisplay(0), [0]); assert.equal(form.splitNoticeDisplay(Number.MAX_SAFE_INTEGER).length, 53);
});

test('disclaimer protocol enforces six App platforms, splash and closability without losing zero or ordinary nonclosable notices', () => {
  const { load } = setup(); const form = load('components/admin/notice-form.ts');
  const values = { title: '首次启动', channel: ['web'], display: [4, 2 ** 33], firstLaunchPopup: 1, termsVersion: ' 2026.10 ', readSeconds: 0, dialogClosable: 0, isShow: 0 };
  const payload = form.noticeEditorPayload(values, { feedbackReply: false, defaultBatch: '2026.11' });
  assert.equal(payload.channel, form.DISCLAIMER_NOTICE_CHANNELS.join(',')); assert.equal(payload.display, 2 ** 33 + 5); assert.equal(payload.dialogClosable, 1); assert.equal(payload.readSeconds, 0); assert.equal(payload.termsVersion, '2026.10');
  const ordinary = form.noticeEditorPayload({ ...values, firstLaunchPopup: 0 }, { feedbackReply: false, defaultBatch: '2026.11' });
  assert.equal(ordinary.channel, 'web'); assert.equal(ordinary.display, 2 ** 33 + 4); assert.equal(ordinary.dialogClosable, 0); assert.equal(ordinary.readSeconds, null); assert.equal(ordinary.termsVersion, null);
  assert.equal(form.noticeEditorPayload(values, { feedbackReply: true, defaultBatch: '2026.11' }).channel, 'all');
  assert.throws(() => form.noticeEditorPayload({ ...values, readSeconds: 61 }, { feedbackReply: false, defaultBatch: '2026.11' }), /0–60/);
  assert.throws(() => form.noticeEditorPayload({ ...values, display: [Number.MAX_SAFE_INTEGER] }, { feedbackReply: false, defaultBatch: '2026.11' }), /展示位置/);
  assert.throws(() => form.noticeEditorPayload({ ...values, versionMin: 2, versionMax: 1 }, { feedbackReply: false, defaultBatch: '2026.11' }), /下限/);
  const dated = form.noticeEditorValues({ title: '测试', effectiveStart: '2026-11-07 08:00:00', effectiveEnd: '2026-11-07 09:00:00' });
  assert.throws(() => form.noticeEditorPayload({ ...dated, effectiveRange: [...dated.effectiveRange].reverse() }, { feedbackReply: false, defaultBatch: '2026.11' }), /开始时间/);
});

test('notice dictionary metadata checks business failure and does not mislabel unknown types or channels', async () => {
  const { api, client } = setup();
  client.defaults.adapter = async (config) => ({ ...response(config, []), data: { code: 300, msg: '字典读取失败', data: [] } });
  await assert.rejects(api.loadNoticeDicts(), /字典读取失败/);
  assert.equal(api.noticeTypeLabel('future-type', []), 'future-type'); assert.equal(api.noticeTypeLabel('feedback', []), 'notice');
  assert.equal(api.noticeChannelLabel('future-platform', []), 'future-platform'); assert.deepEqual(api.noticeTypeOptions([]).map((option) => option.label), ['announce', 'notice']);
  client.defaults.adapter = async (config) => response(config, []); await assert.rejects(api.loadNoticeDicts(), /字典缺失/);
});

test('an older successful notice dictionary refresh cannot overwrite the latest public cache', async () => {
  const { api, client } = setup(); const started = deferred(); const pending = []; let calls = 0;
  const rows = (prefix) => [{ dictValue: 'all', dictLabel: `${prefix}-channel` }, { dictValue: 'announce', dictLabel: `${prefix}-type` }];
  client.defaults.adapter = async (config) => {
    calls += 1; if (calls <= 2) { const gate = deferred(); pending.push({ gate, config }); if (calls === 2) started.resolve(); return gate.promise; }
    return response(config, rows('fresh'));
  };
  const old = api.loadNoticeDicts(); await started.promise; await api.loadNoticeDicts();
  assert.equal(api.noticeTypeLabel('announce'), 'fresh-type'); assert.equal(api.noticeChannelLabel('all'), 'fresh-channel');
  pending.forEach(({ gate, config }) => gate.resolve(response(config, rows('old')))); await old;
  assert.equal(api.noticeTypeLabel('announce'), 'fresh-type'); assert.equal(api.noticeChannelLabel('all'), 'fresh-channel');
});
