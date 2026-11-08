import { safeImageUrl, safeUrl } from '@/lib/url';

/** Build one literal Markdown label/destination, not additional Markdown syntax. */
export function formatMarkdownLink(label: string, rawUrl: string, image = false): string | null {
  const url = image ? safeImageUrl(rawUrl.trim()) : safeUrl(rawUrl.trim());
  if (!url) return null;
  const text = (label || (image ? '图片' : url))
    .replace(/[\r\n]+/g, ' ')
    .replace(/[\\[\]`*_<>]/g, '\\$&');
  const destination = url.replace(
    /[<>()]/g,
    (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`,
  );
  return `${image ? '!' : ''}[${text}](<${destination}>)`;
}
