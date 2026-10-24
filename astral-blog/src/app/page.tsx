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
  // 后端只会把私密文章返回给有权限的登录用户，这里据此给一句提示（匿名访客永远为 0）
  const privateCount = res.ok ? res.data.records.filter((item) => item.status === 'private').length : 0;

  return (
    <div className="page-shell py-10 sm:py-12">
      {/* 左侧栏固定 264px，右侧主列吃满剩余宽度。
          窄屏时用 order 把文章列提到前面（侧栏在手机上整整一屏高，
          访客要滑很久才见到第一篇文章），排序写在两侧容器上。 */}
      <div className="grid gap-8 lg:grid-cols-[264px_minmax(0,1fr)] lg:gap-10">
        <BlogSidebar />

        <div className="order-1 min-w-0 lg:order-2">
          <header className="mb-5 flex items-end justify-between gap-4 border-b border-line pb-3.5">
            <h1 className="text-[22px] font-semibold tracking-[-0.02em] text-ink sm:text-[26px]">
              最近文章
            </h1>
            {res.ok ? (
              <p className="pb-0.5 text-[11.5px] tabular-nums text-ink-faint">
                共 {res.data.total} 篇
              </p>
            ) : null}
          </header>

          {res.ok ? (
            <>
              {privateCount > 0 ? (
                <p className="mb-4 rounded-lg border border-amber-200 bg-amber-50 px-3.5 py-2.5 text-[12.5px] leading-relaxed text-amber-700">
                  其中 {privateCount} 篇为私密文章：仅登录且有内容权限的成员可见，外部访客不可见。
                </p>
              ) : null}
              <PostList
                posts={res.data.records}
                emptyTitle="暂无文章"
                emptyDescription="后台发布文章后将在这里展示。"
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
