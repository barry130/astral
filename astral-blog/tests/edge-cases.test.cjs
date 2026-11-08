'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { requireSource, runWithCookies, json, forbidNetwork } = require('./module-loader.cjs');
const nativeFetch = global.fetch;
const bff = requireSource('lib/bff.ts');
const { parseResult } = requireSource('lib/result.ts');
const { parsePageNumber } = requireSource('lib/page-input.ts');
const { formatMarkdownLink } = requireSource('lib/markdown-insert.ts');
const { renderMarkdown } = requireSource('lib/markdown.ts');
const { parsePageData, parsePostSummary } = requireSource('lib/cms-validation.ts');
const editor = requireSource('lib/editor.ts');
const cms = requireSource('lib/cms.ts');
const login = requireSource('app/api/auth/login/route.ts');
const exportRoute = requireSource('app/api/admin/export/[...path]/route.ts');
const sitemap = requireSource('app/sitemap.ts').default;
const { getContentStatuses } = requireSource('lib/cms-status.ts');
const user = {
  id: 7,
  username: 'fixture',
  userType: 'ADMIN',
  roles: [],
  permissions: ['admin:cms:view', 'admin:cms:content:edit'],
  mustChangePassword: false,
};
const post = (id) => ({
  id,
  title: `Fixture ${id}`,
  status: 'published',
  slug: `p-${id}`,
  content: '# Fixture',
});
test.afterEach(forbidNetwork);

test('the actual BFF metadata producer emits a canonical parseable success envelope', async () => {
  global.fetch = async (url) =>
    json({
      code: 200,
      data: url.endsWith('/auth/info')
        ? user
        : url.includes('/dict/data/')
          ? [{ dictValue: 'published', dictLabel: 'Fixture release', status: 1 }]
          : [],
    });
  const response = await runWithCookies({ satoken: 'synthetic' }, () =>
    requireSource('app/api/admin/meta/route.ts').GET(),
  );
  assert.equal(parseResult(await response.json())?.code, 200);
});

test('complete canonical envelopes are required before void outcomes or session expiry can be trusted', async () => {
  const canonical = { code: 200, msg: 'ok', data: null, timestamp: 1 };
  for (const bad of [
    { code: 200, msg: 'ok', timestamp: 1 },
    { code: 200, msg: 'ok', data: null },
    ...[null, '1', NaN, Infinity, 1.5, -1].map((timestamp) => ({ ...canonical, timestamp })),
    { ...canonical, msg: [], message: 'legacy must not override bad canonical msg' },
    { ...canonical, errorCode: 7 },
  ]) {
    assert.equal(parseResult(bad), null);
    const outcome = await bff.relayResult(Response.json(bad));
    assert.notEqual((await outcome.json()).code, 200);
  }
  global.fetch = async () => Response.json({ code: 401, msg: 'expired', data: null });
  const session = await runWithCookies({ satoken: 'synthetic' }, () =>
    requireSource('app/api/auth/session/route.ts').GET(
      new Request('https://blog.example/api/auth/session'),
    ),
  );
  assert.equal(session.status, 502);
  assert.equal(session.headers.get('set-cookie'), null);
  assert.equal(parseResult(canonical)?.code, 200);
  assert.equal(parseResult({ ...canonical, msg: undefined, message: 'legacy' })?.code, 200);
});

test('a transport outage carrying business 404 is recoverable, not a missing resource', async () => {
  for (const status of [201, 503]) {
    global.fetch = async () => json({ code: 404, msg: 'missing', data: null }, status);
    const result = await cms.getPost(`transport-${status}`);
    assert.equal(result.ok, false);
    assert.equal(result.notFound, false);
  }
  global.fetch = async () => json({ code: 404, msg: 'missing', data: null });
  assert.equal((await cms.getPost('proven-business-404')).notFound, true);
});

