'use strict';

// Genuine React/ReactDOM, authored public exports, and the real Next router
// context. jsdom replaces only the external DOM boundary; this is not browser QA.
// Optional tooling is supplied explicitly rather than changing the pnpm lock.
const assert = require('node:assert/strict');
const test = require('node:test');
const { JSDOM } = require(process.env.BLOG_TEST_JSDOM_PATH || 'jsdom');
const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'https://blog.example',
  pretendToBeVisual: true,
});
for (const key of [
  'window',
  'document',
  'HTMLElement',
  'HTMLInputElement',
  'HTMLTextAreaElement',
  'HTMLButtonElement',
  'HTMLFormElement',
  'HTMLSelectElement',
  'HTMLOptionElement',
  'Option',
  'SVGElement',
  'DocumentFragment',
  'Element',
  'Node',
  'NodeFilter',
  'Event',
  'CustomEvent',
  'KeyboardEvent',
  'MouseEvent',
  'FocusEvent',
  'MutationObserver',
  'DOMRect',
]) {
  global[key] = dom.window[key];
}
Object.defineProperty(global, 'navigator', { configurable: true, value: dom.window.navigator });
global.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
global.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window);
global.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window);
global.IS_REACT_ACT_ENVIRONMENT = true;
// jsdom lacks layout observers/scroll APIs. These external-boundary no-ops are
// not used as evidence for geometry, scrolling, focus containment or contrast.
global.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
global.PointerEvent = dom.window.MouseEvent;
// jsdom has no fullscreen platform. NWSAPI 2.2.27 otherwise recursively calls
// Element.matches(':fullscreen') when evaluating its UA stylesheet (confirmed
// by the complete-suite CPU profile). This unsupported external state is false;
// this fixture does not verify production CSS, geometry or trusted browser input.
const domMatches = dom.window.Element.prototype.matches;
dom.window.Element.prototype.matches = function (selector) {
  if ([':fullscreen', ':-webkit-full-screen', ':-moz-full-screen'].includes(selector)) return false;
  return domMatches.call(this, selector);
};
dom.window.HTMLElement.prototype.scrollIntoView = function () {};
// Nonzero fixture geometry permits Radix/Floating UI to position menus. These
// synthetic rectangles are solely a DOM boundary, never layout/browser proof.
dom.window.HTMLElement.prototype.getBoundingClientRect = function () {
  return new dom.window.DOMRect(0, 0, 240, 32);
};
dom.window.HTMLElement.prototype.hasPointerCapture = function () {
  return false;
};
dom.window.HTMLElement.prototype.setPointerCapture = function () {};
dom.window.HTMLElement.prototype.releasePointerCapture = function () {};

