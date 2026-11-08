import { backendFetch, canEditContent, canViewContent, getSessionState } from '@/lib/auth-server';
import {
  guardWriteOrigin,
  privateJson,
  readRequestJson,
  relayResult,
  requestBodyError,
  upstreamFailure,
} from '@/lib/bff';
import { parseResult } from '@/lib/result';
import { parseEditorRecord, validateContentSave, type ContentKind } from '@/lib/editor';

export interface ContentRouteContext {
  params: Promise<{ id: string }>;
}

function validId(raw: string): number | null {
  const value = /^\d+$/.test(raw) ? Number(raw) : NaN;
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

export async function readAdminContent(
  kind: ContentKind,
  _request: Request,
  { params }: ContentRouteContext,
) {
  const id = validId((await params).id);
  if (!id) return privateJson({ code: 320, msg: '内容 ID 无效。', data: null });
  const session = await getSessionState();
  if (!session.available)
    return privateJson({ code: 502, msg: '会话暂时无法确认，请稍后重试。', data: null }, 502);
  const user = session.user;
  if (!user) return privateJson({ code: 401, msg: '请先登录。', data: null });
  if (!canViewContent(user))
    return privateJson({ code: 403, msg: '当前账号没有内容查看权限。', data: null });
  try {
    const upstream = await backendFetch(`/api/v1/admin/cms/${kind}/${id}`);
    const body = parseResult(await upstream.json().catch(() => null));
    if (upstream.status !== 200 || body?.code !== 200)
      return upstreamFailure(body, '内容响应异常。', upstream.status);
    const record = parseEditorRecord(kind, body.data);
    if (!record || record.id !== id)
      return privateJson({ code: 502, msg: '内容响应格式异常。', data: null }, 502);
    return privateJson({ ...body, data: record });
  } catch {
    return privateJson({ code: 502, msg: '内容服务暂时不可达。', data: null }, 502);
  }
}

export async function saveAdminContent(
  kind: ContentKind,
  request: Request,
  { params }: ContentRouteContext,
) {
  const denied = guardWriteOrigin(request);
  if (denied) return denied;
  const id = validId((await params).id);
  if (!id) return privateJson({ code: 320, msg: '内容 ID 无效。', data: null });
  let raw: unknown;
  try {
    raw = await readRequestJson(request);
  } catch (error) {
    return requestBodyError(error);
  }
  const payload = validateContentSave(kind, raw, id);
  if (!payload.ok) return privateJson({ code: 320, msg: payload.error, data: null });
  const session = await getSessionState();
  if (!session.available)
    return privateJson({ code: 502, msg: '会话暂时无法确认，请稍后重试。', data: null }, 502);
  const user = session.user;
  if (!user) return privateJson({ code: 401, msg: '请先登录。', data: null });
  if (!canEditContent(user))
    return privateJson({ code: 403, msg: '当前账号没有内容编辑权限或须先修改密码。', data: null });
  try {
    return await relayResult(
      await backendFetch(`/api/v1/admin/cms/${kind}/${id}`, {
        method: 'PUT',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload.data),
      }),
    );
  } catch {
    return privateJson({ code: 502, msg: '保存结果无法确认，请核对后再重试。', data: null }, 502);
  }
}
