import Link from 'next/link';
import { getCategoryTree, getTagList, type CategoryNode } from '@/lib/cms';
import { loadSiteSettings, SITE_DESCRIPTION_SHORT } from '@/lib/site';
import { CategoryTreeList } from '@/components/category-tree';
import { TagChip } from '@/components/tag-chip';

/** 右侧栏目：站点说明、分类索引与标签，数据源仍各自独立容错。 */
export async function BlogSidebar({ postTotal = null }: { postTotal?: number | null }) {
  const [categoriesRes, tagsRes, settings] = await Promise.all([
    getCategoryTree(),
    getTagList(),
    loadSiteSettings(),
  ]);
  const categories = categoriesRes.ok ? categoriesRes.data : null;
  const tags = tagsRes.ok ? tagsRes.data : null;
  const description = settings.siteDescription.trim() || SITE_DESCRIPTION_SHORT;
  const initial = Array.from(settings.siteTitle.trim())[0]?.toUpperCase() || 'A';

  return (
    <aside className="order-2 space-y-9 lg:sticky lg:top-24 lg:order-2 lg:self-start">
      <section className="border-ink border-t-2 pt-5">
        <div className="flex items-center gap-3">
          <span
            aria-hidden
            className="bg-brand font-display text-primary-foreground flex size-11 items-center justify-center text-[23px]"
          >
            {initial}
          </span>
          <h2 className="font-display text-ink text-[22px] font-semibold">关于本站</h2>
        </div>
        <p className="text-ink-soft mt-4 text-[13px] leading-7">{description}</p>
        <dl className="border-line mt-5 flex gap-8 border-y py-3">
          <div className="flex flex-row-reverse items-baseline gap-2">
            <dt className="text-ink-faint text-[11px]">篇文章</dt>
            <dd className="font-display text-ink text-[22px] font-semibold tabular-nums">
              {postTotal ?? '—'}
            </dd>
          </div>
          <div className="flex flex-row-reverse items-baseline gap-2">
            <dt className="text-ink-faint text-[11px]">个分类</dt>
            <dd className="font-display text-ink text-[22px] font-semibold tabular-nums">
              {categories ? countCategories(categories) : '—'}
            </dd>
          </div>
        </dl>
      </section>

      <section className="border-line-strong border-t pt-5">
        <div className="flex items-center justify-between gap-2">
          <h2 className="eyebrow">分类索引</h2>
          <Link
            href="/categories"
            className="text-ink-faint hover:text-brand inline-flex min-h-11 items-center text-[12px] sm:min-h-0"
          >
            全部
          </Link>
        </div>
        <div className="-mx-2 mt-2">
          {categories ? (
            categories.length > 0 ? (
              <CategoryTreeList nodes={categories} />
            ) : (
              <p className="text-ink-faint px-2 text-[12px]">暂无分类</p>
            )
          ) : (
            <p className="text-ink-faint px-2 text-[12px]">分类加载失败</p>
          )}
        </div>
      </section>

      <section className="border-line-strong border-t pt-5">
        <div className="flex items-baseline justify-between gap-2">
          <h2 className="eyebrow">标签索引</h2>
          {tags && tags.length > 0 ? (
            <span className="text-ink-faint text-[11px] tabular-nums">{tags.length} 个</span>
          ) : null}
        </div>
        <div className="mt-4">
          {tags === null ? (
            <p className="text-ink-faint text-[12px]">标签加载失败</p>
          ) : tags.length > 0 ? (
            <div className="flex flex-wrap gap-2">
              {tags.map((tag) => (
                <TagChip key={tag.id} tag={tag} count={tag.postCount} />
              ))}
            </div>
          ) : (
            <p className="text-ink-faint text-[12px]">暂无标签</p>
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
