import { cookies } from 'next/headers';
import { backendFetch, canEditContent } from '@/lib/auth-server';
import { TOKEN_COOKIE, clearAuthCookies, forwardAuthCookies, privateJson } from '@/lib/bff';
import { parseResult } from '@/lib/result';
import { parseServerSessionUser, projectSessionUser } from '@/lib/session';

export const dynamic = 'force-dynamic';

/** Anonymous is normal; outages are not fabricated successful anonymous reads. */
export async function GET(request: Request) {
  if (!(await cookies()).get(TOKEN_COOKIE)?.value) return privateJson({ loggedIn: false });
  try {
    const upstream = await backendFetch('/api/v1/all/auth/info');
    const body = parseResult(await upstream.json().catch(() => null));
    if (upstream.status === 200 && body?.code === 401) {
      const response = privateJson({ loggedIn: false });
      clearAuthCookies(response, request);
      return response;
    }
    const user =
      upstream.status === 200 && body?.code === 200 ? parseServerSessionUser(body.data) : null;
    if (!user) return privateJson({ code: 502, msg: '会话服务暂时不可用。', data: null }, 502);
    const response = privateJson({
      loggedIn: true,
      user: projectSessionUser(user),
      canEdit: canEditContent(user),
    });
    // Auth info may renew missing CSRF/session cookies. RSC reads cannot write
    // them, so the explicit browser refresh endpoint performs the safe renewal.
    forwardAuthCookies(upstream, response, request);
    return response;
  } catch {
    return privateJson({ code: 502, msg: '会话服务暂时不可用。', data: null }, 502);
  }
}
