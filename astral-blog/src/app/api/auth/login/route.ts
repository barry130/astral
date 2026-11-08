import { encryptPassword, PasswordEncodingTooLong } from '@/lib/crypto';
import { fetchBackend } from '@/lib/backend';
import {
  CSRF_COOKIE,
  TOKEN_COOKIE,
  forwardAuthCookies,
  guardWriteOrigin,
  privateJson,
  readRequestJson,
  requestBodyError,
  upstreamFailure,
} from '@/lib/bff';
import { isRecord, parseResult } from '@/lib/result';
import { parseServerSessionUser, projectSessionUser } from '@/lib/session';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Passwords stay transient and server-only; reusable tokens never enter JSON. */
export async function POST(request: Request) {
  const denied = guardWriteOrigin(request);
  if (denied) return denied;
  let raw: unknown;
  try {
    raw = await readRequestJson(request, 4096);
  } catch (error) {
    return requestBodyError(error);
  }
  const payload = isRecord(raw) ? raw : {};
  const username = typeof payload.username === 'string' ? payload.username.trim() : '';
  const password = typeof payload.password === 'string' ? payload.password : '';
  const totpCode = typeof payload.totpCode === 'string' ? payload.totpCode.trim() : '';
  if (
    !username ||
    username.length > 64 ||
    !password ||
    password.length > 128 ||
    (totpCode && !/^\d{6}$/.test(totpCode))
  ) {
    return privateJson({ code: 320, msg: '请填写有效用户名、密码和 6 位动态验证码。', data: null });
  }
  try {
    const keyResponse = await fetchBackend('/api/v1/all/auth/public-key');
    const keyResult = parseResult(await keyResponse.json().catch(() => null));
    if (
      keyResponse.status !== 200 ||
      keyResult?.code !== 200 ||
      !isRecord(keyResult.data) ||
      typeof keyResult.data.publicKey !== 'string'
    ) {
      return upstreamFailure(keyResult, '无法获取登录公钥，请稍后重试。', keyResponse.status);
    }
    let encrypted: string;
    try {
      encrypted = encryptPassword(password, keyResult.data.publicKey);
    } catch (error) {
      return error instanceof PasswordEncodingTooLong
        ? privateJson({ code: 320, msg: '密码的 UTF-8 编码超过登录密钥允许的长度。', data: null })
        : privateJson({ code: 502, msg: '登录公钥无效，请稍后重试。', data: null }, 502);
    }
    const upstream = await fetchBackend('/api/v1/all/auth/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username, password: encrypted, ...(totpCode ? { totpCode } : {}) }),
    });
    const body = parseResult(await upstream.json().catch(() => null));
    if (upstream.status !== 200 || body?.code !== 200)
      return upstreamFailure(body, '登录失败，请稍后重试。', upstream.status);
    const user = parseServerSessionUser(body.data);
    if (!user) return privateJson({ code: 502, msg: '后端登录响应异常。', data: null }, 502);
    const response = privateJson({ code: 200, msg: '登录成功', data: projectSessionUser(user) });
    const cookies = forwardAuthCookies(upstream, response, request);
    if (!cookies.has(TOKEN_COOKIE) || !cookies.has(CSRF_COOKIE)) {
      return privateJson(
        { code: 502, msg: '后端未下发完整登录 Cookie，请稍后重试。', data: null },
        502,
      );
    }
    return response;
  } catch {
    return privateJson({ code: 502, msg: '登录服务暂时不可达，请稍后重试。', data: null }, 502);
  }
}
