import Link from 'next/link';
import type { PostSummary } from '@/lib/cms';
import { formatCount, formatDate } from '@/lib/format';
import { postHref } from '@/lib/links';
import { EmptyState } from '@/components/empty-state';
import { IconCalendar, IconEye, IconPin } from '@/components/icons';
import { PostCategories } from '@/components/post-categories';
import { PrivateBadge } from '@/components/private-badge';
import { TagChip } from '@/components/tag-chip';

interface PostListProps {
  posts: PostSummary[] | null;
  emptyTitle: string;
  emptyDescription?: string;
  /** 首页第一页把首篇提升为卷首文章，其余页面保持连续目录。 */
  leadFirst?: boolean;
}

/** 编辑式文章目录（首页 / 分类页 / 标签页共用）。 */
export function PostList({ posts, emptyTitle, emptyDescription, leadFirst = false }: PostListProps) {
  if (!posts) {
    return <EmptyState title="文章暂时无法加载" description="后端服务不可达，请稍后刷新重试。" />;
  }
  if (posts.length === 0) {
    return <EmptyState title={emptyTitle} description={emptyDescription} />;
  }

  const [first, ...rest] = posts;
  if (leadFirst) {
    return (
      <>
        <LeadPost post={first} />
        {rest.length > 0 ? (
          <section aria-labelledby="more-posts" className="mt-9">
            <div className="flex items-baseline justify-between gap-3">
              <h2 id="more-posts" className="font-display text-[23px] font-semibold text-ink">继续阅读</h2>
              <span className="text-[12px] tabular-nums text-ink-faint">{rest.length} 篇</span>
            </div>
            <PostDirectory posts={rest} nested />
          </section>
        ) : null}
      </>
    );
  }

  return <PostDirectory posts={posts} />;
}

function PostDirectory({ posts, nested = false }: { posts: PostSummary[]; nested?: boolean }) {
  return (
    <ul className="mt-4 border-t border-ink">
      {posts.map((post) => (
        <DirectoryPost key={post.id} post={post} heading={nested ? 'h3' : 'h2'} />
      ))}
    </ul>
  );
}

/** 首篇以标题、摘要与封面组成完整编辑位，不再是一张等权卡片。 */
function LeadPost({ post }: { post: PostSummary }) {
  const tags = post.tags ?? [];
  const publishedAt = post.publishedAt ?? post.createTime;

  return (
    <article className="group relative border-b border-ink pb-8 sm:pb-10">
      <div className={post.cover ? 'grid gap-7 md:grid-cols-[minmax(0,1.15fr)_minmax(250px,0.85fr)] md:gap-8' : ''}>
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
            <p className="eyebrow text-brand">本期首篇</p>
            {post.isTop ? <TopBadge /> : null}
            {post.status === 'private' ? <PrivateBadge /> : null}
          </div>
          <h2 className="mt-4 font-display text-[32px] font-semibold leading-[1.32] tracking-[-0.015em] text-ink transition-colors group-hover:text-brand sm:text-[clamp(36px,3vw,46px)]">
            <Link href={postHref(post)}>
              <span aria-hidden className="absolute inset-0" />
              {post.title}
            </Link>
          </h2>
          {post.summary ? (
            <p className="mt-4 line-clamp-4 max-w-[40rem] text-[15px] leading-[1.95] text-ink-soft sm:text-[16px]">
              {post.summary}
            </p>
          ) : null}
          {tags.length > 0 ? (
            <div className="relative z-10 mt-5 flex flex-wrap gap-2">
              {tags.map((tag) => <TagChip key={tag.id} tag={tag} />)}
            </div>
          ) : null}
          <PostMeta post={post} publishedAt={publishedAt} className="mt-6" />
        </div>

        {post.cover ? (
          <div className="relative aspect-[16/9] overflow-hidden border border-line bg-surface-muted md:aspect-auto md:min-h-[290px]">
            {/* eslint-disable-next-line @next/next/no-img-element -- 后台封面允许任意外链 */}
            <img
              src={post.cover}
              alt=""
              loading="lazy"
              decoding="async"
              referrerPolicy="no-referrer"
              className="absolute inset-0 h-full w-full object-cover transition-transform duration-500 group-hover:scale-[1.015]"
            />
          </div>
        ) : null}
      </div>
    </article>
  );
}

/** 后续文章使用书刊目录行，封面退为小插图，标题始终是视觉主角。 */
function DirectoryPost({ post, heading: Heading }: { post: PostSummary; heading: 'h2' | 'h3' }) {
  const tags = post.tags ?? [];
  const publishedAt = post.publishedAt ?? post.createTime;

  return (
    <li className="border-b border-line">
      <article className="group relative flex gap-5 py-6 sm:py-7">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
            <PostCategories post={post} />
            {post.isTop ? <TopBadge compact /> : null}
            {post.status === 'private' ? <PrivateBadge className="px-1.5 py-0 text-[10px]" /> : null}
          </div>
          <Heading className="mt-2 font-display text-[23px] font-semibold leading-[1.45] text-ink transition-colors group-hover:text-brand sm:text-[25px]">
            <Link href={postHref(post)}>
              <span aria-hidden className="absolute inset-0" />
              {post.title}
            </Link>
          </Heading>
          {post.summary ? <p className="mt-2 line-clamp-2 text-[13.5px] leading-7 text-ink-soft sm:text-[14px]">{post.summary}</p> : null}
          {tags.length > 0 ? (
            <div className="relative z-10 mt-3 flex flex-wrap gap-1.5">
              {tags.map((tag) => <TagChip key={tag.id} tag={tag} />)}
            </div>
          ) : null}
          <PostMeta post={post} publishedAt={publishedAt} className="mt-4" showCategory={false} />
        </div>

        {post.cover ? (
          <div className="relative hidden h-[116px] w-[164px] shrink-0 self-center overflow-hidden border border-line bg-surface-muted sm:block">
            {/* eslint-disable-next-line @next/next/no-img-element -- 后台封面允许任意外链 */}
            <img
              src={post.cover}
              alt=""
              loading="lazy"
              decoding="async"
              referrerPolicy="no-referrer"
              className="absolute inset-0 h-full w-full object-cover"
            />
          </div>
        ) : null}
      </article>
    </li>
  );
}

function PostMeta({
  post,
  publishedAt,
  className = '',
  showCategory = true,
}: {
  post: PostSummary;
  publishedAt: string | null;
  className?: string;
  showCategory?: boolean;
}) {
  return (
    <div className={`flex flex-wrap items-center gap-x-4 gap-y-2 text-[12px] text-ink-soft ${className}`}>
      {showCategory ? <PostCategories post={post} /> : null}
      <time dateTime={publishedAt ?? undefined} className="inline-flex items-center gap-1.5 tabular-nums">
        <IconCalendar className="h-3.5 w-3.5 text-ink-faint" />
        {formatDate(publishedAt)}
      </time>
      <span className="inline-flex items-center gap-1.5">
        <IconEye className="h-3.5 w-3.5 text-ink-faint" />
        {formatCount(post.views)} 阅读
      </span>
    </div>
  );
}

function TopBadge({ compact = false }: { compact?: boolean }) {
  return (
    <span className={`relative z-10 inline-flex items-center gap-1 border border-brand-line bg-brand-wash px-2 py-0.5 font-medium text-brand ${compact ? 'text-[10px]' : 'text-[11px]'}`}>
      <IconPin className="h-2.5 w-2.5" />
      置顶
    </span>
  );
}
