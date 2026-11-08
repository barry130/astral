const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

const { createElement, Fragment } = require('react');

test('two unnamed forms have distinct valid label/control IDs', () => {
  const view = (key) =>
    createElement(
      Form,
      { key, form: createFormInstance() },
      createElement(
        Form.Item,
        { name: ['endpoint', 'url'], label: '上传地址' },
        createElement('input'),
      ),
    );
  const html = renderToString(createElement(Fragment, null, view('first'), view('second')));
  const ids = [...html.matchAll(/<input[^>]*id="([^"]+)"/g)].map((match) => match[1]);
  assert.equal(ids.length, 2);
  assert.equal(new Set(ids).size, 2);
  assert.ok(
    ids.every((id) => !id.includes('\u0000')),
    'field IDs must survive HTML parsing',
  );
});
const { renderToString } = require('react-dom/server');
const { createFormInstance, runRules, Form } = createSourceLoader()(
  'components/antd-compat/Form.tsx',
);

function renderFields(form, fields, props = {}) {
  return renderToString(
    createElement(
      Form,
      { form, ...props },
      fields.map(({ name, rules, initialValue }) =>
        createElement(
          Form.Item,
          { key: String(name), name, rules, initialValue },
          createElement('input'),
        ),
      ),
    ),
  );
}

test('validating one corrected field preserves errors on other fields', async () => {
  const form = createFormInstance();
  renderFields(form, [
    { name: 'username', rules: [{ required: true, message: '请填写用户名' }] },
    { name: 'password', rules: [{ required: true, message: '请填写密码' }] },
  ]);
  await assert.rejects(form.validateFields(), (failure) => failure.errorFields.length === 2);
  form.setFieldValue('username', 'audit-user');
  await form.validateFields(['username']);
  assert.deepEqual(form.getFieldError('password'), ['请填写密码']);
});

test('invalid compatibility inputs expose required state and a linked error to assistive technology', async () => {
  const { Input } = createSourceLoader()('components/antd-compat/Inputs.tsx');
  const form = createFormInstance();
  const view = () =>
    createElement(
      Form,
      { form },
      createElement(
        Form.Item,
        {
          name: 'username',
          label: '用户名',
          rules: [{ required: true, message: '请填写用户名' }],
        },
        createElement(Input),
      ),
    );
  renderToString(view());
  await assert.rejects(form.validateFields());
  const html = renderToString(view());
  const input = html.match(/<input[^>]*>/)?.[0] ?? '';
  assert.match(input, /aria-invalid="true"/);
  assert.match(input, /aria-required="true"/);
  const description = input.match(/aria-describedby="([^"]+)"/)?.[1];
  assert.ok(description);
  assert.ok(html.includes(`id="${description}"`));
  assert.match(html, /请填写用户名/);
});

test('ordinary help is descriptive text, not an error, and remains linked beside extra text', () => {
  const { Input } = createSourceLoader()('components/antd-compat/Inputs.tsx');
  const form = createFormInstance();
  const html = renderToString(
    createElement(
      Form,
      { form },
      createElement(
        Form.Item,
        { name: 'endpoint', help: '请填写 HTTPS 地址', extra: '地址由管理员批准' },
        createElement(Input),
      ),
    ),
  );
  const input = html.match(/<input[^>]*>/)?.[0] ?? '';
  assert.doesNotMatch(input, /aria-invalid="true"/);
  assert.doesNotMatch(html, /role="alert"/);
  const descriptions = input.match(/aria-describedby="([^"]+)"/)?.[1].split(' ') ?? [];
  assert.equal(descriptions.length, 2);
  for (const id of descriptions) assert.ok(html.includes(`id="${id}"`));
  assert.match(html, /请填写 HTTPS 地址/);
  assert.match(html, /地址由管理员批准/);
});

test('changing an invalid field runs its validator again and handles the expected rejection', async () => {
  const { Input } = createSourceLoader()('components/antd-compat/Inputs.tsx');
  const form = createFormInstance();
  const seen = [];
  let change;
  function CaptureInput(props) {
    change = props.onChange;
    return createElement(Input, props);
  }
  const rules = [
    {
      validator: async (_rule, value) => {
        seen.push(value);
        if (value !== 'valid') throw new Error('值格式不正确');
      },
    },
  ];
  form.setFieldValue('endpoint', 'invalid');
  const view = () => createElement(Form, { form },
    createElement(Form.Item, { name: 'endpoint', rules }, createElement(CaptureInput)));
  renderToString(view());
  await assert.rejects(form.validateFields());
  renderToString(view());
  change({ target: { value: 'still-invalid' } });
  await new Promise(setImmediate);
  assert.deepEqual(seen, ['invalid', 'still-invalid']);
  assert.deepEqual(form.getFieldError('endpoint'), ['值格式不正确']);
});