const { requireSource, json, forbidNetwork } = require('./module-loader.cjs');
const React = require('react');
const { act } = React;
const { createRoot } = require('react-dom/client');
const { AppRouterContext } = require('next/dist/shared/lib/app-router-context.shared-runtime');
const { SessionProvider, useSession } = requireSource('components/session-provider.tsx');
const { CommentForm } = requireSource('components/comment-form.tsx');
const { EditableArticle } = requireSource('components/editable-article.tsx');
const { MarkdownEditor } = requireSource('components/markdown-editor.tsx');
const { LoginDialog } = requireSource('components/login-dialog.tsx');
const { Progress } = requireSource('components/ui/progress.tsx');
const { MarkdownEnhance } = requireSource('components/markdown-enhance.tsx');
const h = React.createElement;
const user = {
  id: 7,
  username: 'fixture-admin',
  nickname: null,
  userType: 'ADMIN',
  mustChangePassword: false,
};
const snapshot = (person = user) => ({ loggedIn: true, canEdit: true, user: person });
const post = {
  id: 7,
  title: 'Fixture title',
  content: '# Fixture content',
  status: 'published',
  slug: null,
  summary: null,
  cover: null,
  isTop: false,
  categoryId: null,
  categoryName: null,
  categories: [],
  tags: [],
  views: 0,
  authorId: 7,
  publishedAt: '2026-11-01 08:00:00',
  createTime: null,
  updateTime: null,
};
const meta = {
  categories: [],
  tags: [],
  pages: [],
  statuses: [
    { value: 'published', label: 'Fixture release state' },
    { value: 'draft', label: 'Fixture draft state' },
  ],
};
const deferred = () => {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
const button = (text) =>
  Array.from(document.querySelectorAll('button')).find((node) => node.textContent.trim() === text);
const setInput = (node, value) => {
  assert.ok(node, 'input exists');
  const prototype =
    node instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype
      : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(prototype, 'value').set.call(node, value);
  node.dispatchEvent(new Event('input', { bubbles: true }));
};
const flush = () =>
  act(async () => {
    await new Promise(setImmediate);
  });

async function harness(t, child, initial = true) {
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  let api;
  let refreshes = 0;
  const router = {
    back() {},
    forward() {},
    push() {},
    replace() {},
    prefetch() {},
    refresh() {
      refreshes++;
    },
  };
  function Probe() {
    api = useSession();
    return h(
      'output',
      { 'data-session': true },
      JSON.stringify({
        loggedIn: api.loggedIn,
        canEdit: api.canEdit,
        user: api.user,
        error: api.error,
      }),
    );
  }
  const wrap = (element) =>
    h(
      AppRouterContext.Provider,
      { value: router },
      h(
        SessionProvider,
        { initialLoggedIn: initial, initialCanEdit: initial, initialUser: initial ? user : null },
        h(Probe),
        element,
      ),
    );
  await act(async () => root.render(wrap(child)));
  t.after(async () => {
    await act(async () => root.unmount());
    container.remove();
    forbidNetwork();
  });
  return {
    get api() {
      return api;
    },
    get refreshes() {
      return refreshes;
    },
    async render(next) {
      await act(async () => root.render(wrap(next)));
    },
  };
}

async function fillComment() {
  await act(async () => {
    setInput(document.querySelector('input[aria-label="昵称"]'), 'Fixture commenter');
    setInput(document.querySelector('textarea[aria-label="评论内容"]'), 'Fixture comment');
  });
}
function submitForm() {
  document
    .querySelector('form')
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
}
async function openEditor() {
  await act(async () => button('编辑').click());
  await flush();
}

forbidNetwork();

test('actual SessionProvider prevents a pre-logout refresh from resurrecting identity', async (t) => {
  const pending = deferred();
  global.fetch = async (url) =>
    url === '/api/auth/session'
      ? pending.promise
      : json({ code: 200, data: { localLoggedOut: true, upstreamRevoked: true } });
  const app = await harness(t, null);
  let read;
  await act(async () => {
    read = app.api.refresh();
  });
  await act(async () => {
    await app.api.logout();
  });
  assert.equal(app.api.loggedIn, false);
  await act(async () => {
    pending.resolve(json(snapshot()));
    await read;
  });
  assert.equal(app.api.loggedIn, false);
  assert.equal(app.api.user, null);
});

test('actual SessionProvider applies only the latest concurrent refresh', async (t) => {
  const a = deferred();
  const b = deferred();
  let calls = 0;
  global.fetch = async () => (++calls === 1 ? a.promise : b.promise);
  const app = await harness(t, null);
  let first;
  let second;
  await act(async () => {
    first = app.api.refresh();
    second = app.api.refresh();
  });
  await act(async () => {
    b.resolve(json(snapshot({ ...user, id: 8, username: 'fixture-newer' })));
    await second;
  });
  await act(async () => {
    a.resolve(json(snapshot()));
    await first;
  });
  assert.equal(app.api.user.id, 8);
});

test('actual SessionProvider preserves identity but disables editing during a session outage', async (t) => {
  global.fetch = async () => json({ code: 502, msg: 'Fixture session unavailable' }, 503);
  const app = await harness(t, null);
  await act(async () => {
    await app.api.refresh();
  });
  assert.equal(app.api.loggedIn, true);
  assert.equal(app.api.user.username, user.username);
  assert.equal(app.api.canEdit, false);
  assert.match(app.api.error, /unavailable|暂时|会话/);
});

test('actual SessionProvider does not claim local cookie removal on a failed logout request', async (t) => {
  global.fetch = async () => {
    throw new Error('Fixture transport failure');
  };
  const app = await harness(t, null);
  let result;
  await act(async () => {
    result = await app.api.logout();
  });
  assert.equal(result.localLoggedOut, false);
  assert.equal(app.api.loggedIn, true);
  assert.equal(app.api.canEdit, false);
});

test('actual SessionProvider rejects malformed snapshots and never exposes token fields', async (t) => {
  global.fetch = async () =>
    json(
      snapshot({ ...user, id: [], token: 'fixture-only-token', csrfToken: 'fixture-only-csrf' }),
    );
  const app = await harness(t, null);
  let result;
  await act(async () => {
    result = await app.api.refresh();
  });
  assert.equal(result, false);
  assert.equal(Object.hasOwn(app.api.user, 'token'), false);
  assert.equal(app.api.canEdit, false);
});

test('actual CommentForm prevents synchronous duplicate submissions', async (t) => {
  const pending = deferred();
  let calls = 0;
  global.fetch = async () => {
    calls++;
    await pending.promise;
    return json({ code: 200 });
  };
  await harness(t, h(CommentForm, { targetType: 'post', targetId: 7, needReview: true }));
  await fillComment();
  await act(async () => {
    submitForm();
    submitForm();
  });
  assert.equal(calls, 1);
  await act(async () => {
    pending.resolve();
  });
});

test('actual CommentForm locks pending fields and preserves the draft on business failure', async (t) => {
  const pending = deferred();
  global.fetch = async () => {
    await pending.promise;
    return json({ code: 429, msg: 'Fixture rate limit' });
  };
  const app = await harness(
    t,
    h(CommentForm, { targetType: 'post', targetId: 7, needReview: false }),
  );
  await fillComment();
  await act(async () => submitForm());
  assert.equal(document.querySelector('textarea').disabled, true);
  assert.equal(document.querySelector('input').disabled, true);
  await act(async () => {
    pending.resolve();
  });
  assert.equal(document.querySelector('textarea').value, 'Fixture comment');
  assert.match(document.querySelector('[role=alert]').textContent, /Fixture rate limit/);
  assert.equal(app.refreshes, 0);
});

test('actual CommentForm ignores an old target response after navigation', async (t) => {
  const pending = deferred();
  global.fetch = async () => {
    await pending.promise;
    return json({ code: 200 });
  };
  const app = await harness(
    t,
    h(CommentForm, { targetType: 'post', targetId: 7, needReview: false }),
  );
  await fillComment();
  await act(async () => submitForm());
  await app.render(h(CommentForm, { targetType: 'page', targetId: 8, needReview: true }));
  await act(async () => setInput(document.querySelector('textarea'), 'New target draft'));
  await act(async () => {
    pending.resolve();
  });
  assert.equal(document.querySelector('textarea').value, 'New target draft');
  assert.equal(app.refreshes, 0);
});

test('actual EditableArticle refuses failed metadata instead of opening with empty options', async (t) => {
  global.fetch = async (url) =>
    json(
      url === '/api/admin/meta'
        ? { code: 403, msg: 'Fixture metadata denied', data: null }
        : { code: 200, data: post },
    );
  await harness(
    t,
    h(EditableArticle, { kind: 'post', id: 7 }, h('article', null, 'Fixture reader')),
  );
  await openEditor();
  assert.equal(document.querySelector('[role=dialog]'), null);
  assert.match(document.body.textContent, /Fixture metadata denied/);
});

test('actual EditableArticle requires a successful detail envelope, not just HTTP 200 data', async (t) => {
  global.fetch = async (url) =>
    json(
      url === '/api/admin/meta'
        ? { code: 200, data: meta }
        : { code: 403, msg: 'Fixture detail denied', data: post },
    );
  await harness(t, h(EditableArticle, { kind: 'post', id: 7 }, 'Fixture reader'));
  await openEditor();
  assert.equal(document.querySelector('[role=dialog]'), null);
});

test('actual EditableArticle prevents duplicate saves and blocks pending Escape/discard', async (t) => {
  const pending = deferred();
  let writes = 0;
  global.fetch = async (url, init) => {
    if (init?.method === 'PUT') {
      writes++;
      await pending.promise;
      return json({ code: 200 });
    }
    return json({ code: 200, data: url === '/api/admin/meta' ? meta : post });
  };
  await harness(t, h(EditableArticle, { kind: 'post', id: 7 }, 'Fixture reader'));
  await openEditor();
  await act(async () =>
    setInput(document.querySelector('input[aria-label="标题"]'), 'Changed fixture title'),
  );
  await act(async () => {
    button('保存').click();
    button('保存').click();
  });
  assert.equal(writes, 1);
  await act(async () =>
    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(document.querySelectorAll('[role=dialog]').length, 1);
  assert.equal(document.querySelector('input[aria-label="标题"]').disabled, true);
  await act(async () => {
    pending.resolve();
  });
});

test('actual EditableArticle renders status labels from the returned dictionary', async (t) => {
  global.fetch = async (url) => json({ code: 200, data: url === '/api/admin/meta' ? meta : post });
  await harness(t, h(EditableArticle, { kind: 'post', id: 7 }, 'Fixture reader'));
  await openEditor();
  assert.match(document.querySelector('[role=combobox]').textContent, /Fixture release state/);
});

test('actual EditableArticle never saves on IME composition or from a nested insertion dialog', async (t) => {
  let writes = 0;
  global.fetch = async (url, init) => {
    if (init?.method === 'PUT') {
      writes++;
      return json({ code: 200 });
    }
    return json({ code: 200, data: url === '/api/admin/meta' ? meta : post });
  };
  await harness(t, h(EditableArticle, { kind: 'post', id: 7 }, 'Fixture reader'));
  await openEditor();
  const title = document.querySelector('input[aria-label="标题"]');
  await act(async () => setInput(title, 'Changed title'));
  await act(async () =>
    title.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 's',
        ctrlKey: true,
        isComposing: true,
        bubbles: true,
        cancelable: true,
      }),
    ),
  );
  assert.equal(writes, 0);
  await act(async () => button('链接').click());
  assert.equal(document.querySelectorAll('[role=dialog]').length, 2);
  const nested = Array.from(document.querySelectorAll('[role=dialog]')).find((node) =>
    node.textContent.includes('插入链接'),
  );
  const input = nested.querySelector('input');
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 's', ctrlKey: true, bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(writes, 0);
});

