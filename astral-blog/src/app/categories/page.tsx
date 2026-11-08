import type { Metadata } from 'next';
import { CategoryTreeList } from '@/components/category-tree';
import { EmptyState } from '@/components/empty-state';
import { PageHeader } from '@/components/page-header';
import { TagChip } from '@/components/tag-chip';
import { getCategoryTree, getTagList } from '@/lib/cms';

export const dynamic = 'force-dynamic';
export async function generateMetadata(): Promise<Metadata> {
  const [categories, tags] = await Promise.all([getCategoryTree(), getTagList()]);
  return {
    title: '分类',
    description: '全部分类与标签',
    alternates: { canonical: '/categories' },
    ...(!categories.ok || !tags.ok ? { robots: { index: false, follow: false } } : {}),
  };
}

export default async function CategoriesPage() {
  const [categoriesRes, tagsRes] = await Promise.all([getCategoryTree(), getTagList()]);
  const categories = categoriesRes.ok ? categoriesRes.data : null;
  const tags = tagsRes.ok ? tagsRes.data : null;

  return (
    <div className="page-shell py-8 sm:py-12">
      <PageHeader
        eyebrow="内容索引"
        title="分类与标签"
        description="分类呈现稳定的内容脉络，标签连接跨栏目的文章。"
      />
      <section
        aria-label="分类目录"
        className="grid gap-12 lg:grid-cols-[minmax(0,1fr)_320px] lg:items-start lg:gap-16"
      >
        <div>
          <div className="border-ink flex items-baseline justify-between gap-3 border-t pt-5">
            <h2 className="font-display text-ink text-[25px] font-semibold">按分类查阅</h2>
            {categories ? (
              <span className="text-ink-faint text-[11px] tabular-nums">
                {categories.length} 个主分类
              </span>
            ) : null}
          </div>
          <div className="mt-4">
            {categories ? (
              categories.length > 0 ? (
                <CategoryTreeList nodes={categories} />
              ) : (
                <EmptyState title="暂无分类" description="后台创建分类后将在这里展示。" />
              )
            ) : (
              <EmptyState
                title="分类暂时无法加载"
                description={
                  categoriesRes.ok ? undefined : (categoriesRes.error ?? '内容服务暂时不可用。')
                }
                retry
              />
            )}
          </div>
        </div>
        <div>
          <div className="border-ink flex items-baseline justify-between gap-3 border-t pt-5">
            <h2 className="font-display text-ink text-[25px] font-semibold">按标签查阅</h2>
            {tags ? (
              <span className="text-ink-faint text-[11px] tabular-nums">{tags.length} 个</span>
            ) : null}
          </div>
          <div className="mt-5">
            {tags ? (
              tags.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                  {tags.map((tag) => (
                    <TagChip key={tag.id} tag={tag} count={tag.postCount} size="md" />
                  ))}
                </div>
              ) : (
                <p className="text-ink-faint text-[12.5px]">暂无标签</p>
              )
            ) : (
              <EmptyState
                title="标签暂时无法加载"
                description={tagsRes.ok ? undefined : (tagsRes.error ?? '内容服务暂时不可用。')}
                retry
              />
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
