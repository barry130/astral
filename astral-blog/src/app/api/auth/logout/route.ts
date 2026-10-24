import { NextResponse } from 'next/server';
import { backendFetch } from '@/lib/auth-server';

/**
 * 登出转发：后端会清 satoken / astral_csrf Cookie，
 * 这里把 Set-Cookie 透传回浏览器，使博客域上的会话一并失效。
 */
export async function POST() {
  let upstream: Response;
  try {
    upstream = await backendFetch('/api/v1/all/auth/logout', { method: 'POST' });
  } catch {
    return NextResponse.json({ code: 502, message: '后端服务暂时不可达。' }, { status: 502 });
  }

  let body: unknown = { code: 200, message: '已退出登录', data: null };
  try {
    body = await upstream.json();
  } catch {
    // 后端返回非 JSON 时仍按成功处理：本地 Cookie 已随 Set-Cookie 清除
  }

  const res = NextResponse.json(body, { status: upstream.status });
  const list = upstream.headers.getSetCookie?.() ?? [];
  for (const cookie of list) {
    res.headers.append('set-cookie', cookie);
  }
  // 兜底：即便后端没回 Set-Cookie，也在本域清掉两个令牌，避免残留导致「退不掉」
  if (list.length === 0) {
    res.headers.append('set-cookie', 'satoken=; Path=/; Max-Age=0; SameSite=Lax');
    res.headers.append('set-cookie', 'astral_csrf=; Path=/; Max-Age=0; SameSite=Lax');
  }
  return res;
}
