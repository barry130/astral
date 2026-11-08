'use client';

import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { validateComment } from '@/lib/comment';
import { requestJson } from '@/lib/client-request';
import { parseResult, resultMessage } from '@/lib/result';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';

interface CommentFormProps {
  targetType: 'post' | 'page';
  targetId: number;
  needReview: boolean;
}
type Feedback = { kind: 'ok' | 'error'; text: string } | null;

/** A different target owns a different draft and invalidates old submissions. */
export function CommentForm(props: CommentFormProps) {
  return <TargetCommentForm key={`${props.targetType}:${props.targetId}`} {...props} />;
}

function TargetCommentForm({ targetType, targetId, needReview }: CommentFormProps) {
  const router = useRouter();
  const [nickname, setNickname] = useState('');
  const [email, setEmail] = useState('');
  const [content, setContent] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<Feedback>(null);
  const mounted = useRef(true);
  const pending = useRef(false);
  const controller = useRef<AbortController | null>(null);
  const currentContent = useRef(content);
  currentContent.current = content;
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending.current) return;
    const payload = validateComment({ targetType, targetId, nickname, email, content });
    if (!payload.ok) {
      setFeedback({ kind: 'error', text: payload.error });
      return;
    }
    pending.current = true;
    controller.current = new AbortController();
    const submittedContent = content;
    setSubmitting(true);
    setFeedback(null);
    try {
      const { response, body: raw } = await requestJson('/api/comment', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload.data),
        signal: controller.current.signal,
      });
      if (!mounted.current) return;
      const body = parseResult(raw);
      if (response.ok && body?.code === 200) {
        setFeedback({ kind: 'ok', text: needReview ? '评论已提交，审核通过后展示' : '评论已发表' });
        // Pending inputs are disabled; this extra revision check also protects
        // drafts changed programmatically while a request is in flight.
        if (currentContent.current === submittedContent) setContent('');
        router.refresh();
      } else
        setFeedback({
          kind: 'error',
          text: resultMessage(body, '提交结果无法确认，请稍后核对再重试。'),
        });
    } catch {
      if (mounted.current)
        setFeedback({ kind: 'error', text: '提交结果无法确认，请稍后核对再重试。' });
    } finally {
      pending.current = false;
      if (mounted.current) setSubmitting(false);
    }
  }

  const prefix = `comment-${targetType}-${targetId}`;
  return (
    <form
      onSubmit={handleSubmit}
      aria-busy={submitting}
      className="border-line-strong bg-surface/45 mt-8 border-y px-5 py-6 sm:px-7"
    >
      <div className="border-line-soft flex flex-wrap items-baseline justify-between gap-2 border-b pb-4">
        <h3 className="text-ink text-sm font-medium">写下评论</h3>
        <p className="text-ink-faint text-xs">
          {needReview ? '提交后需审核通过才会展示' : '提交后立即展示'}
        </p>
      </div>
      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor={`${prefix}-nickname`}>昵称</Label>
          <Input
            id={`${prefix}-nickname`}
            name="nickname"
            spellCheck={false}
            required
            maxLength={64}
            disabled={submitting}
            value={nickname}
            onChange={(event) => setNickname(event.target.value)}
            aria-label="昵称"
            autoComplete="nickname"
            className="h-11"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor={`${prefix}-email`}>邮箱（选填，不公开）</Label>
          <Input
            id={`${prefix}-email`}
            name="email"
            type="email"
            maxLength={128}
            disabled={submitting}
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            aria-label="邮箱"
            autoComplete="email"
            spellCheck={false}
            className="h-11"
          />
        </div>
      </div>
      <Label htmlFor={`${prefix}-content`} className="mt-4 mb-1.5">
        评论内容
      </Label>
      <Textarea
        id={`${prefix}-content`}
        name="content"
        autoComplete="off"
        required
        maxLength={2000}
        disabled={submitting}
        rows={4}
        value={content}
        onChange={(event) => setContent(event.target.value)}
        placeholder="写下你的评论（不超过 2000 字）…"
        aria-label="评论内容"
        className="min-h-[116px] leading-relaxed"
      />
      {feedback ? (
        <p
          role={feedback.kind === 'ok' ? 'status' : 'alert'}
          className={`mt-3 text-sm ${feedback.kind === 'ok' ? 'text-brand' : 'text-destructive'}`}
        >
          {feedback.text}
        </p>
      ) : null}
      <Button type="submit" disabled={submitting} className="mt-4 h-11">
        {submitting ? '提交中…' : '提交评论'}
      </Button>
    </form>
  );
}
