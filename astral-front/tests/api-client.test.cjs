const assert = require('node:assert/strict');
const { test, afterEach } = require('node:test');
const axios = require('axios');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document');
const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
const originalNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
const originalClientVersion = process.env.NEXT_PUBLIC_CLIENT_VERSION;
afterEach(() => {
  if (originalClientVersion === undefined) delete process.env.NEXT_PUBLIC_CLIENT_VERSION;
  else process.env.NEXT_PUBLIC_CLIENT_VERSION = originalClientVersion;
  for (const [key, descriptor] of [
    ['document', originalDocument],
    ['window', originalWindow],
    ['navigator', originalNavigator],
  ]) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor);
    else delete globalThis[key];
  }
});

function loadClient() {
  return createSourceLoader()('api/client.ts');
}

function setDocument(cookie) {
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie } });
}

function respond(config, data, status = 200) {
  return { data, status, statusText: '', headers: {}, config };
}

function rejectHttp(config, data, status) {
  return new axios.AxiosError(
    'transport rejected',
    'ERR_BAD_REQUEST',
    config,
    {},
    respond(config, data, status),
  );
}

test('a gateway HTTP401 with a Result message redirects protected pages and preserves that message', async () => {
  const api = loadClient();
  const location = { pathname: '/dashboard/system/user', href: '' };
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { location } });
  api.default.defaults.adapter = async (config) => {
    throw rejectHttp(config, { code: 401, errorCode: 'AUTH001', msg: '登录已失效' }, 401);
  };
  await assert.rejects(api.request.get('/api/v1/admin/system/user/page'), {
    message: '登录已失效',
  });
  assert.equal(location.href, '/login');
});

test('clearing a session while CSRF refresh is pending prevents late token restoration and replay', async () => {
  const api = loadClient();
  setDocument('');
  api.setCsrfToken('old-session-token');
  let releaseInfo;
  let infoStarted;
  let writes = 0;
  const started = new Promise((resolve) => {
    infoStarted = resolve;
  });
  api.default.defaults.adapter = (config) => {
    if (config.url === '/api/v1/all/auth/info') {
      infoStarted();
      return new Promise((resolve) => {
        releaseInfo = () =>
          resolve(
            respond(config, {
              code: 200,
              msg: '成功',
              data: { csrfToken: 'late-old-session-token' },
              timestamp: 1,
            }),
          );
      });
    }
    writes += 1;
    return Promise.resolve(
      respond(config, {
        code: 403,
        errorCode: 'AUTH013',
        msg: '安全令牌已失效',
        data: null,
        timestamp: 1,
      }),
    );
  };
  const operation = api.request
    .post('/api/v1/admin/system/config', { value: 'old-edit' })
    .catch((error) => error);
  await started;
  api.clearCsrfToken();
  releaseInfo();
  const failure = await operation;
  assert.equal(failure.errorCode, 'AUTH013');
  assert.equal(api.readCsrfToken(), '');
  assert.equal(writes, 1, 'a previous session write must not replay after logout');
});

test('a previous session AUTH013 response cannot replay its write using a newly logged-in session', async () => {
  const api = loadClient();
  setDocument('');
  api.setCsrfToken('session-a');
  let releaseWrite;
  let signalWrite;
  let writes = 0;
  let infos = 0;
  const started = new Promise((resolve) => {
    signalWrite = resolve;
  });
  api.default.defaults.adapter = (config) => {
    if (config.url === '/api/v1/all/auth/info') {
      infos += 1;
      return Promise.resolve(
        respond(config, {
          code: 200,
          msg: '成功',
          data: { csrfToken: 'refreshed-session-b' },
          timestamp: 1,
        }),
      );
    }
    writes += 1;
    if (writes === 1) {
      signalWrite();
      return new Promise((resolve) => {
        releaseWrite = () =>
          resolve(
            respond(config, {
              code: 403,
              errorCode: 'AUTH013',
              msg: '旧请求的安全令牌已失效',
              data: null,
              timestamp: 1,
            }),
          );
      });
    }
    return Promise.resolve(respond(config, { code: 200, msg: '成功', data: null, timestamp: 1 }));
  };
  const operation = api.request
    .post('/api/v1/admin/system/config', { value: 'session-a-edit' })
    .catch((error) => error);
  await started;
  api.setCsrfToken('session-b');
  releaseWrite();
  const failure = await operation;
  assert.equal(failure.errorCode, 'AUTH013');
  assert.equal(writes, 1);
  assert.equal(infos, 0, 'old session failures must not refresh the new session');
  assert.equal(api.readCsrfToken(), 'session-b');
});

