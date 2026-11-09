const assert = require('node:assert/strict');
const { test } = require('node:test');
const { JSDOM } = require(process.env.FRONT_TEST_JSDOM_PATH || 'jsdom');
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://fixture.example' });
for (const key of ['window', 'document', 'HTMLElement', 'Element', 'Node', 'Event']) global[key] = dom.window[key];
global.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react');
const { createRoot } = require('react-dom/client');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');
const { Form, createFormInstance } = createSourceLoader()('components/antd-compat/Form.tsx');
const { createElement: h, act, StrictMode } = React;

test('unmounted channel fields stop blocking saves while their draft values survive remounting', async () => {
  const form = createFormInstance();
  form.setFieldsValue({ subject: '', content: '', templateCode: '' });
  const root = createRoot(document.createElement('div'));
  const view = (mail) => h(StrictMode, null, h(Form, { form },
    mail ? h(Form.Item, { key: 'subject', name: 'subject', rules: [{ required: true, message: '请填写主题' }] }, h('input')) : null,
    mail ? h(Form.Item, { key: 'content', name: 'content' }, h('input')) : null,
    h(Form.Item, { name: 'templateCode', rules: [{ required: true }] }, h('input'))));
  try {
    await act(async () => root.render(view(true)));
    await act(async () => form.setFieldsValue({ templateCode: 'fixture', content: 'Draft' }));
    await act(async () => assert.rejects(form.validateFields()));
    assert.deepEqual(form.getFieldError('subject'), ['请填写主题']);
    await act(async () => root.render(view(false)));
    await act(async () => assert.doesNotReject(form.validateFields()));
    assert.deepEqual(form.getFieldError('subject'), []);
    assert.equal(form.getFieldValue('content'), 'Draft');
    await act(async () => root.render(view(true)));
    await act(async () => assert.rejects(form.validateFields()));
    await act(async () => form.setFieldValue('subject', 'Ready'));
    await act(async () => assert.doesNotReject(form.validateFields()));
    assert.equal(form.getFieldValue('content'), 'Draft');
  } finally {
    await act(async () => root.unmount());
  }
});

test('validation from an unmounted field cannot publish an error after the field remounts', async () => {
  const form = createFormInstance();
  form.setFieldValue('endpoint', 'fixture');
  const root = createRoot(document.createElement('div'));
  let release, signalStarted, calls = 0;
  const gate = new Promise(resolve => { release = resolve; });
  const started = new Promise(resolve => { signalStarted = resolve; });
  const rules = [{ validator: async () => {
    if (++calls === 1) {
      signalStarted();
      await gate;
      throw new Error('Stale result');
    }
  } }];
  const view = show => h(StrictMode, null, h(Form, { form },
    show ? h(Form.Item, { name: 'endpoint', rules }, h('input')) : null));
  try {
    await act(async () => root.render(view(true)));
    const oldValidation = form.validateFields().catch(failure => failure);
    await started;
    await act(async () => root.render(view(false)));
    await act(async () => root.render(view(true)));
    await act(async () => assert.doesNotReject(form.validateFields()));
    release();
    const failure = await oldValidation;
    assert.equal(failure.outOfDate, true);
    assert.deepEqual(form.getFieldError('endpoint'), []);
    assert.equal(form.getFieldValue('endpoint'), 'fixture');
  } finally {
    release();
    await act(async () => root.unmount());
  }
});

test('replacing a field with another control at the same path preserves the replacement rule', async () => {
  const form = createFormInstance();
  form.setFieldValue('destination', '');
  const root = createRoot(document.createElement('div'));
  const view = mode => h(Form, { form }, h(Form.Item,
    { key: mode, name: 'destination', rules: [{ required: true, message: mode }] }, h('input')));
  try {
    await act(async () => root.render(view('email')));
    await act(async () => root.render(view('phone')));
    await act(async () => assert.rejects(form.validateFields()));
    assert.deepEqual(form.getFieldError('destination'), ['phone']);
  } finally {
    await act(async () => root.unmount());
  }
});
