import { NextResponse } from 'next/server';
import { postCommentToBackend, type CommentSubmitPayload } from '@/lib/cms';

/**
 * 评论提交转发（Route Handler）：
 * 浏览器 → /api/comment（本应用）→ {CMS_API_BASE}/api/v1/app/cms/comment（astral 后端）。
 * 浏览器不直连后端，无 CORS 问题；后端限流按服务端出口 IP 记（部署反向代理如需真实客户端 IP，
 * 需在转发时透传 X-Forwarded-For，并确认该出口在后端 trusted-proxies 白名单内）。
 */
export async function POST(request: Request) {
  let payload: Partial<CommentSubmitPayload> | null = null;
  try {
    payload = (await request.json()) as Partial<CommentSubmitPayload>;
  } catch {
    payload = null;
  }

  const targetType = payload?.targetType;
  const targetId = payload?.targetId;
  const nickname = typeof payload?.nickname === 'string' ? payload.nickname.trim() : '';
  const content = typeof payload?.content === 'string' ? payload.content.trim() : '';
  const email = typeof payload?.email === 'string' ? payload.email.trim() : '';

  if (
    (targetType !== 'post' && targetType !== 'page') ||
    typeof targetId !== 'number' ||
    !Number.isInteger(targetId) ||
    !nickname ||
    !content
  ) {
    return NextResponse.json(
      { code: 400, message: '参数不完整：targetType、targetId、昵称与内容均为必填。' },
      { status: 400 },
    );
  }
  if (content.length > 2000) {
    return NextResponse.json({ code: 400, message: '评论内容不能超过 2000 字。' }, { status: 400 });
  }

  const body: CommentSubmitPayload = {
    targetType,
    targetId,
    nickname,
    content,
    ...(email ? { email } : {}),
  };

  const upstream = await postCommentToBackend(body);
  if (!upstream.reachable || !upstream.result) {
    return NextResponse.json(
      { code: 502, message: '后端服务暂时不可达，请稍后重试。' },
      { status: 502 },
    );
  }

  // 透传后端 Result：业务失败（评论关闭/限流/审核策略等）时 code != 200，message 交给前端展示
  return NextResponse.json(upstream.result, { status: upstream.httpStatus });
}