test('actual MarkdownEditor body fullscreen round-trips the same textarea, selection and undo history', async (t) => {
  function Controlled() {
    const [value, setValue] = React.useState('ab');
    return h(MarkdownEditor, { value, onChange: setValue });
  }
  await harness(t, h(Controlled));
  const input = document.querySelector('textarea');
  input.focus();
  input.setSelectionRange(1, 1);
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }),
    ),
  );
  await act(async () => new Promise(requestAnimationFrame));
  const enter = button('全屏编辑');
  assert.ok(enter, 'body fullscreen control exists');
  // Match pointer activation without allowing the control to take textarea focus.
  await act(async () => {
    enter.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    enter.click();
  });
  assert.ok(document.querySelector('[role="region"][aria-label="正文全屏编辑"]'));
  assert.equal(document.querySelector('textarea'), input);
  assert.equal(document.activeElement, input);
  assert.equal(input.value, 'a  b');
  assert.equal(input.selectionStart, 3);
  assert.equal(input.selectionEnd, 3);
  const imeEscape = new KeyboardEvent('keydown', {
    key: 'Escape',
    isComposing: true,
    bubbles: true,
    cancelable: true,
  });
  await act(async () => input.dispatchEvent(imeEscape));
  assert.equal(imeEscape.defaultPrevented, false);
  assert.ok(button('退出全屏'));
  await act(async () => button('退出全屏').click());
  assert.ok(button('全屏编辑'));
  assert.equal(document.querySelector('textarea'), input);
  assert.equal(input.selectionStart, 3);
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(input.value, 'ab');
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'y', ctrlKey: true, bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(input.value, 'a  b');
  input.dispatchEvent(new Event('compositionstart', { bubbles: true }));
  await act(async () => button('全屏编辑').click());
  assert.ok(button('全屏编辑'), 'fullscreen waits for composition to finish');
  input.dispatchEvent(new Event('compositionend', { bubbles: true }));
  await act(async () => button('全屏编辑').click());
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    ),
  );
  assert.ok(button('全屏编辑'));
  assert.equal(document.querySelector('textarea'), input);
});

