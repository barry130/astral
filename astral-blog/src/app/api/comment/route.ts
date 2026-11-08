import { postCommentToBackend } from '@/lib/cms';
import {
  guardWriteOrigin,
  privateJson,
  readRequestJson,
  requestBodyError,
  upstreamFailure,
} from '@/lib/bff';
import { validateComment } from '@/lib/comment';
import { COMMENT_COOKIE, commentLimiter, resolveThrottleKey } from '@/lib/rate-limit';

export const dynamic = 'force-dynamic';

export async function POST(request: Request) {
  const denied = guardWriteOrigin(request);
  if (denied) return denied;
  let raw: unknown;
  try {
    raw = await readRequestJson(request, 8192);
  } catch (error) {
    return requestBodyError(error);
  }
  const payload = validateComment(raw);
  if (!payload.ok) return privateJson({ code: 320, msg: payload.error, data: null });
  // Optional best-effort local flood control. The backend remains authoritative;
  // local visitor cookies do not repair its shared-BFF-IP quota.
  const throttle =
    process.env.BLOG_COMMENT_LOCAL_LIMIT === 'true' ? resolveThrottleKey(request) : null;
  if (throttle) {
    const verdict = commentLimiter.check(throttle.key);
    if (!verdict.allowed) {
      const response = privateJson({
        code: 429,
        msg: `提交太频繁，请 ${verdict.retryAfterSeconds} 秒后重试。`,
        data: null,
      });
      response.headers.set('retry-after', String(verdict.retryAfterSeconds));
      return response;
    }
  }
  const upstream = await postCommentToBackend(payload.data);
  const response =
    !upstream.reachable || !upstream.result
      ? privateJson({ code: 502, msg: '提交结果无法确认，请稍后核对再重试。', data: null }, 502)
      : upstream.httpStatus === 200
        ? privateJson(upstream.result)
        : upstreamFailure(
            upstream.result,
            '提交结果无法确认，请稍后核对再重试。',
            upstream.httpStatus,
          );
  if (throttle?.issuedVisitorId)
    response.cookies.set(COMMENT_COOKIE, throttle.issuedVisitorId, {
      httpOnly: true,
      sameSite: 'lax',
      path: '/',
      maxAge: 86400,
      secure: new URL(request.headers.get('origin') || request.url).protocol === 'https:',
    });
  return response;
}
