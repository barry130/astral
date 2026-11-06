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
interface WikiPageProps { params: Promise<{ slug: string }> }

export async function generateMetadata({ params }: WikiPageProps): Promise<Metadata> {
  const { slug } = await params;
  const res = await getWikiPage(slug);
  return res.ok ? { title: res.data.title } : { title: 'Wiki' };
}

export default async function WikiPage({ params }: WikiPageProps) {
  const { slug } = await params;
  const res = await getWikiPage(slug);
  if (!res.ok) {
    if (res.error === null) {
      return <div className="page-shell py-16"><EmptyState title="页面暂时无法加载" description="后端服务不可达，请稍后刷新重试。" /></div>;
    }
    notFound();
  }

  const page = res.data;
  const [commentsRes, settings] = await Promise.all([
    getCommentPage('page', page.id, 1, COMMENT_PAGE_SIZE),
    loadSiteSettings(),
  ]);
  const comments = commentsRes.ok ? commentsRes.data.records : [];
  const showComments = settings.commentEnabled || comments.length > 0;
  const { html, toc } = renderMarkdownWithToc(page.content, { articleTitle: page.title });
  const breadcrumb: Crumb[] = page.breadcrumb ?? [];
  const children = page.children ?? [];
  const updatedAt = page.updateTime ?? page.publishedAt ?? null;
  const hasToc = toc.length > 0;

  return (
    <div className="page-shell py-7 sm:py-10">
      {breadcrumb.length === 0 ? (
        <Link href="/wiki" className="inline-flex min-h-11 items-center gap-1.5 text-[12px] text-ink-soft hover:text-brand sm:min-h-0"><IconChevronLeft className="h-3.5 w-3.5" />Wiki 目录</Link>
      ) : (
        <nav aria-label="面包屑" className="py-2">
          <ol className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px] text-ink-soft">
            <li><Link href="/wiki" className="inline-flex min-h-11 items-center hover:text-brand sm:min-h-0">Wiki</Link></li>
            {breadcrumb.map((crumb) => (
              <li key={crumb.id} className="flex items-center gap-x-2"><span aria-hidden className="text-line-strong">/</span><Link href={wikiHref(crumb)} className="inline-flex min-h-11 items-center hover:text-brand sm:min-h-0">{crumb.title}</Link></li>
            ))}
          </ol>
        </nav>
      )}

      <article className="mt-4">
        <EditableArticle kind="page" id={page.id}>
          <div className="relative">
            <header className="border-y border-ink py-8 sm:py-10">
              <div className="flex flex-wrap items-center gap-3 pr-28 sm:pr-24">
                <p className="eyebrow text-brand">知识库 / Wiki</p>
                {page.status === 'private' ? <PrivateBadge /> : null}
              </div>
              <h1 className="mt-5 max-w-[62rem] font-display text-[36px] font-semibold leading-[1.28] tracking-[-0.018em] text-ink sm:text-[clamp(42px,4vw,54px)]">{page.title}</h1>
              <div className="mt-6 flex flex-wrap items-center gap-x-5 gap-y-2 text-[12px] text-ink-soft">
                {typeof page.views === 'number' ? <span className="inline-flex items-center gap-1.5"><IconEye className="h-3.5 w-3.5 text-ink-faint" />{formatCount(page.views)} 次阅读</span> : null}
                {updatedAt ? <span className="tabular-nums">更新于 {updatedAt.slice(0, 10)}</span> : null}
              </div>
            </header>

            <div className={`mt-9 sm:mt-11 ${hasToc ? 'grid gap-12 lg:grid-cols-[minmax(0,46rem)_minmax(180px,220px)] lg:justify-between' : ''}`}>
              <div className="min-w-0">
                <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />
                {children.length > 0 ? (
                  <section aria-label="子页面" className="mt-14 border-t border-ink pt-6">
                    <div className="flex items-baseline justify-between gap-3">
                      <h2 className="font-display text-[25px] font-semibold text-ink">继续查阅</h2>
                      <span className="text-[11px] tabular-nums text-ink-faint">{children.length} 个页面</span>
                    </div>
                    <ul className="mt-4 border-t border-line">
                      {children.map((child) => (
                        <li key={child.id} className="border-b border-line">
                          <Link href={wikiHref(child)} className="group flex min-h-12 items-center justify-between gap-4 py-3 text-[14px] text-ink-body hover:text-brand">
                            <span>{child.title}</span><IconArrowRight className="h-3.5 w-3.5 shrink-0 text-ink-faint group-hover:text-brand" />
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </section>
                ) : null}
              </div>
              {hasToc ? (
                <aside className="hidden lg:block">
                  <div className="sticky top-24 max-h-[calc(100vh-8rem)] overflow-y-auto border-t border-ink pt-4 pr-1">
                    <p className="mb-4 font-display text-[18px] font-semibold text-ink">本页目录</p><Toc items={toc} />
                  </div>
                </aside>
              ) : null}
            </div>
          </div>
        </EditableArticle>
      </article>

      {showComments ? (
        <section aria-label="评论" className="mt-16 max-w-[46rem] border-t border-ink pt-6">
          <div className="flex items-baseline justify-between gap-3"><h2 className="font-display text-[27px] font-semibold text-ink">读者评论</h2><span className="text-[12px] tabular-nums text-ink-faint">{commentsRes.ok ? commentsRes.data.total : 0} 条</span></div>
          <CommentList comments={comments} failed={!commentsRes.ok} total={commentsRes.ok ? commentsRes.data.total : 0} />
          {settings.commentEnabled ? <CommentForm targetType="page" targetId={page.id} needReview={settings.commentNeedReview} /> : null}
        </section>
      ) : null}
    </div>
  );
}