test('actual EditableArticle body fullscreen hides and inerts metadata, retains real popup ownership and restores fields', async (t) => {
  global.fetch = async (url) => json({ code: 200, data: url === '/api/admin/meta' ? meta : post });
  await harness(t, h(EditableArticle, { kind: 'post', id: 7 }, 'Fixture reader'));
  await openEditor();
  const input = document.querySelector('textarea[name="markdown"]');
  const title = document.querySelector('input[aria-label="标题"]');
  const aside = document.querySelector('aside[aria-label="发布属性"]');
  const article = input.closest('[data-article-editor]');
  const header = article.querySelector('header');
  input.focus();
  input.setSelectionRange(2, 9);
  await act(async () => {
    const enter = button('全屏编辑');
    enter.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    enter.click();
  });
  assert.ok(title.closest('[hidden][inert]'), 'covered title cannot remain in keyboard navigation');
  assert.equal(header.hidden, true);
  assert.equal(header.hasAttribute('inert'), true);
  assert.equal(aside.hidden, true);
  assert.equal(aside.hasAttribute('inert'), true);
  assert.equal(document.querySelector('textarea[name="markdown"]'), input);
  assert.equal(input.selectionStart, 2);
  assert.equal(input.selectionEnd, 9);
  assert.equal(document.activeElement, input);
  await act(async () => document.querySelector('button[aria-label="插入表情"]').click());
  const popup = document.querySelector('[data-slot="popover-content"]');
  assert.equal(popup.closest('[data-slot="dialog-content"]'), article);
  assert.equal(popup.closest('[inert]'), null);
  // Actual widget DOM contract only; utility class preserves existing pointer policy,
  // not jsdom hit-testing, computed browser CSS, geometry or real wheel evidence.
  assert.ok(popup.classList.contains('pointer-events-auto'));
  await act(async () => document.querySelector('button[aria-label="插入 😀"]').click());
  await act(async () => new Promise(requestAnimationFrame));
  assert.equal(input.value, '# 😀 content');
  await act(async () => button('链接').click());
  const nested = [...document.querySelectorAll('[role="dialog"]')].find((node) => node !== article);
  assert.ok(nested);
  await act(async () =>
    nested
      .querySelector('input')
      .dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      ),
  );
  assert.ok(button('退出全屏'), 'nested Escape must not exit the body mode');
  assert.equal(document.querySelectorAll('[role="dialog"]').length, 1);
  input.focus();
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    ),
  );
  assert.ok(button('全屏编辑'));
  assert.equal(document.querySelectorAll('[role="dialog"]').length, 1);
  assert.equal(document.querySelector('textarea[name="markdown"]'), input);
  assert.equal(document.querySelector('input[aria-label="标题"]'), title);
  assert.equal(title.closest('[hidden]'), null);
  assert.equal(header.hidden, false);
  assert.equal(header.hasAttribute('inert'), false);
  assert.equal(aside.hidden, false);
  assert.equal(aside.hasAttribute('inert'), false);
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(input.value, '# Fixture content');
});

