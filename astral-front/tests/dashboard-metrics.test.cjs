const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');
const { JSDOM } = require(process.env.FRONT_TEST_JSDOM_PATH || 'jsdom');
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://fixture.example' });
for (const key of ['window', 'document', 'HTMLElement', 'Element', 'Node', 'Event']) global[key] = dom.window[key];
Object.defineProperty(global, 'navigator', { configurable: true, value: dom.window.navigator });
global.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react');
const { createRoot } = require('react-dom/client');

test('dashboard labels unavailable host samples without claiming healthy zero usage', async () => {
  const load = createSourceLoader({
    '@/context/AuthContext': { useAuth: () => ({ user: { permissions: ['admin:monitor:view'] } }) },
  });
  const api = load('api/client.ts');
  api.default.defaults.adapter = async config => ({
    config, status: 200, statusText: 'OK', headers: {},
    data: { code: 200, msg: 'OK', data: {
      system: { cpuUsage: null, memoryUsage: null, diskUsage: null, totalMemory: null, usedMemory: null, uptime: 2000 },
      jvm: { heapUsed: 1024, heapMax: 8192 },
      database: { available: null }, redis: { available: null },
    } },
  });
  const Component = load('app/dashboard/page.tsx').default;
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  try {
    await React.act(async () => { root.render(React.createElement(Component)); });
    assert.match(container.textContent, /部分资源指标不可用/);
    assert.match(container.textContent, /主机内存采样不可用/);
    assert.match(container.textContent, /主机 CPU 采样不可用/);
    assert.doesNotMatch(container.textContent, /核心服务运行平稳/);
    assert.match(container.textContent, /1 KB/);
  } finally {
    await React.act(async () => root.unmount());
    container.remove();
  }
});
