import Link from 'next/link';
import { getCategoryTree, getPostPage, getTagList, type CategoryNode } from '@/lib/cms';
import { loadSiteSettings, SITE_DESCRIPTION_SHORT } from '@/lib/site';
import { CategoryTreeList } from '@/components/category-tree';
import { TagChip } from '@/components/tag-chip';

/**
 * 站点侧栏（左侧）。
 *
 * 版式对齐原站（Typecho + Joe）：左边一窄列放「博主卡 → 分类 → 标签」，
 * 右边才是文章正文，读长文时左侧信息常驻但不会抢视线。
 * 各数据源独立容错：某块拉取失败只在该块显示提示，不影响其余部分。
 */
export async function BlogSidebar() {
  const [categoriesRes, tagsRes, postsRes, settings] = await Promise.all([
    getCategoryTree(),
    getTagList(),
    // pageSize=1 只为拿 total（文章总数），不关心具体内容
    getPostPage({ pageNum: 1, pageSize: 1 }),
    loadSiteSettings(),
  ]);
  const categories = categoriesRes.ok ? categoriesRes.data : null;
  const tags = tagsRes.ok ? tagsRes.data : null;
  const postTotal = postsRes.ok ? postsRes.data.total : null;
  const description = settings.siteDescription.trim() || SITE_DESCRIPTION_SHORT;
  const initial = settings.siteTitle.trim().slice(0, 1).toUpperCase() || 'A';

  return (
    <aside className="order-2 space-y-5 lg:order-1 lg:sticky lg:top-24 lg:self-start">
      {/* 简介卡：头像 + 一句简介 + 文章/分类计数。
          站点名在顶栏已经有了，这里不再重复一遍（一屏里出现四次会显得很吵）。 */}
      <section className="paper overflow-hidden">
        <div className="h-14 bg-brand-wash" aria-hidden />
        <div className="px-5 pb-5">
          <div className="-mt-7">
            <span className="flex h-14 w-14 items-center justify-center rounded-full border-[3px] border-surface bg-brand text-[18px] font-semibold text-primary-foreground shadow-card">
              {initial}
            </span>
          </div>

          <p className="mt-3 text-[12.5px] leading-[1.8] text-ink-soft">{description}</p>

          <dl className="mt-4 grid grid-cols-2 divide-x divide-line-soft border-t border-line-soft pt-3.5 text-center">
            <div>
              <dd className="text-[15px] font-semibold tabular-nums text-ink">
                {postTotal ?? '—'}
              </dd>
              <dt className="mt-0.5 text-[11px] text-ink-faint">文章</dt>
            </div>
            <div>
              <dd className="text-[15px] font-semibold tabular-nums text-ink">
                {categories ? countCategories(categories) : '—'}
              </dd>
              <dt className="mt-0.5 text-[11px] text-ink-faint">分类</dt>
            </div>
          </dl>
        </div>
      </section>

      <section className="paper p-5">
        <div className="mb-2.5 flex items-baseline justify-between gap-2">
          <h2 className="eyebrow">分类</h2>
          <Link
            href="/categories"
            className="text-[11px] text-ink-faint transition-colors hover:text-brand"
          >
            全部
          </Link>
        </div>
        <div className="-mx-2.5">
          {categories ? (
            categories.length > 0 ? (
              <CategoryTreeList nodes={categories} />
            ) : (
              <p className="px-2.5 text-[12px] text-ink-faint">暂无分类</p>
            )
          ) : (
            <p className="px-2.5 text-[12px] text-ink-faint">分类加载失败</p>
          )}
        </div>
      </section>

      <section className="paper p-5">
        <div className="mb-3 flex items-baseline justify-between gap-2">
          <h2 className="eyebrow">标签</h2>
          {tags && tags.length > 0 ? (
            <span className="text-[11px] tabular-nums text-ink-faint">{tags.length} 个</span>
          ) : null}
        </div>
        {tags === null ? (
          <p className="text-[12px] text-ink-faint">标签加载失败</p>
        ) : tags.length > 0 ? (
          <div className="flex flex-wrap gap-1.5">
            {tags.map((tag) => (
              <TagChip key={tag.id} tag={tag} count={tag.postCount} />
            ))}
          </div>
        ) : (
          <p className="text-[12px] text-ink-faint">暂无标签</p>
        )}
      </section>
    </aside>
  );
}

/** 分类总数（含子分类），用于博主卡的计数 */
function countCategories(nodes: CategoryNode[]): number {
  let total = 0;
  for (const node of nodes) {
    total += 1;
    if (node.children && node.children.length > 0) {
      total += countCategories(node.children);
    }
  }
  return total;
}