test('actual Progress preserves native max semantics and announces indeterminate values honestly', async (t) => {
  const app = await harness(t, h(Progress, { value: 100, max: 200 }));
  assert.equal(document.querySelector('[role=progressbar]').getAttribute('aria-valuemax'), '200');
  assert.equal(
    document.querySelector('[data-slot=progress-indicator]').style.transform,
    'translateX(-50%)',
  );
  await app.render(h(Progress, { value: NaN, max: 0 }));
  assert.equal(document.querySelector('[role=progressbar]').getAttribute('aria-valuenow'), null);
});

test('actual LoginDialog ignores an old close/reopen response and releases its pending lock', async (t) => {
  const pending = deferred();
  let logins = 0,
    confirmations = 0,
    booting = true;
  global.fetch = async (url) => {
    if (url === '/api/auth/login') {
      logins++;
      return pending.promise;
    }
    if (booting) return json(snapshot({ loggedIn: false, canEdit: false, user: null }));
    confirmations++;
    return json(snapshot());
  };
  const dialog = (open) => h(LoginDialog, { open, onOpenChange() {} });
  const app = await harness(t, dialog(true), false);
  booting = false;
  await act(async () => {
    setInput(document.getElementById('login-username'), 'fixture');
    setInput(document.getElementById('login-password'), 'synthetic_secret');
  });
  await act(async () => {
    submitForm();
    submitForm();
  });
  assert.equal(logins, 1);
  assert.equal(document.getElementById('login-password').disabled, true);
  await app.render(dialog(false));
  await app.render(dialog(true));
  assert.equal(document.getElementById('login-password').value, '');
  assert.equal(document.getElementById('login-password').disabled, false);
  await act(async () => pending.resolve(json({ code: 200 })));
  assert.equal(confirmations, 0);
  assert.equal(app.api.loggedIn, false);
});

test('actual LoginDialog confirms an unknown result without replaying a password', async (t) => {
  let logins = 0,
    confirmations = 0,
    booting = true;
  global.fetch = async (url) => {
    if (url === '/api/auth/login') {
      logins++;
      throw new Error('synthetic lost response');
    }
    if (booting) return json(snapshot({ loggedIn: false, canEdit: false, user: null }));
    confirmations++;
    return json(snapshot());
  };
  let closed = 0;
  await harness(
    t,
    h(LoginDialog, {
      open: true,
      onOpenChange(next) {
        if (!next) closed++;
      },
    }),
    false,
  );
  booting = false;
  await act(async () => {
    setInput(document.getElementById('login-username'), 'fixture');
    setInput(document.getElementById('login-password'), 'synthetic_secret');
    submitForm();
  });
  assert.equal(logins, 1);
  assert.equal(document.getElementById('login-password').value, '');
  await act(async () => submitForm());
  assert.equal(logins, 1);
  assert.equal(confirmations, 1);
  assert.equal(closed, 1);
});

test('actual LoginDialog aborts on unmount and cannot resurrect a successful identity', async (t) => {
  const pending = deferred();
  let signal,
    confirmations = 0,
    booting = true;
  global.fetch = async (url, init) => {
    if (url === '/api/auth/login') {
      signal = init.signal;
      return pending.promise;
    }
    if (booting) return json(snapshot({ loggedIn: false, canEdit: false, user: null }));
    confirmations++;
    return json(snapshot());
  };
  const app = await harness(t, h(LoginDialog, { open: true, onOpenChange() {} }), false);
  booting = false;
  await act(async () => {
    setInput(document.getElementById('login-username'), 'fixture');
    setInput(document.getElementById('login-password'), 'synthetic_secret');
    submitForm();
  });
  await app.render(null);
  assert.equal(signal.aborted, true);
  await act(async () => pending.resolve(json({ code: 200 })));
  assert.equal(confirmations, 0);
  assert.equal(app.api.loggedIn, false);
});

