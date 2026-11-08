/** 前端外观偏好：只保存用户的显式选择，系统默认不写入持久化存储。 */
export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'astral:theme';
const SYSTEM_QUERY = '(prefers-color-scheme: dark)';
const listeners = new Set<() => void>();
let inMemoryPreference: ThemeMode | null = null;
let disconnect: (() => void) | undefined;

const isThemeMode = (value: string | null): value is ThemeMode =>
  value === 'light' || value === 'dark';

export const getServerThemeSnapshot = (): ThemeMode => 'light';

export function getThemeSnapshot(): ThemeMode {
  if (typeof window === 'undefined') return getServerThemeSnapshot();
  if (inMemoryPreference) return inMemoryPreference;
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (isThemeMode(stored)) return stored;
  } catch {
    // 隐私存储不可读时仍跟随系统，而不是强制浅色。
  }
  try {
    return window.matchMedia(SYSTEM_QUERY).matches ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

const notify = (): void => listeners.forEach((listener) => listener());

export function setThemePreference(mode: ThemeMode): void {
  if (typeof window === 'undefined') return;
  inMemoryPreference = mode;
  try {
    window.localStorage.setItem(STORAGE_KEY, mode);
  } catch {
    // 即使写入失败，本页内的显式选择仍然有效。
  }
  notify();
}

export function subscribeThemePreference(listener: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  listeners.add(listener);
  if (listeners.size === 1) {
    const browserWindow = window;
    const media = browserWindow.matchMedia(SYSTEM_QUERY);
    const onStorage = (event: StorageEvent): void => {
      if (event.key === STORAGE_KEY || event.key === null) {
        inMemoryPreference = null;
        notify();
      }
    };
    media.addEventListener('change', notify);
    browserWindow.addEventListener('storage', onStorage);
    disconnect = () => {
      media.removeEventListener('change', notify);
      browserWindow.removeEventListener('storage', onStorage);
    };
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      disconnect?.();
      disconnect = undefined;
    }
  };
}

/** 与水合快照配套的首帧防闪脚本；受限localStorage不妨碍系统主题。 */
export const THEME_BOOTSTRAP_SCRIPT = `(function(){var t;try{t=localStorage.getItem('astral:theme');}catch(e){}if(t!=='light'&&t!=='dark'){try{t=window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';}catch(e){t='light';}}var r=document.documentElement;r.setAttribute('data-theme',t);r.classList.toggle('dark',t==='dark');})();`;
