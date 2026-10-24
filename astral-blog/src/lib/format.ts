// 后端时间字段为 LocalDateTime 序列化（无时区）："2026-10-22T15:30:00" 或 "2026-10-22 15:30:00"。
// 展示时按字面量截取，不做时区换算，避免服务器时区差异导致日期偏移。

/** "2026-10-22T15:30:00" -> "2026-10-22"；解析失败时原样返回 */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '';
  const s = value.replace('T', ' ');
  return /^\d{4}-\d{2}-\d{2}/.test(s) ? s.slice(0, 10) : s;
}

/** "2026-10-22T15:30:41" -> "2026-10-22 15:30"；解析失败时原样返回 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '';
  const s = value.replace('T', ' ');
  return /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(s) ? s.slice(0, 16) : s;
}

/** 浏览量等数字的展示兜底 */
export function formatCount(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '0';
}

/**
 * 粗略估算阅读时长（分钟）：中文按 350 字/分钟，西文按 200 词/分钟。
 * 只在文章详情展示，用于给出「读完要多久」的预期，不追求精确。
 */
export function estimateReadingMinutes(content: string | null | undefined): number {
  if (!content) return 1;
  // 去掉代码块，代码通常扫读而不是逐行阅读
  const text = content.replace(/```[\s\S]*?```/g, ' ');
  const cjk = (text.match(/[\u4e00-\u9fff]/g) ?? []).length;
  const words = (text.replace(/[\u4e00-\u9fff]/g, ' ').match(/[A-Za-z0-9]+/g) ?? []).length;
  return Math.max(1, Math.round(cjk / 350 + words / 200));
}