test('conditional custom validators receive an empty field value', async () => {
  const message = await runRules(undefined, [
    {
      validator: async (_rule, value) => {
        if (!value) throw new Error('请填写上传地址');
      },
    },
  ]);
  assert.equal(message, '请填写上传地址');
});

test('removing a field rule removes its old validation requirement', async () => {
  const form = createFormInstance();
  renderFields(form, [
    { name: 'endpoint', rules: [{ required: true, message: '请填写上传地址' }] },
  ]);
  await assert.rejects(form.validateFields());
  renderFields(form, [{ name: 'endpoint' }]);
  await assert.doesNotReject(form.validateFields());
  assert.deepEqual(form.getFieldError('endpoint'), []);
});

test('a late async validation result cannot overwrite a newer field value and validation', async () => {
  const form = createFormInstance();
  let release;
  let signalStarted;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const started = new Promise((resolve) => {
    signalStarted = resolve;
  });
  renderFields(form, [
    {
      name: 'endpoint',
      rules: [
        {
          validator: async (_rule, value) => {
            if (value === 'old') {
              signalStarted();
              await gate;
              throw new Error('旧地址无效');
            }
          },
        },
      ],
    },
  ]);
  form.setFieldValue('endpoint', 'old');
  const oldValidation = form.validateFields().catch((failure) => failure);
  await started;
  form.setFieldValue('endpoint', 'new');
  await form.validateFields();
  release();
  const failure = await oldValidation;
  assert.deepEqual(form.getFieldError('endpoint'), []);
  assert.equal(form.getFieldValue('endpoint'), 'new');
  assert.equal(failure.outOfDate, true);
});

test('a newer failed validation wins even when an earlier async validation later succeeds', async () => {
  const form = createFormInstance();
  let release;
  let signalStarted;
  let call = 0;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const started = new Promise((resolve) => {
    signalStarted = resolve;
  });
  renderFields(form, [
    {
      name: 'endpoint',
      rules: [
        {
          validator: async () => {
            if (++call === 1) {
              signalStarted();
              await gate;
            } else {
              throw new Error('最新地址无效');
            }
          },
        },
      ],
    },
  ]);
  form.setFieldValue('endpoint', 'same-value');
  const oldValidation = form.validateFields().catch((failure) => failure);
  await started;
  await assert.rejects(form.validateFields());
  release();
  const failure = await oldValidation;
  assert.equal(failure.outOfDate, true);
  assert.deepEqual(form.getFieldError('endpoint'), ['最新地址无效']);
});

test('form paths do not treat prototype properties as submitted fields', () => {
  const form = createFormInstance();
  assert.equal(form.getFieldValue('constructor'), undefined);
  assert.equal(form.getFieldValue('toString'), undefined);
  form.setFieldsValue(JSON.parse('{"__proto__":{"name":"ordinary-field"}}'));
  const values = form.getFieldsValue();
  assert.equal(Object.getPrototypeOf(values), Object.prototype);
  assert.equal(Object.hasOwn(values, '__proto__'), true);
  assert.equal(values.__proto__.name, 'ordinary-field');
  assert.equal({}.name, undefined);
});

test('an explicitly selected optional field can be validated without rules', async () => {
  const form = createFormInstance();
  renderFields(form, [{ name: 'description' }, { name: 'title', rules: [{ required: true }] }]);
  form.setFieldValue('description', 'optional-value');
  form.setFieldValue('title', 'valid-title');
  assert.deepEqual(await form.validateFields(['description']), { description: 'optional-value' });
  assert.deepEqual(await form.validateFields(['description', 'title']), {
    description: 'optional-value',
    title: 'valid-title',
  });
});

test('prototype-named fields have ordinary validation errors', async () => {
  const form = createFormInstance();
  assert.deepEqual(form.getFieldError('constructor'), []);
  renderFields(form, [{ name: '__proto__', rules: [{ required: true, message: '请填写值' }] }]);
  await assert.rejects(form.validateFields());
  assert.deepEqual(form.getFieldError('__proto__'), ['请填写值']);
});

test('selected nested form values preserve the request object shape', () => {
  const form = createFormInstance();
  form.setFieldsValue({
    profile: { nickname: '明月', email: 'audit@example.invalid' },
    enabled: true,
  });
  assert.deepEqual(form.getFieldsValue([['profile', 'nickname']]), {
    profile: { nickname: '明月' },
  });
});
