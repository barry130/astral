/**
 * 日期时间格式化。
 *
 * 后台表格里大量直接写 `new Date(v).toLocaleString()`：它跟随浏览器默认区域，
 * 同一个页面在不同机器上可能显示 `2026/10/24 18:13:57`、`24/10/2026, 18:13:57`
 * 甚至 `2026年10月24日 18:13:57`，列宽抖动且和其余中文界面不统一。
 * 这里固定成 `YYYY-MM-DD HH:mm:ss`（与后端返回、CMS 管理端的 formatDateTime 一致），
 * 让所有列表时间列长得一样、可排序、可按列对齐。
 */

function pad(value: number, length = 2): string {
  return String(value).padStart(length, '0');
}

/** 解析后端时间：兼容 `2026-10-24 18:13:57`、ISO 串与毫秒数。 */
export function parseTime(value?: string | number | null): Date | null {
  if (value === null || value === undefined || value === '') return null;
  // 后端给的是无时区的 `YYYY-MM-DD HH:mm:ss`，Safari 不认这个格式，先换成 ISO 风格
  const normalized = typeof value === 'string' ? value.trim().replace(' ', 'T') : value;
  const date = new Date(normalized);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** `2026-10-24 18:13:57`；无法解析时返回占位符。 */
export function formatDateTime(value?: string | number | null, fallback = '-'): string {
  const date = parseTime(value);
  if (!date) return fallback;
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/** `2026-10-24`；无法解析时返回占位符。 */
export function formatDate(value?: string | number | null, fallback = '-'): string {
  const date = parseTime(value);
  if (!date) return fallback;
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** 相对时间（`3 分钟前`），用于「最近登录」这类需要一眼看出新旧的列。 */
export function formatRelativeTime(value?: string | number | null, fallback = '-'): string {
  const date = parseTime(value);
  if (!date) return fallback;
  const diffMs = Date.now() - date.getTime();
  const abs = Math.abs(diffMs);
  const minute = 60_000;
  const hour = 60 * minute;
  const day = 24 * hour;
  const suffix = diffMs >= 0 ? '前' : '后';
  if (abs < minute) return '刚刚';
  if (abs < hour) return `${Math.floor(abs / minute)} 分钟${suffix}`;
  if (abs < day) return `${Math.floor(abs / hour)} 小时${suffix}`;
  if (abs < 30 * day) return `${Math.floor(abs / day)} 天${suffix}`;
  return formatDate(value, fallback);
}
