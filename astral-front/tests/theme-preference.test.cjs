const assert = require('node:assert/strict');
const { test, afterEach } = require('node:test');
const { createElement } = require('react');
const { renderToString } = require('react-dom/server');
const { createSourceLoader } = require('./helpers/load-typescript.cjs');

const originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
afterEach(() => {
  if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow);
  else delete globalThis.window;
});

function browser(initial = null, dark = false, storageUnavailable = false) {
  const entries = new Map(initial === null ? [] : [['astral:theme', initial]]);
  const mediaListeners = new Set();
  const storageListeners = new Set();
  const mq = {
    matches: dark,
    addEventListener: (_type, cb) => mediaListeners.add(cb),
    removeEventListener: (_type, cb) => mediaListeners.delete(cb),
  };
  const localStorage = {
    getItem: (key) => {
      if (storageUnavailable) throw new Error('storage unavailable');
      return entries.get(key) ?? null;
    },
    setItem: (key, value) => {
      if (storageUnavailable) throw new Error('storage unavailable');
      entries.set(key, value);
    },
  };
  const value = {
    localStorage,
    matchMedia: () => mq,
    addEventListener: (_type, cb) => storageListeners.add(cb),
    removeEventListener: (_type, cb) => storageListeners.delete(cb),
  };
  Object.defineProperty(globalThis, 'window', { configurable: true, value });
  return {
    entries,
    mediaListeners,
    storageListeners,
    changeSystem: (dark) => {
      mq.matches = dark;
      for (const cb of mediaListeners) cb();
    },
    changeStorage: (value) => {
      if (value === null) entries.delete('astral:theme');
      else entries.set('astral:theme', value);
      for (const cb of storageListeners) cb({ key: 'astral:theme' });
    },
  };
}

test('system theme changes do not silently turn into a saved user preference', () => {
  const env = browser();
  const theme = createSourceLoader()('lib/theme-preference.ts');
  const observed = [];
  const unsubscribe = theme.subscribeThemePreference(() => observed.push(theme.getThemeSnapshot()));
  assert.equal(theme.getThemeSnapshot(), 'light');
  env.changeSystem(true);
  assert.deepEqual(observed, ['dark']);
  assert.equal(env.entries.size, 0);
  unsubscribe();
});

test('unavailable storage still follows the system until a user makes an explicit choice', () => {
  const env = browser(null, true, true);
  const theme = createSourceLoader()('lib/theme-preference.ts');
  const unsubscribe = theme.subscribeThemePreference(() => {});
  assert.equal(theme.getThemeSnapshot(), 'dark');
  theme.setThemePreference('light');
  env.changeSystem(false);
  env.changeSystem(true);
  assert.equal(theme.getThemeSnapshot(), 'light');
  unsubscribe();
});

test('preferences synchronize across tabs and removing them restores system following', () => {
  const env = browser('dark', false);
  const theme = createSourceLoader()('lib/theme-preference.ts');
  const unsubscribe = theme.subscribeThemePreference(() => {});
  assert.equal(theme.getThemeSnapshot(), 'dark');
  theme.setThemePreference('light');
  assert.equal(env.entries.get('astral:theme'), 'light');
  env.changeStorage('dark');
  assert.equal(theme.getThemeSnapshot(), 'dark');
  env.changeStorage(null);
  assert.equal(theme.getThemeSnapshot(), 'light');
  env.changeSystem(true);
  assert.equal(theme.getThemeSnapshot(), 'dark');
  unsubscribe();
});

test('the last unsubscribe removes shared native media and storage listeners', () => {
  const env = browser();
  const theme = createSourceLoader()('lib/theme-preference.ts');
  const first = theme.subscribeThemePreference(() => {});
  const second = theme.subscribeThemePreference(() => {});
  assert.equal(env.mediaListeners.size, 1);
  assert.equal(env.storageListeners.size, 1);
  first();
  assert.equal(env.mediaListeners.size, 1);
  second();
  assert.equal(env.mediaListeners.size, 0);
  assert.equal(env.storageListeners.size, 0);
});

test('server-rendered theme consumers use a stable hydration snapshot, not browser preferences', () => {
  browser('dark', true);
  const { Providers, useTheme } = createSourceLoader()('app/providers.tsx');
  function Probe() {
    return createElement('output', null, useTheme().mode);
  }
  const html = renderToString(createElement(Providers, null, createElement(Probe)));
  assert.match(html, /<output>light<\/output>/);
});
