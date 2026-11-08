import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import manifestModule from './source-manifest.cjs';
import { pathToFileURL, fileURLToPath } from 'node:url';

// Real production Next, real Chromium geometry/focus/wheel; only the backend and
// clipboard external boundaries are synthetic. No production hosts or credentials.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixture = path.resolve(process.argv[2] || '');
if (!fixture.startsWith(path.join(root, 'tests', '.artifacts') + path.sep))
  throw new Error('Use an in-blog isolated fixture');
const build = JSON.parse(fs.readFileSync(path.join(fixture, 'build-receipt.json'), 'utf8'));
assert.equal(build.status, 0, 'The fixture must have an accepted production build');
const diagnostic = process.argv.includes('--diagnostic');
const currentManifest = manifestModule.authoredManifest(root);
const differences = [
  ...new Set([...Object.keys(build.authoredManifest), ...Object.keys(currentManifest)]),
].filter((name) => build.authoredManifest[name] !== currentManifest[name]);
const driverHash = createHash('sha256')
  .update(fs.readFileSync(fileURLToPath(import.meta.url)))
  .digest('hex');
const backendHash = createHash('sha256')
  .update(fs.readFileSync(path.join(root, 'tests/fixture-api.cjs')))
  .digest('hex');
const requiredChecks = [
  'production anonymous home, strict query rejection and page-specific canonical',
  'real BFF login confirms safe identity and normalized root cookies',
  'actual SSR and Flight exclude reusable credentials and concurrent anonymous requests remain isolated',
  'actual parent choices retain ancestors/unrelated pages and exclude the edited descendant subtree',
  'real CDP wheel scrolls dialog-local Select with pointer events and focus containment',
  'native Markdown list Enter, undo/redo and plain Tab work through the real editor',
  'safe insertion, IME and pending saves preserve article ownership and the draft',
  'nonsecret page navigation and TOC preserve Next history state and do not create horizontal overflow',
  'real computed reading and syntax-comment contrast meets WCAG AA',
  'mobile reading uses real responsive geometry and accessible touch navigation',
  'code copy completes through the safe platform boundary and restores focus',
  'local logout clears both cookies even when backend revocation fails',
];
if (differences.length && !diagnostic)
  throw new Error(`Source changed since build: ${differences.join(', ')}`);
const out = path.join(fixture, `browser-${randomUUID()}`);
fs.mkdirSync(out);
const temp = path.join(out, 'temp');
fs.mkdirSync(temp);
process.env.TEMP = process.env.TMP = process.env.TMPDIR = temp;
const external = process.env.BLOG_TEST_PLAYWRIGHT_PATH;
const { chromium } = await import(
  external ? pathToFileURL(path.resolve(external)).href : 'playwright-core'
);
const base = 'http://127.0.0.1:41890',
  api = 'http://127.0.0.1:41891';
const results = [],
  errors = [],
  externalRequests = [];
const context = await chromium.launchPersistentContext(path.join(out, 'profile'), {
  channel: process.env.BLOG_TEST_BROWSER_CHANNEL || 'chrome',
  headless: true,
  viewport: { width: 1440, height: 1000 },
  downloadsPath: path.join(out, 'downloads'),
  tracesDir: path.join(out, 'traces'),
  args: [
    '--disable-background-networking',
    '--disable-component-update',
    '--disable-breakpad',
    '--no-first-run',
  ],
});
context.setDefaultTimeout(8000);
await context.route('**/*', (route) => {
  const url = new URL(route.request().url());
  if (
    ['data:', 'blob:'].includes(url.protocol) ||
    (url.hostname === '127.0.0.1' && ['41890', '41891'].includes(url.port))
  )
    return route.continue();
  externalRequests.push(url.origin);
  return route.abort();
});
await context.addInitScript(() => {
  // Do not overwrite the host user's real clipboard during the audit.
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: {
      writeText: async (text) => {
        window.__fixtureCopied = text;
      },
    },
  });
});
const page = await context.newPage();
page.on('pageerror', (error) => errors.push(error.message));
const state = async (patch) => {
  const response = await context.request.post(`${api}/_fixture/state`, { data: patch });
  return (await response.json()).data;
};
const currentState = async () =>
  (await (await context.request.get(`${api}/_fixture/state`)).json()).data;
