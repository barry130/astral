import Link from 'next/link';
import { notFound } from 'next/navigation';
import { BlogSidebar } from '@/components/blog-sidebar';
import { EmptyState } from '@/components/empty-state';
import { Pagination } from '@/components/pagination';
import { PostList } from '@/components/post-list';
import type { Metadata } from 'next';
import { getPostPage } from '@/lib/cms';
import { pageHref, parsePageNumber } from '@/lib/page-input';

// 数据页一律动态渲染：构建期不发真实请求，后端不可达也能 build
export const dynamic = 'force-dynamic';

const PAGE_SIZE = 10;

interface HomePageProps {
  searchParams: Promise<{ page?: string | string[] }>;
}

export async function generateMetadata({ searchParams }: HomePageProps): Promise<Metadata> {
  const page = parsePageNumber((await searchParams).page);
  return {
    title: page && page > 1 ? `最近刊出 · 第 ${page} 页` : undefined,
    alternates: { canonical: pageHref('/', page ?? 1) },
    ...(page === null ? { robots: { index: false, follow: false } } : {}),
  };
}

export default async function HomePage({ searchParams }: HomePageProps) {
  const { page } = await searchParams;
  const pageNum = parsePageNumber(page);
  if (pageNum === null) notFound();
  const res = await getPostPage({ pageNum, pageSize: PAGE_SIZE });
  // 越界页码不渲染“第 999 页 / 共 3 篇”的空列表，直接 404 由 not-found 页面引导回首页。
  if (res.ok && pageNum > Math.max(1, res.data.pages)) notFound();
  const privateCount = res.ok
    ? res.data.records.filter((item) => item.status === 'private').length
    : 0;

  return (
    <div className="page-shell py-7 sm:py-10">
      <section className="border-ink grid border-y py-7 sm:py-9 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end lg:gap-12">
        <div>
          <p className="eyebrow text-brand">公开写作与知识档案</p>
          <h1 className="font-display text-ink mt-4 text-[40px] leading-[1.15] font-semibold tracking-[-0.02em] sm:text-[54px]">
            最近刊出
          </h1>
          <p className="text-ink-soft mt-4 max-w-xl text-[14px] leading-7 sm:text-[15px]">
            技术笔记、产品记录与持续修订的知识页面，按时间依次归档。
          </p>
        </div>
        <div className="border-line mt-6 flex items-center justify-between gap-5 border-t pt-4 lg:mt-0 lg:min-w-36 lg:flex-col lg:items-end lg:border-0 lg:pt-0">
          {res.ok ? (
            <p className="text-ink-soft text-[12px] tabular-nums">
              第 {pageNum} 页 <span className="text-line-strong mx-1">/</span> 共 {res.data.total}{' '}
              篇
            </p>
          ) : (
            <span />
          )}
          <Link
            href="/categories"
            className="border-brand text-brand hover:text-brand-strong inline-flex min-h-11 items-center border-b text-[13px] font-medium lg:min-h-0 lg:pb-1"
          >
            浏览全部栏目
          </Link>
        </div>
      </section>

      <div className="mt-8 grid gap-12 lg:grid-cols-[minmax(0,1fr)_250px] lg:gap-12 xl:grid-cols-[minmax(0,1fr)_270px] xl:gap-16">
        <div className="order-1 min-w-0">
          {res.ok ? (
            <>
              {privateCount > 0 ? (
                <p className="border-brand bg-brand-wash text-brand mb-5 border-l-2 px-4 py-3 text-[12.5px] leading-relaxed">
                  其中 {privateCount} 篇为私密文章：仅登录且有内容权限的成员可见，外部访客不可见。
                </p>
              ) : null}
              <PostList
                posts={res.data.records}
                emptyTitle="暂无文章"
                emptyDescription="后台发布文章后将在这里展示。"
                leadFirst={pageNum === 1}
              />
              <div className="mt-9">
                <Pagination current={pageNum} pages={res.data.pages} basePath="/" />
              </div>
            </>
          ) : (
            <EmptyState
              title="文章暂时无法加载"
              description={res.error ?? '内容服务暂时不可用。'}
              retry
            />
          )}
        </div>
        <BlogSidebar postTotal={res.ok ? res.data.total : null} />
      </div>
    </div>
  );
}