test('code-only and ill-typed canonical envelopes cannot become successful void writes', () => {
  assert.equal(parseResult({ code: 200 }), null);
  assert.equal(parseResult({ code: 200, msg: [] }), null);
  assert.equal(parseResult({ code: Number.MAX_SAFE_INTEGER + 1, msg: 'bad' }), null);
  assert.equal(parseResult({ code: 300, msg: 'rejected', data: null, timestamp: 1 }).code, 300);
});
test('upstream transport and malformed-success failures never display an ok message', async () => {
  const transport = bff.upstreamFailure({ code: 200, msg: 'ok' }, 'Fixture failure', 503);
  assert.equal(transport.status, 503);
  assert.deepEqual(
    { code: (await transport.clone().json()).code, msg: (await transport.json()).msg },
    { code: 502, msg: 'Fixture failure' },
  );
  const business = bff.upstreamFailure(
    { code: 300, msg: 'backend rejection', data: null, timestamp: 1 },
    'fallback',
  );
  assert.equal(business.status, 200);
  assert.equal((await business.json()).msg, 'backend rejection');
});
test('HTTP-failed public-key success envelope never sends a password or reports ok', async () => {
  let calls = 0;
  global.fetch = async () => {
    calls++;
    return json({ code: 200, msg: 'ok', data: { publicKey: 'invalid' } }, 503);
  };
  const response = await login.POST(
    new Request('https://blog.example/api/auth/login', {
      method: 'POST',
      headers: { origin: 'https://blog.example' },
      body: JSON.stringify({ username: 'fixture', password: 'synthetic' }),
    }),
  );
  assert.equal(response.status, 503);
  assert.equal(calls, 1);
  assert.notEqual((await response.json()).msg, 'ok');
});
test('HTTP 201 is not invented as a completed save and bodyless transport failures become honest JSON', async () => {
  const response = await bff.relayResult(json({ code: 200, msg: 'ok', data: null }, 201));
  assert.notEqual((await response.json()).code, 200);
  for (const status of [204, 205, 304]) {
    const failure = bff.upstreamFailure(null, 'unavailable', status);
    assert.equal(failure.status, 502);
    assert.equal((await failure.json()).code, 502);
  }
});
test('a transport outage carrying code 401 must not revoke local cookies or report anonymous success', async () => {
  global.fetch = async () => json({ code: 401, msg: 'unavailable', data: null }, 503);
  const response = await runWithCookies({ satoken: 'synthetic' }, () =>
    requireSource('app/api/auth/session/route.ts').GET(
      new Request('https://blog.example/api/auth/session'),
    ),
  );
  assert.equal(response.status, 502);
  assert.equal(response.headers.get('set-cookie'), null);
});

test('cookie forwarding rejects expired credentials and normalizes host scope and secure transport', () => {
  const upstream = json({ code: 200 }, 200, {
    'set-cookie': 'satoken=expired; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/api',
  });
  const response = bff.privateJson({ code: 200, msg: 'fixture', data: null });
  assert.equal(
    bff.forwardAuthCookies(upstream, response, new Request('https://blog.example/')).size,
    0,
  );
  assert.equal(response.cookies.get('satoken'), undefined);
});
test('strict page queries reject repeated, noncanonical, partial and overflow values', () => {
  assert.equal(parsePageNumber(undefined), 1);
  assert.equal(parsePageNumber('2147483647'), 2147483647);
  for (const raw of ['0', '01', '-1', '2x', '1.5', '1e2', '2147483648', ['2'], ['1', '2']])
    assert.equal(parsePageNumber(raw), null);
});
test('canonical page queries reject trailing line terminators instead of accepting a numeric prefix', () => {
  for (const raw of ['1\n', '2\r', '3\u2028', '4\u2029'])
    assert.equal(requireSource('lib/page-input.ts').parsePageNumber(raw), null);
});

