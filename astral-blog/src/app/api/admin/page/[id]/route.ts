import { NextResponse } from 'next/server';
import { backendFetch, getSessionUser, canEditContent } from '@/lib/auth-server';

/**
 * 单篇 Wiki 页面的管理端转发（读 / 写）。
 * 与文章转发同构，只是换到 AdminCmsPageController。
 */

const ADMIN_PAGE = '/api/v1/admin/cms/page';

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

export async function GET(_request: Request, { params }: RouteContext) {
  const denied = await ensureEditable();
  if (denied) return denied;

  const { id } = await params;
  try {
    const upstream = await backendFetch(`${ADMIN_PAGE}/${encodeURIComponent(id)}`);
    const body = await upstream.json();
    return NextResponse.json(body, { status: upstream.status });
  } catch {
    return NextResponse.json({ code: 502, message: '后端服务暂时不可达。' }, { status: 502 });
  }
}

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
    const upstream = await backendFetch(`${ADMIN_PAGE}/${encodeURIComponent(id)}`, {
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