test('concurrent AUTH013 responses share one same-session token refresh', async () => {
  const api = loadClient();
  setDocument('');
  api.setCsrfToken('session-a');
  const releases = [];
  let writes = 0;
  let infos = 0;
  let signalInfo;
  const infoStarted = new Promise((resolve) => {
    signalInfo = resolve;
  });
  api.default.defaults.adapter = (config) => {
    if (config.url === '/api/v1/all/auth/info') {
      infos += 1;
      signalInfo();
      return new Promise((resolve) => {
        releases.push(() =>
          resolve(
            respond(config, {
              code: 200,
              msg: '成功',
              data: { csrfToken: 'session-a-refreshed' },
              timestamp: 1,
            }),
          ),
        );
      });
    }
    writes += 1;
    return Promise.resolve(
      respond(
        config,
        writes <= 2
          ? { code: 403, errorCode: 'AUTH013', msg: '令牌过期', data: null, timestamp: 1 }
          : { code: 200, msg: '成功', data: null, timestamp: 1 },
      ),
    );
  };
  const operations = Promise.allSettled([
    api.request.post('/api/v1/admin/system/config', { key: 'first' }),
    api.request.post('/api/v1/admin/system/config', { key: 'second' }),
  ]);
  await infoStarted;
  await new Promise(setImmediate);
  for (const release of releases) release();
  const outcomes = await operations;
  assert.equal(infos, 1);
  assert.equal(writes, 4);
  assert.ok(outcomes.every((outcome) => outcome.status === 'fulfilled'));
});

test('a session change while the public retry interceptor waits prevents actual cross-account dispatch', async () => {
  const api = loadClient();
  setDocument('');
  api.setCsrfToken('session-a');
  let gateOpened;
  let releaseGate;
  let interceptedWrites = 0;
  let adapterWrites = 0;
  const started = new Promise((resolve) => {
    gateOpened = resolve;
  });
  api.default.interceptors.request.use(async (config) => {
    if (config.url !== '/api/v1/all/auth/info' && ++interceptedWrites === 2) {
      gateOpened();
      await new Promise((resolve) => {
        releaseGate = resolve;
      });
    }
    return config;
  });
  api.default.defaults.adapter = async (config) => {
    if (config.url === '/api/v1/all/auth/info') {
      return respond(config, {
        code: 200,
        msg: '成功',
        data: { csrfToken: 'refreshed-session-a' },
        timestamp: 1,
      });
    }
    adapterWrites += 1;
    return respond(
      config,
      adapterWrites === 1
        ? { code: 403, errorCode: 'AUTH013', msg: '原请求安全令牌过期', data: null, timestamp: 1 }
        : { code: 200, msg: '成功', data: null, timestamp: 1 },
    );
  };
  const operation = api.request
    .post('/api/v1/admin/system/config', { value: 'session-a-edit' })
    .catch((error) => error);
  await started;
  api.setCsrfToken('session-b');
  releaseGate();
  const failure = await operation;
  assert.equal(adapterWrites, 1);
  assert.equal(failure.errorCode, 'AUTH013');
  assert.equal(failure.message, '原请求安全令牌过期');
  assert.equal(api.readCsrfToken(), 'session-b');
});

