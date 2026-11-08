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

test('document editor waits for full content and ignores a closed editor response on reopen', async () => {
  const load = createSourceLoader({
    '@/context/AuthContext': { useAuth: () => ({ user: { permissions: ['*:*:*'] } }) },
  });
  const client = load('api/client.ts');
  const { default: Component } = load('components/ai/admin/KnowledgePanel.tsx');
  const pending = [];
  const listRequests = [];
  client.default.defaults.adapter = async config => {
    let data;
    if (config.url.includes('/dict/')) data = {};
    else if (config.url.endsWith('/sources')) data = [{ id: 1, name: 'Fixture source', status: 'READY' }];
    else if (config.url.endsWith('/sources/1/index-job')) data = null;
    else if (config.url.endsWith('/sources/1/documents')) {
      listRequests.push(config);
      data = { records: [{ id: 10, title: 'Existing document', docKey: 'key' }], total: 21 };
    } else if (config.url.endsWith('/documents/10')) data = await new Promise(resolve => pending.push(resolve));
    else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, msg: 'OK', data } };
  };
  const container = document.createElement('div'); document.body.append(container);
  const root = createRoot(container);
  const click = async element => {
    assert.ok(element, 'action must be present');
    await React.act(async () => { element.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true })); });
  };
  const button = text => [...document.querySelectorAll('button')].find(item => item.textContent.trim() === text);
  try {
    await React.act(async () => { root.render(React.createElement(Component)); });
    assert.equal(listRequests[0].params.pageNum, 1);
    assert.equal(listRequests[0].params.pageSize, 20);
    await click(document.querySelector('[aria-label="下一页"]'));
    assert.equal(listRequests.at(-1).params.pageNum, 2);
    assert.equal(listRequests.at(-1).params.pageSize, 20);
    await click(document.querySelector('[aria-label="上一页"]'));
    assert.equal(listRequests.at(-1).params.pageNum, 1);
    await click(document.querySelector('[aria-label="编辑文档：Existing document"]'));
    assert.ok(button('保存并索引').disabled);
    assert.match(document.body.textContent, /正在加载完整正文/);
    await click(button('取消'));
    await click(document.querySelector('[aria-label="编辑文档：Existing document"]'));
    await React.act(async () => { pending[1]({ id: 10, docKey: 'key', title: 'Existing document', content: 'current full body' }); });
    assert.equal(document.querySelector('textarea').value, 'current full body');
    await React.act(async () => { pending[0]({ id: 10, docKey: 'key', title: 'Stale', content: 'stale body' }); });
    assert.equal(document.querySelector('textarea').value, 'current full body');
    assert.equal(button('保存并索引').disabled, false);
  } finally {
    await React.act(async () => root.unmount()); container.remove();
  }
});

test('durable index job is restored, cancelled and retried without claiming early completion', async () => {
  const load = createSourceLoader({ '@/context/AuthContext': { useAuth: () => ({ user: { permissions: ['*:*:*'] } }) } });
  const api = load('api/client.ts'); api.setCsrfToken('fixture-csrf');
  const { default: Panel } = load('components/ai/admin/KnowledgePanel.tsx');
  const writes = [];
  api.default.defaults.adapter = async config => {
    let data;
    if (config.url.includes('/dict/')) data = {};
    else if (config.url.endsWith('/sources')) data = [{ id: 1, name: 'Fixture source', status: 'READY' }];
    else if (config.url.endsWith('/documents')) data = { records: [], total: 0 };
    else if (config.url.endsWith('/sources/1/index-job')) data = { id: 9, sourceId: 1, status: 'RUNNING', totalCount: 5, processedCount: 2 };
    else if (config.url.endsWith('/9/cancel')) { writes.push(config.url); data = { id: 9, status: 'CANCELLED', totalCount: 5, processedCount: 2 }; }
    else if (config.url.endsWith('/9/retry')) { writes.push(config.url); data = { id: 10, status: 'PENDING', totalCount: 5, processedCount: 0 }; }
    else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, msg: 'OK', data } };
  };
  const container = document.createElement('div'); document.body.append(container); const root = createRoot(container);
  const button = text => [...container.querySelectorAll('button')].find(item => item.textContent.trim() === text);
  const click = async element => { assert.ok(element); await React.act(async () => element.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }))); };
  try {
    await React.act(async () => root.render(React.createElement(Panel)));
    assert.match(container.textContent, /已处理 2/);
    assert.ok(button('重新索引').disabled);
    await click(button('取消任务'));
    assert.match(container.textContent, /CANCELLED/);
    await click(button('重试任务'));
    assert.match(container.textContent, /PENDING/);
    assert.doesNotMatch(container.textContent, /索引重建完成/);
    assert.deepEqual(writes, ['/api/v1/admin/ai/knowledge/index-jobs/9/cancel', '/api/v1/admin/ai/knowledge/index-jobs/9/retry']);
  } finally { await React.act(async () => root.unmount()); container.remove(); }
});

test('refresh after a job error cannot strand a pending cancellation in the busy state', async () => {
  const load = createSourceLoader({ '@/context/AuthContext': { useAuth: () => ({ user: { permissions: ['*:*:*'] } }) } });
  const api = load('api/client.ts'); api.setCsrfToken('fixture-csrf');
  const { default: Panel } = load('components/ai/admin/KnowledgePanel.tsx');
  let cancelAttempts = 0;
  let latestReads = 0;
  let finishCancel;
  const refreshes = [];
  api.default.defaults.adapter = async config => {
    let data;
    if (config.url.includes('/dict/')) data = {};
    else if (config.url.endsWith('/sources')) data = [{ id: 1, name: 'Fixture source', status: 'READY' }];
    else if (config.url.endsWith('/documents')) data = { records: [], total: 0 };
    else if (config.url.endsWith('/sources/1/index-job')) {
      latestReads++;
      data = latestReads === 1 ? { id: 9, status: 'RUNNING', totalCount: 5, processedCount: 2 }
        : await new Promise(resolve => refreshes.push(resolve));
    } else if (config.url.endsWith('/9/cancel')) {
      if (++cancelAttempts === 1) throw new Error('fixture cancellation failure');
      data = await new Promise(resolve => { finishCancel = resolve; });
    } else if (config.url.endsWith('/9/retry')) data = { id: 10, status: 'PENDING', totalCount: 5, processedCount: 0 };
    else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, msg: 'OK', data } };
  };
  const container = document.createElement('div'); document.body.append(container); const root = createRoot(container);
  const button = text => [...container.querySelectorAll('button')].find(item => item.textContent.trim() === text);
  const click = async element => { assert.ok(element); await React.act(async () => element.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }))); };
  try {
    await React.act(async () => root.render(React.createElement(Panel)));
    await click(button('取消任务'));
    assert.match(container.textContent, /fixture cancellation failure/);
    await click(button('取消任务'));
    await click(button('刷新任务状态'));
    await React.act(async () => {
      finishCancel({ id: 9, status: 'CANCELLED', totalCount: 5, processedCount: 2 });
      for (const finishRefresh of refreshes) finishRefresh({ id: 9, status: 'CANCELLED', totalCount: 5, processedCount: 2 });
    });
    assert.equal(button('重试任务').disabled, false, 'the completed write must release its busy state');
    assert.equal(latestReads, 1, 'refresh must wait for the active job operation');
    await click(button('重试任务'));
    assert.match(container.textContent, /PENDING/);
    assert.equal(button('取消任务').disabled, false);
  } finally { await React.act(async () => root.unmount()); container.remove(); }
});
