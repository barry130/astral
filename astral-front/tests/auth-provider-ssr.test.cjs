const assert = require('node:assert/strict');
const test = require('node:test');
const React = require('react');
const { renderToString } = require('react-dom/server');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

// This runs the actual public provider and hook with the installed React renderer.
// Only the authentication HTTP/CSRF boundary is replaced; SSR is not browser evidence.
test('AuthProvider SSR has a neutral stable snapshot and performs no authentication side effects', async () => {
  let httpCalls = 0;
  let csrfWrites = 0;
  const unexpectedHttp = () => {
    httpCalls += 1;
    throw new Error('SSR must not dispatch authentication HTTP');
  };
  const load = createSourceLoader({
    '@/api/client': {
      request: { get: unexpectedHttp, post: unexpectedHttp },
      setCsrfToken: () => {
        csrfWrites += 1;
      },
      clearCsrfToken: () => {
        csrfWrites += 1;
      },
    },
  });
  const { AuthProvider, useAuth } = load('context/AuthContext.tsx');
  const { AuthSession } = load('context/auth-session-store.ts');
  const previous = new AuthSession({
    getUserInfo: async () => ({
      code: 200,
      msg: '成功',
      timestamp: 1,
      data: {
        id: 41,
        username: 'audit-user-41',
        nickname: '另一会话',
        roles: [],
        permissions: [],
        mustChangePassword: 1,
        userType: 'ADMIN',
        csrfToken: 'audit-csrf-41',
      },
    }),
    login: unexpectedHttp,
    logout: unexpectedHttp,
    setCsrfToken: () => undefined,
    clearCsrfToken: () => undefined,
  });
  const connection = previous.connect();
  await connection.ready;
  assert.equal(previous.getSnapshot().user.id, 41);
  assert.equal(previous.getServerSnapshot().user, null);

  function Probe() {
    const session = useAuth();
    assert.equal(session.user, null);
    assert.equal(session.userInfo, null);
    assert.equal(session.isLogin, false);
    assert.equal(session.loading, true);
    return React.createElement('output', { 'data-session': 'anonymous-loading' }, '恢复会话中');
  }
  const first = renderToString(React.createElement(AuthProvider, null, React.createElement(Probe)));
  const second = renderToString(
    React.createElement(AuthProvider, null, React.createElement(Probe)),
  );
  assert.equal(first, second);
  assert.ok(first.includes('anonymous-loading'));
  assert.ok(!first.includes('audit-user-41'));
  assert.equal(httpCalls, 0);
  assert.equal(csrfWrites, 0);
  connection.disconnect();
});
