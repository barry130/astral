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

interface PostPageProps {
  params: Promise<{ slug: string }>;
}

export async function generateMetadata({ params }: PostPageProps): Promise<Metadata> {
  const { slug } = await params;
  const res = await getPost(slug);
  if (!res.ok) {
    return { title: '文章' };
  }
  return {
    title: res.data.title,
    description: res.data.summary ?? undefined,
  };
}

export default async function PostPage({ params }: PostPageProps) {
  const { slug } = await params;
  const res = await getPost(slug);

  // 业务性失败（不存在 / 未发布）→ 404；网络层失败（error === null）→ 友好空态
  if (!res.ok) {
    if (res.error === null) {
      return (
        <div className="page-shell py-16">
          <EmptyState
            title="文章暂时无法加载"
            description="后端服务不可达，请稍后刷新重试。"
          />
        </div>
      );
    }
    notFound();
  }

  const post = res.data;
  const [commentsRes, settings] = await Promise.all([
    getCommentPage('post', post.id, 1, COMMENT_PAGE_SIZE),
    loadSiteSettings(),
  ]);
  const comments = commentsRes.ok ? commentsRes.data.records : [];
  // 评论关闭时仍展示已有评论；一条都没有则整块不渲染
  const showComments = settings.commentEnabled || comments.length > 0;
  const { html, toc } = renderMarkdownWithToc(post.content, { articleTitle: post.title });
  const tags = post.tags ?? [];
  const publishedAt = post.publishedAt ?? post.createTime;
  const readingMinutes = estimateReadingMinutes(post.content);
  // 没有任何 h1~h3 时不占用左侧栏，正文改回居中的单栏（窄屏本就不显示目录）
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
          <Link
            href="/"
            className="inline-flex items-center gap-1.5 text-[12px] text-ink-soft transition-colors hover:text-brand"
          >
            <IconChevronLeft className="h-3.5 w-3.5" />
            返回文章列表
          </Link>

          <article className="paper mt-5 px-6 py-8 shadow-paper sm:px-10 sm:py-11">
            <EditableArticle kind="post" id={post.id}>
              <div className="relative">
                <header>
                  <div className="flex flex-wrap items-center gap-2">
                    <PostCategories post={post} variant="chip" />
                    {post.isTop ? (
                      <span className="inline-flex items-center gap-1 rounded-full border border-brand-line/70 bg-brand-wash px-2.5 py-0.5 text-[11px] font-medium text-brand">
                        <IconPin className="h-3 w-3" />
                        置顶
                      </span>
                    ) : null}
                    {/* 私密内容只有登录态可见，标注出来避免误以为已公开 */}
                    {post.status === 'private' ? <PrivateBadge /> : null}
                  </div>

                  <h1 className="mt-4 text-[27px] font-semibold leading-[1.3] tracking-[-0.02em] text-ink sm:text-[clamp(30px,0.6vw+22px,38px)]">
                    {post.title}
                  </h1>

                  <div className="mt-5 flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line-soft pb-6 text-[12px] text-ink-soft">
                    <span className="inline-flex items-center gap-1.5">
                      <IconCalendar className="h-3.5 w-3.5 text-ink-faint" />
                      <time dateTime={publishedAt ?? undefined}>{formatDate(publishedAt)}</time>
                    </span>
                    <span className="inline-flex items-center gap-1.5">
                      <IconEye className="h-3.5 w-3.5 text-ink-faint" />
                      {formatCount(post.views)} 次阅读
                    </span>
                    <span className="inline-flex items-center gap-1.5">
                      <IconClock className="h-3.5 w-3.5 text-ink-faint" />
                      约 {readingMinutes} 分钟读完
                    </span>
                  </div>
                </header>

                {post.cover ? (
                  <figure className="mt-7 overflow-hidden rounded-panel border border-line bg-surface-muted">
                    {/* eslint-disable-next-line @next/next/no-img-element -- 封面来自后台任意图床，不接入 next/image 域名白名单 */}
                    <img
                      src={post.cover}
                      alt={post.title}
                      loading="lazy"
                      decoding="async"
                      // 图床按 Referer 防盗链：不发 Referer 才能取到图（否则非白名单域 403）
                      referrerPolicy="no-referrer"
                      // 不裁剪：封面常常是超宽截图（如 1209x205），object-cover 会把它压成一条窄带
                      className="block h-auto max-h-[440px] w-full object-contain"
                    />
                  </figure>
                ) : null}

                <div className="markdown mt-9" dangerouslySetInnerHTML={{ __html: html }} />

                {tags.length > 0 ? (
                  <footer className="mt-12 border-t border-line-soft pt-6">
                    <p className="eyebrow">标签</p>
                    <div className="mt-3 flex flex-wrap gap-1.5">
                      {tags.map((tag) => (
                        <TagChip key={tag.id} tag={tag} />
                      ))}
                    </div>
                  </footer>
                ) : null}
              </div>
            </EditableArticle>
          </article>

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
                  targetType="post"
                  targetId={post.id}
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
