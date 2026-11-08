const assert = require('node:assert/strict');
const test = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((ok, fail) => {
    resolve = ok;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function info(id) {
  return {
    id,
    username: `audit-user-${id}`,
    nickname: '审阅夹具',
    userType: 'ADMIN',
    roles: ['AUDIT_READER'],
    permissions: ['admin:profile:view'],
    mustChangePassword: 1,
    csrfToken: `audit-csrf-${id}`,
  };
}

const result = (data) => ({ code: 200, msg: '成功', data, timestamp: 1 });
const tick = () => new Promise((resolve) => setImmediate(resolve));

// Only the HTTP boundary is controlled; AuthProvider imports this exact store.
function fixture(changes = {}) {
  const exports = createSourceLoader()('context/auth-session-store.ts');
  const cachedTokens = [];
  const backend = {
    getUserInfo: async () => result(info(41)),
    login: async () => result({ ...info(42), token: 'audit-login-result-not-a-real-credential' }),
    logout: async () => result(undefined),
    setCsrfToken: (token) => cachedTokens.push(token),
    clearCsrfToken: () => undefined,
    ...changes,
  };
  return { store: new exports.AuthSession(backend), backend, cachedTokens, exports };
}

test('an older restore cannot replace a newer login identity or CSRF snapshot', async () => {
  const oldInfo = deferred();
  const { store, cachedTokens } = fixture({ getUserInfo: () => oldInfo.promise });
  const connection = store.connect();
  await tick();
  const fresh = await store.login({ username: 'audit-user-42', password: 'not-a-real-password' });
  oldInfo.resolve(result(info(41)));
  await connection.ready;

  assert.equal(store.getSnapshot().user.id, 42);
  assert.equal(store.getSnapshot().loading, false);
  assert.deepEqual(cachedTokens, ['audit-csrf-42']);
  assert.equal(Object.hasOwn(store.getSnapshot().user, 'token'), false);
  assert.equal(fresh.token, 'audit-login-result-not-a-real-credential');
});

test('login cannot dispatch before an earlier cookie-mutating logout finishes', async () => {
  const closing = deferred();
  const events = [];
  const { store } = fixture({
    logout: async () => {
      events.push('logout:start');
      await closing.promise;
      events.push('logout:finish');
      return result(undefined);
    },
    login: async () => {
      events.push('login:start');
      return result({ ...info(42), token: 'audit-login-result-not-a-real-credential' });
    },
  });
  await store.connect().ready;
  const logout = store.logout();
  await tick();
  const login = store.login({ username: 'audit-user-42', password: 'not-a-real-password' });
  await tick();
  closing.resolve();
  await Promise.all([logout, login]);

  assert.deepEqual(events, ['logout:start', 'logout:finish', 'login:start']);
  assert.equal(store.getSnapshot().user.id, 42);
});

test('a remounted provider waits for the old logout without invalidating its active CSRF generation', async () => {
  const closing = deferred();
  const events = [];
  let infoReads = 0;
  let csrfClears = 0;
  const { store, backend, cachedTokens, exports } = fixture({
    getUserInfo: async () => {
      infoReads += 1;
      return result(info(41));
    },
    clearCsrfToken: () => {
      csrfClears += 1;
    },
    logout: async () => {
      events.push('logout:start');
      await closing.promise;
      events.push('logout:finish');
      return result(undefined);
    },
    login: async () => {
      events.push('login:start');
      return result({ ...info(42), token: 'audit-login-result-not-a-real-credential' });
    },
  });
  const oldConnection = store.connect();
  await oldConnection.ready;
  const logout = store.logout();
  await tick();
  const clearsDuringLogout = csrfClears;
  oldConnection.disconnect();
  // Both stores use the same actual module exports, as provider remounts do in one document.
  const nextStore = new exports.AuthSession(backend);
  const nextConnection = nextStore.connect();
  const login = nextStore.login({ username: 'audit-user-42', password: 'not-a-real-password' });
  await tick();
  assert.equal(csrfClears, clearsDuringLogout);
  assert.equal(infoReads, 1, 'restore must not observe the intermediate cookie actor');
  assert.deepEqual(events, ['logout:start']);
  closing.resolve();
  await Promise.all([logout, login, nextConnection.ready]);
  assert.deepEqual(events, ['logout:start', 'logout:finish', 'login:start']);
  assert.equal(store.getSnapshot().user, null);
  assert.equal(nextStore.getSnapshot().user.id, 42);
  assert.equal(infoReads, 1);
  assert.deepEqual(cachedTokens, ['audit-csrf-41', 'audit-csrf-42']);
});
