import { backendFetch } from '@/lib/auth-server';
import { clearAuthCookies, guardWriteOrigin, privateJson } from '@/lib/bff';
import { parseResult, resultMessage } from '@/lib/result';

export const dynamic = 'force-dynamic';

/** Local logout and upstream token revocation are separate, explicit outcomes. */
export async function POST(request: Request) {
  const denied = guardWriteOrigin(request);
  if (denied) return denied;
  let code = 502;
  let status = 502;
  let msg = '已退出本站，但后端不可达，无法确认令牌已撤销。';
  let upstreamRevoked = false;
  try {
    const upstream = await backendFetch('/api/v1/all/auth/logout', { method: 'POST' });
    const body = parseResult(await upstream.json().catch(() => null));
    upstreamRevoked = upstream.status === 200 && body?.code === 200;
    if (upstreamRevoked) {
      code = 200;
      status = 200;
      msg = '已退出登录';
    } else if (body) {
      code = body.code === 200 ? 502 : body.code;
      status = upstream.status;
      msg = resultMessage(body.code === 200 ? null : body, msg);
    }
  } catch {
    // Always remove the two local credentials; do not invent upstream success.
  }
  const response = privateJson(
    { code, msg, data: { localLoggedOut: true, upstreamRevoked } },
    status,
  );
  clearAuthCookies(response, request);
  return response;
}
