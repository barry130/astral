const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');
const response = (config, data, code = 200) => ({ config, status: 200, statusText: 'OK', headers: {},
  data: { code, msg: code === 200 ? '成功' : '模拟业务失败', data, timestamp: 1 } });

test('statistics civil dates reject invalid and reversed ranges without changing timezone or silently normalizing', () => {
  const { statisticsTimeRange } = createSourceLoader()('api/message-statistics.ts');
  assert.deepEqual(statisticsTimeRange('2026-11-07T00:00', '2026-11-07T08:00:01'),
    { start: '2026-11-07 00:00:00', end: '2026-11-07 08:00:01' });
  assert.deepEqual(statisticsTimeRange('', ''), {});
  assert.throws(() => statisticsTimeRange('2026-02-30T08:00'), /时间无效/);
  assert.throws(() => statisticsTimeRange('2026-11-07T08:00', '2026-11-06T08:00'), /不能晚于/);
});

test('statistics publishes one checked snapshot from three concurrent real axios requests using an immutable common filter', async () => {
  const load = createSourceLoader(), client = load('api/client.ts').default;
  const { loadMessageStatistics } = load('api/message-statistics.ts');
  const seen = []; let release, started;
  const gate = new Promise((resolve) => { release = resolve; });
  const allStarted = new Promise((resolve) => { started = resolve; });
  client.defaults.adapter = async (config) => {
    seen.push({ url: config.url, params: config.params }); if (seen.length === 3) started(); await gate;
    const data = config.url.endsWith('/overview') ? { total: 1, success: 1, fail: 0 } :
      config.url.endsWith('/trend') ? [{ date: '2026-11-07', channel: 'EMAIL', total: 1, success: 1, fail: 0 }] :
        { records: [{ id: 1, channel: 'EMAIL', status: 1 }], total: 1 };
    return response(config, data);
  };
  const query = { keyword: 'original', status: 0, days: 14, pageNum: 2, pageSize: 20 };
  let completed = false;
  const pending = loadMessageStatistics(query).then((value) => { completed = true; return value; });
  query.keyword = 'changed'; await allStarted;
  assert.equal(completed, false); assert.ok(seen.every((row) => row.params.keyword === 'original' && row.params.status === 0));
  release(); const snapshot = await pending;
  assert.equal(snapshot.total, 1); assert.equal(snapshot.overview.total, 1); assert.equal(snapshot.trend.length, 1);
  assert.equal(snapshot.rows[0].id, 1);
  assert.equal(seen.find((row) => row.url.endsWith('/trend')).params.days, 14);
  assert.equal(seen.find((row) => row.url.endsWith('/list')).params.pageNum, 2);
});

test('statistics rejects business and missing-data responses instead of synthesizing zero counts and empty lists', async () => {
  const load = createSourceLoader(), client = load('api/client.ts').default;
  const { loadMessageStatistics } = load('api/message-statistics.ts');
  client.defaults.adapter = async (config) => response(config, null, config.url.endsWith('/overview') ? 300 : 200);
  await assert.rejects(loadMessageStatistics({}), /模拟业务失败/);
  client.defaults.adapter = async (config) => response(config, config.url.endsWith('/trend') ? [] : null);
  await assert.rejects(loadMessageStatistics({}), /数据缺失/);
});

test('public message-template saves project write fields and preserve independently preconfigured channels', async () => {
  const load = createSourceLoader(), client = load('api/client.ts').default;
  const { messageTemplateApi, messageTemplatePayload } = load('api/message-template.ts');
  let captured;
  client.defaults.adapter = async (config) => { captured = JSON.parse(config.data); return response(config, null); };
  const values = { templateCode: 'fixture', templateName: '测试', scene: 'testEvent', channels: ['EMAIL', 'SMS'],
    subject: '测试', content: '<p>${name}</p>', variables: '["name"]', id: 99, createTime: 'readonly', futureFlag: true };
  const projected = messageTemplatePayload(values); values.channels.push('INAPP');
  assert.deepEqual(projected.channels, ['EMAIL', 'SMS']);
  await messageTemplateApi.save({ ...values, channels: ['EMAIL', 'SMS'] });
  assert.equal(captured.id, undefined); assert.equal(captured.createTime, undefined); assert.equal(captured.futureFlag, undefined);
  assert.deepEqual(captured.channels, ['EMAIL', 'SMS']);
});

test('template previews snapshot the chosen row and preserve own variable keys without consulting another editor', async () => {
  const load = createSourceLoader(), client = load('api/client.ts').default;
  const { previewMessageTemplate } = load('api/message-template.ts'); let captured;
  client.defaults.adapter = async (config) => { captured = JSON.parse(config.data); return response(config, '<p>row B</p>'); };
  const input = { content: '<p>${b}</p>', variables: '["b","__proto__"]' }, fields = ['b'];
  const pending = previewMessageTemplate(input, fields);
  input.content = '<p>${a}</p>'; input.variables = '["a"]'; fields.push('late');
  assert.equal(await pending, '<p>row B</p>'); assert.equal(captured.content, '<p>${b}</p>');
  assert.equal(captured.variables.b, '示例值'); assert.equal(captured.variables.a, undefined);
  assert.equal(captured.variables.late, undefined); assert.ok(Object.hasOwn(captured.variables, '__proto__'));
  client.defaults.adapter = async (config) => response(config, null);
  await assert.rejects(previewMessageTemplate({ content: '<p>fixture</p>' }), /预览数据缺失/);
});

test('mail-center route helpers normalize aliases on every read and preserve unrelated query parameters', () => {
  const { mailCenterTab, mailCenterHref } = createSourceLoader()('components/admin/mail-center-tabs.ts');
  assert.equal(mailCenterTab('template'), 'messageTemplate'); assert.equal(mailCenterTab('smsTemplate'), 'messageTemplate');
  assert.equal(mailCenterTab('notice'), 'notice'); assert.equal(mailCenterTab('rule'), 'rule');
  assert.equal(mailCenterTab(null), 'account'); assert.equal(mailCenterTab('__proto__'), 'account');
  const url = new URL(mailCenterHref('/dashboard/system/mail', 'tab=template&keep=1', 'smsProvider'), 'https://fixture.invalid');
  assert.equal(url.searchParams.get('tab'), 'smsProvider'); assert.equal(url.searchParams.get('keep'), '1');
});

test('message template variable declarations reject malformed JSON rather than silently erasing declared variables', () => {
  const { parseTemplateVariables, mergeTemplateVariables } = createSourceLoader()('api/message-template.ts');
  assert.throws(() => parseTemplateVariables('{bad'), /JSON 字符串数组/);
  assert.throws(() => parseTemplateVariables('[1]'), /JSON 字符串数组/);
  assert.deepEqual(JSON.parse(mergeTemplateVariables('["name"]', ['${name} ${item.title}', '${count}'])), ['name', 'item.title', 'count']);
});
