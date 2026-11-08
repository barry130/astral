import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { CommentsSection } from '@/components/comments-section';
import { EditableArticle } from '@/components/editable-article';
import { PrivateBadge } from '@/components/private-badge';
import { IconArrowRight, IconChevronLeft, IconEye } from '@/components/icons';
import { Toc } from '@/components/toc';
import { getCommentPage, getWikiPage, type Crumb } from '@/lib/cms';
import { formatCount, formatDate } from '@/lib/format';
import { renderMarkdownWithToc } from '@/lib/markdown';
import { wikiHref } from '@/lib/links';
import { loadSiteSettings } from '@/lib/site';
import { parsePageNumber } from '@/lib/page-input';

export const dynamic = 'force-dynamic';
const COMMENT_PAGE_SIZE = 50;
interface WikiPageProps {
  params: Promise<{ slug: string }>;
  searchParams: Promise<{ commentPage?: string | string[] }>;
}

export async function generateMetadata({ params }: WikiPageProps): Promise<Metadata> {
  const { slug } = await params;
  const res = await getWikiPage(slug);
  if (!res.ok) return { title: 'Wiki 不可用', robots: { index: false, follow: false } };
  const href = wikiHref(res.data);
  return {
    title: res.data.title,
    alternates: { canonical: href },
    ...(res.data.status && res.data.status !== 'published'
      ? { robots: { index: false, follow: false } }
      : {}),
    openGraph: { type: 'article', title: res.data.title, url: href },
  };
}

export default async function WikiPage({ params, searchParams }: WikiPageProps) {
  const commentPage = parsePageNumber((await searchParams).commentPage);
  if (commentPage === null) notFound();
  const { slug } = await params;
  const res = await getWikiPage(slug);
  if (!res.ok) {
    if (res.notFound) notFound();
    throw new Error('页面暂时无法加载，请重试。');
  }

  const page = res.data;
  const [commentsRes, settings] = await Promise.all([
    getCommentPage('page', page.id, commentPage, COMMENT_PAGE_SIZE),
    loadSiteSettings(),
  ]);
  if (commentsRes.ok && commentPage > Math.max(1, commentsRes.data.pages)) notFound();
  const { html, toc } = renderMarkdownWithToc(page.content, { articleTitle: page.title });
  const breadcrumb: Crumb[] = page.breadcrumb ?? [];
  const children = page.children ?? [];
  const updatedAt = page.updateTime ?? page.publishedAt ?? null;
  const hasToc = toc.length > 0;

  return (
    <div className="page-shell py-7 sm:py-10">
      {breadcrumb.length === 0 ? (
        <Link
          href="/wiki"
          className="text-ink-soft hover:text-brand inline-flex min-h-11 items-center gap-1.5 text-[12px] sm:min-h-0"
        >
          <IconChevronLeft className="h-3.5 w-3.5" />
          Wiki 目录
        </Link>
      ) : (
        <nav aria-label="面包屑" className="py-2">
          <ol className="text-ink-soft flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px]">
            <li>
              <Link
                href="/wiki"
                className="hover:text-brand inline-flex min-h-11 items-center sm:min-h-0"
              >
                Wiki
              </Link>
            </li>
            {breadcrumb.map((crumb) => (
              <li key={crumb.id} className="flex items-center gap-x-2">
                <span aria-hidden className="text-line-strong">
                  /
                </span>
                <Link
                  href={wikiHref(crumb)}
                  className="hover:text-brand inline-flex min-h-11 items-center sm:min-h-0"
                >
                  {crumb.title}
                </Link>
              </li>
            ))}
          </ol>
        </nav>
      )}

      <article className="mt-4">
        <EditableArticle kind="page" id={page.id}>
          <div className="relative">
            <header className="border-ink border-y py-8 sm:py-10">
              <div className="flex flex-wrap items-center gap-3 pr-28 sm:pr-24">
                <p className="eyebrow text-brand">知识库 / Wiki</p>
                {page.status === 'private' ? <PrivateBadge /> : null}
              </div>
              <h1 className="font-display text-ink mt-5 max-w-[62rem] text-[36px] leading-[1.28] font-semibold tracking-[-0.018em] sm:text-[clamp(42px,4vw,54px)]">
                {page.title}
              </h1>
              <div className="text-ink-soft mt-6 flex flex-wrap items-center gap-x-5 gap-y-2 text-[12px]">
                {typeof page.views === 'number' ? (
                  <span className="inline-flex items-center gap-1.5">
                    <IconEye className="text-ink-faint h-3.5 w-3.5" />
                    {formatCount(page.views)} 次阅读
                  </span>
                ) : null}
                {updatedAt ? (
                  <span className="tabular-nums">更新于 {formatDate(updatedAt)}</span>
                ) : null}
              </div>
            </header>

            <div
              className={`mt-9 sm:mt-11 ${hasToc ? 'grid gap-12 lg:grid-cols-[minmax(0,46rem)_minmax(180px,220px)] lg:justify-between' : ''}`}
            >
              <div className="min-w-0">
                <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />
                {children.length > 0 ? (
                  <section aria-label="子页面" className="border-ink mt-14 border-t pt-6">
                    <div className="flex items-baseline justify-between gap-3">
                      <h2 className="font-display text-ink text-[25px] font-semibold">继续查阅</h2>
                      <span className="text-ink-faint text-[11px] tabular-nums">
                        {children.length} 个页面
                      </span>
                    </div>
                    <ul className="border-line mt-4 border-t">
                      {children.map((child) => (
                        <li key={child.id} className="border-line border-b">
                          <Link
                            href={wikiHref(child)}
                            className="group text-ink-body hover:text-brand flex min-h-12 items-center justify-between gap-4 py-3 text-[14px]"
                          >
                            <span>{child.title}</span>
                            <IconArrowRight className="text-ink-faint group-hover:text-brand h-3.5 w-3.5 shrink-0" />
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </section>
                ) : null}
              </div>
              {hasToc ? (
                <aside className="hidden lg:block">
                  <div className="border-ink sticky top-24 max-h-[calc(100vh-8rem)] overflow-y-auto border-t pt-4 pr-1">
                    <p className="font-display text-ink mb-4 text-[18px] font-semibold">本页目录</p>
                    <Toc items={toc} />
                  </div>
                </aside>
              ) : null}
            </div>
          </div>
        </EditableArticle>
      </article>

      <CommentsSection
        targetType="page"
        targetId={page.id}
        result={commentsRes}
        settings={settings}
        current={commentPage}
        basePath={wikiHref(page)}
      />
    </div>
  );
}
