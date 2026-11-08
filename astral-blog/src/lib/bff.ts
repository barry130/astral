import { NextResponse } from 'next/server';
import { getSiteUrl } from '@/lib/site-url';
import { isRecord, parseResult, resultMessage } from '@/lib/result';
import { MAX_SAVE_BYTES } from '@/lib/editor';

export const PRIVATE_CACHE = 'private, no-store, max-age=0';
export const TOKEN_COOKIE = 'satoken';
export const CSRF_COOKIE = 'astral_csrf';
export const CSRF_HEADER = 'X-CSRF-Token';

export function privateJson(body: unknown, status = 200): NextResponse {
  const envelope =
    isRecord(body) && typeof body.code === 'number'
      ? {
          ...body,
          msg:
            typeof body.msg === 'string'
              ? body.msg
              : body.code === 200
                ? '请求成功。'
                : '请求未完成，请重试。',
          data: body.data ?? null,
          timestamp: body.timestamp ?? Date.now(),
        }
      : body;
  return NextResponse.json(envelope, {
    status: [204, 205, 304].includes(status) ? 502 : status,
    headers: { 'cache-control': PRIVATE_CACHE, 'x-content-type-options': 'nosniff' },
  });
}

/** Origin is mandatory: cookie-derived upstream CSRF alone cannot protect a BFF. */
export function guardWriteOrigin(request: Request): NextResponse | null {
  const raw = request.headers.get('origin');
  if (!raw || request.headers.get('sec-fetch-site') === 'cross-site') {
    return privateJson({ code: 403, msg: '仅允许本站发起此操作。', data: null });
  }
  try {
    const origin = new URL(raw);
    const allowed = new Set([new URL(request.url).origin, getSiteUrl()]);
    if (origin.origin !== raw || !allowed.has(origin.origin)) throw new Error('Origin mismatch');
    return null;
  } catch {
    return privateJson({ code: 403, msg: '仅允许本站发起此操作。', data: null });
  }
}

export class PayloadTooLarge extends Error {}

/** Bound decoded request bytes, including chunked bodies without Content-Length. */
export async function readRequestJson(
  request: Request,
  maxBytes = MAX_SAVE_BYTES,
): Promise<unknown> {
  const length = Number(request.headers.get('content-length'));
  if (Number.isFinite(length) && length > maxBytes) throw new PayloadTooLarge();
  if (!request.body) throw new SyntaxError('Missing JSON body');
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) {
        await reader.cancel();
        throw new PayloadTooLarge();
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) as unknown;
}

export function requestBodyError(error: unknown): NextResponse {
  return error instanceof PayloadTooLarge
    ? privateJson({ code: 413, msg: '请求内容过大。', data: null }, 413)
    : privateJson({ code: 320, msg: '请求体不是合法 JSON。', data: null });
}

/** Relay transport and business results separately; malformed JSON is not success. */
export async function relayResult(upstream: Response): Promise<NextResponse> {
  const body = parseResult(await upstream.json().catch(() => null));
  if (upstream.status !== 200 || !body)
    return upstreamFailure(body, '后端响应异常，结果无法确认。', upstream.status);
  // This relay is used by void save operations, not a generic entity proxy.
  return privateJson({ ...body, data: null }, upstream.status);
}

function secureRequest(request: Request): boolean {
  // Canonical configuration also covers GET renewals behind TLS termination.
  // Origin/proxy headers cannot downgrade a deployment's Secure credentials.
  return new URL(getSiteUrl()).protocol === 'https:' || new URL(request.url).protocol === 'https:';
}

/** Auth cookies are host-only, path-rooted and never relay unrelated cookies. */
export function forwardAuthCookies(
  upstream: Response,
  response: NextResponse,
  request: Request,
): Set<string> {
  const names = new Set<string>();
  for (const raw of upstream.headers.getSetCookie()) {
    const [pair, ...attributes] = raw.split(';');
    const equals = pair.indexOf('=');
    if (equals < 1) continue;
    const name = pair.slice(0, equals).trim();
    const value = pair.slice(equals + 1).trim();
    if ((name !== TOKEN_COOKIE && name !== CSRF_COOKIE) || !value || /[\s;,\r\n]/.test(value))
      continue;
    const maxAgeAttribute = attributes.find((attribute) => /^\s*max-age=/i.test(attribute));
    const maxAge = maxAgeAttribute ? Number(maxAgeAttribute.split('=')[1]) : undefined;
    if (maxAge !== undefined && (!Number.isSafeInteger(maxAge) || maxAge <= 0)) continue;
    const expiresAttribute = attributes.find((attribute) => /^\s*expires=/i.test(attribute));
    const expires = expiresAttribute
      ? new Date(expiresAttribute.slice(expiresAttribute.indexOf('=') + 1))
      : undefined;
    if (
      maxAge === undefined &&
      expires &&
      (!Number.isFinite(expires.getTime()) || expires.getTime() <= Date.now())
    )
      continue;
    response.cookies.set(name, value, {
      path: '/',
      httpOnly: name === TOKEN_COOKIE,
      secure: secureRequest(request),
      sameSite: 'strict',
      ...(maxAge === undefined ? {} : { maxAge }),
      ...(expires && Number.isFinite(expires.getTime()) ? { expires } : {}),
    });
    names.add(name);
  }
  return names;
}

export function clearAuthCookies(response: NextResponse, request: Request): void {
  for (const name of [TOKEN_COOKIE, CSRF_COOKIE]) {
    response.cookies.set(name, '', {
      path: '/',
      httpOnly: name === TOKEN_COOKIE,
      secure: secureRequest(request),
      sameSite: 'strict',
      maxAge: 0,
    });
  }
}

export function upstreamFailure(
  body: unknown,
  fallback: string,
  transportStatus = 200,
  businessFallback = fallback,
): NextResponse {
  const result = parseResult(body);
  const businessFailure = result !== null && result.code !== 200;
  return privateJson(
    {
      code: businessFailure ? result.code : 502,
      errorCode: businessFailure ? result.errorCode : undefined,
      msg: businessFailure ? resultMessage(result, businessFallback) : fallback,
      data: null,
    },
    transportStatus !== 200 ? transportStatus : businessFailure ? 200 : 502,
  );
}
