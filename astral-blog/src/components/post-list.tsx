import Link from 'next/link';
import type { PostSummary } from '@/lib/cms';
import { formatCount, formatDate } from '@/lib/format';
import { postHref } from '@/lib/links';
import { resolvePostCategories } from '@/lib/post';
import { EmptyState } from '@/components/empty-state';
import { IconCalendar, IconEye, IconPin } from '@/components/icons';
import { PostCategories } from '@/components/post-categories';
import { PrivateBadge } from '@/components/private-badge';
import { TagChip } from '@/components/tag-chip';

interface PostListProps {
  posts: PostSummary[] | null;
  emptyTitle: string;
  emptyDescription?: string;
}

/** 文章列表（首页 / 分类页 / 标签页共用） */
export function PostList({ posts, emptyTitle, emptyDescription }: PostListProps) {
  if (!posts) {
    return (
      <EmptyState title="文章暂时无法加载" description="后端服务不可达，请稍后刷新重试。" />
    );
  }
  if (posts.length === 0) {
    return <EmptyState title={emptyTitle} description={emptyDescription} />;
  }
  return (
    <ul className="space-y-4">
      {posts.map((post) => (
        <PostListItem key={post.id} post={post} />
      ))}
    </ul>
  );
}

/**
 * 文章卡片。
 *
 * 阅读顺序按「先看内容、再看元信息」排：
 *   标题 → 摘要 →（标签）→ 分类 · 日期 · 阅读数
 * 元信息压在卡片底部、字号最小，扫读时不会和标题抢视线。
 */
function PostListItem({ post }: { post: PostSummary }) {
  const tags = post.tags ?? [];
  const categories = resolvePostCategories(post);
  const publishedAt = post.publishedAt ?? post.createTime;

  return (
    <li>
      <article className="group relative flex flex-col overflow-hidden rounded-card border border-line bg-surface shadow-card transition-[border-color,box-shadow] duration-200 focus-within:border-brand/40 focus-within:shadow-card-hover sm:flex-row">
        {post.cover ? (
          // 封面来自后台任意图床，不接 next/image 域名白名单
          <div className="relative h-40 w-full shrink-0 overflow-hidden border-b border-line-soft bg-surface-muted sm:h-auto sm:min-h-[184px] sm:w-[200px] sm:border-b-0 sm:border-r">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={post.cover}
              alt=""
              loading="lazy"
              decoding="async"
              // 图床按 Referer 防盗链：不发 Referer 才能取到图（否则非白名单域 403）
              referrerPolicy="no-referrer"
              className="absolute inset-0 h-full w-full object-cover"
            />
          </div>
        ) : null}

        <div className="flex min-w-0 flex-1 flex-col p-5 sm:px-6 sm:py-5">
          <h2 className="text-[17px] font-semibold leading-[1.55] tracking-[-0.005em] text-ink transition-colors duration-150 group-hover:text-brand sm:text-[clamp(18px,0.3vw+15px,20.5px)]">
            {post.isTop ? (
              <span className="mr-2 inline-flex translate-y-[-1px] items-center gap-1 rounded-full border border-brand-line/70 bg-brand-wash px-2 py-[2px] align-middle text-[10.5px] font-medium leading-none text-brand">
                <IconPin className="h-2.5 w-2.5" />
                置顶
              </span>
            ) : null}
            {/* 私密文章只有登录且有权的人能看到，列表里同样标出来 */}
            {post.status === 'private' ? (
              <PrivateBadge className="mr-2 translate-y-[-1px] px-2 py-[2px] text-[10.5px] leading-none align-middle" />
            ) : null}
            {/* stretched-link：整张卡片可点，分类/标签链接用 z-10 浮在上层 */}
            <Link href={postHref(post)}>
              <span aria-hidden className="absolute inset-0" />
              {post.title}
            </Link>
          </h2>

          {post.summary ? (
            <p
              className={`mt-2.5 text-[13.5px] leading-[1.85] text-ink-soft sm:text-[clamp(13.5px,0.2vw+12px,15px)] ${
                post.cover ? 'line-clamp-2' : 'line-clamp-3'
              }`}
            >
              {post.summary}
            </p>
          ) : null}

          {tags.length > 0 ? (
            <div className="relative z-10 mt-3 flex flex-wrap gap-1.5">
              {tags.map((tag) => (
                <TagChip key={tag.id} tag={tag} />
              ))}
            </div>
          ) : null}

          {/* 元信息沉底：分类 · 日期 · 阅读数 */}
          <div className="mt-auto flex flex-wrap items-center gap-x-2.5 gap-y-1.5 pt-4 text-[11.5px] leading-none text-ink-soft">
            <PostCategories post={post} />
            {categories.length > 0 ? (
              <span aria-hidden className="h-[3px] w-[3px] rounded-full bg-line-strong" />
            ) : null}
            <time dateTime={publishedAt ?? undefined} className="inline-flex items-center gap-1.5">
              <IconCalendar className="h-3 w-3 text-ink-faint" />
              {formatDate(publishedAt)}
            </time>
            <span aria-hidden className="h-[3px] w-[3px] rounded-full bg-line-strong" />
            <span className="inline-flex items-center gap-1.5">
              <IconEye className="h-3 w-3 text-ink-faint" />
              {formatCount(post.views)} 阅读
            </span>
          </div>
        </div>
      </article>
    </li>
  );
}
