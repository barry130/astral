import type { Metadata } from 'next';
import Link from 'next/link';
import { CategoryTreeList } from '@/components/category-tree';
import { EmptyState } from '@/components/empty-state';
import { IconChevronLeft } from '@/components/icons';
import { PageHeader } from '@/components/page-header';
import { Pagination } from '@/components/pagination';
import { PostList } from '@/components/post-list';
import { getCategoryTree, getPostPage, type CategoryNode } from '@/lib/cms';

export const dynamic = 'force-dynamic';
const PAGE_SIZE = 10;
interface CategoryPageProps { params: Promise<{ slug: string }>; searchParams: Promise<{ page?: string }> }

function findCategory(nodes: CategoryNode[], key: string): CategoryNode | null {
  for (const node of nodes) {
    if (node.slug === key || String(node.id) === key) return node;
    if (node.children && node.children.length > 0) {
      const hit = findCategory(node.children, key);
      if (hit) return hit;
    }
  }
  return null;
}

export async function generateMetadata({ params }: CategoryPageProps): Promise<Metadata> {
  const { slug } = await params;
  return { title: `分类：${decodeURIComponent(slug)}` };
}

export default async function CategoryPage({ params, searchParams }: CategoryPageProps) {
  const { slug } = await params;
  const { page } = await searchParams;
  const pageNum = Math.max(1, Number.parseInt(page ?? '1', 10) || 1);
  const [treeRes, postsRes] = await Promise.all([getCategoryTree(), getPostPage({ pageNum, pageSize: PAGE_SIZE, categorySlug: slug })]);
  const matched = treeRes.ok ? findCategory(treeRes.data, slug) : null;
  const heading = matched ? matched.name : decodeURIComponent(slug);
  const description = matched?.description?.trim() || (postsRes.ok ? `共 ${postsRes.data.total} 篇文章` : undefined);
  const basePath = `/category/${encodeURIComponent(slug)}`;

  return (
    <div className="page-shell py-8 sm:py-12">
      <Link href="/categories" className="inline-flex min-h-11 items-center gap-1.5 text-[12px] text-ink-soft hover:text-brand sm:min-h-0"><IconChevronLeft className="h-3.5 w-3.5" />全部分类</Link>
      <div className="mt-3"><PageHeader eyebrow="分类档案" title={heading} description={description} /></div>
      <div className="grid gap-12 lg:grid-cols-[minmax(0,1fr)_250px] lg:gap-14">
        <div className="min-w-0">
          {postsRes.ok ? (
            <>
              <PostList posts={postsRes.data.records} emptyTitle="该分类下暂无文章" emptyDescription="发布文章后将在这里展示。" />
              <div className="mt-9"><Pagination current={pageNum} pages={postsRes.data.pages} basePath={basePath} /></div>
            </>
          ) : <EmptyState title="文章暂时无法加载" description="后端服务不可达，请稍后刷新重试。" />}
        </div>
        <aside className="border-t border-ink pt-5 lg:sticky lg:top-24 lg:self-start">
          <h2 className="font-display text-[21px] font-semibold text-ink">分类目录</h2>
          <div className="-mx-2 mt-3">
            {treeRes.ok && treeRes.data.length > 0 ? <CategoryTreeList nodes={treeRes.data} activeId={matched?.id} /> : <p className="px-2 text-[12px] text-ink-faint">暂无分类</p>}
          </div>
        </aside>
      </div>
    </div>
  );
}