async function check(name, action) {
  try {
    const detail = await action();
    results.push({ name, pass: true, detail });
    console.log(`PASS ${name}`);
  } catch (error) {
    results.push({ name, pass: false, error: error.stack });
    console.error(`FAIL ${name}: ${error.message}`);
    await page
      .screenshot({ path: path.join(out, `failure-${results.length}.png`), fullPage: true })
      .catch(() => {});
  }
}
try {
  const fixtureState = await state({
    reset: true,
    outage: false,
    logoutFailure: false,
    saveFailure: false,
    holdSave: false,
    releaseSaves: true,
  });
  assert.equal(
    fixtureState.fixtureSourceHash,
    backendHash,
    'The running backend must match the current fixture source',
  );
  await check(
    'production anonymous home, strict query rejection and page-specific canonical',
    async () => {
      const response = await page.goto(base, { waitUntil: 'networkidle' });
      assert.equal(response.status(), 200);
      const expectedBuildId = fs.readFileSync(path.join(fixture, '.next/BUILD_ID'), 'utf8').trim();
      assert.ok(
        (await response.text()).includes(expectedBuildId),
        'The served HTML must identify this exact production build',
      );
      assert.ok(await page.getByRole('heading', { level: 1 }).count());
      await page.goto(`${base}/?page=2`, { waitUntil: 'networkidle' });
      assert.equal(
        await page.locator('link[rel=canonical]').getAttribute('href'),
        `${base}/?page=2`,
      );
      await page.goto(`${base}/?page=1x`, { waitUntil: 'networkidle' });
      assert.equal(await page.getByRole('heading', { name: '页面不存在', exact: true }).count(), 1);
      await page.goto(base, { waitUntil: 'networkidle' });
    },
  );
  await check('real BFF login confirms safe identity and normalized root cookies', async () => {
    await page.getByRole('button', { name: /登录/ }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('用户名', { exact: true }).fill('fixture_editor');
    await dialog.getByLabel('密码', { exact: true }).fill('fixture_password');
    await dialog.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('[role=dialog]'));
    const cookies = await context.cookies(base),
      token = cookies.find((cookie) => cookie.name === 'satoken'),
      csrf = cookies.find((cookie) => cookie.name === 'astral_csrf');
    assert.ok(token?.httpOnly);
    assert.equal(token?.domain, '127.0.0.1');
    assert.equal(token?.path, '/');
    assert.equal(csrf?.path, '/');
    assert.equal(csrf?.httpOnly, false);
    const snapshot = await (await context.request.get(`${base}/api/auth/session`)).json();
    assert.equal(snapshot.loggedIn, true);
    const serialized = JSON.stringify(snapshot);
    assert.doesNotMatch(
      serialized,
      /FIXTURE_BODY_TOKEN|FIXTURE_BODY_CSRF|FIXTURE_ROLE|mustChangePassword|permissions/,
    );
    assert.equal((await currentState()).loginCount, 1);
  });
  await check(
    'actual SSR and Flight exclude reusable credentials and concurrent anonymous requests remain isolated',
    async () => {
      const anonymous = await context.browser().newContext();
      try {
        const [authorized, publicVisitor] = await Promise.all([
          context.request.get(`${base}/post/private-fixture`),
          anonymous.request.get(`${base}/post/private-fixture`),
        ]);
        const a = await authorized.text(),
          b = await publicVisitor.text();
        assert.match(a, /Fixture article 70/);
        assert.doesNotMatch(b, /Fixture article 70|Fixture editor/);
        assert.doesNotMatch(
          a,
          /FIXTURE_BODY_TOKEN|FIXTURE_BODY_CSRF|FIXTURE_ROLE_MUST_NOT_REACH_FLIGHT|fixture-session|fixture-csrf|mustChangePassword|"permissions"|"roles"/,
        );
        const flight = await (
          await context.request.get(`${base}/post/private-fixture?_rsc=fixture`, {
            headers: { RSC: '1' },
          })
        ).text();
        assert.doesNotMatch(
          flight,
          /FIXTURE_BODY_TOKEN|FIXTURE_BODY_CSRF|FIXTURE_ROLE_MUST_NOT_REACH_FLIGHT|fixture-session|fixture-csrf|mustChangePassword|"permissions"|"roles"/,
        );
        await page.goto(`${base}/post/private-fixture`, { waitUntil: 'networkidle' });
        assert.match(
          await page.locator('meta[name=robots]').last().getAttribute('content'),
          /noindex/,
        );
        assert.equal(await page.getByText('Fixture restricted state', { exact: true }).count(), 1);
      } finally {
        await anonymous.close();
      }
    },
  );
  await check(
    'actual parent choices retain ancestors/unrelated pages and exclude the edited descendant subtree',
    async () => {
      await page.goto(`${base}/wiki/7`, { waitUntil: 'networkidle' });
      await page.getByRole('button', { name: /编辑/ }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('textbox', { name: 'Markdown 正文' }).waitFor();
      assert.match(
        await dialog.getByRole('combobox').first().textContent(),
        /Fixture release state/,
      );
      await dialog.getByRole('combobox').nth(1).press('ArrowDown');
      await page.locator('[data-slot=select-content]').waitFor();
      const labels = (await page.getByRole('option').allTextContents()).join('\n');
      assert.match(labels, /Fixture ancestor/);
      assert.match(labels, /Fixture unrelated/);
      assert.doesNotMatch(labels, /Fixture descendant|Fixture edited/);
      return { options: await page.getByRole('option').count() };
    },
  );
  await check(
    'real CDP wheel scrolls dialog-local Select with pointer events and focus containment',
    async () => {
      const popup = page.locator('[data-slot=select-content]');
      const geometry = await popup.evaluate((element) => {
        const viewport = element.querySelector('[data-radix-select-viewport]');
        const rect = viewport.getBoundingClientRect();
        return {
          inDialog: Boolean(element.closest('[data-slot=dialog-content]')),
          pointer: getComputedStyle(element).pointerEvents,
          x: rect.x + rect.width / 2,
          y: rect.y + rect.height / 2,
          before: viewport.scrollTop,
          scrollHeight: viewport.scrollHeight,
          clientHeight: viewport.clientHeight,
        };
      });
      assert.equal(geometry.inDialog, true);
      assert.equal(geometry.pointer, 'auto');
      assert.ok(geometry.scrollHeight > geometry.clientHeight);
      await page.evaluate(() => {
        window.__fixtureWheel = null;
        document.addEventListener(
          'wheel',
          (event) =>
            queueMicrotask(() => {
              window.__fixtureWheel = {
                defaultPrevented: event.defaultPrevented,
                trusted: event.isTrusted,
              };
            }),
          { once: true, passive: true },
        );
      });
      const cdp = await context.newCDPSession(page);
      await cdp.send('Input.dispatchMouseEvent', {
        type: 'mouseWheel',
        x: geometry.x,
        y: geometry.y,
        deltaX: 0,
        deltaY: 360,
      });
      await page.waitForFunction(
        (before) => document.querySelector('[data-radix-select-viewport]').scrollTop > before,
        geometry.before,
      );
      const wheel = await page.evaluate(() => window.__fixtureWheel);
      assert.equal(wheel?.trusted, true);
      assert.equal(wheel?.defaultPrevented, false);
      await page.keyboard.press('Escape');
      for (let index = 0; index < 12; index++) {
        await page.keyboard.press('Tab');
        assert.equal(
          await page.evaluate(() =>
            Boolean(document.activeElement?.closest('[data-slot=dialog-content]')),
          ),
          true,
        );
      }
      return { ...geometry, wheel };
    },
  );
  await check(
    'native Markdown list Enter, undo/redo and plain Tab work through the real editor',
    async () => {
      const textarea = page.getByRole('textbox', { name: 'Markdown 正文' });
      await textarea.fill('1. Fixture');
      await textarea.press('End');
      await textarea.press('Enter');
      assert.equal(await textarea.inputValue(), '1. Fixture\n2. ');
      await textarea.press('Control+z');
      assert.equal(await textarea.inputValue(), '1. Fixture');
      await textarea.press('Control+Shift+z');
      assert.equal(await textarea.inputValue(), '1. Fixture\n2. ');
      // Ordinary Tab inserts indentation; Escape releases the following native Tab.
      await textarea.press('Tab');
      assert.equal(await textarea.inputValue(), '1. Fixture\n2.   ');
      assert.equal(await textarea.evaluate((element) => document.activeElement === element), true);
      await textarea.press('Control+z');
      assert.equal(await textarea.inputValue(), '1. Fixture\n2. ');
      await textarea.press('Control+a');
      await textarea.press('Tab');
      assert.equal(await textarea.inputValue(), '  1. Fixture\n  2. ');
      await textarea.press('Shift+Tab');
      assert.equal(await textarea.inputValue(), '1. Fixture\n2. ');
      assert.equal(await textarea.evaluate((element) => document.activeElement === element), true);
      await textarea.press('ArrowRight');
      await textarea.press('Escape');
      await textarea.press('Tab');
      assert.equal(await textarea.evaluate((element) => document.activeElement === element), false);
      const editor = page.locator('[data-article-editor]');
      await editor.getByRole('button', { name: '插入表情', exact: true }).click();
      const emoji = page.getByRole('button', { name: '插入 😀', exact: true });
      assert.equal(
        await emoji.evaluate((element) => Boolean(element.closest('[data-article-editor]'))),
        true,
      );
      await emoji.click();
      assert.ok((await textarea.inputValue()).includes('😀'));
      await textarea.press('Control+z');
      assert.equal(await textarea.inputValue(), '1. Fixture\n2. ');
      await editor.getByRole('button', { name: 'Markdown 语法速查', exact: true }).click();
      await page.getByRole('menuitem', { name: '脚注', exact: true }).click();
      assert.match(await textarea.inputValue(), /\[\^1\]/);
      await textarea.press('Control+z');
      const original = await textarea.inputValue();
      const longBody = Array.from(
        { length: 150 },
        (_, index) => `Paragraph ${index}: fixture synchronized reading.`,
      ).join('\n\n');
      await textarea.fill(longBody);
      await editor.getByRole('button', { name: '分栏预览', exact: true }).click();
      const preview = editor.getByRole('region', { name: 'Markdown 预览', exact: true });
      await page.waitForFunction(() =>
        document
          .querySelector('[aria-label="Markdown 预览"]')
          ?.textContent?.includes('Paragraph 149'),
      );
      await textarea.scrollIntoViewIfNeeded();
      await textarea.evaluate((element) => {
        element.scrollTop = 0;
      });
      await page.waitForFunction(
        () =>
          document.querySelector('textarea[name="markdown"]')?.scrollTop === 0 &&
          document.querySelector('[aria-label="Markdown 预览"]')?.scrollTop === 0,
      );
      await textarea.evaluate((element) =>
        element.addEventListener(
          'wheel',
          (event) =>
            queueMicrotask(() => {
              window.__fixtureEditorWheel = {
                trusted: event.isTrusted,
                defaultPrevented: event.defaultPrevented,
              };
            }),
          { once: true },
        ),
      );
      const box = await textarea.boundingBox();
      const cdp = await context.newCDPSession(page);
      await cdp.send('Input.dispatchMouseEvent', {
        type: 'mouseWheel',
        x: box.x + box.width / 2,
        y: box.y + Math.min(120, box.height / 2),
        deltaX: 0,
        deltaY: 600,
      });
      await page.waitForFunction(
        () =>
          document.querySelector('textarea[name="markdown"]')?.scrollTop > 0 &&
          document.querySelector('[aria-label="Markdown 预览"]')?.scrollTop > 0,
      );
      const ratios = await page.evaluate(() => {
        const source = document.querySelector('textarea[name="markdown"]'),
          target = document.querySelector('[aria-label="Markdown 预览"]');
        return {
          editorTop: source.scrollTop,
          previewTop: target.scrollTop,
          editor: source.scrollTop / (source.scrollHeight - source.clientHeight),
          preview: target.scrollTop / (target.scrollHeight - target.clientHeight),
          wheel: window.__fixtureEditorWheel,
        };
      });
      assert.equal(ratios.wheel.trusted, true);
      assert.equal(ratios.wheel.defaultPrevented, false);
      assert.ok(
        ratios.editorTop > 0 && ratios.previewTop > 0,
        'The trusted wheel must actually move both panes from zero',
      );
      assert.ok(Math.abs(ratios.editor - ratios.preview) < 0.02);
      await preview.evaluate((element) =>
        element.addEventListener(
          'wheel',
          (event) =>
            queueMicrotask(() => {
              window.__fixturePreviewWheel = {
                trusted: event.isTrusted,
                defaultPrevented: event.defaultPrevented,
              };
            }),
          { once: true },
        ),
      );
      const previewBox = await preview.boundingBox();
      await cdp.send('Input.dispatchMouseEvent', {
        type: 'mouseWheel',
        x: previewBox.x + previewBox.width / 2,
        y: previewBox.y + Math.min(120, previewBox.height / 2),
        deltaX: 0,
        deltaY: 400,
      });
      await page.waitForFunction(
        (before) =>
          document.querySelector('textarea[name="markdown"]')?.scrollTop > before.editorTop &&
          document.querySelector('[aria-label="Markdown 预览"]')?.scrollTop > before.previewTop,
        ratios,
      );
      const reverse = await page.evaluate(() => {
        const input = document.querySelector('textarea[name="markdown"]'),
          output = document.querySelector('[aria-label="Markdown 预览"]');
        return {
          editor: input.scrollTop / (input.scrollHeight - input.clientHeight),
          preview: output.scrollTop / (output.scrollHeight - output.clientHeight),
          wheel: window.__fixturePreviewWheel,
        };
      });
      assert.equal(reverse.wheel.trusted, true);
      assert.equal(reverse.wheel.defaultPrevented, false);
      assert.ok(Math.abs(reverse.editor - reverse.preview) < 0.02);
      await cdp.detach();
      await editor.getByRole('button', { name: '编辑', exact: true }).click();
      await textarea.fill(original);
      await page.screenshot({ path: path.join(out, 'desktop-editor.png'), fullPage: false });
      return { forward: ratios, reverse };
    },
  );
  await check(
    'safe insertion, IME and pending saves preserve article ownership and the draft',
    async () => {
      const textarea = page.getByRole('textbox', { name: 'Markdown 正文' });
      const dialog = page.locator('[data-article-editor]');
      await dialog.getByRole('button', { name: '链接', exact: true }).click();
      const insertion = page.getByRole('dialog', { name: '插入链接' });
      await insertion.getByLabel('链接文字', { exact: true }).fill('Fixture ] safe');
      await insertion.getByLabel('URL', { exact: true }).fill('javascript:alert(1)');
      assert.equal(
        await insertion.getByRole('button', { name: '插入', exact: true }).isDisabled(),
        true,
      );
      await insertion.getByLabel('URL', { exact: true }).fill('https://example.com/(fixture)');
      const before = (await currentState()).saves;
      await insertion.getByLabel('URL', { exact: true }).press('Control+s');
      assert.equal((await currentState()).saves, before);
      await insertion.getByRole('button', { name: '插入', exact: true }).click();
      assert.match(await textarea.inputValue(), /https:\/\/example\.com\/\%28fixture\%29/);
      await textarea.focus();
      await page.evaluate(() => {
        window.__fixtureImeKey = null;
        document.addEventListener('keydown', (event) => {
          if (event.key === 's')
            window.__fixtureImeKey = { composing: event.isComposing, trusted: event.isTrusted };
        });
      });
      const cdp = await context.newCDPSession(page);
      await cdp.send('Input.imeSetComposition', {
        text: '中文',
        selectionStart: 2,
        selectionEnd: 2,
      });
      await cdp.send('Input.dispatchKeyEvent', {
        type: 'keyDown',
        key: 's',
        code: 'KeyS',
        windowsVirtualKeyCode: 83,
        modifiers: 2,
      });
      await cdp.send('Input.dispatchKeyEvent', {
        type: 'keyUp',
        key: 's',
        code: 'KeyS',
        windowsVirtualKeyCode: 83,
        modifiers: 2,
      });
      const ime = await page.evaluate(() => window.__fixtureImeKey);
      assert.equal(ime?.composing, true);
      assert.equal(ime?.trusted, true);
      assert.equal((await currentState()).saves, before);
      await cdp.send('Input.imeSetComposition', { text: '', selectionStart: 0, selectionEnd: 0 });
      await cdp.detach();
      const draft = await textarea.inputValue();
      await state({ saveFailure: true, holdSave: true });
      try {
        const submitted = page.waitForRequest(
          (request) => request.method() === 'PUT' && request.url().includes('/api/admin/page/7'),
        );
        await dialog.getByRole('button', { name: '保存', exact: true }).click();
        await submitted;
        await page.getByRole('button', { name: '保存中…', exact: true }).waitFor();
        const held = (
          await (await context.request.get(`${api}/_fixture/await-save?count=${before + 1}`)).json()
        ).data;
        assert.equal(held.pendingSaves, 1);
        assert.equal(await textarea.isDisabled(), true);
        await textarea.press('Control+s');
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('[data-article-editor]').count(), 1);
        await state({ holdSave: false, releaseSaves: true });
        await dialog.getByRole('alert').filter({ hasText: 'Fixture failure' }).waitFor();
        assert.equal(await textarea.inputValue(), draft);
        assert.equal((await currentState()).saves, before + 1);
      } finally {
        await state({ saveFailure: false, holdSave: false, releaseSaves: true });
      }
      await dialog.getByRole('button', { name: '取消', exact: true }).click();
      const discard = page.getByRole('dialog', { name: /放弃/ });
      await discard.getByRole('button', { name: /放弃/ }).click();
      return { ime, saveRequests: 1, draftPreserved: true };
    },
  );
  await check(
    'nonsecret page navigation and TOC preserve Next history state and do not create horizontal overflow',
    async () => {
      await page.goto(`${base}/post/fixture-7?commentPage=2`, { waitUntil: 'networkidle' });
      assert.ok(await page.getByText('Fixture reader 51', { exact: true }).count());
      assert.equal(await page.getByText('Fixture reader 1', { exact: true }).count(), 0);
      const stateBefore = await page.evaluate(() => JSON.stringify(history.state));
      const toc = page.locator('a[href="#first-section"]');
      assert.ok(await toc.count(), 'The real article must expose its first-section TOC anchor');
      await toc.first().click();
      assert.equal(await page.evaluate(() => JSON.stringify(history.state)), stateBefore);
      const geometry = await page.evaluate(() => ({
        width: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
      }));
      assert.ok(geometry.scrollWidth <= geometry.width + 1);
      return geometry;
    },
  );
  await check('real computed reading and syntax-comment contrast meets WCAG AA', async () => {
    const samples = await page.evaluate(() => {
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 1;
      const ctx = canvas.getContext('2d');
      const rgba = (color) => {
        ctx.clearRect(0, 0, 1, 1);
        ctx.fillStyle = color;
        ctx.fillRect(0, 0, 1, 1);
        return [...ctx.getImageData(0, 0, 1, 1).data];
      };
      const merge = (front, back) => {
        const alpha = front[3] / 255;
        return front.slice(0, 3).map((channel, i) => channel * alpha + back[i] * (1 - alpha));
      };
      const luminance = (rgb) =>
        rgb
          .map((channel) => {
            const c = channel / 255;
            return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
          })
          .reduce((sum, value, i) => sum + value * [0.2126, 0.7152, 0.0722][i], 0);
      return [...document.querySelectorAll('.text-ink-faint, .markdown p, .markdown .hljs-comment')]
        .filter((element) => {
          const rect = element.getBoundingClientRect();
          return rect.width > 2 && rect.height > 2 && getComputedStyle(element).opacity === '1';
        })
        .map((element) => {
          const chain = [];
          for (let ancestor = element; ancestor; ancestor = ancestor.parentElement)
            chain.unshift(ancestor);
          let background = [255, 255, 255];
          for (const ancestor of chain)
            background = merge(rgba(getComputedStyle(ancestor).backgroundColor), background);
          const style = getComputedStyle(element),
            foreground = merge(rgba(style.color), background);
          const a = luminance(foreground),
            b = luminance(background);
          return {
            text: element.textContent.slice(0, 50),
            className: element.getAttribute('class') || '',
            color: style.color,
            background,
            ratio: (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05),
          };
        });
    });
    assert.ok(samples.length > 10);
    assert.ok(samples.some((sample) => sample.className.includes('hljs-comment')));
    for (const sample of samples)
      assert.ok(sample.ratio >= 4.5, `${sample.text}: contrast ${sample.ratio}`);
    return {
      count: samples.length,
      minimum: Math.min(...samples.map((sample) => sample.ratio)),
      samples,
    };
  });
  await check(
    'mobile reading uses real responsive geometry and accessible touch navigation',
    async () => {
      await page.setViewportSize({ width: 390, height: 844 });
      const cdp = await context.newCDPSession(page);
      try {
        await page.goto(`${base}/post/fixture-7`, { waitUntil: 'networkidle' });
        const sizes = await page.evaluate(() => ({
          width: document.documentElement.clientWidth,
          scrollWidth: document.documentElement.scrollWidth,
          bodyFont: getComputedStyle(document.querySelector('.markdown') || document.body).fontSize,
        }));
        assert.ok(sizes.scrollWidth <= sizes.width + 1);
        assert.ok(parseFloat(sizes.bodyFont) >= 14);
        await page.screenshot({ path: path.join(out, 'mobile-reading.png'), fullPage: true });
        await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
        const tap = async (locator) => {
          await locator.waitFor({ state: 'visible' });
          await locator.evaluate(async (element) => {
            const animations = new Set();
            for (let ancestor = element; ancestor; ancestor = ancestor.parentElement)
              for (const animation of ancestor.getAnimations())
                if (Number.isFinite(animation.effect?.getComputedTiming().endTime))
                  animations.add(animation);
            await Promise.all(
              [...animations].map((animation) => animation.finished.catch(() => {})),
            );
          });
          const rect = await locator.boundingBox();
          assert.ok(rect && rect.height >= 44);
          await cdp.send('Input.dispatchTouchEvent', {
            type: 'touchStart',
            touchPoints: [{ x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 }],
          });
          await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
          return rect;
        };
        await page.evaluate(() => {
          window.__fixtureTouch = null;
          document.addEventListener(
            'pointerdown',
            (event) => {
              window.__fixtureTouch = { type: event.pointerType, trusted: event.isTrusted };
            },
            { once: true },
          );
        });
        const trigger = await tap(page.getByRole('button', { name: '打开导航菜单', exact: true }));
        await page.getByRole('menu').waitFor();
        const target = await tap(page.getByRole('menuitem', { name: '分类', exact: true }));
        await page.waitForURL(`${base}/categories`);
        await page.getByRole('menu').waitFor({ state: 'detached' });
        assert.equal(await page.getByRole('menu').count(), 0);
        const touch = await page.evaluate(() => window.__fixtureTouch);
        assert.equal(touch?.type, 'touch');
        assert.equal(touch?.trusted, true);
        return { ...sizes, trigger, target, touch };
      } finally {
        await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: false });
        await cdp.detach();
        await page.setViewportSize({ width: 1440, height: 1000 });
      }
    },
  );
  await check(
    'code copy completes through the safe platform boundary and restores focus',
    async () => {
      await page.goto(`${base}/post/fixture-7`, { waitUntil: 'networkidle' });
      const copy = page.locator('.md-code-copy').first();
      await copy.click();
      await page.waitForFunction(() => Boolean(window.__fixtureCopied));
      assert.match(await page.evaluate(() => window.__fixtureCopied), /console\.log\("safe"\)/);
      assert.equal(await copy.getAttribute('data-copied'), 'true');
      await page.getByRole('button', { name: '编辑', exact: true }).click();
      const editor = page.locator('[data-article-editor]');
      await editor.getByRole('textbox', { name: 'Markdown 正文' }).waitFor();
      await editor.getByRole('button', { name: '分栏预览', exact: true }).click();
      await page.evaluate(() => {
        navigator.clipboard.writeText = async () => {
          throw new Error('Synthetic clipboard permission denial');
        };
        document.execCommand = () => {
          const active = document.activeElement;
          window.__fixtureFallback = {
            inDialog: Boolean(active.closest('[data-article-editor]')),
            text: active.value,
          };
          return true;
        };
      });
      const previewCopy = editor.locator('.md-code-copy').first();
      await previewCopy.focus();
      const selected = await editor
        .locator('.markdown p')
        .first()
        .evaluate((element) => {
          const selection = getSelection();
          selection.removeAllRanges();
          const range = document.createRange();
          range.selectNodeContents(element);
          selection.addRange(range);
          return selection.toString();
        });
      await previewCopy.press('Enter');
      await page.waitForFunction(() => Boolean(window.__fixtureFallback));
      const fallback = await page.evaluate(() => window.__fixtureFallback);
      assert.equal(fallback.inDialog, true);
      assert.match(fallback.text, /console\.log/);
      assert.equal(
        await previewCopy.evaluate((element) => document.activeElement === element),
        true,
      );
      assert.equal(await page.evaluate(() => getSelection().toString()), selected);
      await editor.getByRole('button', { name: '取消', exact: true }).click();
      let mobileGeometry;
      await page.setViewportSize({ width: 390, height: 844 });
      try {
        await page.getByRole('button', { name: '编辑', exact: true }).click();
        await editor.getByRole('textbox', { name: 'Markdown 正文' }).waitFor();
        await editor.getByRole('button', { name: '分栏预览', exact: true }).click();
        const body = await editor.locator('[aria-label="正文编辑"]').boundingBox();
        const properties = await editor.locator('[aria-label="发布属性"]').boundingBox();
        assert.ok(
          body && properties && properties.y >= body.y + body.height - 1,
          'Mobile publishing properties must not overlap or intercept the Markdown toolbar',
        );
        mobileGeometry = { body, properties };
        await editor.getByRole('button', { name: '取消', exact: true }).click();
      } finally {
        await page.setViewportSize({ width: 1440, height: 1000 });
      }
      return {
        platformBoundary: 'both clipboard paths stubbed; host clipboard unchanged',
        fallback,
        mobileGeometry,
      };
    },
  );
  await check('local logout clears both cookies even when backend revocation fails', async () => {
    await page.goto(`${base}/post/fixture-7`, { waitUntil: 'networkidle' });
    await state({ logoutFailure: true });
    await page.getByRole('button', { name: /Fixture editor|用户菜单/ }).click();
    await page.getByRole('menuitem', { name: /退出|登出/ }).click();
    await page.waitForFunction(() =>
      Boolean(
        Array.from(document.querySelectorAll('button')).find((button) =>
          button.textContent.includes('登录'),
        ),
      ),
    );
    const cookies = await context.cookies(base);
    assert.ok(cookies.every((cookie) => !['satoken', 'astral_csrf'].includes(cookie.name)));
    await state({ logoutFailure: false });
  });
} finally {
  const finalManifest = manifestModule.authoredManifest(root);
  const finalDifferences = [
    ...new Set([...Object.keys(build.authoredManifest), ...Object.keys(finalManifest)]),
  ].filter((name) => build.authoredManifest[name] !== finalManifest[name]);
  const driverStable =
    createHash('sha256')
      .update(fs.readFileSync(fileURLToPath(import.meta.url)))
      .digest('hex') === driverHash;
  const backendStable =
    createHash('sha256')
      .update(fs.readFileSync(path.join(root, 'tests/fixture-api.cjs')))
      .digest('hex') === backendHash;
  const missingChecks = requiredChecks.filter(
    (name) => !results.some((result) => result.name === name && result.pass),
  );
  const fixtureRequests = (await currentState()).requests;
  const systemHeadersComplete =
    fixtureRequests.length > 0 && fixtureRequests.every((request) => request.systemHeaders);
  const receipt = {
    accepted:
      !diagnostic &&
      differences.length === 0 &&
      finalDifferences.length === 0 &&
      driverStable &&
      backendStable &&
      missingChecks.length === 0 &&
      results.length === requiredChecks.length &&
      results.every((result) => result.pass) &&
      errors.length === 0 &&
      externalRequests.length === 0 &&
      systemHeadersComplete,
    missingChecks,
    systemHeadersComplete,
    driverHash,
    backendHash,
    diagnostic,
    sourceDifferences: [...new Set([...differences, ...finalDifferences])],
    driverStable,
    backendStable,
    fixture,
    url: base,
    node: process.version,
    browser: context.browser().version(),
    backend: 'synthetic loopback fixture only',
    clipboard: 'external platform boundary stub; no host clipboard write',
    results,
    pageErrors: errors,
    blockedExternalRequests: externalRequests,
  };
  const receiptPath = path.join(out, 'browser-receipt.json');
  fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + '\n');
  console.log(`BROWSER_RECEIPT=${receiptPath}`);
  console.log(`ACCEPTED=${receipt.accepted}`);
  await context.close();
  if (results.some((result) => !result.pass) || (!diagnostic && !receipt.accepted))
    process.exitCode = 1;
}
