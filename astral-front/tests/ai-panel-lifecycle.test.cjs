const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');
const { JSDOM } = require(process.env.FRONT_TEST_JSDOM_PATH || 'jsdom');
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://fixture.example' });
for (const key of ['window', 'document', 'HTMLElement', 'Element', 'Node', 'NodeFilter', 'Event', 'CustomEvent', 'MutationObserver', 'HTMLInputElement', 'HTMLTextAreaElement']) global[key] = dom.window[key];
Object.defineProperty(global, 'navigator', { configurable: true, value: dom.window.navigator });
global.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
global.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
global.requestAnimationFrame = callback => setTimeout(callback, 0);
global.cancelAnimationFrame = clearTimeout;
dom.window.matchMedia = query => ({ matches: false, media: query, addEventListener() {}, removeEventListener() {} });
global.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react');
const { createRoot } = require('react-dom/client');
const button = text => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === text);
async function click(element) {
  assert.ok(element, 'action must exist');
  await React.act(async () => element.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })));
}
function fill(element, value) {
  Object.getOwnPropertyDescriptor(element.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value').set.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
}
const auth = { useAuth: () => ({ user: { permissions: ['*:*:*'] } }) };

test('old approval completion cannot refresh a reopened detail for the same request', async () => {
  const load = createSourceLoader({ '@/context/AuthContext': auth });
  const api = load('api/client.ts'); api.setCsrfToken('fixture-csrf');
  const { default: Panel } = load('components/ai/admin/RequestPanel.tsx');
  let detailLoads = 0, finishApproval;
  api.default.defaults.adapter = async config => {
    let data;
    if (config.url.includes('/dict/')) data = {};
    else if (config.url.endsWith('/requests')) data = { items: [{ id: 7, status: 'WAITING_APPROVAL' }], total: 1 };
    else if (config.url.endsWith('/requests/7/content')) {
      detailLoads++;
      data = { request: { id: 7, status: 'WAITING_APPROVAL' }, toolCalls: [{ id: 9, toolName: 'Fixture tool', status: 'WAITING_APPROVAL', approvalStatus: 'ADMIN_CONFIRM' }] };
    } else if (config.url.endsWith('/tool-calls/9/approve')) data = await new Promise(resolve => { finishApproval = resolve; });
    else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, data, msg: 'OK' } };
  };
  const container = document.createElement('div'); document.body.append(container); const root = createRoot(container);
  try {
    await React.act(async () => root.render(React.createElement(Panel)));
    const open = () => document.querySelector('tbody button');
    await click(open());
    await click(button('工具调用 (1)'));
    await click(button('确认执行'));
    await click(button('关闭'));
    await click(open());
    assert.equal(detailLoads, 2);
    await React.act(async () => { finishApproval({ status: 'SUCCEEDED' }); });
    assert.equal(detailLoads, 2, 'stale operation must not refresh new detail generation');
  } finally { await React.act(async () => root.unmount()); container.remove(); }
});

test('enter while conversation history loads preserves the draft and sends nothing', async () => {
  const load = createSourceLoader({ '@/context/AuthContext': auth, 'next/navigation': { useRouter: () => ({ push() {} }) } });
  const api = load('api/client.ts'); api.setCsrfToken('fixture-csrf');
  const { default: Panel } = load('components/ai/ChatPanel.tsx');
  let finishHistory, sends = 0;
  api.default.defaults.adapter = async config => {
    let data;
    if (config.url.endsWith('/assistants')) data = [{ agentCode: 'fixture', name: 'Fixture' }];
    else if (config.url.endsWith('/conversations')) data = [{ id: 1, title: 'Fixture' }];
    else if (config.url.endsWith('/messages') && config.method === 'get') data = await new Promise(resolve => { finishHistory = resolve; });
    else if (config.url.endsWith('/messages')) { sends++; data = {}; }
    else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, data, msg: 'OK' } };
  };
  const container = document.createElement('div'); document.body.append(container); const root = createRoot(container);
  try {
    await React.act(async () => root.render(React.createElement(Panel, { agentCode: 'fixture' })));
    const input = document.querySelector('textarea');
    assert.ok(input);
    await React.act(async () => fill(input, '保留这条草稿'));
    await React.act(async () => input.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true })));
    assert.equal(input.value, '保留这条草稿');
    assert.equal(sends, 0);
    await React.act(async () => { finishHistory({ items: [] }); });
    assert.equal(input.value, '保留这条草稿');
  } finally { await React.act(async () => root.unmount()); container.remove(); }
});

