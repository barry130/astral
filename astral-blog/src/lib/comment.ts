import { isRecord } from '@/lib/result';
import type { CommentSubmitPayload } from '@/lib/cms';

export function validateComment(
  raw: unknown,
): { ok: true; data: CommentSubmitPayload } | { ok: false; error: string } {
  if (
    !isRecord(raw) ||
    (raw.targetType !== 'post' && raw.targetType !== 'page') ||
    typeof raw.targetId !== 'number' ||
    !Number.isSafeInteger(raw.targetId) ||
    raw.targetId <= 0
  ) {
    return { ok: false, error: '评论目标无效。' };
  }
  const nickname = typeof raw.nickname === 'string' ? raw.nickname.trim() : '';
  const content = typeof raw.content === 'string' ? raw.content.trim() : '';
  const email = typeof raw.email === 'string' ? raw.email.trim() : '';
  if (!nickname || nickname.length > 64) return { ok: false, error: '昵称必填且不能超过 64 字。' };
  if (!content || content.length > 2000)
    return { ok: false, error: '评论必填且不能超过 2000 字。' };
  if (
    (raw.email != null && typeof raw.email !== 'string') ||
    email.length > 128 ||
    (email && !/^[^\s@]+@[^\s@]+$/.test(email))
  )
    return { ok: false, error: '请填写有效邮箱（不超过 128 字）。' };
  return {
    ok: true,
    data: {
      targetType: raw.targetType,
      targetId: raw.targetId,
      nickname,
      content,
      ...(email ? { email } : {}),
    },
  };
}
