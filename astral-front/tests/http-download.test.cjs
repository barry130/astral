const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

// Exercise the real exported helper. Only browser-owned DOM/URL/clock APIs are
// replaced; these handoff assertions are not a real-browser download receipt.
function installBrowserBoundary(t) {
  const documentDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'document');
  const createObjectURL = URL.createObjectURL;
  const revokeObjectURL = URL.revokeObjectURL;
  const files = [];
  const clicks = [];
  const revoked = [];
  const anchors = [];
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: {
      createElement(tag) {
        assert.equal(tag, 'a');
        const anchor = {
          href: '',
          download: '',
          hidden: false,
          isConnected: false,
          click() {
            clicks.push({ href: this.href, filename: this.download, connected: this.isConnected });
          },
          remove() {
            this.isConnected = false;
          },
        };
        anchors.push(anchor);
        return anchor;
      },
      body: {
        appendChild(anchor) {
          anchor.isConnected = true;
          return anchor;
        },
      },
    },
  });
  URL.createObjectURL = (blob) => {
    files.push(blob);
    return 'blob:https://audit.invalid/owned-export';
  };
  URL.revokeObjectURL = (url) => revoked.push(url);
  t.mock.timers.enable({ apis: ['setTimeout'] });
  t.after(() => {
    URL.createObjectURL = createObjectURL;
    URL.revokeObjectURL = revokeObjectURL;
    if (documentDescriptor) Object.defineProperty(globalThis, 'document', documentDescriptor);
    else delete globalThis.document;
  });
  return { files, clicks, revoked, anchors };
}

test('file handoff keeps its object URL live until a later task and removes the temporary anchor', async (t) => {
  const browser = installBrowserBoundary(t);
  const { downloadResponse } = createSourceLoader()('lib/http-download.ts');
  const response = new Response(new Blob(['id,username\n41,audit-user\n']), {
    headers: { 'Content-Type': 'text/csv; charset=UTF-8' },
  });

  await downloadResponse(response, 'users.csv');

  assert.deepEqual(
    browser.revoked,
    [],
    'the click must not be followed by synchronous URL revocation',
  );
  assert.equal(browser.files.length, 1);
  assert.equal(await browser.files[0].text(), 'id,username\n41,audit-user\n');
  assert.deepEqual(browser.clicks, [
    {
      href: 'blob:https://audit.invalid/owned-export',
      filename: 'users.csv',
      connected: true,
    },
  ]);
  assert.equal(browser.anchors[0].isConnected, false);
  t.mock.timers.runAll();
  assert.deepEqual(browser.revoked, ['blob:https://audit.invalid/owned-export']);
});

test('the owned object URL is released even when the browser cannot attach the handoff anchor', async (t) => {
  const browser = installBrowserBoundary(t);
  const { downloadResponse } = createSourceLoader()('lib/http-download.ts');
  const failure = new Error('页面已卸载，不能附加下载节点');
  document.body.appendChild = () => {
    throw failure;
  };

  await assert.rejects(
    downloadResponse(new Response(new Blob(['id\n41\n'])), 'users.csv'),
    failure,
  );

  assert.deepEqual(browser.clicks, []);
  assert.deepEqual(browser.revoked, [], 'cleanup must keep the same deferred URL lifetime');
  t.mock.timers.runAll();
  assert.deepEqual(browser.revoked, ['blob:https://audit.invalid/owned-export']);
});
