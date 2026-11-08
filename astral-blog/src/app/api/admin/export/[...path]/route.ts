import { NextResponse } from 'next/server';
import { backendFetch, canViewContent, getSessionState } from '@/lib/auth-server';
import { PRIVATE_CACHE, privateJson, upstreamFailure } from '@/lib/bff';

/** Stream only four real backend exports; credentials remain in the BFF. */
function resolveExportPath(path: string[]): string | null {
  if (path.length === 1 && (path[0] === 'zip' || path[0] === 'markdown')) return path[0];
  if (
    path.length === 2 &&
    (path[0] === 'post' || path[0] === 'page') &&
    /^[1-9]\d*$/.test(path[1]) &&
    Number.isSafeInteger(Number(path[1]))
  )
    return `${path[0]}/${path[1]}`;
  return null;
}

export async function GET(_request: Request, { params }: { params: Promise<{ path: string[] }> }) {
  const session = await getSessionState();
  if (!session.available)
    return privateJson({ code: 502, msg: '会话暂时无法确认，请稍后重试。', data: null }, 502);
  const user = session.user;
  if (!user) return privateJson({ code: 401, msg: '请先登录。', data: null });
  if (!canViewContent(user))
    return privateJson({ code: 403, msg: '当前账号没有内容导出权限。', data: null });
  const { path } = await params;
  const suffix = resolveExportPath(path);
  if (!suffix) return privateJson({ code: 404, msg: '导出地址不存在。', data: null }, 404);
  let upstream: Response;
  try {
    upstream = await backendFetch(`/api/v1/admin/cms/export/${suffix}`);
  } catch {
    return privateJson({ code: 502, msg: '内容服务暂时不可用。', data: null }, 502);
  }
  const type = (upstream.headers.get('content-type') ?? '').toLowerCase();
  if (upstream.status !== 200 || type.includes('json')) {
    return upstreamFailure(
      await upstream.json().catch(() => null),
      '后端导出响应异常。',
      upstream.status,
      '导出失败。',
    );
  }
  if (
    !upstream.body ||
    !/^(application\/(?:zip|octet-stream)|text\/(?:plain|markdown))(?:;|$)/.test(type)
  )
    return privateJson({ code: 502, msg: '后端导出文件格式异常。', data: null }, 502);
  const headers = new Headers({
    'content-type': type,
    'cache-control': PRIVATE_CACHE,
    'x-content-type-options': 'nosniff',
  });
  const disposition = upstream.headers.get('content-disposition');
  headers.set(
    'content-disposition',
    disposition && /^attachment(?:;|$)/i.test(disposition)
      ? disposition
      : 'attachment; filename="astral-export"',
  );
  return new NextResponse(upstream.body, { status: 200, headers });
}
