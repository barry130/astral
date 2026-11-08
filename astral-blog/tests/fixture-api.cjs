'use strict';
const http = require('node:http');
const { generateKeyPairSync, createHash } = require('node:crypto');
const fixtureSourceHash = createHash('sha256')
  .update(require('node:fs').readFileSync(__filename))
  .digest('hex');
const pendingSaves = new Set();
const saveObservers = new Set();
const { publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const key = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const user = {
  id: 7,
  username: 'fixture_editor',
  nickname: 'Fixture editor',
  userType: 'ADMIN',
  roles: ['FIXTURE_ROLE_MUST_NOT_REACH_FLIGHT'],
  permissions: ['admin:cms:view', 'admin:cms:content:edit'],
  mustChangePassword: 0,
  token: 'FIXTURE_BODY_TOKEN_MUST_NOT_REACH_FLIGHT',
  csrfToken: 'FIXTURE_BODY_CSRF_MUST_NOT_REACH_FLIGHT',
};
const states = {
  fixtureSourceHash,
  outage: false,
  logoutFailure: false,
  saveFailure: false,
  holdSave: false,
  loginCount: 0,
  saves: 0,
  comments: 0,
  requests: [],
};
const content =
  '## First section\n\nFixture text.\n\n' +
  Array.from(
    { length: 20 },
    (_, i) => `## Section ${i + 2}\n\nA fixture paragraph with useful reading content.\n`,
  ).join('\n') +
  '\n```js\n// fixture comment\nconsole.log("safe");\n```';
const named = {
  id: 1,
  name: 'Fixture category',
  slug: 'fixture-category',
  children: [],
  postCount: 25,
};
const tags = [{ id: 1, name: 'Fixture tag', slug: 'fixture-tag', postCount: 25 }];
const post = (id, status = 'published') => ({
  id,
  title: `Fixture article ${id}`,
  slug: `fixture-${id}`,
  status,
  content,
  summary: 'A synthetic authored-app validation article.',
  categories: [named],
  tags,
  isTop: false,
  views: 7,
  cover: null,
  publishedAt: '2026-11-06 12:00:00',
  updateTime: '2026-11-06 12:00:00',
});
const tree = [
  {
    id: 1,
    title: 'Fixture root',
    slug: null,
    children: [
      {
        id: 2,
        title: 'Fixture ancestor',
        slug: null,
        children: [
          {
            id: 7,
            title: 'Fixture edited',
            slug: null,
            children: [{ id: 8, title: 'Fixture descendant', slug: null, children: [] }],
          },
        ],
      },
    ],
  },
  { id: 9, title: 'Fixture unrelated', slug: null, children: [] },
  ...Array.from({ length: 60 }, (_, i) => ({
    id: 100 + i,
    title: `Fixture other ${i}`,
    slug: null,
    children: [],
  })),
];
const page = {
  id: 7,
  title: 'Fixture page',
  slug: null,
  content,
  status: 'published',
  parentId: 2,
  sort: 0,
  publishedAt: null,
  children: [],
  breadcrumb: [],
};
function send(response, data, code = 200, status = 200, headers = {}) {
  response.writeHead(status, {
    'content-type': 'application/json',
    'cache-control': 'no-store',
    ...headers,
  });
  response.end(
    JSON.stringify({
      code,
      msg: code === 200 ? 'fixture' : 'Fixture failure',
      data,
      timestamp: Date.now(),
    }),
  );
}
const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1:41891');
  const cookie = request.headers.cookie || '';
  const loggedIn = cookie.includes('satoken=fixture-session');
  if (url.pathname === '/_fixture/await-save') {
    const count = Number(url.searchParams.get('count'));
    if (!Number.isSafeInteger(count) || count <= 0) {
      send(response, null, 320);
      return;
    }
    const observer = () => {
      if (states.saves >= count) {
        saveObservers.delete(observer);
        send(response, { saves: states.saves, pendingSaves: pendingSaves.size });
      }
    };
    saveObservers.add(observer);
    response.on('close', () => saveObservers.delete(observer));
    observer();
    return;
  }
  if (url.pathname === '/_fixture/state') {
    if (request.method === 'POST') {
      let body = '';
      for await (const chunk of request) body += chunk;
      const patch = JSON.parse(body || '{}');
      for (const field of ['outage', 'logoutFailure', 'saveFailure', 'holdSave'])
        if (typeof patch[field] === 'boolean') states[field] = patch[field];
      if (patch.releaseSaves) {
        for (const finish of pendingSaves) finish();
        pendingSaves.clear();
      }
      if (patch.reset) {
        states.loginCount = states.saves = states.comments = 0;
        states.requests = [];
      }
    }
    send(response, states);
    return;
  }
  states.requests.push({
    path: url.pathname,
    method: request.method,
    authenticated: loggedIn,
    systemHeaders: ['x-app-ut', 'x-app-version', 'x-device', 'x-os'].every((name) =>
      Boolean(request.headers[name]),
    ),
  });
  if (url.pathname === '/api/v1/all/auth/public-key') {
    send(response, { publicKey: key });
    return;
  }
  if (url.pathname === '/api/v1/all/auth/login') {
    let body = '';
    for await (const chunk of request) body += chunk;
    const input = JSON.parse(body);
    states.loginCount++;
    if (
      input.username !== 'fixture_editor' ||
      typeof input.password !== 'string' ||
      Buffer.from(input.password, 'base64').length !== 256
    ) {
      send(response, null, 401);
      return;
    }
    send(response, user, 200, 200, {
      'set-cookie': [
        'satoken=fixture-session; Domain=backend.invalid; Path=/api; HttpOnly; Max-Age=3600',
        'astral_csrf=fixture-csrf; Domain=backend.invalid; Path=/api; Max-Age=3600',
      ],
    });
    return;
  }
  if (url.pathname === '/api/v1/all/auth/info') {
    send(
      response,
      states.outage ? null : loggedIn ? user : null,
      states.outage ? 503 : loggedIn ? 200 : 401,
      states.outage ? 503 : 200,
    );
    return;
  }
  if (url.pathname === '/api/v1/all/auth/logout') {
    send(response, null, states.logoutFailure ? 503 : 200, states.logoutFailure ? 503 : 200);
    return;
  }
  if (url.pathname.includes('/dict/data/byCode')) {
    send(response, [
      { dictValue: 'draft', dictLabel: 'Fixture draft', status: 1 },
      { dictValue: 'published', dictLabel: 'Fixture release state', status: 1 },
      { dictValue: 'private', dictLabel: 'Fixture restricted state', status: 1 },
    ]);
    return;
  }
  if (url.pathname.endsWith('/cms/category/tree')) {
    send(response, [named]);
    return;
  }
  if (url.pathname.endsWith('/cms/tag/list')) {
    send(response, tags);
    return;
  }
  if (url.pathname.endsWith('/cms/page/tree')) {
    send(response, tree);
    return;
  }
  if (url.pathname.endsWith('/cms/setting')) {
    send(response, {
      site_title: 'Fixture Astral',
      site_description: 'Synthetic boundary only.',
      footer_text: 'Offline authored application',
      comment_enabled: 'true',
      comment_need_review: 'true',
    });
    return;
  }
  if (url.pathname.endsWith('/cms/post/page')) {
    const current = Number(url.searchParams.get('pageNum') || 1),
      size = Number(url.searchParams.get('pageSize') || 10),
      total = 25;
    send(response, {
      records: Array.from(
        { length: Math.max(0, Math.min(size, total - (current - 1) * size)) },
        (_, i) => post((current - 1) * size + i + 1),
      ),
      current,
      size,
      total,
      pages: Math.ceil(total / size),
    });
    return;
  }
  if (url.pathname.endsWith('/cms/comment/page')) {
    const current = Number(url.searchParams.get('pageNum') || 1),
      size = Number(url.searchParams.get('pageSize') || 20),
      total = 55;
    send(response, {
      records: Array.from(
        { length: Math.max(0, Math.min(size, total - (current - 1) * size)) },
        (_, i) => ({
          id: (current - 1) * size + i + 1,
          nickname: `Fixture reader ${(current - 1) * size + i + 1}`,
          content: 'Synthetic comment.',
          createTime: '2026-11-06 12:00:00',
        }),
      ),
      current,
      size,
      total,
      pages: Math.ceil(total / size),
    });
    return;
  }
  if (url.pathname.endsWith('/cms/comment') && request.method === 'POST') {
    states.comments++;
    send(response, null);
    return;
  }
  if (/\/cms\/(?:post|page)\/\d+$/.test(url.pathname) && request.method === 'PUT') {
    if (!loggedIn || request.headers['x-csrf-token'] !== 'fixture-csrf') {
      send(response, null, 403);
      return;
    }
    states.saves++;
    const finish = () => send(response, null, states.saveFailure ? 300 : 200);
    if (states.holdSave) pendingSaves.add(finish);
    else finish();
    for (const observer of saveObservers) observer();
    return;
  }
  if (url.pathname.endsWith('/cms/post/private-fixture')) {
    send(response, loggedIn ? post(70, 'private') : null, loggedIn ? 200 : 404);
    return;
  }
  if (/\/cms\/post\/(?:fixture-)?\d+$/.test(url.pathname)) {
    const data = post(Number(url.pathname.match(/\d+$/)[0]));
    send(
      response,
      url.pathname.includes('/admin/') ? { ...data, categoryIds: [1], tagIds: [1] } : data,
    );
    return;
  }
  if (url.pathname.endsWith('/cms/page/7')) {
    send(response, page);
    return;
  }
  if (url.pathname.includes('/cms/share/')) {
    send(response, { targetType: 'post', targetId: 7, post: post(7), page: null, expireAt: null });
    return;
  }
  send(response, null, 404, 404);
});
server.listen(41891, '127.0.0.1', () => console.log('FIXTURE_API=http://127.0.0.1:41891'));
for (const signal of ['SIGINT', 'SIGTERM'])
  process.on(signal, () => server.close(() => process.exit(0)));
