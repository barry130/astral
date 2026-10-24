import { NextResponse } from 'next/server';
import { encryptPassword } from '@/lib/crypto';

/**
 * 登录转发（Route Handler）。
 *
 * 浏览器把明文密码发到这里（同源，HTTP），本服务端用后端 RSA 公钥加密后
 * 转发给 {CMS_API_BASE}/api/v1/all/auth/login，再把后端下发的 Set-Cookie
 * （satoken / astral_csrf）原样透传给浏览器 —— 浏览器全程只与博客域打交道。
 *
 * 密码加密放在服务端而不是客户端，是为了避免把 jsencrypt 这类依赖塞进浏览器包；
 * 明文只在本服务进程内短暂存在，不再落盘或打日志。
 */

interface ResultEnvelope<T> {
  code: number;
  errorCode?: string | null;
  message?: string | null;
  data: T;
}

const BACKEND_AUTH = '/api/v1/all/auth';

function apiBase(): string {
  return (process.env.CMS_API_BASE || 'http://localhost:27000').replace(/\/+$/, '');
}

/** 从后端 RSA 公钥接口取公钥（一次请求一次，不在进程内长缓存） */
async function fetchPublicKey(): Promise<string | null> {
  try {
    const res = await fetch(`${apiBase()}${BACKEND_AUTH}/public-key`, {
      cache: 'no-store',
      headers: { accept: 'application/json', 'X-App-Ut': 'web' },
    });
    if (!res.ok) return null;
    const body = (await res.json()) as ResultEnvelope<{ publicKey?: string }>;
    return body?.data?.publicKey ?? null;
  } catch {
    return null;
  }
}

/** 把后端响应的 Set-Cookie 透传给浏览器（Next 支持 getSetCookie 数组形式） */
function forwardSetCookies(upstream: Response, res: NextResponse): void {
  const list = upstream.headers.getSetCookie?.() ?? [];
  for (const cookie of list) {
    res.headers.append('set-cookie', cookie);
  }
}

export async function POST(request: Request) {
  let raw: unknown = null;
  try {
    raw = await request.json();
  } catch {
    raw = null;
  }
  const payload = (raw !== null && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;

  const username = typeof payload.username === 'string' ? payload.username.trim() : '';
  const password = typeof payload.password === 'string' ? payload.password : '';
  const totpCode = typeof payload.totpCode === 'string' ? payload.totpCode.trim() : '';

  if (!username || !password) {
    return NextResponse.json({ code: 400, message: '请输入用户名和密码。' }, { status: 400 });
  }

  const publicKey = await fetchPublicKey();
  if (!publicKey) {
    return NextResponse.json(
      { code: 502, message: '后端服务暂时不可达，无法获取登录公钥。' },
      { status: 502 },
    );
  }

  let encrypted: string;
  try {
    encrypted = encryptPassword(password, publicKey);
  } catch {
    return NextResponse.json({ code: 500, message: '密码加密失败。' }, { status: 500 });
  }

  let upstream: Response;
  try {
    upstream = await fetch(`${apiBase()}${BACKEND_AUTH}/login`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        accept: 'application/json',
        'X-App-Ut': 'web',
      },
      body: JSON.stringify({ username, password: encrypted, ...(totpCode ? { totpCode } : {}) }),
      cache: 'no-store',
    });
  } catch {
    return NextResponse.json({ code: 502, message: '后端服务暂时不可达。' }, { status: 502 });
  }

  let body: ResultEnvelope<unknown>;
  try {
    body = (await upstream.json()) as ResultEnvelope<unknown>;
  } catch {
    return NextResponse.json({ code: 502, message: '后端响应异常。' }, { status: 502 });
  }

  const res = NextResponse.json(body, { status: upstream.status });
  if (body?.code === 200) {
    forwardSetCookies(upstream, res);
  }
  return res;
}
