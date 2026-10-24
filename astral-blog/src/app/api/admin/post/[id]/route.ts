import { NextResponse } from 'next/server';
import { backendFetch, getSessionUser, canEditContent } from '@/lib/auth-server';

/**
 * 单篇文章的管理端转发（读 / 写）。
 *
 * 为什么不让浏览器直接打后端：
 *   1. 后端在 27000，博客在 3001/独立域名 —— 跨域且要带 HttpOnly Cookie；
 *   2. 后端的 CSRF 双提交需要同时拿到 Cookie 与 X-CSRF-Token，服务端转发最省事。
 * 这里只做鉴权与透传，业务校验全部留在后端（权限码 admin:cms:content:edit）。
 */

const ADMIN_POST = '/api/v1/admin/cms/post';

interface RouteContext {
  params: Promise<{ id: string }>;
}

async function ensureEditable(): Promise<NextResponse | null> {
  const user = await getSessionUser();
  if (!user) {
    return NextResponse.json({ code: 401, message: '请先登录。' }, { status: 401 });
  }
  if (!canEditContent(user)) {
    return NextResponse.json({ code: 403, message: '当前账号没有内容编辑权限。' }, { status: 403 });
  }
  return null;
}

/** 取完整文章（含正文、分类、标签、状态），用于填充编辑表单 */
export async function GET(_request: Request, { params }: RouteContext) {
  const denied = await ensureEditable();
  if (denied) return denied;

  const { id } = await params;
  try {
    const upstream = await backendFetch(`${ADMIN_POST}/${encodeURIComponent(id)}`);
    const body = await upstream.json();
    return NextResponse.json(body, { status: upstream.status });
  } catch {
    return NextResponse.json({ code: 502, message: '后端服务暂时不可达。' }, { status: 502 });
  }
}

/** 保存文章，请求体是后端 PostSaveDto（字段与后端一致，不再转换） */
export async function PUT(request: Request, { params }: RouteContext) {
  const denied = await ensureEditable();
  if (denied) return denied;

  const { id } = await params;
  let payload: unknown;
  try {
    payload = await request.json();
  } catch {
    return NextResponse.json({ code: 400, message: '请求体不是合法 JSON。' }, { status: 400 });
  }

  try {
    const upstream = await backendFetch(`${ADMIN_POST}/${encodeURIComponent(id)}`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const body = await upstream.json();
    return NextResponse.json(body, { status: upstream.status });
  } catch {
    return NextResponse.json({ code: 502, message: '后端服务暂时不可达。' }, { status: 502 });
  }
}