for (const transport of ['business', 'gateway']) {
  test(`${transport} 401 does not mistake a dashboard-like sibling for the protected route`, async () => {
    const api = loadClient();
    const location = { pathname: '/dashboard-preview', href: '' };
    Object.defineProperty(globalThis, 'window', { configurable: true, value: { location } });
    api.default.defaults.adapter = async (config) => {
      const body = { code: 401, errorCode: 'AUTH001', msg: '未登录', data: null, timestamp: 1 };
      if (transport === 'gateway') throw rejectHttp(config, body, 401);
      return respond(config, body);
    };
    await assert.rejects(api.request.get('/api/v1/admin/system/config'), { message: '未登录' });
    assert.equal(location.href, '');
  });

  test(`a late ${transport} 401 from session A cannot redirect the newly logged-in session B`, async () => {
    const api = loadClient();
    setDocument('');
    api.setCsrfToken('session-a');
    const location = { pathname: '/dashboard/system/config', href: '' };
    Object.defineProperty(globalThis, 'window', { configurable: true, value: { location } });
    let release;
    let signal;
    const started = new Promise((resolve) => {
      signal = resolve;
    });
    api.default.defaults.adapter = (config) =>
      new Promise((resolve, reject) => {
        signal();
        release = () => {
          const body = {
            code: 401,
            errorCode: 'AUTH001',
            msg: '旧会话失效',
            data: null,
            timestamp: 1,
          };
          if (transport === 'gateway') reject(rejectHttp(config, body, 401));
          else resolve(respond(config, body));
        };
      });
    const operation = api.request.get('/api/v1/admin/system/config').catch((error) => error);
    await started;
    api.setCsrfToken('session-b');
    release();
    assert.equal((await operation).message, '旧会话失效');
    assert.equal(location.href, '');
    assert.equal(api.readCsrfToken(), 'session-b');
  });
}

test('a config-less interceptor rejection cannot end a different active request', async () => {
  const load = createSourceLoader();
  const api = load('api/client.ts');
  const loading = load('lib/requestLoading.ts');
  let count = 0;
  const unsubscribe = loading.subscribeRequestCount((active) => {
    count = active;
  });
  let release;
  let signal;
  const started = new Promise((resolve) => {
    signal = resolve;
  });
  api.default.defaults.adapter = (config) =>
    new Promise((resolve) => {
      signal();
      release = () =>
        resolve(respond(config, { code: 200, msg: '成功', data: null, timestamp: 1 }));
    });
  const active = api.request.get('/api/v1/admin/system/config');
  await started;
  const rejector = api.default.interceptors.request.use(() => {
    throw new Error('取消第二次请求');
  });
  await assert.rejects(api.request.get('/blocked-before-dispatch'), { message: '取消第二次请求' });
  api.default.interceptors.request.eject(rejector);
  const remaining = count;
  release();
  await active;
  unsubscribe();
  assert.equal(remaining, 1);
  assert.equal(count, 0);
});

test('malformed successful transport responses reject with a useful contract error', async () => {
  const api = loadClient();
  for (const data of [
    null,
    undefined,
    'not JSON',
    { code: '200', msg: 'wrong code type' },
    { code: 200, msg: {} },
  ]) {
    api.default.defaults.adapter = async (config) => respond(config, data);
    await assert.rejects(api.request.get('/api/v1/admin/system/config'), {
      message: '服务器响应格式异常，请稍后重试',
    });
  }
});

test('a malformed CSRF cookie cannot block requests and falls back to the response cache', () => {
  const api = loadClient();
  api.setCsrfToken('test-only-cache');
  setDocument('unrelated=1; astral_csrf=%E0%A4%A');
  assert.equal(api.readCsrfToken(), 'test-only-cache');
});

test('an explicit CSV download uses the shared axios origin, credentials, headers and loading ownership', async () => {
  const load = createSourceLoader();
  const api = load('api/client.ts');
  const loading = load('lib/requestLoading.ts');
  // The compiled-module seam must supply Next's build-time version and a browser UA.
  process.env.NEXT_PUBLIC_CLIENT_VERSION = '1.0.0-audit';
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: {
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36',
    },
  });
  setDocument('');
  api.setCsrfToken('audit-csrf-fixture');
  api.default.defaults.baseURL = 'https://api.audit.invalid';
  const counts = [];
  const unsubscribe = loading.subscribeRequestCount((count) => counts.push(count));
  let dispatched;
  api.default.defaults.adapter = async (config) => {
    dispatched = config;
    return {
      ...respond(config, new Blob(['id,username\n41,audit-user\n'], { type: 'text/csv' })),
      headers: { 'content-type': 'text/csv; charset=UTF-8' },
    };
  };
  try {
    const file = await api.request.getBlob('/api/v1/admin/system/user/export', {
      mediaTypes: ['text/csv'],
      params: { userType: 'ADMIN' },
    });
    assert.ok(file instanceof Blob);
    assert.equal(await file.text(), 'id,username\n41,audit-user\n');
    assert.equal(dispatched.baseURL, 'https://api.audit.invalid');
    assert.equal(dispatched.withCredentials, true);
    assert.equal(dispatched.responseType, 'blob');
    assert.equal(dispatched.params.userType, 'ADMIN');
    assert.equal(dispatched.headers.get('X-App-Ut'), 'web');
    for (const name of ['X-App-Version', 'X-Device', 'X-OS']) {
      assert.equal(typeof dispatched.headers.get(name), 'string', name);
    }
    assert.equal(dispatched.headers.get('X-CSRF-Token'), 'audit-csrf-fixture');
    assert.deepEqual(counts, [0, 1, 0]);
  } finally {
    unsubscribe();
  }
});

