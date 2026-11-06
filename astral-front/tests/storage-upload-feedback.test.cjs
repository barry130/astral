const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const ts = require('typescript');

// Execute the actual production handlers with isolated API/state/toast adapters.
// This is a handler-contract regression, not a browser rendering test.
function handler(relativePath, name, context) {
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
  assert.ok(expression, `Missing production handler: ${name}`);
  const compiled = ts.transpileModule(`(${expression})`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
  }).outputText;
  return vm.runInNewContext(compiled, { Error, ...context }, { filename });
}

function entry(id, providerType = 'CUSTOM_HTTP') {
  return {
    id,
    folderId: 3003,
    folderName: '/qt-media/avatar',
    providerType,
    state: 'wait',
    file: {
      name: `${id}.png`,
      type: 'image/png',
      size: 2097152,
      arrayBuffer: async () => new ArrayBuffer(0),
    },
  };
}

function harness(entries, upload, onQueueChange = () => {}) {
  let rows = entries.map((item) => ({ ...item }));
  let refresh = 0;
  let nextToastId = 0;
  const notifications = [];
  const busy = [];
  const lock = { current: false };
  const context = {
    uploadLock: lock,
    admin: true,
    uploadStorageFile: upload,
    setUploading: (value) => busy.push(value),
    setQueue: (update) => {
      rows = update(rows);
      onQueueChange(rows);
    },
    setRefresh: (update) => {
      refresh = update(refresh);
    },
    toast: {
      error: (title, options) => {
        const id = options.id ?? ++nextToastId;
        notifications.push({ title, options: { ...options }, id });
        return id;
      },
    },
  };
  context.errorText = handler('src/components/storage/FileManager.tsx', 'errorText', {});
  const run = handler('src/components/storage/FileManager.tsx', 'uploadEntries', context);
  return { run, lock, busy, notifications, rows: () => rows, refresh: () => refresh };
}

const serverMessage = '上传请求校验失败: 文件大小超过上限（最大 1 MB），请压缩后重试';

test('HTTP 200/code 300 STORAGE045 is shown immediately and retained in the queue', async () => {
  let contentSent = false;
  const response = { code: 300, data: null, errorCode: 'STORAGE045', msg: serverMessage };
  const api = {
    createUploadOperation: async () => response,
    putUploadContent: async () => {
      contentSent = true;
      throw new Error('Unexpected content send');
    },
  };
  const uploadViaUploadOperations = handler(
    'src/lib/storage-upload.ts',
    'uploadViaUploadOperations',
    {
      storageApi: api,
      sha256Hex: async () => 'test-digest',
      idempotencyKeyFor: async () => 'test-idempotency-key',
    },
  );
  const upload = handler('src/lib/storage-upload.ts', 'uploadStorageFile', {
    uploadViaUploadOperations,
  });
  const item = entry('oversize');
  const h = harness([item], upload);
  await h.run([item]);
  assert.equal(h.rows()[0].state, 'fail');
  assert.equal(h.rows()[0].error, serverMessage);
  assert.equal(contentSent, false);
  assert.equal(h.notifications.length, 1);
  assert.match(h.notifications[0].options.description, /oversize\.png/);
  assert.ok(h.notifications[0].options.description.includes(serverMessage));
  assert.equal(h.lock.current, false);
  assert.deepEqual(h.busy, [true, false]);
  assert.equal(h.refresh(), 1);
});

test('concurrent batch failures update one popup without losing individual errors', async () => {
  const items = [entry('a'), entry('b'), entry('ok'), entry('c')];
  const h = harness(items, async (_folder, file) => {
    if (file.name === 'ok.png') return 'public-id';
    throw new Error(file.name === 'c.png' ? '网络请求失败' : serverMessage);
  });
  await h.run(items);
  assert.equal(h.notifications.length, 3);
  assert.equal(new Set(h.notifications.map((item) => item.id)).size, 1);
  assert.equal(h.notifications[0].options.id, undefined);
  assert.ok(h.notifications.slice(1).every((item) => item.options.id === h.notifications[0].id));
  assert.match(h.notifications.at(-1).title, /3/);
  assert.equal(h.rows().filter((item) => item.state === 'fail').length, 3);
  assert.equal(h.rows().find((item) => item.id === 'ok').state, 'ok');
  assert.equal(h.rows().find((item) => item.id === 'c').error, '网络请求失败');
});

test('successful batch does not display a failure popup', async () => {
  const items = [entry('ok1'), entry('ok2')];
  const h = harness(items, async () => 'public-id');
  await h.run(items);
  assert.equal(h.notifications.length, 0);
  assert.ok(h.rows().every((item) => item.state === 'ok'));
  assert.equal(h.lock.current, false);
});

test('an explicit retry starts a fresh popup instead of suppressing the same error', async () => {
  const item = entry('retry');
  const h = harness([item], async () => {
    throw new Error(serverMessage);
  });
  await h.run([item]);
  await h.run([item]);
  assert.equal(h.notifications.length, 2);
  assert.notEqual(h.notifications[0].id, h.notifications[1].id);
  assert.equal(h.refresh(), 2);
});

test('locked queue does not start another batch or display a popup', async () => {
  const item = entry('locked');
  const h = harness([item], async () => {
    throw new Error('Unexpected upload');
  });
  h.lock.current = true;
  await h.run([item]);
  assert.equal(h.notifications.length, 0);
  assert.deepEqual(h.busy, []);
  assert.equal(h.rows()[0].state, 'wait');
  assert.equal(h.lock.current, true);
});

test('failure popup appears before another concurrent upload finishes', async () => {
  let release;
  let notify;
  const waiting = new Promise((resolve) => {
    release = resolve;
  });
  const firstNotification = new Promise((resolve) => {
    notify = resolve;
  });
  const items = [entry('fail-now'), entry('slow')];
  const h = harness(
    items,
    async (_folder, file) => {
      if (file.name === 'fail-now.png') throw new Error(serverMessage);
      await waiting;
      return 'public-id';
    },
    (rows) => {
      if (rows.some((item) => item.state === 'fail')) notify();
    },
  );
  // Resolve the observation from the real catch-path queue update. The popup must
  // already be emitted by the time the observer resumes on the next microtask.
  const run = h.run(items);
  await firstNotification;
  try {
    assert.equal(h.notifications.length, 1);
    assert.equal(h.rows().find((item) => item.id === 'slow').state, 'run');
    assert.equal(h.lock.current, true);
  } finally {
    release();
    await run;
  }
});
