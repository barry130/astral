/**
 * 统一客户端系统头（管理台 = web 端）
 *
 * 凡请求 astral 后端的调用都必须携带下面 4 个头，服务端两条链路共用同一份：
 * 接口统计（ApiRequestMetricInterceptor → stat_api_hourly）与
 * 反馈提交（AppFeedbackController.submit → sys_feedback）。
 *
 * 契约的权威定义在后端 `astral-common` 的 `com.astral.common.util.ClientHeaders`；
 * 另两个端点的实现在 qt-uniappx `services/client-info.ts`、qt-pc `src-tauri/src/astral.rs`。
 * 改这里必须同步改那三处（含 stat_platform 字典）。
 *
 * | 头名            | 管理台的取值                                  |
 * | --------------- | -------------------------------------------- |
 * | `X-App-Ut`      | 固定 `web`                                    |
 * | `X-App-Version` | package.json 的 version（构建期注入）          |
 * | `X-Device`      | 浏览器及大版本，如 `Chrome 131`                |
 * | `X-OS`          | 操作系统及版本，如 `Windows 10/11`、`macOS 14`  |
 *
 * 注意：只有走本 axios 实例的 `/api/**` 请求（rewrite 到 astral）才带头。
 * 对象存储预签名直传用的是原生 fetch，绝不能加这些头——会破坏签名校验。
 */

/** 平台头名（值固定 `web`） */
export const HEADER_UT = 'X-App-Ut';
/** 版本头名 */
export const HEADER_VERSION = 'X-App-Version';
/** 设备头名 */
export const HEADER_DEVICE = 'X-Device';
/** 系统头名 */
export const HEADER_OS = 'X-OS';

/** 管理台的平台标识（与后端 stat_platform 字典同源） */
export const CLIENT_UT_WEB = 'web';

/** 设备字段长度上限（与 sys_feedback.device 列宽一致） */
const MAX_DEVICE = 128;
/** 系统字段长度上限（与 sys_feedback.os 列宽一致） */
const MAX_OS = 64;

/** 截断到上限（按码点，别截出半个代理对） */
function clip(value: string, max: number): string {
  const chars = Array.from(value);
  return chars.length > max ? chars.slice(0, max).join('') : value;
}

/** 浏览器及大版本号。顺序有讲究：Edge 的 UA 里含 Chrome，Opera 含 OPR 而非 Opera。 */
function detectBrowser(ua: string): string {
  const patterns: Array<[RegExp, string]> = [
    [/Edg(?:e|A|iOS)?\/(\d+)/, 'Edge'],
    [/OPR\/(\d+)/, 'Opera'],
    [/Firefox\/(\d+)/, 'Firefox'],
    [/Chrome\/(\d+)/, 'Chrome'],
    [/Version\/(\d+)[.\d]* Safari/, 'Safari'],
  ];
  for (const [pattern, name] of patterns) {
    const matched = pattern.exec(ua);
    if (matched) return `${name} ${matched[1]}`;
  }
  return '';
}

/** 操作系统及版本。顺序有讲究：Android UA 含 Linux，iOS UA 含「like Mac OS X」。 */
function detectOs(ua: string): string {
  if (/Windows NT 10\.0/.test(ua)) return 'Windows 10/11';
  if (/Windows NT 6\.3/.test(ua)) return 'Windows 8.1';
  if (/Windows NT 6\.1/.test(ua)) return 'Windows 7';
  if (/Windows/.test(ua)) return 'Windows';
  const android = /Android (\d+(?:\.\d+)?)/.exec(ua);
  if (android) return `Android ${android[1]}`;
  if (/Android/.test(ua)) return 'Android';
  if (/iPhone|iPad|iPod/.test(ua)) {
    const ios = /OS (\d+)[._](\d+)/.exec(ua);
    return ios ? `iOS ${ios[1]}.${ios[2]}` : 'iOS';
  }
  const mac = /Mac OS X (\d+)[._](\d+)/.exec(ua);
  if (mac) return `macOS ${mac[1]}.${mac[2]}`;
  if (/Linux/.test(ua)) return 'Linux';
  return '';
}

/**
 * 组装统一客户端系统头。服务端渲染阶段拿不到 navigator，
 * 此时只发平台与版本（其余留空表示「未携带」）。
 */
export function buildClientHeaders(): Record<string, string> {
  const headers: Record<string, string> = { [HEADER_UT]: CLIENT_UT_WEB };

  const version = process.env.NEXT_PUBLIC_CLIENT_VERSION ?? '';
  if (version) headers[HEADER_VERSION] = version;

  if (typeof navigator !== 'undefined') {
    const ua = navigator.userAgent ?? '';
    const browser = detectBrowser(ua);
    if (browser) headers[HEADER_DEVICE] = clip(browser, MAX_DEVICE);
    const os = detectOs(ua);
    if (os) headers[HEADER_OS] = clip(os, MAX_OS);
  }
  return headers;
}

/** 进程内只算一次：UA 与版本在运行期不变 */
let cachedHeaders: Record<string, string> | null = null;

/** 取（并缓存）统一客户端系统头 */
export function clientHeaders(): Record<string, string> {
  if (cachedHeaders === null) {
    cachedHeaders = buildClientHeaders();
  }
  return cachedHeaders;
}
