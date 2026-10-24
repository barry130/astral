import { NextResponse } from 'next/server';

import { backendFetch } from '@/lib/auth-server';

/**
 * 导出转发：浏览器 → 博客（同源）→ astral 后端。
 *
 * 为什么要转发而不是让浏览器直连后端：
 * 后端导出接口在 `/api/v1/admin/**` 下，需要 `admin:cms:view` 权限，认证靠 HttpOnly Cookie；
 * 浏览器只与博客域打交道，Cookie 也只属于博客域，所以由 Route Handler 读 Cookie 再带给后端。
 *
 * 响应体是文件流（不是 Result 包装），因此这里原样透传 content-type 与 content-disposition，
 * 浏览器收到后直接按附件下载。
 */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ path: string[] }> },
) {
  const { path } = await params;
  const suffix = path.map(encodeURIComponent).join('/');

  let upstream: Response;
  try {
    upstream = await backendFetch(`/api/v1/admin/cms/export/${suffix}`);
  } catch {
    return NextResponse.json({ code: 502, message: '内容服务暂时不可用' }, { status: 502 });
  }

  if (!upstream.ok) {
    // 未登录 401 / 无权限 403 / 目标不存在 404：把后端业务信息交给前端提示
    let message = `导出失败（HTTP ${upstream.status}）`;
    try {
      const body = (await upstream.json()) as { message?: string };
      if (body?.message) message = body.message;
    } catch {
      // 后端返回非 JSON 时保留兜底文案
    }
    return NextResponse.json({ code: upstream.status, message }, { status: upstream.status });
  }

  const headers = new Headers();
  headers.set(
    'content-type',
    upstream.headers.get('content-type') ?? 'application/octet-stream',
  );
  const disposition = upstream.headers.get('content-disposition');
  if (disposition) headers.set('content-disposition', disposition);
  headers.set('cache-control', 'no-store');

  return new NextResponse(upstream.body, { status: 200, headers });
}