test('actual MarkdownEditor retains the baseline emoji and syntax insertion conveniences with undo', async (t) => {
  function Controlled() {
    const [value, setValue] = React.useState('Fixture');
    return h(MarkdownEditor, { value, onChange: setValue });
  }
  await harness(t, h(Controlled));
  const input = document.querySelector('textarea');
  input.focus();
  input.setSelectionRange(0, 7);
  const emojiTrigger = document.querySelector('button[aria-label="插入表情"]');
  assert.ok(emojiTrigger);
  await act(async () => emojiTrigger.click());
  // The real browser gate separately uses accessible-name lookup. This plain
  // DOM attribute lookup is not evidence for browser CSS or hit-testing.
  const emoji = Array.from(document.querySelectorAll('button')).find(
    (node) => node.getAttribute('aria-label') === '插入 😀',
  );
  assert.ok(emoji);
  await act(async () => emoji.click());
  await flush();
  assert.equal(input.value, '😀');
  await act(async () => button('撤销').click());
  assert.equal(input.value, 'Fixture');
  input.setSelectionRange(input.value.length, input.value.length);
  await act(async () =>
    document
      .querySelector('button[aria-label="Markdown 语法速查"]')
      .dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, button: 0, ctrlKey: false })),
  );
  await act(async () => document.querySelector('[data-snippet-label="脚注"]').click());
  await flush();
  assert.match(input.value, /\[\^1\]/);
});

test('actual MarkdownEditor preserves bidirectional proportional preview scroll without feedback', async (t) => {
  await harness(t, h(MarkdownEditor, { value: '# Fixture\n\nParagraph', onChange() {} }));
  await act(async () => button('分栏预览').click());
  const input = document.querySelector('textarea'),
    preview = document.querySelector('[aria-label="Markdown 预览"]');
  for (const [node, height] of [
    [input, 400],
    [preview, 600],
  ]) {
    Object.defineProperty(node, 'scrollHeight', { configurable: true, value: height });
    Object.defineProperty(node, 'clientHeight', { configurable: true, value: 200 });
  }
  input.scrollTop = 100;
  await act(async () => input.dispatchEvent(new Event('scroll', { bubbles: true })));
  assert.equal(preview.scrollTop, 200);
  preview.scrollTop = 300;
  await act(async () => preview.dispatchEvent(new Event('scroll', { bubbles: true })));
  assert.equal(input.scrollTop, 150);
});

test('actual MarkdownEditor continues list items, supports undo/redo and leaves plain Tab/IME to the platform', async (t) => {
  function Controlled() {
    const [value, setValue] = React.useState('1. Fixture');
    return h(MarkdownEditor, { value, onChange: setValue });
  }
  await harness(t, h(Controlled));
  const input = document.querySelector('textarea');
  input.focus();
  input.setSelectionRange(input.value.length, input.value.length);
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(input.value, '1. Fixture\n2. ');
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(input.value, '1. Fixture');
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'z',
        ctrlKey: true,
        shiftKey: true,
        bubbles: true,
        cancelable: true,
      }),
    ),
  );
  assert.equal(input.value, '1. Fixture\n2. ');
  const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
  const ime = new KeyboardEvent('keydown', {
    key: 'Enter',
    isComposing: true,
    bubbles: true,
    cancelable: true,
  });
  // Restored SPEC Tab editing has an explicit keyboard-navigation release.
  // Keep every original native-navigation/IME assertion below unchanged.
  await act(async () => {
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    input.dispatchEvent(tab);
    input.dispatchEvent(ime);
  });
  assert.equal(tab.defaultPrevented, false);
  assert.equal(ime.defaultPrevented, false);
  assert.equal(input.value, '1. Fixture\n2. ');
});

test('actual MarkdownEditor inserts two spaces at a collapsed Tab caret and preserves undo/navigation/IME', async (t) => {
  function Controlled() {
    const [value, setValue] = React.useState('ab');
    return h(MarkdownEditor, { value, onChange: setValue });
  }
  await harness(t, h(Controlled));
  const input = document.querySelector('textarea');
  input.focus();
  input.setSelectionRange(1, 1);
  const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
  await act(async () => input.dispatchEvent(tab));
  await act(async () => new Promise(requestAnimationFrame));
  assert.equal(input.value, 'a  b');
  assert.equal(tab.defaultPrevented, true);
  assert.equal(document.activeElement, input);
  assert.equal(input.selectionStart, 3);
  assert.equal(input.selectionEnd, 3);
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'z', ctrlKey: true, bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(input.value, 'ab');
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'y', ctrlKey: true, bubbles: true, cancelable: true }),
    ),
  );
  assert.equal(input.value, 'a  b');
  const imeTab = new KeyboardEvent('keydown', {
    key: 'Tab',
    isComposing: true,
    bubbles: true,
    cancelable: true,
  });
  await act(async () => input.dispatchEvent(imeTab));
  assert.equal(imeTab.defaultPrevented, false);
  assert.equal(input.value, 'a  b');
  await act(async () =>
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    ),
  );
  const navigation = new KeyboardEvent('keydown', {
    key: 'Tab',
    shiftKey: true,
    bubbles: true,
    cancelable: true,
  });
  await act(async () => input.dispatchEvent(navigation));
  assert.equal(navigation.defaultPrevented, false);
  assert.equal(input.value, 'a  b');
  assert.match(
    document.getElementById(input.getAttribute('aria-describedby')).textContent,
    /Esc.*Tab/,
  );
});

