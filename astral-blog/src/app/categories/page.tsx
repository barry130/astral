import type { Metadata } from 'next';
import { CategoryTreeList } from '@/components/category-tree';
import { EmptyState } from '@/components/empty-state';
import { PageHeader } from '@/components/page-header';
import { TagChip } from '@/components/tag-chip';
import { getCategoryTree, getTagList } from '@/lib/cms';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = {
  title: '分类',
  description: '全部分类与标签',
};

/** 顶部导航「分类」落地页：分类树 + 标签列表 */
export default async function CategoriesPage() {
  const [categoriesRes, tagsRes] = await Promise.all([getCategoryTree(), getTagList()]);
  const categories = categoriesRes.ok ? categoriesRes.data : null;
  const tags = tagsRes.ok ? tagsRes.data : null;

  return (
    <div className="page-shell py-10 sm:py-14">
      <PageHeader title="分类与标签" />

      <section aria-label="分类目录" className="lg:grid lg:grid-cols-[minmax(0,1fr)_320px] lg:items-start lg:gap-6">
        <div>
          <div className="mb-4 flex items-baseline justify-between gap-3">
            <h2 className="eyebrow">分类</h2>
            {categories ? (
              <span className="text-[11px] tabular-nums text-ink-faint">{categories.length} 个</span>
            ) : null}
          </div>
          {categories ? (
            categories.length > 0 ? (
              <div className="paper p-2 sm:p-3">
                <CategoryTreeList nodes={categories} />
              </div>
            ) : (
              <EmptyState title="暂无分类" description="后台创建分类后将在这里展示。" />
            )
          ) : (
            <EmptyState title="分类暂时无法加载" description="后端服务不可达，请稍后刷新重试。" />
          )}
        </div>

        <div className="mt-10 lg:mt-0">
          <div className="mb-4 flex items-baseline justify-between gap-3">
            <h2 className="eyebrow">标签</h2>
            {tags ? (
              <span className="text-[11px] tabular-nums text-ink-faint">{tags.length} 个</span>
            ) : null}
          </div>
          <div className="paper p-5 sm:p-6">
            {tags ? (
              tags.length > 0 ? (
                <div className="flex flex-wrap gap-1.5">
                  {tags.map((tag) => (
                    <TagChip key={tag.id} tag={tag} count={tag.postCount} size="md" />
                  ))}
                </div>
              ) : (
                <p className="text-[12.5px] text-ink-faint">暂无标签</p>
              )
            ) : (
              <p className="text-[12.5px] text-ink-faint">标签加载失败，请稍后刷新重试。</p>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
