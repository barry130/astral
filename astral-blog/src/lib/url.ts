/** URL validation shared by Markdown, asset rendering and the editor. */
const SAFE_PROTOCOLS = new Set(['http:', 'https:', 'mailto:', 'tel:']);
const RELATIVE_BASE = 'https://relative.invalid/';

export function isSafeUrl(raw: string): boolean {
  if (typeof raw !== 'string') return false;
  const url = raw.trim();
  // Browsers normalize controls and backslashes differently from authors and
  // parsers. Reject them rather than returning an ambiguously normalized URL.
  if (
    !url ||
    Array.from(url).some(
      (char) => char.charCodeAt(0) <= 32 || char.charCodeAt(0) === 127 || char === '\\',
    )
  )
    return false;
  try {
    const parsed = new URL(url, RELATIVE_BASE);
    if (!SAFE_PROTOCOLS.has(parsed.protocol) || parsed.username || parsed.password) return false;
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:')
      return Boolean(parsed.hostname);
    return parsed.pathname.length > 0;
  } catch {
    return false;
  }
}

export function safeUrl(raw: string): string | null {
  return isSafeUrl(raw) ? raw.trim() : null;
}

/** Images and cover metadata cannot use mailto/tel or executable/data schemes. */
export function safeImageUrl(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string') return null;
  const url = safeUrl(raw);
  if (!url) return null;
  const parsed = new URL(url, RELATIVE_BASE);
  return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? url : null;
}

export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