test('late knowledge bindings from a previous version cannot be saved to the current draft', async () => {
  const load = createSourceLoader({ '@/context/AuthContext': auth });
  const api = load('api/client.ts'); api.setCsrfToken('fixture-csrf');
  const { default: Panel } = load('components/ai/admin/AgentPanel.tsx');
  let finishOld, saved;
  api.default.defaults.adapter = async config => {
    let data;
    if (config.url.includes('/dict/')) data = {};
    else if (config.url.endsWith('/agents')) data = [{ id: 1, name: 'Fixture', publishedVersionId: 11 }];
    else if (config.url.endsWith('/agents/1/versions')) data = [
      { id: 11, versionNo: 1, versionName: 'Old version', status: 'PUBLISHED' },
      { id: 12, versionNo: 2, versionName: 'Current draft', status: 'DRAFT' },
    ];
    else if (config.url.endsWith('/knowledge/sources')) data = [{ id: 31, name: 'Old source' }, { id: 32, name: 'Current source' }];
    else if (config.url.endsWith('/agent-versions/11/knowledge')) data = await new Promise(resolve => { finishOld = resolve; });
    else if (config.url.endsWith('/agent-versions/12/knowledge')) {
      if (config.method === 'put') { saved = JSON.parse(config.data); data = saved; }
      else data = [{ sourceId: 32, retrievalTopK: 7, minScore: 0.6 }];
    } else if (config.url.endsWith('/routes') || config.url.endsWith('/prompts')) data = [];
    else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, data, msg: 'OK' } };
  };
  const container = document.createElement('div'); document.body.append(container); const root = createRoot(container);
  try {
    await React.act(async () => root.render(React.createElement(Panel)));
    await click(button('知识源'));
    assert.equal(typeof finishOld, 'function');
    const currentRow = [...document.querySelectorAll('tbody tr')].find(row => row.textContent.includes('v2'));
    await click([...currentRow.querySelectorAll('button')].find(item => item.textContent.trim() === '查看'));
    await React.act(async () => finishOld([{ sourceId: 31, retrievalTopK: 2, minScore: 0.1 }]));
    await click(button('保存知识绑定'));
    assert.deepEqual(saved, [{ sourceId: 32, enabled: true, priority: 100, retrievalTopK: 7, minScore: 0.6, citationPolicy: 'REQUIRED' }]);
  } finally { await React.act(async () => root.unmount()); container.remove(); }
});

