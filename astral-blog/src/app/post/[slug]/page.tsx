import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { CommentForm } from '@/components/comment-form';
import { CommentList } from '@/components/comment-list';
import { EditableArticle } from '@/components/editable-article';
import { EmptyState } from '@/components/empty-state';
import { IconCalendar, IconChevronLeft, IconClock, IconEye, IconPin } from '@/components/icons';
import { PostCategories } from '@/components/post-categories';
import { PrivateBadge } from '@/components/private-badge';
import { TagChip } from '@/components/tag-chip';
import { Toc } from '@/components/toc';
import { getCommentPage, getPost } from '@/lib/cms';
import { estimateReadingMinutes, formatCount, formatDate } from '@/lib/format';
import { renderMarkdownWithToc } from '@/lib/markdown';
import { loadSiteSettings } from '@/lib/site';

export const dynamic = 'force-dynamic';
const COMMENT_PAGE_SIZE = 50;

interface PostPageProps { params: Promise<{ slug: string }> }

export async function generateMetadata({ params }: PostPageProps): Promise<Metadata> {
  const { slug } = await params;
  const res = await getPost(slug);
  if (!res.ok) return { title: '文章' };
  return { title: res.data.title, description: res.data.summary ?? undefined };
}

export default async function PostPage({ params }: PostPageProps) {
  const { slug } = await params;
  const res = await getPost(slug);
  if (!res.ok) {
    if (res.error === null) {
      return <div className="page-shell py-16"><EmptyState title="文章暂时无法加载" description="后端服务不可达，请稍后刷新重试。" /></div>;
    }
    notFound();
  }

  const post = res.data;
  const [commentsRes, settings] = await Promise.all([
    getCommentPage('post', post.id, 1, COMMENT_PAGE_SIZE),
    loadSiteSettings(),
  ]);
  const comments = commentsRes.ok ? commentsRes.data.records : [];
  const showComments = settings.commentEnabled || comments.length > 0;
  const { html, toc } = renderMarkdownWithToc(post.content, { articleTitle: post.title });
  const tags = post.tags ?? [];
  const publishedAt = post.publishedAt ?? post.createTime;
  const readingMinutes = estimateReadingMinutes(post.content);
  const hasToc = toc.length > 0;

  return (
    <div className="page-shell py-7 sm:py-10">
      <Link href="/" className="inline-flex min-h-11 items-center gap-1.5 text-[12px] text-ink-soft hover:text-brand sm:min-h-0">
        <IconChevronLeft className="h-3.5 w-3.5" />返回文章目录
      </Link>

      <article className="mt-4">
        <EditableArticle kind="post" id={post.id}>
          <div className="relative">
            <header className="border-y border-ink py-8 sm:py-10">
              <div className="flex flex-wrap items-center gap-2 pr-28 sm:pr-24">
                <PostCategories post={post} variant="chip" />
                {post.isTop ? (
                  <span className="inline-flex items-center gap-1 border border-brand-line bg-brand-wash px-2 py-0.5 text-[11px] font-medium text-brand">
                    <IconPin className="h-3 w-3" />置顶
                  </span>
                ) : null}
                {post.status === 'private' ? <PrivateBadge /> : null}
              </div>
              <h1 className="mt-5 max-w-[62rem] font-display text-[36px] font-semibold leading-[1.28] tracking-[-0.018em] text-ink sm:text-[clamp(42px,4.2vw,58px)]">
                {post.title}
              </h1>
              {post.summary ? (
                <p className="mt-5 max-w-[48rem] font-display text-[18px] leading-[1.85] text-ink-soft sm:text-[20px]">{post.summary}</p>
              ) : null}
              <div className="mt-7 flex flex-wrap items-center gap-x-5 gap-y-2 text-[12px] text-ink-soft">
                <span className="inline-flex items-center gap-1.5"><IconCalendar className="h-3.5 w-3.5 text-ink-faint" /><time dateTime={publishedAt ?? undefined}>{formatDate(publishedAt)}</time></span>
                <span className="inline-flex items-center gap-1.5"><IconEye className="h-3.5 w-3.5 text-ink-faint" />{formatCount(post.views)} 次阅读</span>
                <span className="inline-flex items-center gap-1.5"><IconClock className="h-3.5 w-3.5 text-ink-faint" />约 {readingMinutes} 分钟读完</span>
              </div>
            </header>

            {post.cover ? (
              <figure className="my-8 max-w-[66rem] border border-line bg-surface-muted sm:my-10">
                {/* eslint-disable-next-line @next/next/no-img-element -- 封面来自后台任意图床 */}
                <img src={post.cover} alt={post.title} loading="lazy" decoding="async" referrerPolicy="no-referrer" className="block h-auto max-h-[520px] w-full object-contain" />
              </figure>
            ) : null}

            <div className={`mt-9 sm:mt-11 ${hasToc ? 'grid gap-12 lg:grid-cols-[minmax(0,46rem)_minmax(180px,220px)] lg:justify-between' : ''}`}>
              <div className="min-w-0">
                <div className="markdown" dangerouslySetInnerHTML={{ __html: html }} />
                {tags.length > 0 ? (
                  <footer className="mt-14 border-t border-line-strong pt-5">
                    <p className="eyebrow">本文标签</p>
                    <div className="mt-4 flex flex-wrap gap-2">{tags.map((tag) => <TagChip key={tag.id} tag={tag} size="md" />)}</div>
                  </footer>
                ) : null}
              </div>
              {hasToc ? (
                <aside className="hidden lg:block">
                  <div className="sticky top-24 max-h-[calc(100vh-8rem)] overflow-y-auto border-t border-ink pt-4 pr-1">
                    <p className="mb-4 font-display text-[18px] font-semibold text-ink">本文目录</p>
                    <Toc items={toc} />
                  </div>
                </aside>
              ) : null}
            </div>
          </div>
        </EditableArticle>
      </article>

      {showComments ? (
        <section aria-label="评论" className="mt-16 max-w-[46rem] border-t border-ink pt-6">
          <div className="flex items-baseline justify-between gap-3">
            <h2 className="font-display text-[27px] font-semibold text-ink">读者评论</h2>
            <span className="text-[12px] tabular-nums text-ink-faint">{commentsRes.ok ? commentsRes.data.total : 0} 条</span>
          </div>
          <CommentList comments={comments} failed={!commentsRes.ok} total={commentsRes.ok ? commentsRes.data.total : 0} />
          {settings.commentEnabled ? <CommentForm targetType="post" targetId={post.id} needReview={settings.commentNeedReview} /> : null}
        </section>
      ) : null}
    </div>
  );
}