test('actual MarkdownEditor Tab indents a selected block and Shift+Tab reverses it without losing bracket shortcuts', async (t) => {
  function Controlled() {
    const [value, setValue] = React.useState('a\nb');
    return h(MarkdownEditor, { value, onChange: setValue });
  }
  await harness(t, h(Controlled));
  const input = document.querySelector('textarea');
  input.focus();
  input.setSelectionRange(0, 3);
  const press = async (key, options = {}) => {
    const event = new KeyboardEvent('keydown', {
      key,
      bubbles: true,
      cancelable: true,
      ...options,
    });
    await act(async () => input.dispatchEvent(event));
    await act(async () => new Promise(requestAnimationFrame));
    return event;
  };
  assert.equal((await press('Tab')).defaultPrevented, true);
  assert.equal(input.value, '  a\n  b');
  assert.equal(input.selectionStart, 0);
  assert.equal(input.selectionEnd, 7);
  assert.equal((await press('Tab', { shiftKey: true })).defaultPrevented, true);
  assert.equal(input.value, 'a\nb');
  assert.equal(input.selectionStart, 0);
  assert.equal(input.selectionEnd, 3);
  await press(']', { ctrlKey: true });
  assert.equal(input.value, '  a\n  b');
  await press('[', { metaKey: true });
  assert.equal(input.value, 'a\nb');
  input.setSelectionRange(2, 2);
  await press('Tab');
  assert.equal(input.value, 'a\n  b');
  await press('Tab', { shiftKey: true });
  assert.equal(input.value, 'a\nb');
  assert.equal(document.activeElement, input);
});

test('actual MarkdownEnhance suppresses duplicate copy and ignores an obsolete clipboard completion', async (t) => {
  const a = deferred(),
    b = deferred();
  let writes = 0;
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: () => (++writes === 1 ? a.promise : b.promise) },
  });
  const html = requireSource('lib/markdown.ts').renderMarkdown(
    '```js\nfirst();\n```\n\n```js\nsecond();\n```',
  );
  await harness(
    t,
    h(
      React.Fragment,
      null,
      h('div', { dangerouslySetInnerHTML: { __html: html } }),
      h(MarkdownEnhance),
    ),
  );
  const copies = document.querySelectorAll('.md-code-copy');
  await act(async () => {
    copies[0].click();
    copies[0].click();
    copies[1].click();
  });
  assert.equal(writes, 2);
  await act(async () => b.resolve());
  assert.equal(copies[1].dataset.copied, 'true');
  await act(async () => a.resolve());
  assert.equal(copies[0].dataset.copied, undefined);
});

test('actual MarkdownEnhance restores document selection and modal focus after the clipboard fallback', async (t) => {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: {
      writeText: async () => {
        throw new Error('Fixture permission denial');
      },
    },
  });
  const { Dialog, DialogContent, DialogTitle, DialogDescription } = requireSource(
    'components/ui/dialog.tsx',
  );
  const html = requireSource('lib/markdown.ts').renderMarkdown(
    'Selected fixture text.\n\n```js\nfixture();\n```',
  );
  await harness(
    t,
    h(
      Dialog,
      { open: true },
      h(
        DialogContent,
        null,
        h(DialogTitle, null, 'Fixture copy'),
        h(DialogDescription, null, 'Fixture selection'),
        h('div', { className: 'markdown', dangerouslySetInnerHTML: { __html: html } }),
        h(MarkdownEnhance),
      ),
    ),
  );
  const copy = document.querySelector('.md-code-copy');
  copy.focus();
  const selection = document.getSelection(),
    range = document.createRange();
  range.selectNodeContents(document.querySelector('.markdown p'));
  selection.removeAllRanges();
  selection.addRange(range);
  const previousExec = document.execCommand;
  t.after(() => {
    document.execCommand = previousExec;
  });
  let observed;
  document.execCommand = () => {
    observed = {
      inDialog: Boolean(document.activeElement.closest('[data-slot="dialog-content"]')),
      text: document.activeElement.value,
    };
    // Synthetic execCommand boundary deliberately replaces the document selection.
    selection.removeAllRanges();
    return true;
  };
  await act(async () => copy.click());
  assert.equal(copy.dataset.copied, 'true');
  assert.equal(document.activeElement, copy);
  assert.equal(selection.toString(), 'Selected fixture text.');
  assert.equal(observed.inDialog, true);
  assert.equal(observed.text, 'fixture();');
});

