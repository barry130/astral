import { formatDateTime } from '@/lib/format';
import type { CommentItem } from '@/lib/cms';

interface CommentListProps {
  comments: CommentItem[];
  failed: boolean;
  total: number;
}

/** 已通过审核的评论列表（服务端渲染，数据由页面 RSC 拉取） */
export function CommentList({ comments, failed, total }: CommentListProps) {
  if (failed) {
    return <p className="mt-6 text-[12.5px] text-ink-soft">评论加载失败，请稍后刷新重试。</p>;
  }
  if (comments.length === 0) {
    return (
      <p className="mt-6 rounded-panel border border-dashed border-line bg-surface/50 px-5 py-6 text-center text-[12.5px] text-ink-soft">
        还没有评论，来留下第一条。
      </p>
    );
  }
  return (
    <ul className="mt-6 space-y-3">
      {comments.map((comment) => {
        const initial = Array.from(comment.nickname.trim())[0] ?? '·';
        return (
          <li
            key={comment.id}
            className="rounded-panel border border-line bg-surface px-4 py-3.5 shadow-card"
          >
            <div className="flex items-center gap-2.5">
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full border border-brand-line/70 bg-brand-wash text-[11px] font-medium text-brand">
                {initial}
              </span>
              <span className="min-w-0 truncate text-[12.5px] font-medium text-ink">
                {comment.nickname}
              </span>
              <time
                dateTime={comment.createTime ?? undefined}
                className="ml-auto shrink-0 text-[11px] tabular-nums text-ink-faint"
              >
                {formatDateTime(comment.createTime)}
              </time>
            </div>
            <p className="mt-2.5 whitespace-pre-wrap pl-[38px] text-[13px] leading-[1.85] text-ink-body">
              {comment.content}
            </p>
          </li>
        );
      })}
      {/* 评论接口一次拉取前 50 条，超过时给出提示（公开博客场景足够） */}
      {total > comments.length ? (
        <li className="pt-1 text-center text-[11px] text-ink-faint">
          仅展示最近 {comments.length} 条评论
        </li>
      ) : null}
    </ul>
  );
}
