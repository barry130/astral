import { CommentForm } from '@/components/comment-form';
import { CommentList } from '@/components/comment-list';
import { Pagination } from '@/components/pagination';
import { RetryButton } from '@/components/retry-button';
import type { ApiResult, CommentItem, PageData, SiteSettings } from '@/lib/cms';

export interface CommentsSectionProps {
  targetType: 'post' | 'page';
  targetId: number;
  result: ApiResult<PageData<CommentItem>>;
  settings: SiteSettings;
  current: number;
  basePath: string;
}

export function CommentsSection({
  targetType,
  targetId,
  result,
  settings,
  current,
  basePath,
}: CommentsSectionProps) {
  const records = result.ok ? result.data.records : [];
  if (!settings.commentEnabled && result.ok && !records.length && result.data.total === 0)
    return null;
  return (
    <section
      id="comments"
      aria-label="评论"
      className="border-ink mt-16 max-w-[46rem] border-t pt-6"
    >
      <div className="flex items-baseline justify-between gap-3">
        <h2 className="font-display text-ink text-[27px] font-semibold">读者评论</h2>
        <span className="text-ink-faint text-xs tabular-nums">
          {result.ok ? `${result.data.total} 条` : '加载失败'}
        </span>
      </div>
      <CommentList
        comments={records}
        failed={!result.ok}
        total={result.ok ? result.data.total : 0}
      />
      {result.ok ? (
        <div className="mt-6">
          <Pagination
            current={current}
            pages={result.data.pages}
            basePath={basePath}
            pageParam="commentPage"
            hash="comments"
          />
        </div>
      ) : (
        <RetryButton />
      )}
      {settings.commentEnabled ? (
        <CommentForm
          targetType={targetType}
          targetId={targetId}
          needReview={settings.commentNeedReview}
        />
      ) : null}
    </section>
  );
}
