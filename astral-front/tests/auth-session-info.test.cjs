const assert = require('node:assert/strict');
const test = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

// Actual public API export; this projection is also used by AuthProvider.
test('the user snapshot omits reusable credentials without changing the fresh login result', () => {
  const { sessionInfoFromResponse } = createSourceLoader()('api/auth.ts');
  const fresh = {
    id: 41,
    username: 'audit-user',
    nickname: '审阅夹具',
    userType: 'APP',
    roles: ['APP_USER'],
    permissions: ['user:storage:file:view'],
    mustChangePassword: 1,
    csrfToken: 'audit-csrf-fixture',
    token: 'audit-only-not-a-real-credential',
    unexpectedPrivateField: 'must not enter the user context',
  };

  const snapshot = sessionInfoFromResponse(fresh);

  assert.deepEqual(snapshot, {
    id: 41,
    username: 'audit-user',
    nickname: '审阅夹具',
    userType: 'APP',
    roles: ['APP_USER'],
    permissions: ['user:storage:file:view'],
    mustChangePassword: 1,
    csrfToken: 'audit-csrf-fixture',
  });
  assert.equal(fresh.token, 'audit-only-not-a-real-credential');
});

test('a string permission payload is rejected instead of becoming a logged-in snapshot', () => {
  const { sessionInfoFromResponse } = createSourceLoader()('api/auth.ts');
  const malformed = {
    id: 41,
    username: 'audit-user',
    nickname: '审阅夹具',
    roles: [],
    permissions: '*:*:*',
  };

  assert.throws(() => sessionInfoFromResponse(malformed), /登录信息格式异常/);
});
