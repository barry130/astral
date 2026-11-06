import { formatDateTime } from '@/lib/format';
import type { CommentItem } from '@/lib/cms';

interface CommentListProps { comments: CommentItem[]; failed: boolean; total: number }

/** 评论作为正文后的连续读者来信，不再逐条套卡片。 */
export function CommentList({ comments, failed, total }: CommentListProps) {
  if (failed) return <p className="mt-6 text-[12.5px] text-ink-soft">评论加载失败，请稍后刷新重试。</p>;
  if (comments.length === 0) {
    return <p className="mt-6 border-y border-dashed border-line px-4 py-8 text-center text-[12.5px] text-ink-soft">还没有评论，来留下第一条。</p>;
  }
  return (
    <ul className="mt-6 border-t border-line">
      {comments.map((comment) => {
        const initial = Array.from(comment.nickname.trim())[0] ?? '·';
        return (
          <li key={comment.id} className="border-b border-line py-5">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <span aria-hidden className="flex size-8 items-center justify-center border border-brand-line bg-brand-wash font-display text-[14px] text-brand">{initial}</span>
              <span className="min-w-0 truncate text-[13px] font-medium text-ink">{comment.nickname}</span>
              <time dateTime={comment.createTime ?? undefined} className="ml-auto shrink-0 text-[11px] tabular-nums text-ink-faint">{formatDateTime(comment.createTime)}</time>
            </div>
            <p className="mt-3 whitespace-pre-wrap pl-11 text-[14px] leading-[1.9] text-ink-body">{comment.content}</p>
          </li>
        );
      })}
      {total > comments.length ? <li className="pt-3 text-center text-[11px] text-ink-faint">仅展示最近 {comments.length} 条评论</li> : null}
    </ul>
  );
}
