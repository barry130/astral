import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { CommentForm } from '@/components/comment-form';
import { CommentList } from '@/components/comment-list';
import { EditableArticle } from '@/components/editable-article';
import { EmptyState } from '@/components/empty-state';
import { PrivateBadge } from '@/components/private-badge';
import { IconArrowRight, IconChevronLeft, IconEye } from '@/components/icons';
import { Toc } from '@/components/toc';
import { getCommentPage, getWikiPage, type Crumb } from '@/lib/cms';
import { formatCount } from '@/lib/format';
import { renderMarkdownWithToc } from '@/lib/markdown';
import { wikiHref } from '@/lib/links';
import { loadSiteSettings } from '@/lib/site';

export const dynamic = 'force-dynamic';

const COMMENT_PAGE_SIZE = 50;

interface WikiPageProps {
  params: Promise<{ slug: string }>;
}

export async function generateMetadata({ params }: WikiPageProps): Promise<Metadata> {
  const { slug } = await params;
  const res = await getWikiPage(slug);
  if (!res.ok) {
    return { title: 'Wiki' };
  }
  return { title: res.data.title };
}

export default async function WikiPage({ params }: WikiPageProps) {
  const { slug } = await params;
  const res = await getWikiPage(slug);

  // 业务性失败（不存在 / 未发布）→ 404；网络层失败（error === null）→ 友好空态
  if (!res.ok) {
    if (res.error === null) {
      return (
        <div className="page-shell py-16">
          <EmptyState title="页面暂时无法加载" description="后端服务不可达，请稍后刷新重试。" />
        </div>
      );
    }
    notFound();
  }

  const page = res.data;
  const [commentsRes, settings] = await Promise.all([
    getCommentPage('page', page.id, 1, COMMENT_PAGE_SIZE),
    loadSiteSettings(),
  ]);
  const comments = commentsRes.ok ? commentsRes.data.records : [];
  // 评论关闭时仍展示已有评论；一条都没有则整块不渲染
  const showComments = settings.commentEnabled || comments.length > 0;
  const { html, toc } = renderMarkdownWithToc(page.content, { articleTitle: page.title });
  const breadcrumb: Crumb[] = page.breadcrumb ?? [];
  const children = page.children ?? [];
  const updatedAt = page.updateTime ?? page.publishedAt ?? null;
  // 没有 h2~h6 时不占用左侧栏，正文回到居中单栏
  const hasToc = toc.length > 0;

  return (
    <div className="page-shell py-8 sm:py-12">
      <div
        className={
          hasToc
            ? 'grid gap-10 lg:grid-cols-[220px_minmax(0,1fr)] lg:gap-12'
            : 'mx-auto w-full max-w-[1100px]'
        }
      >
        {hasToc ? (
          <aside className="hidden lg:block">
            <div className="sticky top-24 max-h-[calc(100vh-8rem)] overflow-y-auto pr-1">
              <p className="mb-3 border-b border-line-soft pb-2 text-[11px] font-medium uppercase tracking-[0.14em] text-ink-faint">
                目录
              </p>
              <Toc items={toc} />
            </div>
          </aside>
        ) : null}

        <div className="min-w-0">
          {/* 顶级页面用「返回 Wiki 目录」；有祖先时改用面包屑，避免同一处出现两遍 Wiki */}
          {breadcrumb.length === 0 ? (
            <Link
              href="/wiki"
              className="inline-flex items-center gap-1.5 text-[12px] text-ink-soft transition-colors hover:text-brand"
            >
              <IconChevronLeft className="h-3.5 w-3.5" />
              Wiki 目录
            </Link>
          ) : (
            <nav aria-label="面包屑">
              <ol className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[12px] text-ink-soft">
                <li>
                  <Link href="/wiki" className="transition-colors hover:text-brand">
                    Wiki
                  </Link>
                </li>
                {breadcrumb.map((crumb) => (
                  <li key={crumb.id} className="flex items-center gap-x-1.5">
                    <span aria-hidden className="text-line-strong">
                      /
                    </span>
                    <Link href={wikiHref(crumb)} className="transition-colors hover:text-brand">
                      {crumb.title}
                    </Link>
                  </li>
                ))}
              </ol>
            </nav>
          )}

          <article className="paper mt-5 px-6 py-8 shadow-paper sm:px-10 sm:py-11">
            <EditableArticle kind="page" id={page.id}>
              <div className="relative">
                <header>
                  <div className="flex flex-wrap items-center gap-2">
                    {/* 私密页面只有登录态可见，标注出来避免误以为已公开 */}
                    {page.status === 'private' ? <PrivateBadge /> : null}
                  </div>
                  <h1 className="mt-2.5 text-[26px] font-semibold leading-[1.3] tracking-[-0.02em] text-ink sm:text-[30px]">
                    {page.title}
                  </h1>
                  <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line-soft pb-6 text-[12px] text-ink-soft">
                    {typeof page.views === 'number' ? (
                      <span className="inline-flex items-center gap-1.5">
                        <IconEye className="h-3.5 w-3.5 text-ink-faint" />
                        {formatCount(page.views)} 次阅读
                      </span>
                    ) : null}
                    {updatedAt ? (
                      <span className="tabular-nums">更新于 {updatedAt.slice(0, 10)}</span>
                    ) : null}
                  </div>
                </header>

                <div className="markdown mt-8" dangerouslySetInnerHTML={{ __html: html }} />
              </div>
            </EditableArticle>
          </article>

          {children.length > 0 ? (
            <section aria-label="子页面" className="mt-10">
              <div className="mb-3 flex items-baseline justify-between gap-3 border-b border-line pb-3">
                <h2 className="eyebrow">子页面</h2>
                <span className="text-[11px] tabular-nums text-ink-faint">{children.length} 篇</span>
              </div>
              <ul className="paper divide-y divide-line-soft px-2 py-1">
                {children.map((child) => (
                  <li key={child.id}>
                    <Link
                      href={wikiHref(child)}
                      className="group flex items-center justify-between gap-3 rounded-lg px-3 py-2.5 text-[13px] text-ink-body transition-colors hover:bg-brand-wash/60 hover:text-brand"
                    >
                      <span className="truncate" title={child.title}>
                        {child.title}
                      </span>
                      <IconArrowRight className="h-3.5 w-3.5 shrink-0 text-ink-faint transition-colors group-hover:text-brand" />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {showComments ? (
            <section aria-label="评论" className="mt-14">
              <div className="flex items-baseline justify-between gap-3 border-b border-line pb-4">
                <h2 className="text-[15px] font-semibold tracking-[-0.01em] text-ink">评论</h2>
                <span className="text-[11.5px] tabular-nums text-ink-faint">
                  {commentsRes.ok ? commentsRes.data.total : 0} 条
                </span>
              </div>
              <CommentList
                comments={comments}
                failed={!commentsRes.ok}
                total={commentsRes.ok ? commentsRes.data.total : 0}
              />
              {settings.commentEnabled ? (
                <CommentForm
                  targetType="page"
                  targetId={page.id}
                  needReview={settings.commentNeedReview}
                />
              ) : null}
            </section>
          ) : null}
        </div>
      </div>
    </div>
  );
}
