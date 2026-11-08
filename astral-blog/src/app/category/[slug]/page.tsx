import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { CategoryTreeList } from '@/components/category-tree';
import { EmptyState } from '@/components/empty-state';
import { IconChevronLeft } from '@/components/icons';
import { PageHeader } from '@/components/page-header';
import { Pagination } from '@/components/pagination';
import { PostList } from '@/components/post-list';
import { getCategoryTree, getPostPage, type CategoryNode } from '@/lib/cms';
import { categoryHref } from '@/lib/links';
import { pageHref, parsePageNumber } from '@/lib/page-input';

export const dynamic = 'force-dynamic';
const PAGE_SIZE = 10;
interface CategoryPageProps {
  params: Promise<{ slug: string }>;
  searchParams: Promise<{ page?: string | string[] }>;
}

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

export async function generateMetadata({
  params,
  searchParams,
}: CategoryPageProps): Promise<Metadata> {
  const page = parsePageNumber((await searchParams).page);
  const { slug } = await params;
  const treeRes = await getCategoryTree();
  const matched = treeRes.ok ? findCategory(treeRes.data, slug) : null;
  const title = `分类：${matched ? matched.name : slug}`;
  const description = matched?.description?.trim() || undefined;
  return {
    title,
    description,
    alternates: { canonical: pageHref(categoryHref(matched ?? { id: 0, slug }), page ?? 1) },
    ...(!treeRes.ok || !matched || page === null
      ? { robots: { index: false, follow: false } }
      : {}),
    openGraph: { type: 'website', title, description },
  };
}

export default async function CategoryPage({ params, searchParams }: CategoryPageProps) {
  const { slug } = await params;
  const { page } = await searchParams;
  const pageNum = parsePageNumber(page);
  if (pageNum === null) notFound();
  const [treeRes, postsRes] = await Promise.all([
    getCategoryTree(),
    getPostPage({ pageNum, pageSize: PAGE_SIZE, categorySlug: slug }),
  ]);
  if (postsRes.ok && pageNum > Math.max(1, postsRes.data.pages)) notFound();
  const matched = treeRes.ok ? findCategory(treeRes.data, slug) : null;
  // 分类目录能取到却找不到该 slug：这是无效地址，给真正的 404，而不是 200 的空分类页。
  // 目录接口失败时保持可访问，避免后端短暂不可用把所有分类页判成 404。
  if (treeRes.ok && !matched) notFound();
  const heading = matched ? matched.name : slug;
  const description =
    matched?.description?.trim() || (postsRes.ok ? `共 ${postsRes.data.total} 篇文章` : undefined);
  const basePath = categoryHref(matched ?? { id: 0, slug });

  return (
    <div className="page-shell py-8 sm:py-12">
      <Link
        href="/categories"
        className="text-ink-soft hover:text-brand inline-flex min-h-11 items-center gap-1.5 text-[12px] sm:min-h-0"
      >
        <IconChevronLeft className="h-3.5 w-3.5" />
        全部分类
      </Link>
      <div className="mt-3">
        <PageHeader eyebrow="分类档案" title={heading} description={description} />
      </div>
      <div className="grid gap-12 lg:grid-cols-[minmax(0,1fr)_250px] lg:gap-14">
        <div className="min-w-0">
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
            <EmptyState
              title="文章暂时无法加载"
              description={postsRes.error ?? '内容服务暂时不可用。'}
              retry
            />
          )}
        </div>
        <aside className="border-ink border-t pt-5 lg:sticky lg:top-24 lg:self-start">
          <h2 className="font-display text-ink text-[21px] font-semibold">分类目录</h2>
          <div className="-mx-2 mt-3">
            {treeRes.ok ? (
              treeRes.data.length > 0 ? (
                <CategoryTreeList nodes={treeRes.data} activeId={matched?.id} />
              ) : (
                <p className="text-ink-faint px-2 text-[12px]">暂无分类</p>
              )
            ) : (
              <EmptyState
                title="分类目录暂时无法加载"
                description={treeRes.error ?? undefined}
                retry
              />
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}
