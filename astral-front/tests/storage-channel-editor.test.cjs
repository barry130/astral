const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const ts = require('typescript');

// 直接执行生产模块里的纯函数，验证渠道编辑器与后端画像/凭据契约一致。
function extract(relativePath, name, context = {}) {
  const filename = path.resolve(__dirname, '..', relativePath);
  const text = fs.readFileSync(filename, 'utf8');
  const source = ts.createSourceFile(filename, text, ts.ScriptTarget.Latest, true);
  let expression;
  function visit(node) {
    if (ts.isVariableDeclaration(node) && node.name.getText(source) === name) {
      expression = node.initializer.getText(source);
    } else if (ts.isFunctionDeclaration(node) && node.name?.text === name) {
      expression = node.getText(source).replace(/^export\s+/, '');
    }
    ts.forEachChild(node, visit);
  }
  visit(source);
  assert.ok(expression, `Missing production declaration: ${name}`);
  const compiled = ts.transpileModule(`(${expression})`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText;
  return vm.runInNewContext(compiled, { Error, URL, ...context }, { filename });
}

function loadEditor() {
  const constants = {
    HARD_MAX_BYTES: extract('src/components/storage/HttpChannelEditorFields.tsx', 'HARD_MAX_BYTES'),
    MIN_ATTEMPT_MS: extract('src/components/storage/HttpChannelEditorFields.tsx', 'MIN_ATTEMPT_MS'),
    MAX_ATTEMPT_MS: extract('src/components/storage/HttpChannelEditorFields.tsx', 'MAX_ATTEMPT_MS'),
    MAX_CONDITIONS: extract('src/components/storage/HttpChannelEditorFields.tsx', 'MAX_CONDITIONS'),
    DEFAULT_ATTEMPT_MS: extract(
      'src/components/storage/HttpChannelEditorFields.tsx',
      'DEFAULT_ATTEMPT_MS',
    ),
    SECRET_NAME: extract('src/components/storage/HttpChannelEditorFields.tsx', 'SECRET_NAME'),
    SECRET_REF: extract('src/components/storage/HttpChannelEditorFields.tsx', 'SECRET_REF'),
    MIME: extract('src/components/storage/HttpChannelEditorFields.tsx', 'MIME'),
    SUPPORTED_MIMES: extract(
      'src/components/storage/HttpChannelEditorFields.tsx',
      'SUPPORTED_MIMES',
    ),
  };
  const rowValue = extract('src/components/storage/HttpChannelEditorFields.tsx', 'rowValue');
  const parseMimes = extract('src/components/storage/HttpChannelEditorFields.tsx', 'parseMimes', {
    ...constants,
  });
  const buildAuth = extract('src/components/storage/HttpChannelEditorFields.tsx', 'buildAuth', {
    ...constants,
  });
  const preservedUnmanaged = extract(
    'src/components/storage/HttpChannelEditorFields.tsx',
    'preservedUnmanaged',
  );
  const buildProfile = extract(
    'src/components/storage/HttpChannelEditorFields.tsx',
    'buildProfile',
    {
      ...constants,
      rowValue,
      parseMimes,
      buildAuth,
      preservedUnmanaged,
    },
  );
  const emptyForm = extract('src/components/storage/HttpChannelEditorFields.tsx', 'emptyForm', {
    ...constants,
  });
  const referencedSecretsFromForm = extract(
    'src/components/storage/HttpChannelEditorFields.tsx',
    'referencedSecretsFromForm',
    { SECRET_REF: constants.SECRET_REF },
  );
  return {
    ...constants,
    rowValue,
    parseMimes,
    buildAuth,
    buildProfile,
    emptyForm,
    referencedSecretsFromForm,
  };
}

const editor = loadEditor();

/** vm 新上下文里创建的对象原型不同，断言前转成当前 realm 的普通对象 */
function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

function validForm() {
  return {
    ...editor.emptyForm(),
    name: 'test-channel',
    endpoint: 'https://upload.example.com/api',
    authMode: 'HEADER',
    authName: 'X-Token',
    authValueTemplate: 'Bearer ${secret:token}',
  };
}

test('buildProfile rejects http endpoints before any request is made', () => {
  const form = { ...validForm(), endpoint: 'http://upload.example.com/api' };
  assert.throws(() => editor.buildProfile(form, []), /https:\/\//);
});

test('buildProfile only emits backend-supported PNG/JPEG MIME types', () => {
  const form = { ...validForm(), allowedMimes: 'image/png, image/webp' };
  assert.throws(() => editor.buildProfile(form, []), /仅支持 image\/png、image\/jpeg/);
});

test('buildProfile keeps approved origins and preserves unmanaged response pointers', () => {
  const base = {
    schemaVersion: 1,
    endpoint: 'https://upload.example.com/api',
    method: 'POST',
    auth: { mode: 'NONE' },
    body: { mode: 'MULTIPART', fileField: 'file', fileNameTemplate: '${publicId}.${extension}' },
    response: {
      success: [],
      urlPointer: '/url',
      allowedUrlOrigins: [],
      thumbPointer: '/thumb',
      idPointer: '/id',
      sizePointer: '/size',
      sizeUnit: 'BYTE',
      numericStringPointers: ['/id'],
    },
    limits: { maxBytes: 1, allowedMimes: ['image/png'] },
    timeouts: { attemptMs: 30000 },
  };
  const profile = editor.buildProfile(validForm(), ['https://cdn.example.com'], base);
  assert.equal(profile.endpoint, 'https://upload.example.com/api');
  assert.deepEqual(profile.response.allowedUrlOrigins, ['https://cdn.example.com']);
  assert.equal(profile.response.thumbPointer, '/thumb');
  assert.equal(profile.response.idPointer, '/id');
  assert.deepEqual(profile.response.numericStringPointers, ['/id']);
  assert.deepEqual(plain(profile.limits.allowedMimes), ['image/png', 'image/jpeg']);
});

test('referencedSecretsFromForm scans BASIC names and ${secret:...} references in all rows', () => {
  const form = {
    ...editor.emptyForm(),
    authMode: 'BASIC',
    usernameSecret: 'basicUser',
    passwordSecret: 'basicPass',
    authValueTemplate: 'Bearer ${secret:token}',
    headers: [{ key: 'X', value: '${secret:headerToken}' }],
    query: [{ key: 'q', value: '${secret:queryToken}' }],
    fields: [{ key: 'field', value: '${secret:fieldToken}' }],
  };
  assert.deepEqual(plain(editor.referencedSecretsFromForm(form)), [
    'basicPass',
    'basicUser',
    'fieldToken',
    'headerToken',
    'queryToken',
    'token',
  ]);
});

function secretHarness(overrides = {}) {
  const buildSecretChanges = extract(
    'src/components/storage/HttpChannelEditorFields.tsx',
    'buildSecretChanges',
    {
      useCallback: (fn) => fn,
      secretList: [],
      secretOps: {},
      referencedNames: [],
      secretBindingChanged: false,
      secretValuesRef: { current: {} },
      ...overrides,
    },
  );
  return buildSecretChanges();
}

test('configured but unreferenced secret is cleared instead of KEEP', () => {
  const changes = secretHarness({
    secretList: [{ name: 'oldToken', configured: true }],
    secretOps: {},
    referencedNames: [],
  });
  assert.deepEqual(plain(changes.oldToken), { op: 'CLEAR' });
});

test('SET requires a value and a live profile reference', () => {
  assert.throws(
    () =>
      secretHarness({
        secretList: [{ name: 'token', configured: false }],
        secretOps: { token: 'SET' },
        referencedNames: ['token'],
        secretValuesRef: { current: {} },
      }),
    /已标记设置但内容为空/,
  );
  assert.throws(
    () =>
      secretHarness({
        secretList: [{ name: 'token', configured: false }],
        secretOps: { token: 'SET' },
        referencedNames: [],
        secretValuesRef: { current: { token: 'test-value' } },
      }),
    /未被当前画像引用/,
  );
});

test('endpoint binding change rejects KEEP for a configured referenced secret', () => {
  assert.throws(
    () =>
      secretHarness({
        secretList: [{ name: 'token', configured: true }],
        referencedNames: ['token'],
        secretBindingChanged: true,
      }),
    /上传地址已变化.*不能沿用/,
  );
});

test('SET/CLEAR/KEEP produce the exact three-state payload', () => {
  const changes = secretHarness({
    secretList: [
      { name: 'keepToken', configured: true },
      { name: 'clearToken', configured: true },
      { name: 'newToken', configured: false },
      { name: 'unconfiguredClear', configured: false },
    ],
    secretOps: { newToken: 'SET', clearToken: 'CLEAR', unconfiguredClear: 'CLEAR' },
    referencedNames: ['keepToken', 'newToken'],
    secretValuesRef: { current: { newToken: 'new-value' } },
  });
  assert.deepEqual(plain(changes), {
    keepToken: { op: 'KEEP' },
    clearToken: { op: 'CLEAR' },
    newToken: { op: 'SET', value: 'new-value' },
  });
});
