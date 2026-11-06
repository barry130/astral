import Link from 'next/link';
import { getCategoryTree, getPostPage, getTagList, type CategoryNode } from '@/lib/cms';
import { loadSiteSettings, SITE_DESCRIPTION_SHORT } from '@/lib/site';
import { CategoryTreeList } from '@/components/category-tree';
import { TagChip } from '@/components/tag-chip';

/** 右侧栏目：站点说明、分类索引与标签，数据源仍各自独立容错。 */
export async function BlogSidebar() {
  const [categoriesRes, tagsRes, postsRes, settings] = await Promise.all([
    getCategoryTree(),
    getTagList(),
    getPostPage({ pageNum: 1, pageSize: 1 }),
    loadSiteSettings(),
  ]);
  const categories = categoriesRes.ok ? categoriesRes.data : null;
  const tags = tagsRes.ok ? tagsRes.data : null;
  const postTotal = postsRes.ok ? postsRes.data.total : null;
  const description = settings.siteDescription.trim() || SITE_DESCRIPTION_SHORT;
  const initial = settings.siteTitle.trim().slice(0, 1).toUpperCase() || 'A';

  return (
    <aside className="order-2 space-y-9 lg:order-2 lg:sticky lg:top-24 lg:self-start">
      <section className="border-t-2 border-ink pt-5">
        <div className="flex items-center gap-3">
          <span aria-hidden className="flex size-11 items-center justify-center bg-brand font-display text-[23px] text-primary-foreground">
            {initial}
          </span>
          <h2 className="font-display text-[22px] font-semibold text-ink">关于本站</h2>
        </div>
        <p className="mt-4 text-[13px] leading-7 text-ink-soft">{description}</p>
        <dl className="mt-5 flex gap-8 border-y border-line py-3">
          <div className="flex items-baseline gap-2">
            <dd className="font-display text-[22px] font-semibold tabular-nums text-ink">{postTotal ?? '—'}</dd>
            <dt className="text-[11px] text-ink-faint">篇文章</dt>
          </div>
          <div className="flex items-baseline gap-2">
            <dd className="font-display text-[22px] font-semibold tabular-nums text-ink">{categories ? countCategories(categories) : '—'}</dd>
            <dt className="text-[11px] text-ink-faint">个分类</dt>
          </div>
        </dl>
      </section>

      <section className="border-t border-line-strong pt-5">
        <div className="flex items-center justify-between gap-2">
          <h2 className="eyebrow">分类索引</h2>
          <Link href="/categories" className="inline-flex min-h-11 items-center text-[12px] text-ink-faint hover:text-brand sm:min-h-0">
            全部
          </Link>
        </div>
        <div className="-mx-2 mt-2">
          {categories ? (
            categories.length > 0 ? <CategoryTreeList nodes={categories} /> : <p className="px-2 text-[12px] text-ink-faint">暂无分类</p>
          ) : (
            <p className="px-2 text-[12px] text-ink-faint">分类加载失败</p>
          )}
        </div>
      </section>

      <section className="border-t border-line-strong pt-5">
        <div className="flex items-baseline justify-between gap-2">
          <h2 className="eyebrow">标签索引</h2>
          {tags && tags.length > 0 ? <span className="text-[11px] tabular-nums text-ink-faint">{tags.length} 个</span> : null}
        </div>
        <div className="mt-4">
          {tags === null ? (
            <p className="text-[12px] text-ink-faint">标签加载失败</p>
          ) : tags.length > 0 ? (
            <div className="flex flex-wrap gap-2">
              {tags.map((tag) => <TagChip key={tag.id} tag={tag} count={tag.postCount} />)}
            </div>
          ) : (
            <p className="text-[12px] text-ink-faint">暂无标签</p>
          )}
        </div>
      </section>
    </aside>
  );
}

function countCategories(nodes: CategoryNode[]): number {
  let total = 0;
  for (const node of nodes) {
    total += 1;
    if (node.children && node.children.length > 0) total += countCategories(node.children);
  }
  return total;
}
