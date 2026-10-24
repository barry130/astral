'use client';

import { useRouter } from 'next/navigation';
import { useState, type FormEvent } from 'react';

interface CommentFormProps {
  targetType: 'post' | 'page';
  targetId: number;
  /** 评论是否需要审核后展示（来自站点设置 comment_need_review） */
  needReview: boolean;
}

type Feedback = { kind: 'ok' | 'error'; text: string } | null;

const inputClass =
  'w-full rounded-lg border border-line bg-canvas/50 px-3 text-[13px] text-ink-body outline-none transition-colors placeholder:text-ink-faint focus:border-brand/50 focus:bg-surface focus:ring-2 focus:ring-brand/10';

/**
 * 匿名评论提交表单（客户端组件）。
 * 提交走本应用的 Route Handler（/api/comment，服务端转发到 astral 后端），浏览器不直连后端。
 * 业务失败时展示后端返回的 message（如站点关闭评论、触发限流等）。
 * 成功后的提示文案随 comment_need_review 变化。
 */
export function CommentForm({ targetType, targetId, needReview }: CommentFormProps) {
  const router = useRouter();
  const [nickname, setNickname] = useState('');
  const [email, setEmail] = useState('');
  const [content, setContent] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<Feedback>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    const trimmedNickname = nickname.trim();
    const trimmedContent = content.trim();
    if (!trimmedNickname || !trimmedContent) {
      setFeedback({ kind: 'error', text: '请填写昵称和评论内容。' });
      return;
    }
    setSubmitting(true);
    setFeedback(null);
    try {
      const res = await fetch('/api/comment', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          targetType,
          targetId,
          nickname: trimmedNickname,
          email: email.trim() || undefined,
          content: trimmedContent,
        }),
      });
      const data: { code?: number; message?: string } | null = await res.json().catch(() => null);
      if (res.ok && data?.code === 200) {
        setFeedback({
          kind: 'ok',
          // 提示文案按站点设置 comment_need_review 区分，不采信后端通用 message
          text: needReview ? '评论已提交，审核通过后展示' : '评论已发表',
        });
        setContent('');
        router.refresh();
      } else {
        setFeedback({
          kind: 'error',
          text: data?.message?.trim() || `提交失败（HTTP ${res.status}），请稍后重试。`,
        });
      }
    } catch {
      setFeedback({ kind: 'error', text: '网络异常，请稍后重试。' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="mt-8 rounded-card border border-line bg-surface p-5 shadow-card sm:p-6"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2 border-b border-line-soft pb-4">
        <p className="text-[13px] font-medium text-ink">写下评论</p>
        <p className="text-[11px] text-ink-faint">
          {needReview ? '提交后需审核通过才会展示' : '提交后立即展示'}
        </p>
      </div>
      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <input
          required
          maxLength={50}
          value={nickname}
          onChange={(event) => setNickname(event.target.value)}
          placeholder="昵称…"
          aria-label="昵称"
          autoComplete="nickname"
          className={`${inputClass} h-10`}
        />
        <input
          type="email"
          maxLength={100}
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          placeholder="邮箱（选填，不会公开）…"
          aria-label="邮箱"
          autoComplete="email"
          spellCheck={false}
          className={`${inputClass} h-10`}
        />
      </div>
      <textarea
        required
        maxLength={2000}
        rows={4}
        value={content}
        onChange={(event) => setContent(event.target.value)}
        placeholder="写下你的评论…（不超过 2000 字）"
        aria-label="评论内容"
        className={`${inputClass} mt-3 min-h-[116px] resize-y py-2.5 leading-relaxed`}
      />
      {feedback ? (
        <p
          role="status"
          className={`mt-3 text-[12.5px] ${
            feedback.kind === 'ok' ? 'text-brand' : 'text-red-600'
          }`}
        >
          {feedback.text}
        </p>
      ) : null}
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button
          type="submit"
          disabled={submitting}
          className="inline-flex h-9 items-center rounded-lg bg-brand px-4 text-[13px] font-medium text-white transition-colors hover:bg-brand-strong disabled:cursor-not-allowed disabled:opacity-60"
        >
          {submitting ? '提交中…' : '提交评论'}
        </button>
      </div>
    </form>
  );
}
