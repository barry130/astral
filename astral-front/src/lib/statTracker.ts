/**
 * 轻量 Web 端统计埋点（STATS_DESIGN.md §7.2 对齐）
 *
 * 生成匿名设备ID，采集 launcher / show / hide / page 事件，
 * 攒批 10s 或满 50 条上报；hide 时持久化队列防丢。
 *
 * 接口：POST /api/v1/stat/report（匿名、免登录）
 */
'use client';

/** 匿名设备ID 的 localStorage key */
const DEVICE_ID_KEY = 'qt_stat_device_id';
/** 持久化队列 key */
const QUEUE_KEY = 'qt_stat_queue';

/** 攒批间隔（毫秒） */
const FLUSH_INTERVAL = 10_000;
/** 单批上限 */
const BATCH_LIMIT = 50;

/* ---------- 事件类型 ---------- */

export interface StatEvent {
  evt: string;
  ts: number;
  deviceId: string;
  ut: string;
  appVersion: string;
  os?: string;
  model?: string;
  page?: string;
  duration?: number;
  errorType?: string;
  message?: string;
  stack?: string;
  extra?: Record<string, unknown>;
}

/* ---------- 内部状态 ---------- */

let deviceId = '';
let lastShowTime = 0;
let pendingEvents: StatEvent[] = [];
let flushTimer: ReturnType<typeof setInterval> | null = null;
let initialized = false;

/* ---------- 工具函数 ---------- */

function uuid(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  // fallback
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function getOrCreateDeviceId(): string {
  if (typeof window === 'undefined') return '';
  let id = localStorage.getItem(DEVICE_ID_KEY);
  if (!id) {
    id = uuid();
    localStorage.setItem(DEVICE_ID_KEY, id);
  }
  return id;
}

function getAppVersion(): string {
  // 优先从 meta 标签读取，兜底取 package.json 版本或硬编码
  if (typeof document === 'undefined') return '1.0.0';
  const meta = document.querySelector('meta[name="app-version"]');
  if (meta) return meta.getAttribute('content') || '1.0.0';
  // Next.js 构建时注入版本号可放在 __NEXT_DATA__ 或全局变量
  return '1.0.0';
}

/** 内部缓存：解析后的精操作系统与浏览器，供各事件复用 */
let currentOs = 'Unknown';
let currentModel = 'Unknown';

/**
 * 检测操作系统分类（同步快速路径）：Windows / macOS / Linux / Android / iOS / Unknown
 */
function detectOsBasic(): string {
  if (typeof navigator === 'undefined') return 'Unknown';
  const ua = navigator.userAgent || '';
  const plat = (navigator as unknown as { userAgentData?: { platform?: string } }).userAgentData?.platform || navigator.platform || '';
  if (/Android/i.test(ua)) return 'Android';
  if (/iPhone|iPad|iPod/i.test(ua)) return 'iOS';
  if (/Mac|Macintosh|Mac OS X/i.test(plat) || /Mac OS X/i.test(ua)) return 'macOS';
  if (/Win/i.test(plat)) return 'Windows';
  if (/Linux/i.test(plat)) return 'Linux';
  return 'Unknown';
}

/**
 * 精化 Windows 版本：Windows 10 / Windows 11 / Windows
 * <p>
 * UA 字符串中 Win10 与 Win11 均为 "Windows NT 10.0"，无法区分。
 * 唯一可靠途径是 Chromium 内核浏览器（Chrome/Edge）的
 * navigator.userAgentData.getHighEntropyValues(['platformVersion'])：
 * 返回 platformVersion 13.x → Windows 11，10.x~12.x → Windows 10。
 * Firefox/Safari 不支持该 API，回退为 "Windows"。
 * </p>
 */
async function detectOs(): Promise<string> {
  const base = detectOsBasic();
  if (base !== 'Windows') return base;
  const uad = (navigator as unknown as {
    userAgentData?: { getHighEntropyValues?: (hints: string[]) => Promise<{ platformVersion?: string }> };
  }).userAgentData;
  if (uad && typeof uad.getHighEntropyValues === 'function') {
    try {
      const { platformVersion } = await uad.getHighEntropyValues(['platformVersion']);
      const major = parseInt(String(platformVersion || '').split('.')[0], 10);
      if (!Number.isNaN(major)) {
        if (major >= 13) return 'Windows 11';
        if (major >= 10) return 'Windows 10';
      }
    } catch {
      // 权限/异常：回退为 Windows
    }
  }
  return 'Windows';
}

/** 检测浏览器型号：Chrome / Edge / Firefox / Safari / Unknown */
function detectBrowser(): string {
  if (typeof navigator === 'undefined') return 'Unknown';
  const ua = navigator.userAgent || '';
  const match = (re: RegExp): string | null => {
    const m = ua.match(re);
    return m ? m[0] : null;
  };
  // Edge 必须优先于 Chrome 判断（Edge UA 同时含 Chrome）
  const edge = match(/Edg\/[\d.]+/i);
  if (edge) return 'Edge ' + edge.replace('Edg/', '');
  const firefox = match(/Firefox\/[\d.]+/i);
  if (firefox) return 'Firefox ' + firefox.replace('Firefox/', '');
  const chrome = match(/Chrome\/[\d.]+/i);
  if (chrome) return 'Chrome ' + chrome.replace('Chrome/', '');
  const safari = match(/Safari\/[\d.]+/i);
  if (safari) return 'Safari ' + safari.replace('Safari/', '');
  return 'Unknown';
}

/** 从 localStorage 恢复未发送的队列 */
function restoreQueue(): StatEvent[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = localStorage.getItem(QUEUE_KEY);
    if (raw) {
      localStorage.removeItem(QUEUE_KEY);
      return JSON.parse(raw) as StatEvent[];
    }
  } catch {
    // ignore
  }
  return [];
}

