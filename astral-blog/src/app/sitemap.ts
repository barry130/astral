import type { MetadataRoute } from 'next';
import { getPostPage, getWikiTree, type WikiNode } from '@/lib/cms';
import { postHref, wikiHref } from '@/lib/links';
import { getSiteUrl } from '@/lib/site-url';

export const dynamic = 'force-dynamic';
const MAX_URLS = 50_000; // Sitemap protocol limit; never silently truncate at 20 pages.

function flattenWiki(nodes: WikiNode[]): WikiNode[] {
  return nodes.flatMap((node) => [node, ...flattenWiki(node.children ?? [])]);
}
function validDate(value?: string | null): string | undefined {
  if (!value) return undefined;
  const date = new Date(value.replace(' ', 'T'));
  return Number.isFinite(date.getTime()) ? date.toISOString() : undefined;
}

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const site = getSiteUrl();
  const [first, wiki] = await Promise.all([
    getPostPage({ pageNum: 1, pageSize: 100 }, { anonymous: true }),
    getWikiTree({ anonymous: true }),
  ]);
  if (!first.ok || !wiki.ok) throw new Error('站点地图数据暂时无法加载。');
  const entries: MetadataRoute.Sitemap = [
    { url: `${site}/`, changeFrequency: 'daily', priority: 1 },
    { url: `${site}/categories`, changeFrequency: 'weekly', priority: 0.8 },
    { url: `${site}/wiki`, changeFrequency: 'weekly', priority: 0.8 },
    ...flattenWiki(wiki.data)
      .filter((page) => !page.status || page.status === 'published')
      .map((page) => ({
        url: `${site}${wikiHref(page)}`,
        changeFrequency: 'monthly' as const,
        priority: 0.6,
      })),
  ];
  if (first.data.total + entries.length > MAX_URLS)
    throw new Error('内容超过单文件站点地图容量，需要分页站点地图。');
  const seen = new Set(entries.map((entry) => entry.url));
  const add = (records: typeof first.data.records) => {
    for (const post of records) {
      if (post.status !== 'published') continue;
      const url = `${site}${postHref(post)}`;
      if (seen.has(url)) continue;
      seen.add(url);
      entries.push({
        url,
        lastModified: validDate(post.updateTime || post.publishedAt),
        changeFrequency: 'weekly',
        priority: 0.7,
      });
    }
  };
  add(first.data.records);
  // Four independent pages per batch, bounded by validated total and 50k URLs.
  for (let page = 2; page <= first.data.pages; page += 4) {
    const results = await Promise.all(
      Array.from({ length: Math.min(4, first.data.pages - page + 1) }, (_, offset) =>
        getPostPage({ pageNum: page + offset, pageSize: 100 }, { anonymous: true }),
      ),
    );
    for (const result of results) {
      if (!result.ok) throw new Error('站点地图分页暂时无法加载。');
      add(result.data.records);
    }
  }
  return entries;
}
