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

interface CategoryPageProps {
  params: Promise<{ slug: string }>;
  searchParams: Promise<{ page?: string }>;
}

/** 后端没有公开的分类详情接口，用分类树反查名称（slug 或数字 id 均可命中） */
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

  const [treeRes, postsRes] = await Promise.all([
    getCategoryTree(),
    getPostPage({ pageNum, pageSize: PAGE_SIZE, categorySlug: slug }),
  ]);

  const matched = treeRes.ok ? findCategory(treeRes.data, slug) : null;
  const heading = matched ? matched.name : decodeURIComponent(slug);
  const description =
    matched?.description?.trim() ||
    (postsRes.ok ? `共 ${postsRes.data.total} 篇文章` : undefined);
  const basePath = `/category/${encodeURIComponent(slug)}`;

  return (
    <div className="page-shell py-10 sm:py-14">
      <Link
        href="/categories"
        className="inline-flex items-center gap-1.5 text-[12px] text-ink-soft transition-colors hover:text-brand"
      >
        <IconChevronLeft className="h-3.5 w-3.5" />
        全部分类
      </Link>

      <div className="mt-4">
        <PageHeader title={heading} description={description} />
      </div>

      {/* 与首页同口径：宽屏左栏 264px；窄屏用 order 把文章列提到前面，
          否则手机上要先滑过一整屏分类目录才看到文章。 */}
      <div className="grid gap-8 lg:grid-cols-[264px_minmax(0,1fr)] lg:gap-10">
        <aside className="order-2 lg:order-1 lg:sticky lg:top-24 lg:self-start">
          <div className="paper p-5">
            <h2 className="eyebrow">分类目录</h2>
            <div className="-mx-2.5 mt-3">
              {treeRes.ok && treeRes.data.length > 0 ? (
                <CategoryTreeList nodes={treeRes.data} activeId={matched?.id} />
              ) : (
                <p className="px-2.5 text-[12px] text-ink-faint">暂无分类</p>
              )}
            </div>
          </div>
        </aside>

        <div className="order-1 min-w-0 lg:order-2">
          {postsRes.ok ? (
            <>
              <PostList
                posts={postsRes.data.records}
                emptyTitle="该分类下暂无文章"
                emptyDescription="发布文章后将在这里展示。"
              />
              <div className="mt-9">
                <Pagination current={pageNum} pages={postsRes.data.pages} basePath={basePath} />
              </div>
            </>
          ) : (
            <EmptyState title="文章暂时无法加载" description="后端服务不可达，请稍后刷新重试。" />
          )}
        </div>
      </div>
    </div>
  );
}
