import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { CommentsSection } from '@/components/comments-section';
import { EditableArticle } from '@/components/editable-article';
import { IconCalendar, IconChevronLeft, IconClock, IconEye, IconPin } from '@/components/icons';
import { PostCategories } from '@/components/post-categories';
import { PrivateBadge } from '@/components/private-badge';
import { TagChip } from '@/components/tag-chip';
import { Toc } from '@/components/toc';
import { getCommentPage, getPost } from '@/lib/cms';
import { postHref } from '@/lib/links';
import { estimateReadingMinutes, formatCount, formatDate } from '@/lib/format';
import { renderMarkdownWithToc } from '@/lib/markdown';
import { loadSiteSettings } from '@/lib/site';
import { parsePageNumber } from '@/lib/page-input';

export const dynamic = 'force-dynamic';
const COMMENT_PAGE_SIZE = 50;

interface PostPageProps {
  params: Promise<{ slug: string }>;
  searchParams: Promise<{ commentPage?: string | string[] }>;
}

export async function generateMetadata({ params }: PostPageProps): Promise<Metadata> {
  const { slug } = await params;
  const res = await getPost(slug);
  if (!res.ok) return { title: '文章不可用', robots: { index: false, follow: false } };
  const post = res.data;
  const description = post.summary ?? undefined;
  return {
    title: post.title,
    description,
    alternates: { canonical: postHref(post) },
    ...(post.status !== 'published' ? { robots: { index: false, follow: false } } : {}),
    openGraph: {
      type: 'article',
      title: post.title,
      description,
      url: postHref(post),
      publishedTime: post.publishedAt ?? undefined,
      modifiedTime: post.updateTime ?? undefined,
      images: post.cover ? [post.cover] : undefined,
    },
  };
}

export default async function PostPage({ params, searchParams }: PostPageProps) {
  const commentPage = parsePageNumber((await searchParams).commentPage);
  if (commentPage === null) notFound();
  const { slug } = await params;
  const res = await getPost(slug);
  if (!res.ok) {
    if (res.notFound) notFound();
    throw new Error('文章暂时无法加载，请重试。');
  }

  const post = res.data;
  const [commentsRes, settings] = await Promise.all([
    getCommentPage('post', post.id, commentPage, COMMENT_PAGE_SIZE),
    loadSiteSettings(),
  ]);
  if (commentsRes.ok && commentPage > Math.max(1, commentsRes.data.pages)) notFound();
  const { html, toc } = renderMarkdownWithToc(post.content, { articleTitle: post.title });
  const tags = post.tags ?? [];
  const publishedAt = post.publishedAt ?? post.createTime;
  const readingMinutes = estimateReadingMinutes(post.content);
  const hasToc = toc.length > 0;

  return (
    <div className="page-shell py-7 sm:py-10">
      <Link
        href="/"
        className="text-ink-soft hover:text-brand inline-flex min-h-11 items-center gap-1.5 text-[12px] sm:min-h-0"
      >
        <IconChevronLeft className="h-3.5 w-3.5" />
        返回文章目录
      </Link>

      <article className="mt-4">
        <EditableArticle kind="post" id={post.id}>
          <div className="relative">
            <header className="border-ink border-y py-8 sm:py-10">
              <div className="flex flex-wrap items-center gap-2 pr-28 sm:pr-24">
                <PostCategories post={post} variant="chip" />
                {post.isTop ? (
                  <span className="border-brand-line bg-brand-wash text-brand inline-flex items-center gap-1 border px-2 py-0.5 text-[11px] font-medium">
                    <IconPin className="h-3 w-3" />
                    置顶
                  </span>
                ) : null}
                {post.status === 'private' ? <PrivateBadge /> : null}
              </div>
              <h1 className="font-display text-ink mt-5 max-w-[62rem] text-[36px] leading-[1.28] font-semibold tracking-[-0.018em] sm:text-[clamp(42px,4.2vw,58px)]">
                {post.title}
              </h1>
              {post.summary ? (
                <p className="font-display text-ink-soft mt-5 max-w-[48rem] text-[18px] leading-[1.85] sm:text-[20px]">
                  {post.summary}
                </p>
              ) : null}
              <div className="text-ink-soft mt-7 flex flex-wrap items-center gap-x-5 gap-y-2 text-[12px]">
                <span className="inline-flex items-center gap-1.5">
                  <IconCalendar className="text-ink-faint h-3.5 w-3.5" />
                  <time dateTime={publishedAt ?? undefined}>{formatDate(publishedAt)}</time>
                </span>
                <span className="inline-flex items-center gap-1.5">
                  <IconEye className="text-ink-faint h-3.5 w-3.5" />
                  {formatCount(post.views)} 次阅读
                </span>
                <span className="inline-flex items-center gap-1.5">
                  <IconClock className="text-ink-faint h-3.5 w-3.5" />约 {readingMinutes} 分钟读完
                </span>
              </div>
            </header>

            {post.cover ? (
              <figure className="border-line bg-surface-muted my-8 max-w-[66rem] border sm:my-10">
                {/* Validated CMS cover, without leaking referrer to remote image hosts. */}
                <img
                  src={post.cover}
                  alt={post.title}
                  loading="lazy"
                  decoding="async"
                  referrerPolicy="no-referrer"
                  className="block h-auto max-h-[520px] w-full object-contain"
                />
              </figure>
            ) : null}

            <div
              className={`mt-9 sm:mt-11 ${hasToc ? 'grid gap-12 lg:grid-cols-[minmax(0,46rem)_minmax(180px,220px)] lg:justify-between' : ''}`}
            >
              <div className="min-w-0">
                <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />
                {tags.length > 0 ? (
                  <footer className="border-line-strong mt-14 border-t pt-5">
                    <p className="eyebrow">本文标签</p>
                    <div className="mt-4 flex flex-wrap gap-2">
                      {tags.map((tag) => (
                        <TagChip key={tag.id} tag={tag} size="md" />
                      ))}
                    </div>
                  </footer>
                ) : null}
              </div>
              {hasToc ? (
                <aside className="hidden lg:block">
                  <div className="border-ink sticky top-24 max-h-[calc(100vh-8rem)] overflow-y-auto border-t pt-4 pr-1">
                    <p className="font-display text-ink mb-4 text-[18px] font-semibold">本文目录</p>
                    <Toc items={toc} />
                  </div>
                </aside>
              ) : null}
            </div>
          </div>
        </EditableArticle>
      </article>

      <CommentsSection
        targetType="post"
        targetId={post.id}
        result={commentsRes}
        settings={settings}
        current={commentPage}
        basePath={postHref(post)}
      />
    </div>
  );
}
