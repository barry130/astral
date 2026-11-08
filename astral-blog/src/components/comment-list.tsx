import { formatDateTime } from '@/lib/format';
import type { CommentItem } from '@/lib/cms';

interface CommentListProps {
  comments: CommentItem[];
  failed: boolean;
  total: number;
}

/** 评论作为正文后的连续读者来信，不再逐条套卡片。 */
export function CommentList({ comments, failed, total }: CommentListProps) {
  if (failed)
    return <p className="text-ink-soft mt-6 text-[12.5px]">评论加载失败，请稍后刷新重试。</p>;
  if (comments.length === 0) {
    return (
      <p className="border-line text-ink-soft mt-6 border-y border-dashed px-4 py-8 text-center text-[12.5px]">
        还没有评论，来留下第一条。
      </p>
    );
  }
  return (
    <ul className="border-line mt-6 border-t">
      {comments.map((comment) => {
        const initial = Array.from(comment.nickname.trim())[0] ?? '·';
        return (
          <li key={comment.id} className="border-line border-b py-5">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <span
                aria-hidden
                className="border-brand-line bg-brand-wash font-display text-brand flex size-8 items-center justify-center border text-[14px]"
              >
                {initial}
              </span>
              <span className="text-ink min-w-0 truncate text-[13px] font-medium">
                {comment.nickname}
              </span>
              <time
                dateTime={comment.createTime ?? undefined}
                className="text-ink-faint ml-auto shrink-0 text-[11px] tabular-nums"
              >
                {formatDateTime(comment.createTime)}
              </time>
            </div>
            <p className="text-ink-body mt-3 pl-11 text-[14px] leading-[1.9] whitespace-pre-wrap">
              {comment.content}
            </p>
          </li>
        );
      })}
      {total > comments.length ? (
        <li className="text-ink-faint pt-3 text-center text-[11px]">
          本页展示 {comments.length} 条评论
        </li>
      ) : null}
    </ul>
  );
}