test('an old UNKNOWN usage can be located by date and reconciled with actual tokens and a note', async () => {
  const load = createSourceLoader({ '@/context/AuthContext': auth, 'next/dynamic': () => () => null });
  const api = load('api/client.ts'); api.setCsrfToken('fixture-csrf');
  const { default: Panel } = load('components/ai/admin/UsagePanel.tsx');
  let saved, listRange, summaryRange;
  api.default.defaults.adapter = async config => {
    let data;
    if (config.url.includes('/dict/')) data = {};
    else if (config.url.endsWith('/agents')) data = [];
    else if (config.url.endsWith('/usage/summary')) { summaryRange = config.params; data = { requestCount: 0, costs: [], trend: [] }; }
    else if (config.url.endsWith('/usage/83/reconcile')) { saved = JSON.parse(config.data); data = {}; }
    else if (config.url.endsWith('/usage')) {
      listRange = config.params;
      data = config.params.from === '2020-01-01' ? [{ id: 83, status: 'UNKNOWN', createTime: '2020-01-15 12:00:00', currency: 'CNY' }] : [];
    } else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, data, msg: 'OK' } };
  };
  const container = document.createElement('div'); document.body.append(container); const root = createRoot(container);
  try {
    await React.act(async () => root.render(React.createElement(Panel)));
    const from = document.querySelector('input[aria-label="用量开始日期"]');
    const to = document.querySelector('input[aria-label="用量结束日期"]');
    assert.ok(from && to, 'historical date range must be reachable');
    await React.act(async () => { fill(from, '2020-01-01'); fill(to, '2020-01-31'); });
    await click(button('查询日期'));
    assert.equal(listRange.from, '2020-01-01'); assert.equal(listRange.to, '2020-01-31');
    assert.equal(summaryRange.from, listRange.from); assert.equal(summaryRange.to, listRange.to);
    assert.ok(document.body.textContent.includes('2020-01-01 至 2020-01-31'));
    await click(button('核对用量'));
    await click(button('保存核对结果'));
    assert.equal(saved, undefined, 'blank reconciliation must not reach the API');
    const dialog = [...document.querySelectorAll('[role="dialog"]')].find(item => item.textContent.includes('核对用量'));
    const fields = dialog.querySelectorAll('input');
    await React.act(async () => { fill(fields[0], '50'); fill(fields[1], '20'); fill(dialog.querySelector('textarea'), '合成账单已核实'); });
    await click(button('保存核对结果'));
    assert.deepEqual(saved, { inputTokens: 50, outputTokens: 20, note: '合成账单已核实' });
  } finally { await React.act(async () => root.unmount()); container.remove(); }
});

test('tool reconciliation submits only the observed outcome and note and never approves or resends', async () => {
  const load = createSourceLoader({ '@/context/AuthContext': auth });
  const api = load('api/client.ts'); api.setCsrfToken('fixture-csrf');
  const { default: Panel } = load('components/ai/admin/RequestPanel.tsx');
  const writes = [];
  api.default.defaults.adapter = async config => {
    let data;
    if (config.method !== 'get') writes.push(config);
    if (config.url.includes('/dict/')) data = config.params.code === 'ai_tool_call_status'
      ? [{ dictValue: 'SUCCEEDED', dictLabel: '已成功' }, { dictValue: 'FAILED', dictLabel: '已失败' }] : [];
    else if (config.url.endsWith('/requests')) data = { items: [{ id: 7, status: 'FAILED' }], total: 1 };
    else if (config.url.endsWith('/requests/7/content')) data = { request: { id: 7, status: 'FAILED' }, toolCalls: [{ id: 9, toolName: 'Fixture tool', status: 'UNKNOWN' }] };
    else if (config.url.endsWith('/tool-calls/9/reconcile')) data = { status: 'SUCCEEDED' };
    else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, data, msg: 'OK' } };
  };
  const container = document.createElement('div'); document.body.append(container); const root = createRoot(container);
  try {
    await React.act(async () => root.render(React.createElement(Panel)));
    await click(document.querySelector('tbody button'));
    await click(button('工具调用 (1)'));
    await click(button('核对执行结果'));
    await click(button('保存核对结果'));
    assert.equal(writes.length, 0);
    const dialog = [...document.querySelectorAll('[role="dialog"]')].find(item => item.textContent.includes('核对工具执行结果'));
    await click(dialog.querySelector('[role="combobox"]'));
    await click(button('已成功'));
    await React.act(async () => fill(dialog.querySelector('textarea'), '合成业务记录确认成功'));
    await click(button('保存核对结果'));
    assert.equal(writes.length, 1);
    assert.ok(writes[0].url.endsWith('/tool-calls/9/reconcile'));
    assert.deepEqual(JSON.parse(writes[0].data), { outcome: 'SUCCEEDED', note: '合成业务记录确认成功' });
  } finally { await React.act(async () => root.unmount()); container.remove(); }
});
