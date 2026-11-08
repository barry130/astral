import { cache } from 'react';
import { cookies } from 'next/headers';
import { fetchBackend } from '@/lib/backend';
import { CSRF_COOKIE, CSRF_HEADER, TOKEN_COOKIE } from '@/lib/bff';
import { parseResult } from '@/lib/result';
import { parseServerSessionUser, type ServerSessionUser } from '@/lib/session';

export { CSRF_COOKIE, CSRF_HEADER, TOKEN_COOKIE } from '@/lib/bff';
export type SessionUser = ServerSessionUser;

/** Request-scoped credentials; never retain cookie/user data in module state. */
export async function backendFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const store = await cookies();
  const headers = new Headers(init.headers);
  headers.delete('cookie');
  headers.delete('authorization');
  headers.delete(TOKEN_COOKIE);
  headers.delete(CSRF_HEADER);
  const allowed = [TOKEN_COOKIE, CSRF_COOKIE].flatMap((name) => {
    const value = store.get(name)?.value;
    return value && !/[\s;,\r\n]/.test(value) ? [`${name}=${value}`] : [];
  });
  if (allowed.length) headers.set('cookie', allowed.join('; '));
  const csrf = store.get(CSRF_COOKIE)?.value;
  if (csrf && !/[\s;,\r\n]/.test(csrf)) headers.set(CSRF_HEADER, csrf);
  return fetchBackend(path, { ...init, headers });
}

/** React cache deduplicates only within a render pass, not between visitors. */
export const getSessionState = cache(
  async (): Promise<{ available: boolean; user: SessionUser | null }> => {
    const store = await cookies();
    if (!store.get(TOKEN_COOKIE)?.value) return { available: true, user: null };
    try {
      const response = await backendFetch('/api/v1/all/auth/info');
      const body = parseResult(await response.json().catch(() => null));
      if (response.status !== 200) return { available: false, user: null };
      if (body?.code === 401) return { available: true, user: null };
      const user = body?.code === 200 ? parseServerSessionUser(body.data) : null;
      return { available: user !== null, user };
    } catch {
      return { available: false, user: null };
    }
  },
);

/** SSR may render anonymously during an outage; guards retain the distinction. */
export const getSessionUser = cache(
  async (): Promise<SessionUser | null> => (await getSessionState()).user,
);

function matchesPermission(held: string, required: string): boolean {
  if (typeof held !== 'string') return false;
  return (
    held === required ||
    held === '*:*:*' ||
    (held.endsWith(':*') && required.startsWith(held.slice(0, -1)))
  );
}

/** Edit does not imply view in the backend's permission model. */
export function canViewContent(user: SessionUser | null): boolean {
  return Boolean(
    user &&
    user.userType === 'ADMIN' &&
    !user.mustChangePassword &&
    Array.isArray(user.permissions) &&
    user.permissions.some((code) => matchesPermission(code, 'admin:cms:view')),
  );
}

export function canEditContent(user: SessionUser | null): boolean {
  return Boolean(
    canViewContent(user) &&
    user?.permissions.some((code) => matchesPermission(code, 'admin:cms:content:edit')),
  );
}
