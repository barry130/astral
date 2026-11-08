// 链接生成：公开侧 slug 解析规则为「纯数字按 ID，否则按 slug」（docs/CMS_PLUGIN.md），
// 因此无 slug 时直接用数字 ID，后端都能解析。

type Sluggable = { id: number; slug: string | null };

/**
 * 路径段统一做 encode：slug 可能包含空格、`#`、`?`、中文等字符，
 * 直接拼进 href 会被浏览器当成查询/片段分隔符，导致详情页打不开。
 * 数字 id 编码后保持原样；后端返回的 slug 应为原始文本，不应预先编码。
 */
function segment(item: Sluggable): string {
  const slug = item.slug?.trim();
  try {
    return encodeURIComponent(slug || String(item.id));
  } catch {
    return String(item.id);
  }
}

export function postHref(post: Sluggable): string {
  return `/post/${segment(post)}`;
}

export function categoryHref(category: Sluggable): string {
  return `/category/${segment(category)}`;
}

export function tagHref(tag: Sluggable): string {
  return `/tag/${segment(tag)}`;
}

export function wikiHref(page: Sluggable): string {
  return `/wiki/${segment(page)}`;
}

/**
 * 解码路由参数。恶意或损坏的 `%` 序列会让 decodeURIComponent 抛 URIError，
 * 这里退回原字符串，交给后端按合法 slug 查不到即可，避免整页 500。
 */
export function safeDecodeURIComponent(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}