/** 持久化队列到 localStorage */
function persistQueue() {
  if (typeof window === 'undefined') return;
  try {
    localStorage.setItem(QUEUE_KEY, JSON.stringify(pendingEvents));
  } catch {
    // localStorage 可能满（单条事件约 200B，50 条 ~10KB，远低于 5MB 限制）
  }
}

/** 发送一批事件 */
async function flush() {
  if (pendingEvents.length === 0) return;
  const batch = pendingEvents.splice(0, BATCH_LIMIT);
  try {
    await fetch('/api/v1/stat/report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ events: batch }),
    });
  } catch {
    // 发送失败→放回队列末尾（重试上限由服务端/客户端共同兜底）
    pendingEvents.unshift(...batch);
  }
}

/** 将事件加入队列（攒批） */
function enqueue(event: StatEvent) {
  pendingEvents.push(event);
  if (pendingEvents.length >= BATCH_LIMIT) {
    flush();
  }
}

/* ---------- 公开 API ---------- */

/**
 * 初始化埋点（仅客户端执行一次）
 * - 生成/复用匿名设备ID
 * - 注册 visibilitychange 监听（show/hide）
 * - 启动定时 flush
 * - 上报 launcher 事件
 * - 恢复并发送历史队列
 */
export function initStatTracker() {
  if (initialized || typeof window === 'undefined') return;
  initialized = true;

  deviceId = getOrCreateDeviceId();

  const ut = 'web';
  const appVersion = getAppVersion();

  // 恢复历史队列优先发送
  const restored = restoreQueue();
  if (restored.length > 0) {
    pendingEvents.push(...restored);
  }

  // OS（含 Windows 版本精化）与浏览器型号为异步获取，解析完成后统一构造 launcher/show，
  // 缓存到 currentOs/currentModel 供后续事件复用。
  detectOs().then((os) => {
    currentOs = os;
    currentModel = detectBrowser();

    // launcher：首次加载
    enqueue({
      evt: 'launcher',
      ts: Date.now(),
      deviceId,
      ut,
      appVersion,
      os,
      model: currentModel,
      extra: {
        referrer: document.referrer || undefined,
        screen: `${screen.width}x${screen.height}`,
        lang: navigator.language,
      },
    });

    // show：当前 tab 可见
    lastShowTime = Date.now();
    enqueue({ evt: 'show', ts: Date.now(), deviceId, ut, appVersion, os, model: currentModel });
  });

  // visibilitychange：show / hide（带停留时长）
  const onVisibility = () => {
    const now = Date.now();
    const os = currentOs;
    const model = currentModel;
    if (document.hidden) {
      // hide
      enqueue({
        evt: 'hide',
        ts: now,
        deviceId,
        ut,
        appVersion,
        os,
        model,
        duration: now - lastShowTime,
      });
      persistQueue(); // persist before sending
    } else {
      // show
      lastShowTime = now;
      enqueue({ evt: 'show', ts: now, deviceId, ut, appVersion, os, model });
    }
  };
  document.addEventListener('visibilitychange', onVisibility);

  // 定时 flush
  flushTimer = setInterval(flush, FLUSH_INTERVAL);

  // 页面关闭前尝试发送剩余队列
  const onBeforeUnload = () => {
    // 用 sendBeacon 异步发送，不阻塞页面关闭
    if (pendingEvents.length > 0 && navigator.sendBeacon) {
      const blob = new Blob([JSON.stringify({ events: pendingEvents.splice(0) })], {
        type: 'application/json',
      });
      navigator.sendBeacon('/api/v1/stat/report', blob);
    }
  };
  window.addEventListener('beforeunload', onBeforeUnload);

  // 立即 flush 一次初始事件
  setTimeout(flush, 2000);
}

/**
 * 手动上报页面浏览事件（由 layout 在路由变化时调用）
 */
export function trackPage(page: string) {
  if (!deviceId) {
    deviceId = getOrCreateDeviceId();
  }
  // 复用已解析的 OS（含 Windows 版本精化）与浏览器型号，保证同设备一致
  const os = currentOs !== 'Unknown' ? currentOs : detectOsBasic();
  const model = currentModel !== 'Unknown' ? currentModel : detectBrowser();
  enqueue({
    evt: 'page',
    ts: Date.now(),
    deviceId,
    ut: 'web',
    appVersion: getAppVersion(),
    os,
    model,
    page,
  });
}