test('CMS guards classify unavailable authentication separately from an expired session', async () => {
  global.fetch = async () => json({ code: 503, msg: 'unavailable', data: null }, 503);
  const response = await runWithCookies({ satoken: 'synthetic' }, () =>
    requireSource('app/api/admin/post/[id]/route.ts').GET(
      new Request('https://blog.example/api/admin/post/7'),
      { params: Promise.resolve({ id: '7' }) },
    ),
  );
  assert.equal(response.status, 502);
  assert.equal((await response.json()).code, 502);
});

test('page DTOs reject impossible counts, oversized records and nonempty out-of-range results', () => {
  const page = { records: [post(1)], total: 1, size: 1, current: 1, pages: 1 };
  assert.ok(parsePageData(page, parsePostSummary));
  for (const patch of [
    { pages: 7 },
    { total: 0, pages: 0 },
    { current: 2 },
    { records: [post(1), post(2)] },
  ])
    assert.equal(parsePageData({ ...page, ...patch }, parsePostSummary), null);
});
test('CMS pages must match the requested page and size, not merely a valid unrelated page', async () => {
  global.fetch = async () =>
    json({ code: 200, data: { records: [], total: 0, size: 10, current: 1, pages: 0 } });
  assert.equal(
    (await cms.getPostPage({ pageNum: 2, pageSize: 10 }, { anonymous: true })).ok,
    false,
  );
  assert.equal((await cms.getPostPage({ pageNum: 1, pageSize: 10 }, { anonymous: true })).ok, true);
  assert.equal((await cms.getCommentPage('post', 7, 2, 20)).ok, false);
});
test('Markdown insertion escapes labels and delimiter characters without creating extra executable syntax', () => {
  assert.equal(formatMarkdownLink('x', 'javascript:alert(1)'), null);
  assert.equal(formatMarkdownLink('x', 'data:image/svg+xml,<svg onload=alert(1)>', true), null);
  const snippet = formatMarkdownLink(
    '[payload](javascript:alert(1))',
    'https://example.test/a_(b)',
  );
  assert.match(snippet, /%28b%29/);
  const html = renderMarkdown(snippet);
  assert.equal((html.match(/<a /g) || []).length, 1);
  assert.doesNotMatch(html, /href="javascript:|<script|onerror=/);
});
test('status dictionaries reject blank or oversized labels and preserve only enabled server labels', () => {
  assert.equal(editor.parseStatusDictionary([{ dictValue: 'x', dictLabel: '' }]), null);
  assert.equal(
    editor.parseStatusDictionary([{ dictValue: 'x', dictLabel: 'x'.repeat(257) }]),
    null,
  );
  assert.deepEqual(
    editor.parseStatusDictionary([
      { dictValue: 'published', dictLabel: 'Fixture release', status: 1 },
      { dictValue: 'draft', dictLabel: 'Disabled', status: 0 },
    ]),
    [{ value: 'published', label: 'Fixture release' }],
  );
});
test('save DTOs whitelist fields, reject impossible dates and apply the same UTF-8 body budget as the BFF', () => {
  const raw = {
    title: ' Fixture ',
    content: '# Fixture',
    status: 'published',
    roles: ['admin'],
    authorId: 99,
    token: 'synthetic',
    categoryIds: [1, 1],
  };
  const valid = editor.validateContentSave('post', raw);
  assert.equal(valid.ok, true);
  assert.equal(valid.data.authorId, undefined);
  assert.equal(valid.data.token, undefined);
  assert.deepEqual(valid.data.categoryIds, [1]);
  assert.equal(
    editor.validateContentSave('post', { ...raw, publishedAt: '2023-02-29 12:00:00' }).ok,
    false,
  );
  assert.equal(
    editor.validateContentSave('post', { ...raw, content: '字'.repeat(400000) }).ok,
    false,
  );
});
test('read-only CMS administrators can load status labels without dictionary-view or edit permission', async () => {
  const paths = [];
  global.fetch = async (url) => {
    paths.push(url);
    return json({
      code: 200,
      data: url.endsWith('/auth/info')
        ? { ...user, permissions: ['admin:cms:view'] }
        : [{ dictValue: 'private', dictLabel: 'Fixture private', status: 1 }],
    });
  };
  const statuses = await runWithCookies({ satoken: 'synthetic' }, () => getContentStatuses());
  assert.deepEqual(statuses, [{ value: 'private', label: 'Fixture private' }]);
  assert.ok(paths.some((url) => url.includes('/dict/data/byCode')));
});
test('anonymous sitemap scans beyond twenty pages without forwarding cookies or silently dropping the tail', async () => {
  const calls = [];
  global.fetch = async (url, init) => {
    calls.push({ url, headers: new Headers(init.headers) });
    if (url.includes('/page/tree')) return json({ code: 200, data: [] });
    const query = new URL(url).searchParams,
      current = Number(query.get('pageNum')),
      size = 100,
      total = 2100;
    return json({
      code: 200,
      data: {
        records: Array.from({ length: size }, (_, i) => post((current - 1) * size + i + 1)),
        current,
        size,
        total,
        pages: 21,
      },
    });
  };
  const entries = await runWithCookies({ satoken: 'must-not-forward' }, () => sitemap());
  assert.ok(entries.some((entry) => entry.url.endsWith('/post/p-2100')));
  assert.equal(entries.length, 2103);
  assert.ok(calls.some((call) => new URL(call.url).searchParams.get('pageNum') === '21'));
  assert.ok(calls.every((call) => call.headers.get('cookie') === null));
});
test('sitemap recovery failures throw rather than masquerading as a complete empty sitemap', async () => {
  global.fetch = async () => json({ code: 500, msg: 'temporary outage', data: null });
  await assert.rejects(() => sitemap(), /站点地图数据暂时无法加载/);
});
test('export JSON-success impostors and unsafe binary MIME types are rejected, not downloaded as HTML', async () => {
  for (const type of ['application/json', 'text/html']) {
    global.fetch = async (url) =>
      url.endsWith('/auth/info')
        ? json({ code: 200, data: user })
        : new Response(
            type === 'application/json'
              ? JSON.stringify({ code: 200, msg: 'ok', data: null })
              : '<script>bad</script>',
            { headers: { 'content-type': type } },
          );
    const response = await runWithCookies({ satoken: 'synthetic' }, () =>
      exportRoute.GET(new Request('https://blog.example/api/admin/export/post/7'), {
        params: Promise.resolve({ path: ['post', '7'] }),
      }),
    );
    assert.equal(response.status, 502);
    assert.equal(response.headers.get('content-disposition'), null);
  }
});
test('non-200 dictionary and logout envelopes cannot invent readable labels or successful revocation', async () => {
  global.fetch = async (url) =>
    url.endsWith('/auth/info')
      ? json({ code: 200, data: user })
      : json(
          {
            code: 200,
            msg: 'ok',
            data: [{ dictValue: 'published', dictLabel: 'Unexpected success', status: 1 }],
          },
          201,
        );
  assert.deepEqual(
    await runWithCookies({ satoken: 'synthetic' }, () =>
      requireSource('lib/cms-status.ts').getContentStatuses(),
    ),
    [],
  );
  const response = await runWithCookies({ satoken: 'synthetic' }, () =>
    requireSource('app/api/auth/logout/route.ts').POST(
      new Request('https://blog.example/api/auth/logout', {
        method: 'POST',
        headers: { origin: 'https://blog.example' },
      }),
    ),
  );
  const body = await response.json();
  assert.equal(body.data.upstreamRevoked, false);
  assert.equal(body.data.localLoggedOut, true);
  assert.notEqual(body.msg, 'ok');
});

test('malformed RSA keys and UTF-8-oversized passwords never send a password request', async () => {
  const { generateKeyPairSync } = require('node:crypto');
  const key = generateKeyPairSync('rsa', { modulusLength: 2048 })
    .publicKey.export({ type: 'spki', format: 'der' })
    .toString('base64');
  for (const [publicKey, password, expected] of [
    ['broken', 'fixture', 502],
    [key, '界'.repeat(100), 320],
  ]) {
    const calls = [];
    global.fetch = async (url) => {
      calls.push(url);
      return json({ code: 200, data: { publicKey } });
    };
    const response = await requireSource('app/api/auth/login/route.ts').POST(
      new Request('https://blog.example/api/auth/login', {
        method: 'POST',
        headers: { origin: 'https://blog.example', 'content-type': 'application/json' },
        body: JSON.stringify({ username: 'fixture', password }),
      }),
    );
    assert.equal((await response.json()).code, expected);
    assert.equal(calls.length, 1);
    assert.match(calls[0], /public-key$/);
  }
});

test('successful allowlisted binary export preserves bytes and private attachment headers', async () => {
  const bytes = new Uint8Array([0x50, 0x4b, 3, 4]);
  global.fetch = async (url) =>
    url.endsWith('/auth/info')
      ? json({ code: 200, data: user })
      : new Response(bytes, { headers: { 'content-type': 'application/zip' } });
  const response = await runWithCookies({ satoken: 'synthetic' }, () =>
    requireSource('app/api/admin/export/[...path]/route.ts').GET(
      new Request('https://blog.example/api/admin/export/zip'),
      { params: Promise.resolve({ path: ['zip'] }) },
    ),
  );
  assert.equal(response.status, 200);
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), bytes);
  assert.match(response.headers.get('content-disposition'), /^attachment;/);
  assert.match(response.headers.get('cache-control'), /private.*no-store/);
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
});