test('standalone DropdownMenuPortal remains inside the nearest actual Dialog DOM', async (t) => {
  const { Dialog, DialogContent, DialogTitle, DialogDescription } = requireSource(
    'components/ui/dialog.tsx',
  );
  const { DropdownMenu, DropdownMenuPortal } = requireSource('components/ui/dropdown-menu.tsx');
  await harness(
    t,
    h(
      Dialog,
      { open: true },
      h(
        DialogContent,
        null,
        h(DialogTitle, null, 'Fixture modal'),
        h(DialogDescription, null, 'Fixture portal'),
        h(
          DropdownMenu,
          { open: true },
          h(
            DropdownMenuPortal,
            null,
            h('div', { id: 'fixture-standalone-portal' }, 'Fixture popup'),
          ),
        ),
      ),
    ),
  );
  assert.ok(
    document.getElementById('fixture-standalone-portal').closest('[data-slot=dialog-content]'),
  );
});

test('actual parent choices retain ancestors/unrelated pages and exclude the edited descendant subtree', async (t) => {
  const page = {
    id: 7,
    title: 'Fixture edited',
    content: '# Fixture',
    status: 'published',
    slug: null,
    parentId: 2,
    sort: 0,
    publishedAt: null,
  };
  const pages = [
    {
      id: 1,
      title: 'Fixture ancestor root',
      children: [
        {
          id: 2,
          title: 'Fixture ancestor parent',
          children: [
            {
              id: 7,
              title: 'Fixture edited',
              children: [{ id: 8, title: 'Fixture descendant', children: [] }],
            },
          ],
        },
      ],
    },
    { id: 9, title: 'Fixture unrelated', children: [] },
  ];
  global.fetch = async (url) =>
    json({ code: 200, data: url === '/api/admin/meta' ? { ...meta, pages } : page });
  await harness(t, h(EditableArticle, { kind: 'page', id: 7 }, 'Fixture reader'));
  await openEditor();
  const parent = document.querySelectorAll('[role=combobox]')[1];
  assert.ok(parent, 'actual parent selector exists');
  await act(async () =>
    parent.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }),
    ),
  );
  await flush();
  const labels = Array.from(document.querySelectorAll('[role=option]'))
    .map((node) => node.textContent)
    .join('\n');
  assert.match(labels, /Fixture ancestor root/);
  assert.match(labels, /Fixture ancestor parent/);
  assert.match(labels, /Fixture unrelated/);
  assert.doesNotMatch(labels, /Fixture descendant|Fixture edited/);
});

for (const interaction of [
  'focus',
  'selection',
  'typing',
  'composition',
  'undo',
  'remount',
  'view',
  'disabled',
]) {
  test(`actual MarkdownEditor never restores an obsolete formatting selection after newer ${interaction}`, async (t) => {
    const previousFrame = global.requestAnimationFrame;
    const previousCancel = global.cancelAnimationFrame;
    const frames = new Map();
    let sequence = 0;
    global.requestAnimationFrame = (callback) => {
      const id = ++sequence;
      frames.set(id, callback);
      return id;
    };
    global.cancelAnimationFrame = (id) => frames.delete(id);
    t.after(() => {
      global.requestAnimationFrame = previousFrame;
      global.cancelAnimationFrame = previousCancel;
    });
    function Controlled({ disabled = false }) {
      const [value, setValue] = React.useState('Fixture');
      return h(
        React.Fragment,
        null,
        h(MarkdownEditor, { value, onChange: setValue, disabled }),
        h('input', { id: 'fixture-next-field' }),
      );
    }
    const mounted = await harness(t, h(Controlled));
    const input = document.querySelector('textarea');
    input.focus();
    input.setSelectionRange(0, 7);
    await act(async () => button('加粗').click());
    assert.equal(input.value, '**Fixture**');
    const next = document.getElementById('fixture-next-field');
    await act(async () => {
      if (interaction === 'focus') next.focus();
      else if (interaction === 'selection') {
        input.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
        input.setSelectionRange(1, 1);
      } else if (interaction === 'typing') setInput(input, '**Fixture** newer draft');
      else if (interaction === 'composition')
        input.dispatchEvent(new Event('compositionstart', { bubbles: true }));
      else if (interaction === 'undo') button('撤销').click();
    });
    if (interaction === 'remount') await mounted.render(h('div', null, h(Controlled)));
    if (interaction === 'disabled') await mounted.render(h(Controlled, { disabled: true }));
    if (interaction === 'view') {
      await act(async () => button('预览').click());
      await act(async () => button('编辑').click());
    }
    const current = document.querySelector('textarea');
    const position = current.selectionStart;
    await act(async () => {
      for (const callback of Array.from(frames.values())) callback(0);
      frames.clear();
    });
    if (interaction === 'focus')
      assert.equal(document.activeElement === next, true, 'newer field keeps focus');
    else assert.equal(current.selectionStart, position);
    if (interaction === 'typing') assert.equal(current.value, '**Fixture** newer draft');
    if (interaction === 'undo' || interaction === 'remount') assert.equal(current.value, 'Fixture');
  });
}
