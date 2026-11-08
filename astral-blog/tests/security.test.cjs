'use strict';
const { test, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const { generateKeyPairSync } = require('node:crypto');
const { runWithCookies, requireSource, json, forbidNetwork } = require('./module-loader.cjs');
const { resultMessage } = requireSource('lib/result.ts');
const { isSafeUrl } = requireSource('lib/url.ts');
const { resolveSiteUrl } = requireSource('lib/site-url.ts');
const { renderMarkdownWithToc, renderMarkdown } = requireSource('lib/markdown.ts');
const auth = requireSource('lib/auth-server.ts');
const cms = requireSource('lib/cms.ts');
const login = requireSource('app/api/auth/login/route.ts');
const logout = requireSource('app/api/auth/logout/route.ts');
const comment = requireSource('app/api/comment/route.ts');
const adminPost = requireSource('app/api/admin/post/[id]/route.ts');
const adminMeta = requireSource('app/api/admin/meta/route.ts');
const exportRoute = requireSource('app/api/admin/export/[...path]/route.ts');
const user = {
  id: 7,
  username: 'editor',
  nickname: null,
  userType: 'ADMIN',
  roles: ['EDITOR'],
  permissions: ['admin:cms:view', 'admin:cms:content:edit'],
  mustChangePassword: 0,
};
const request = (url, body, origin = 'https://blog.example') =>
  new Request(url, {
    method: 'POST',
    headers: { origin, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
const { publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const publicKeyBase64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
afterEach(forbidNetwork);
forbidNetwork();

test('malformed Result messages never throw and msg has precedence', () => {
  assert.equal(resultMessage({ msg: 7, message: ['bad'] }, 'fallback'), 'fallback');
  assert.equal(resultMessage({ msg: '  upstream  ', message: 'old' }, 'fallback'), 'upstream');
  assert.equal(resultMessage(null, 'fallback'), 'fallback');
});
test('site URL rejects credentials and strips paths, query and fragment', () => {
  assert.equal(resolveSiteUrl('https://blog.example/base?q=1#x').href, 'https://blog.example/');
  assert.equal(resolveSiteUrl('https://user:secret@blog.example').href, 'http://localhost:3001/');
  assert.equal(resolveSiteUrl('javascript:alert(1)').href, 'http://localhost:3001/');
});
test('URLs reject executable protocols, controls and invalid schemes but preserve safe links', () => {
  for (const bad of [
    'javascript:alert(1)',
    'java\nscript:alert(1)',
    'data:text/html,x',
    'vbscript:x',
    'https://',
    '\\javascript:alert(1)',
    'x\u0000:y',
  ]) {
    assert.equal(isSafeUrl(bad), false, bad);
  }
  for (const good of [
    'https://example.test/a',
    '/post/a',
    '../wiki',
    '#section',
    'mailto:a@example.test',
    'tel:123',
  ])
    assert.equal(isSafeUrl(good), true, good);
});
test('actual Markdown renderer escapes raw executable HTML and unsafe links/images', () => {
  const html = renderMarkdown(
    '<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[x](javascript:alert(1))\n\n![x](data:image/svg+xml,x)',
  );
  assert.doesNotMatch(html, /<script|<img[^>]*onerror|href="javascript:|src="data:/i);
  assert.match(html, /&lt;script/);
  assert.match(
    renderMarkdown('<details><summary>safe</summary>text</details>'),
    /<details><summary>safe<\/summary>/,
  );
});
test('heading IDs are unique even when duplicate suffixes collide with authored titles', () => {
  const { html, toc } = renderMarkdownWithToc('## A\n## A\n## A-2\n## A');
  const ids = Array.from(html.matchAll(/id="([^"]+)"/g), (match) => match[1]);
  assert.equal(new Set(ids).size, ids.length);
  assert.deepEqual(
    toc.map((item) => item.id),
    ids,
  );
});
test('backend requests remain isolated, allowlisted, uncached and timeout-bounded', async () => {
  const calls = [];
  global.fetch = async (url, init) => {
    calls.push({ url, init });
    return json({ code: 200, data: user });
  };
  await Promise.all([
    runWithCookies({ satoken: 'alpha', astral_csrf: 'csrf-a', unrelated: 'private' }, () =>
      auth.backendFetch('/api/v1/all/auth/info'),
    ),
    runWithCookies({ satoken: 'beta', astral_csrf: 'csrf-b' }, () =>
      auth.backendFetch('/api/v1/all/auth/info'),
    ),
  ]);
  assert.deepEqual(calls.map((call) => new Headers(call.init.headers).get('cookie')).sort(), [
    'satoken=alpha; astral_csrf=csrf-a',
    'satoken=beta; astral_csrf=csrf-b',
  ]);
  for (const { init } of calls) {
    assert.equal(init.cache, 'no-store');
    assert.ok(init.signal instanceof AbortSignal);
    for (const name of ['X-App-Ut', 'X-App-Version', 'X-Device', 'X-OS'])
      assert.ok(new Headers(init.headers).has(name));
  }
});
test('session projection excludes reusable credentials and validates identity and permissions', async () => {
  global.fetch = async () =>
    json({ code: 200, data: { ...user, token: 'secret', csrfToken: 'csrf' } });
  const projected = await runWithCookies({ satoken: 'synthetic' }, () => auth.getSessionUser());
  assert.equal(projected.username, 'editor');
  assert.equal(projected.token, undefined);
  assert.equal(projected.csrfToken, undefined);
  global.fetch = async () =>
    json({ code: 200, data: { ...user, id: 'not-an-id', permissions: [77] } });
  assert.equal(await runWithCookies({ satoken: 'synthetic' }, () => auth.getSessionUser()), null);
});
test('APP identity and forced-password sessions never gain blog content editing', () => {
  assert.equal(auth.canEditContent({ ...user, userType: 'APP' }), false);
  assert.equal(auth.canEditContent({ ...user, mustChangePassword: true }), false);
  assert.equal(auth.canEditContent({ ...user, permissions: ['admin:cms:content:edit'] }), false);
  assert.equal(auth.canEditContent({ ...user, permissions: ['admin:cms:*'] }), true);
});
test('cross-origin login is rejected before contacting the backend', async () => {
  let calls = 0;
  global.fetch = async () => {
    calls += 1;
    return json({ code: 500 });
  };
  const response = await login.POST(
    request(
      'https://blog.example/api/auth/login',
      { username: 'x', password: 'x' },
      'https://evil.example',
    ),
  );
  assert.equal((await response.json()).code, 403);
  assert.equal(calls, 0);
});
test('login validates public-key business errors without sending a password', async () => {
  let calls = 0;
  global.fetch = async () => {
    calls += 1;
    return json({ code: 300, msg: 'key unavailable', data: { publicKey: publicKeyBase64 } });
  };
  const response = await login.POST(
    request('https://blog.example/api/auth/login', { username: 'editor', password: 'secret' }),
  );
  assert.notEqual((await response.json()).code, 200);
  assert.equal(calls, 1);
});
test('login returns only safe identity and host-scoped allowlisted HttpOnly credentials', async () => {
  global.fetch = async (url) => {
    if (url.endsWith('/public-key'))
      return json({ code: 200, data: { publicKey: publicKeyBase64 } });
    const response = json({ code: 200, data: { ...user, token: 'secret', csrfToken: 'csrf' } });
    response.headers.append(
      'set-cookie',
      'satoken=secret; Domain=backend.example; Path=/api; Max-Age=60; SameSite=None',
    );
    response.headers.append(
      'set-cookie',
      'astral_csrf=csrf; Domain=backend.example; Path=/api; Max-Age=60',
    );
    response.headers.append('set-cookie', 'unexpected=leak; Path=/');
    return response;
  };
  const response = await login.POST(
    request('https://blog.example/api/auth/login', { username: 'editor', password: 'secret' }),
  );
  const body = await response.json();
  assert.equal(body.code, 200);
  assert.doesNotMatch(JSON.stringify(body), /"token"|"csrfToken"|secret/);
  const values = response.headers.getSetCookie();
  assert.equal(values.length, 2);
  for (const cookie of values) {
    assert.doesNotMatch(cookie, /Domain=|unexpected=/i);
    assert.match(cookie, /Path=\//i);
    assert.match(cookie, /Secure/i);
    assert.match(cookie, /SameSite=Strict/i);
    assert.match(cookie, /Max-Age=60/i);
  }
  assert.match(
    values.find((value) => value.startsWith('satoken=')),
    /HttpOnly/i,
  );
  assert.match(response.headers.get('cache-control'), /private.*no-store/);
});
test('logout always clears both local cookies even if upstream sends unrelated cookies', async () => {
  global.fetch = async () =>
    json({ code: 200, data: null }, 200, { 'set-cookie': 'unrelated=x; Path=/' });
  const response = await runWithCookies(
    { satoken: 'synthetic', astral_csrf: 'synthetic-csrf' },
    () => logout.POST(request('https://blog.example/api/auth/logout', {})),
  );
  const values = response.headers.getSetCookie();
  assert.ok(values.some((value) => value.startsWith('satoken=') && /Max-Age=0/i.test(value)));
  assert.ok(values.some((value) => value.startsWith('astral_csrf=') && /Max-Age=0/i.test(value)));
  assert.ok(!values.some((value) => value.startsWith('unrelated=')));
});
test('invalid logout JSON is not invented upstream success', async () => {
  global.fetch = async () => new Response('not json', { status: 200 });
  const response = await logout.POST(request('https://blog.example/api/auth/logout', {}));
  assert.notEqual((await response.json()).code, 200);
});
test('invalid/nonpositive comment targets and backend DTO bounds do not reach upstream', async () => {
  let calls = 0;
  global.fetch = async () => {
    calls += 1;
    return json({ code: 200 });
  };
  for (const overrides of [
    { targetId: 0 },
    { targetId: -1 },
    { targetId: 2 ** 53 },
    { nickname: 'a'.repeat(65) },
    { email: 'bad-email' },
    { email: 'a'.repeat(129) },
  ]) {
    const response = await comment.POST(
      request('https://blog.example/api/comment', {
        targetType: 'post',
        targetId: 2,
        nickname: 'x',
        content: 'hello',
        ...overrides,
      }),
    );
    assert.notEqual((await response.json()).code, 200);
  }
  assert.equal(calls, 0);
});
test('cross-origin comment and admin write are rejected before upstream', async () => {
  let calls = 0;
  global.fetch = async () => {
    calls += 1;
    return json({ code: 200, data: user });
  };
  const commentResponse = await comment.POST(
    request(
      'https://blog.example/api/comment',
      { targetType: 'post', targetId: 2, nickname: 'x', content: 'hello' },
      'https://evil.example',
    ),
  );
  assert.equal((await commentResponse.json()).code, 403);
  const put = new Request('https://blog.example/api/admin/post/7', {
    method: 'PUT',
    headers: { origin: 'https://evil.example', 'content-type': 'application/json' },
    body: '{}',
  });
  const adminResponse = await runWithCookies({ satoken: 'synthetic' }, () =>
    adminPost.PUT(put, { params: Promise.resolve({ id: '7' }) }),
  );
  assert.equal((await adminResponse.json()).code, 403);
  assert.equal(calls, 0);
});
test('admin metadata failures cannot become successful empty dictionaries', async () => {
  global.fetch = async (url) =>
    url.endsWith('/auth/info')
      ? json({ code: 200, data: user })
      : json({ code: 300, msg: 'denied', data: null });
  const response = await runWithCookies({ satoken: 'synthetic' }, () => adminMeta.GET());
  assert.notEqual((await response.json()).code, 200);
});
test('admin metadata uses the existing cms_content_status dictionary contract', async () => {
  const paths = [];
  global.fetch = async (url) => {
    paths.push(url);
    if (url.endsWith('/auth/info')) return json({ code: 200, data: user });
    if (url.includes('/dict/data/byCode'))
      return json({
        code: 200,
        data: [{ dictValue: 'published', dictLabel: 'Backend label', dictSort: 2, status: 1 }],
      });
    return json({ code: 200, data: [] });
  };
  const response = await runWithCookies({ satoken: 'synthetic' }, () => adminMeta.GET());
  const body = await response.json();
  assert.equal(body.code, 200);
  assert.deepEqual(body.data.statuses, [{ value: 'published', label: 'Backend label' }]);
  assert.ok(paths.some((path) => path.includes('/dict/data/byCode?code=cms_content_status')));
});
test('malformed successful CMS payload is a recoverable failure, not a render crash', async () => {
  global.fetch = async () => json({ code: 200, data: { records: {}, pages: 'broken', total: 7 } });
  assert.equal((await cms.getPostPage()).ok, false);
  global.fetch = async () => json({ code: 300, msg: 123, data: null });
  assert.equal((await cms.getPostPage()).ok, false);
});
test('CMS distinguishes actual missing resources from transient/business errors', async () => {
  global.fetch = async () => json({ code: 404, msg: 'missing', data: null });
  assert.equal((await cms.getPost('missing')).notFound, true);
  global.fetch = async () => json({ code: 500, msg: 'temporary', data: null });
  assert.notEqual((await cms.getPost('temporary')).notFound, true);
});
test('anonymous sitemap data requests never forward the caller session cookie', async () => {
  const calls = [];
  global.fetch = async (url, init) => {
    calls.push(init);
    return json({ code: 200, data: [] });
  };
  await runWithCookies({ satoken: 'synthetic-private' }, () =>
    cms.getWikiTree({ anonymous: true }),
  );
  assert.equal(new Headers(calls[0].headers).get('cookie'), null);
});
test('export whitelist rejects traversal before backend access and business errors remain JSON', async () => {
  let calls = 0;
  global.fetch = async (url) => {
    calls += 1;
    return url.endsWith('/auth/info')
      ? json({ code: 200, data: user })
      : json({ code: 300, msg: 'export failed', data: null });
  };
  const invalid = await runWithCookies({ satoken: 'synthetic' }, () =>
    exportRoute.GET(new Request('https://blog.example/api/admin/export/post/../x'), {
      params: Promise.resolve({ path: ['post', '..', 'x'] }),
    }),
  );
  assert.notEqual((await invalid.json()).code, 200);
  const response = await runWithCookies({ satoken: 'synthetic' }, () =>
    exportRoute.GET(new Request('https://blog.example/api/admin/export/post/7'), {
      params: Promise.resolve({ path: ['post', '7'] }),
    }),
  );
  assert.match(response.headers.get('content-type'), /json/);
  assert.equal((await response.json()).code, 300);
  assert.equal(response.headers.get('content-disposition'), null);
});
