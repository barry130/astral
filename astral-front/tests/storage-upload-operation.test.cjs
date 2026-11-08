const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const ts = require('typescript');

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
  return vm.runInNewContext(compiled, { Error, Promise, ...context }, { filename });
}

const FILE = {
  name: 'avatar.png',
  type: 'image/png',
  size: 10,
  lastModified: 1,
  arrayBuffer: async () => new ArrayBuffer(0),
};

function operation(overrides = {}) {
  return {
    uploadId: 'upload-1',
    maxBytes: 100,
    canSendContent: true,
    ...overrides,
  };
}

function harness(api) {
  const isTerminal = extract('src/lib/storage-upload.ts', 'isTerminal');
  const settle = extract('src/lib/storage-upload.ts', 'settle');
  const TWO_STEP_MAX_POLLS = extract('src/lib/storage-upload.ts', 'TWO_STEP_MAX_POLLS');
  const TWO_STEP_POLL_MS = extract('src/lib/storage-upload.ts', 'TWO_STEP_POLL_MS');
  return extract('src/lib/storage-upload.ts', 'uploadViaUploadOperations', {
    storageApi: api,
    sha256Hex: async () => 'digest',
    idempotencyKeyFor: async () => 'idempotency-key',
    isTerminal,
    settle,
    TWO_STEP_MAX_POLLS,
    TWO_STEP_POLL_MS,
    setTimeout: (resolve) => resolve(),
  });
}

test('only COMMITTED is treated as success', async () => {
  let gets = 0;
  const upload = harness({
    createUploadOperation: async () => ({ code: 200, data: operation() }),
    putUploadContent: async () => ({
      code: 200,
      data: {
        contentAccepted: true,
        operation: { state: 'COMMITTED', publicId: 'public-1' },
      },
    }),
    getUploadOperation: async () => {
      gets += 1;
      throw new Error('should not poll after COMMITTED');
    },
  });
  assert.equal(await upload(3003, FILE), 'public-1');
  assert.equal(gets, 0);
});

test('contentAccepted with a non-terminal operation is polled until COMMITTED', async () => {
  let gets = 0;
  const upload = harness({
    createUploadOperation: async () => ({ code: 200, data: operation() }),
    putUploadContent: async () => ({
      code: 200,
      data: { contentAccepted: true, operation: { state: 'CREATED' } },
    }),
    getUploadOperation: async () => {
      gets += 1;
      return gets === 1
        ? { code: 200, data: { state: 'REMOTE_SUCCEEDED' } }
        : { code: 200, data: { state: 'COMMITTED', publicId: 'public-2' } };
    },
  });
  assert.equal(await upload(3003, FILE), 'public-2');
  assert.equal(gets, 2);
});

test('UNKNOWN_OUTCOME reports a non-retryable message without resending or re-creating', async () => {
  let creates = 0;
  let puts = 0;
  const upload = harness({
    createUploadOperation: async () => {
      creates += 1;
      return { code: 200, data: operation() };
    },
    putUploadContent: async () => {
      puts += 1;
      return {
        code: 200,
        data: { contentAccepted: true, operation: { state: 'UNKNOWN_OUTCOME' } },
      };
    },
    getUploadOperation: async () => {
      throw new Error('UNKNOWN_OUTCOME must not poll');
    },
  });
  await assert.rejects(upload(3003, FILE), /结果未知.*不要重复上传/);
  assert.equal(creates, 1);
  assert.equal(puts, 1);
});

test('PUT network timeout is surfaced as unknown outcome instead of a plain failure', async () => {
  let creates = 0;
  let puts = 0;
  const upload = harness({
    createUploadOperation: async () => {
      creates += 1;
      return { code: 200, data: operation() };
    },
    putUploadContent: async () => {
      puts += 1;
      throw new Error('timeout of 30000ms exceeded');
    },
    getUploadOperation: async () => {
      throw new Error('timeout must not trigger a fresh create');
    },
  });
  await assert.rejects(upload(3003, FILE), /上传结果未知.*不要重复上传/);
  assert.equal(creates, 1);
  assert.equal(puts, 1);
});

test('create business failure is thrown before content is sent', async () => {
  let puts = 0;
  const upload = harness({
    createUploadOperation: async () => ({
      code: 300,
      errorCode: 'STORAGE045',
      msg: '文件内容不是 JPEG/PNG',
      data: null,
    }),
    putUploadContent: async () => {
      puts += 1;
      throw new Error('unexpected');
    },
    getUploadOperation: async () => {
      throw new Error('unexpected');
    },
  });
  await assert.rejects(upload(3003, FILE), /不是 JPEG\/PNG/);
  assert.equal(puts, 0);
});

test('duplicate create that cannot send content polls the existing operation', async () => {
  let puts = 0;
  const upload = harness({
    createUploadOperation: async () => ({ code: 200, data: operation({ canSendContent: false }) }),
    putUploadContent: async () => {
      puts += 1;
      throw new Error('unexpected');
    },
    getUploadOperation: async () => ({
      code: 200,
      data: { state: 'COMMITTED', publicId: 'public-3' },
    }),
  });
  assert.equal(await upload(3003, FILE), 'public-3');
  assert.equal(puts, 0);
});