test('a JSON business failure received as a Blob cannot be downloaded and preserves auth feedback', async () => {
  const api = loadClient();
  const location = { pathname: '/dashboard/system/user', href: '' };
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { location } });
  api.default.defaults.adapter = async (config) => ({
    ...respond(
      config,
      new Blob(
        [
          JSON.stringify({
            code: 401,
            errorCode: 'AUTH001',
            msg: '登录已失效，不能导出',
            data: null,
            timestamp: 1,
          }),
        ],
        { type: 'application/json' },
      ),
    ),
    headers: { 'content-type': 'application/json; charset=UTF-8' },
  });
  await assert.rejects(
    api.request.getBlob('/api/v1/admin/system/user/export', {
      mediaTypes: ['text/csv'],
    }),
    { message: '登录已失效，不能导出', errorCode: 'AUTH001' },
  );
  assert.equal(location.href, '/login');
});

test('a non-2xx JSON Blob preserves the gateway message and code instead of a generic download error', async () => {
  const api = loadClient();
  api.default.defaults.adapter = async (config) => {
    const response = {
      ...respond(
        config,
        new Blob(
          [
            JSON.stringify({
              code: 503,
              errorCode: 'COMMON001',
              msg: '导出服务暂不可用',
              data: null,
              timestamp: 1,
            }),
          ],
          { type: 'application/json' },
        ),
        503,
      ),
      headers: { 'content-type': 'application/json; charset=UTF-8' },
    };
    throw new axios.AxiosError('transport rejected', 'ERR_BAD_RESPONSE', config, {}, response);
  };
  await assert.rejects(
    api.request.getBlob('/api/v1/admin/system/user/export', {
      mediaTypes: ['text/csv'],
    }),
    { message: '导出服务暂不可用', errorCode: 'COMMON001' },
  );
});

test('an empty declared file type is rejected before dispatch rather than accepting an untyped Blob', async () => {
  const api = loadClient();
  let dispatches = 0;
  api.default.defaults.adapter = async (config) => {
    dispatches += 1;
    return respond(config, new Blob(['not a declared file type']));
  };
  await assert.rejects(
    api.request.getBlob('/api/v1/admin/system/user/export', {
      mediaTypes: [''],
    }),
    { message: '请指定明确的下载文件类型' },
  );
  assert.equal(dispatches, 0);
});

test('an oversized JSON download failure is rejected without decoding its Blob contents', async () => {
  const api = loadClient();
  const blob = new Blob(
    [
      JSON.stringify({
        code: 300,
        msg: '不能把超大错误载荷读入内存',
        data: { padding: 'x'.repeat(65536) },
        timestamp: 1,
      }),
    ],
    { type: 'application/json' },
  );
  const readText = blob.text.bind(blob);
  let textReads = 0;
  blob.text = async () => {
    textReads += 1;
    return readText();
  };
  api.default.defaults.adapter = async (config) => ({
    ...respond(config, blob),
    headers: { 'content-type': 'application/json; charset=UTF-8' },
  });
  await assert.rejects(
    api.request.getBlob('/api/v1/admin/system/user/export', {
      mediaTypes: ['text/csv'],
    }),
    { message: '服务器下载响应格式异常，请稍后重试' },
  );
  assert.equal(textReads, 0);
});
