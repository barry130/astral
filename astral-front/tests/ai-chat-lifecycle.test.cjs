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
const { act } = React;

test('SSE frames survive CRLF boundaries split between network chunks', () => {
  const { createSseDecoder } = createSourceLoader()('components/ai/sse.ts');
  const decode = createSseDecoder();
  assert.deepEqual(decode('id: 1\r'), []);
  assert.deepEqual(decode('\nevent: message.delta\r'), []);
  assert.deepEqual(decode('\ndata: {"delta":"你好"}\r'), []);
  assert.deepEqual(decode('\n\r'), []);
  assert.deepEqual(decode('\n'), [{ id: '1', event: 'message.delta', data: '{"delta":"你好"}' }]);
});

// Real hook, React, API client and SSE parser; only HTTP/DOM boundaries are fixtures.
test('pending confirmation is actionable and resumed messages render under their new IDs', async () => {
  const load = createSourceLoader();
  const api = load('api/client.ts');
  api.setCsrfToken('fixture-csrf');
  const { useAiChat } = load('components/ai/useAiChat.ts');
  let stream;
  const sent = [];
  const originalFetch = global.fetch;
  global.fetch = async (_url, options) => new Response(new ReadableStream({
    start(controller) {
      stream = controller;
      options.signal.addEventListener('abort', () => controller.close(), { once: true });
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } });
  api.default.defaults.adapter = async (config) => {
    sent.push(config);
    let data;
    if (config.url.endsWith('/assistants')) data = [{ agentCode: 'fixture', name: 'Fixture' }];
    else if (config.url.endsWith('/conversations')) data = [{ id: 1, agentCode: 'fixture', title: 'Fixture' }];
    else if (config.url.endsWith('/messages') && config.method === 'get') data = { items: [] };
    else if (config.url.endsWith('/messages')) data = { requestId: 7, userMessageId: 10, assistantMessageId: 11 };
    else if (config.url.endsWith('/approve')) data = { toolCallId: 30, status: 'SUCCEEDED', summary: '已执行' };
    else if (config.url.endsWith('/events')) data = { events: [], terminal: false };
    else throw new Error(`Unexpected fixture request: ${config.method} ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, data, msg: 'OK', timestamp: 1 } };
  };
  let chat;
  function Harness() {
    chat = useAiChat({ agentCode: 'fixture' });
    return React.createElement('div', null, chat.messages.map(message => React.createElement('p', { key: message.id }, message.content)));
  }
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  const event = async (sequence, type, data) => {
    await act(async () => {
      stream.enqueue(new TextEncoder().encode(`id: ${sequence}\nevent: ${type}\ndata: ${JSON.stringify(data)}\n\n`));
      await new Promise(resolve => setTimeout(resolve, 10));
    });
  };
  try {
    await act(async () => { root.render(React.createElement(Harness)); });
    await act(async () => { await chat.send('确认操作'); });
    await event(1, 'tool.approval.required', { toolCallId: 30, name: '发送通知', toolCode: 'send_notice', risk: 'DRAFT', approvalPolicy: 'USER_CONFIRM', arguments: { title: 'Fixture' } });
    assert.equal(chat.tools.length, 1);
    assert.equal(chat.tools[0].status, 'WAITING_APPROVAL');
    await act(async () => { await chat.approveTool(30, true); });
    assert.ok(sent.some(config => config.url === '/api/v1/admin/ai/chat/tool-calls/30/approve'));
    await event(2, 'message.started', { messageId: 12 });
    await event(3, 'message.completed', { messageId: 12, content: '操作已完成', blocks: [] });
    await event(4, 'stream.end', { status: 'SUCCEEDED' });
    assert.equal(chat.run.running, false);
    assert.equal(chat.messages.find(message => message.id === 12).content, '操作已完成');
    assert.match(container.textContent, /操作已完成/);
  } finally {
    await act(async () => root.unmount());
    container.remove();
    global.fetch = originalFetch;
  }
});

test('late send and cancellation responses cannot replace the conversation selected afterwards', async () => {
  const load = createSourceLoader();
  const api = load('api/client.ts');
  api.setCsrfToken('fixture-csrf');
  const { useAiChat } = load('components/ai/useAiChat.ts');
  const streams = [];
  const originalFetch = global.fetch;
  global.fetch = async (url, options) => {
    streams.push({ url, signal: options.signal });
    return new Response(new ReadableStream({ start(controller) {
      options.signal.addEventListener('abort', () => controller.close(), { once: true });
    } }), { headers: { 'Content-Type': 'text/event-stream' } });
  };
  let finishSend, finishCancel;
  api.default.defaults.adapter = async (config) => {
    let data;
    if (config.url.endsWith('/assistants')) data = [{ agentCode: 'fixture' }];
    else if (config.url.endsWith('/conversations')) data = [{ id: 1 }, { id: 2 }];
    else if (config.url.endsWith('/messages') && config.method === 'post') {
      data = await new Promise(resolve => { finishSend = resolve; });
    } else if (config.url.endsWith('/messages')) data = config.url.includes('/2/')
      ? { items: [{ id: 22, requestId: 8, role: 'ASSISTANT', content: '会话B', status: 'PENDING' }] }
      : { items: [] };
    else if (config.url.endsWith('/events')) data = { events: [], terminal: false };
    else if (config.url.endsWith('/cancel')) data = await new Promise(resolve => { finishCancel = resolve; });
    else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, data, msg: 'OK', timestamp: 1 } };
  };
  let chat;
  function Harness() { chat = useAiChat({ agentCode: 'fixture' }); return null; }
  const root = createRoot(document.createElement('div'));
  const flush = () => new Promise(resolve => setImmediate(resolve));
  try {
    await act(async () => { root.render(React.createElement(Harness)); });
    let pending;
    await act(async () => { pending = chat.send('会话A'); await flush(); });
    await act(async () => { await chat.selectConversation(2); });
    await act(async () => { finishSend({ requestId: 7, userMessageId: 10, assistantMessageId: 11 }); await pending; });
    assert.equal(chat.activeConversationId, 2);
    assert.equal(chat.run.requestId, 8);
    assert.equal(chat.messages.length, 1);
    assert.equal(chat.messages[0].content, '会话B');
    assert.ok(streams.every(item => !item.url.includes('/7/')));

    let cancelling;
    await act(async () => { cancelling = chat.stop(); await flush(); });
    await act(async () => { await chat.selectConversation(1); });
    await act(async () => { await chat.selectConversation(2); });
    const currentSignal = streams.at(-1).signal;
    await act(async () => { finishCancel({ status: 'CANCELLED' }); await cancelling; });
    assert.equal(chat.run.requestId, 8);
    assert.equal(chat.run.running, true);
    assert.equal(currentSignal.aborted, false);
    assert.equal(chat.messages[0].status, 'PENDING');
  } finally {
    await act(async () => root.unmount());
    global.fetch = originalFetch;
  }
});

test('history restoration blocks a competing send and consumes events completed after the message snapshot', async () => {
  const load = createSourceLoader();
  const api = load('api/client.ts');
  api.setCsrfToken('fixture-csrf');
  const { useAiChat } = load('components/ai/useAiChat.ts');
  let finishHistory;
  let sends = 0;
  api.default.defaults.adapter = async config => {
    let data;
    if (config.url.endsWith('/assistants')) data = [{ agentCode: 'fixture' }];
    else if (config.url.endsWith('/conversations')) data = [{ id: 1 }];
    else if (config.url.endsWith('/messages') && config.method === 'get') data = await new Promise(resolve => { finishHistory = resolve; });
    else if (config.url.endsWith('/messages')) { sends++; data = {}; }
    else if (config.url.endsWith('/events')) data = { terminal: true, status: 'SUCCEEDED', events: [
      { sequence: 1, type: 'message.completed', data: { messageId: 11, content: '恢复完成的答案' } },
    ] };
    else throw new Error(`Unexpected fixture request ${config.url}`);
    return { config, status: 200, statusText: 'OK', headers: {}, data: { code: 200, data, msg: 'OK', timestamp: 1 } };
  };
  let chat;
  function Harness() { chat = useAiChat({ agentCode: 'fixture' }); return null; }
  const root = createRoot(document.createElement('div'));
  try {
    await act(async () => { root.render(React.createElement(Harness)); });
    assert.equal(chat.loadingMessages, true);
    await act(async () => { await chat.send('不应在恢复时发送'); });
    assert.equal(sends, 0);
    await act(async () => {
      finishHistory({ items: [{ id: 11, role: 'ASSISTANT', status: 'PENDING', content: '', requestId: 7 }] });
      await new Promise(resolve => setImmediate(resolve));
    });
    assert.equal(chat.messages[0].content, '恢复完成的答案');
    assert.notEqual(chat.messages[0].status, 'PENDING');
    assert.equal(chat.run.running, false);
  } finally { await act(async () => root.unmount()); }
});
