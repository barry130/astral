import { cookies } from 'next/headers';
import { getApiBase } from '@/lib/cms';

/**
 * 博客前台的登录态与后端转发（服务端专用）。
 *
 * 背景：后端 astral 用 Sa-Token，令牌在 HttpOnly Cookie `satoken` 里。
 * 浏览器只与博客域（3001）打交道，所以博客的 Route Handler 负责：
 *   1. 登录时把后端下发的 Set-Cookie 原样回写到博客域；
 *   2. 调后端时把博客域的 Cookie 与 CSRF 头转发过去。
 * 这样浏览器全程同源，不需要 CORS，也不暴露后端地址。
 */

export const TOKEN_COOKIE = 'satoken';
export const CSRF_COOKIE = 'astral_csrf';
export const CSRF_HEADER = 'X-CSRF-Token';

/** 后端 LoginResponse 的子集（只取博客用得到的字段） */
export interface SessionUser {
  id: number;
  username: string;
  nickname: string | null;
  roles: string[];
  permissions: string[];
  userType: string | null;
}

interface LoginResponseLike {
  id?: number | string;
  username?: string;
  nickname?: string | null;
  roles?: string[];
  permissions?: string[];
  userType?: string | null;
}

interface ResultEnvelope<T> {
  code: number;
  errorCode?: string | null;
  message?: string | null;
  data: T;
}

/** 把浏览器的 Cookie 拼成转发给后端的 Cookie 头 */
async function forwardedCookieHeader(): Promise<string> {
  const store = await cookies();
  return store
    .getAll()
    .filter((item) => item.name === TOKEN_COOKIE || item.name === CSRF_COOKIE)
    .map((item) => `${item.name}=${item.value}`)
    .join('; ');
}

/**
 * 服务端调后端：自动带上登录 Cookie 与 CSRF 双提交头。
 *
 * 后端 AuthInterceptor 只在「非安全方法 + 凭据来自 Cookie」时校验 CSRF，
 * 所以写操作必须同时带 Cookie 与 X-CSRF-Token（值取 astral_csrf Cookie）。
 */
export async function backendFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  const cookieHeader = await forwardedCookieHeader();
  if (cookieHeader) {
    headers.set('cookie', cookieHeader);
  }
  const csrfToken = (await cookies()).get(CSRF_COOKIE)?.value;
  if (csrfToken) {
    headers.set(CSRF_HEADER, csrfToken);
  }
  headers.set('accept', 'application/json');
  // 客户端系统头：后端统计用，缺失不阻断请求
  headers.set('X-App-Ut', 'web');
  // 登录态相关请求一律不缓存
  return fetch(`${getApiBase()}${path}`, { ...init, headers, cache: 'no-store' });
}

/** 后端 Result 包直接判定成功 */
function unwrap<T>(body: ResultEnvelope<T>): T | null {
  return body && body.code === 200 && body.data != null ? body.data : null;
}

/**
 * 当前登录用户。未登录 / 令牌失效 / 后端不可达一律返回 null，
 * 页面据此隐藏「编辑」入口，不报错也不影响正文渲染。
 */
export async function getSessionUser(): Promise<SessionUser | null> {
  const store = await cookies();
  if (!store.get(TOKEN_COOKIE)?.value) {
    return null;
  }
  try {
    const res = await backendFetch('/api/v1/all/auth/info');
    if (!res.ok) {
      return null;
    }
    const body = (await res.json()) as ResultEnvelope<LoginResponseLike>;
    const data = unwrap(body);
    if (!data || data.username == null) {
      return null;
    }
    return {
      id: Number(data.id ?? 0),
      username: data.username,
      nickname: data.nickname ?? null,
      roles: Array.isArray(data.roles) ? data.roles : [],
      permissions: Array.isArray(data.permissions) ? data.permissions : [],
      userType: data.userType ?? null,
    };
  } catch {
    return null;
  }
}

/** 超级管理员的通配权限码（与后端 PermissionChecker.SUPER_PERMISSION 一致） */
const SUPER_PERMISSION = '*:*:*';

/**
 * 权限码匹配，与后端 PermissionChecker.matches 同语义：
 * - `*:*:*` 命中一切；
 * - `seg:*` 前缀通配（如 `admin:cms:*` 命中 `admin:cms:content:edit`）；
 * - 其余按全等。
 */
function matchesPermission(held: string, required: string): boolean {
  if (!held || !required) return false;
  if (held === required || held === SUPER_PERMISSION) return true;
  if (held.endsWith(':*')) {
    return required.startsWith(held.slice(0, -1));
  }
  return false;
}

/**
 * 是否具备内容编辑权限。
 *
 * 这里只决定「要不要在页面上显示编辑入口」——真正的写入由后端
 * @RequiresPermission("admin:cms:content:edit") 二次校验，前端判宽一点不会造成越权。
 * 有 admin:cms:view 也放行，避免「能看不能点」的困惑。
 */
export function canEditContent(user: SessionUser | null): boolean {
  if (!user) return false;
  return user.permissions.some(
    (code) =>
      matchesPermission(code, 'admin:cms:content:edit') ||
      matchesPermission(code, 'admin:cms:view'),
  );
}