test('public session projection omits forced-password fields while server permission checks retain them', () => {
  const sessions = requireSource('lib/session.ts');
  const raw = { ...user, mustChangePassword: true, token: 'secret', csrfToken: 'secret' };
  const visible = sessions.projectSessionUser(raw);
  assert.equal(Object.hasOwn(visible, 'mustChangePassword'), false);
  assert.equal(Object.hasOwn(visible, 'roles'), false);
  assert.equal(Object.hasOwn(visible, 'permissions'), false);
  assert.equal(sessions.parseServerSessionUser(raw).mustChangePassword, true);
  assert.equal(
    sessions.parseSessionSnapshot({ loggedIn: true, canEdit: true, user: raw }).canEdit,
    false,
  );
});

test('directory failures produce noindex metadata without overriding authenticated layout robots on success', async () => {
  global.fetch = async () => json({ code: 503, msg: 'unavailable', data: null }, 503);
  for (const name of ['app/categories/page.tsx', 'app/wiki/page.tsx']) {
    const module = requireSource(name);
    const metadata =
      typeof module.generateMetadata === 'function'
        ? await runWithCookies({}, () => module.generateMetadata())
        : module.metadata;
    assert.equal(metadata.robots?.index, false);
  }
  global.fetch = async () => json({ code: 200, data: [] });
  for (const name of ['app/categories/page.tsx', 'app/wiki/page.tsx']) {
    assert.equal(
      Object.hasOwn(
        await runWithCookies({}, () => requireSource(name).generateMetadata()),
        'robots',
      ),
      false,
    );
  }
});

test('backend timeout remains attached after headers while a real loopback response body stalls', async () => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.write('{"code":200');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const originalTimeout = AbortSignal.timeout,
    durations = [];
  AbortSignal.timeout = (milliseconds) => {
    durations.push(milliseconds);
    return originalTimeout(100);
  };
  global.fetch = (_url, init) =>
    nativeFetch(`http://127.0.0.1:${server.address().port}/fixture`, init);
  try {
    const response = await requireSource('lib/backend.ts').fetchBackend('/api/v1/fixture');
    await assert.rejects(() => response.json(), /abort|timeout/i);
    assert.deepEqual(durations, [10000]);
  } finally {
    AbortSignal.timeout = originalTimeout;
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
});
