// 链接生成：公开侧 slug 解析规则为「纯数字按 ID，否则按 slug」（docs/CMS_PLUGIN.md），
// 因此无 slug 时直接用数字 ID，后端都能解析。

type Sluggable = { id: number; slug: string | null };

export function postHref(post: Sluggable): string {
  return `/post/${post.slug ?? post.id}`;
}

export function categoryHref(category: Sluggable): string {
  return `/category/${category.slug ?? category.id}`;
}

export function tagHref(tag: Sluggable): string {
  return `/tag/${tag.slug ?? tag.id}`;
}

export function wikiHref(page: Sluggable): string {
  return `/wiki/${page.slug ?? page.id}`;
}
