import Link from 'next/link';
import { BlogSidebar } from '@/components/blog-sidebar';
import { EmptyState } from '@/components/empty-state';
import { Pagination } from '@/components/pagination';
import { PostList } from '@/components/post-list';
import { getPostPage } from '@/lib/cms';

// 数据页一律动态渲染：构建期不发真实请求，后端不可达也能 build
export const dynamic = 'force-dynamic';

const PAGE_SIZE = 10;

interface HomePageProps {
  searchParams: Promise<{ page?: string }>;
}

export default async function HomePage({ searchParams }: HomePageProps) {
  const { page } = await searchParams;
  const pageNum = Math.max(1, Number.parseInt(page ?? '1', 10) || 1);
  const res = await getPostPage({ pageNum, pageSize: PAGE_SIZE });
  const privateCount = res.ok ? res.data.records.filter((item) => item.status === 'private').length : 0;

  return (
    <div className="page-shell py-7 sm:py-10">
      <section className="grid border-y border-ink py-7 sm:py-9 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end lg:gap-12">
        <div>
          <p className="eyebrow text-brand">公开写作与知识档案</p>
          <h1 className="mt-4 font-display text-[40px] font-semibold leading-[1.15] tracking-[-0.02em] text-ink sm:text-[54px]">
            最近刊出
          </h1>
          <p className="mt-4 max-w-xl text-[14px] leading-7 text-ink-soft sm:text-[15px]">
            技术笔记、产品记录与持续修订的知识页面，按时间依次归档。
          </p>
        </div>
        <div className="mt-6 flex items-center justify-between gap-5 border-t border-line pt-4 lg:mt-0 lg:min-w-36 lg:flex-col lg:items-end lg:border-0 lg:pt-0">
          {res.ok ? (
            <p className="text-[12px] tabular-nums text-ink-soft">
              第 {pageNum} 页 <span className="mx-1 text-line-strong">/</span> 共 {res.data.total} 篇
            </p>
          ) : <span />}
          <Link href="/categories" className="inline-flex min-h-11 items-center border-b border-brand text-[13px] font-medium text-brand hover:text-brand-strong lg:min-h-0 lg:pb-1">
            浏览全部栏目
          </Link>
        </div>
      </section>

      <div className="mt-8 grid gap-12 lg:grid-cols-[minmax(0,1fr)_250px] lg:gap-12 xl:grid-cols-[minmax(0,1fr)_270px] xl:gap-16">
        <BlogSidebar />
        <div className="order-1 min-w-0 lg:order-1">
          {res.ok ? (
            <>
              {privateCount > 0 ? (
                <p className="mb-5 border-l-2 border-amber-600 bg-amber-50/60 px-4 py-3 text-[12.5px] leading-relaxed text-amber-800">
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
            <EmptyState title="文章暂时无法加载" description="后端服务不可达，请稍后刷新重试。" />
          )}
        </div>
      </div>
    </div>
  );
}
