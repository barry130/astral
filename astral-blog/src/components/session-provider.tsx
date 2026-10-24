'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

/**
 * 博客前台登录态（客户端）。
 *
 * 令牌在 HttpOnly Cookie 里，JS 读不到，所以登录态只能问服务端：
 * `/api/auth/session` 是本站 Route Handler，它会带着 Cookie 去后端 /auth/info 验。
 *
 * 前台是公开站点，未登录是常态：这里不做重定向、不弹窗，
 * 只在登录后把 canEdit 交给「编辑」按钮决定是否露出。
 */

export interface SessionUser {
  id: number;
  username: string;
  nickname: string | null;
  userType: string | null;
}

interface SessionState {
  /** 首次查询尚未返回 */
  loading: boolean;
  loggedIn: boolean;
  canEdit: boolean;
  user: SessionUser | null;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}

const SessionContext = createContext<SessionState>({
  loading: false,
  loggedIn: false,
  canEdit: false,
  user: null,
  refresh: async () => {},
  logout: async () => {},
});

interface SessionResponse {
  loggedIn?: boolean;
  canEdit?: boolean;
  user?: SessionUser;
}

export function SessionProvider({
  children,
  initialLoggedIn = false,
  initialCanEdit = false,
  initialUser = null,
}: {
  children: React.ReactNode;
  initialLoggedIn?: boolean;
  initialCanEdit?: boolean;
  initialUser?: SessionUser | null;
}) {
  // 服务端已算过一次登录态（layout 里读 Cookie），这里作为初始值，
  // 避免首屏闪现「未登录」再跳成「已登录」。
  const [loggedIn, setLoggedIn] = useState(initialLoggedIn);
  const [canEdit, setCanEdit] = useState(initialCanEdit);
  const [user, setUser] = useState<SessionUser | null>(initialUser);
  const [loading, setLoading] = useState(false);

  const apply = useCallback((data: SessionResponse | null) => {
    if (data?.loggedIn) {
      setLoggedIn(true);
      setCanEdit(Boolean(data.canEdit));
      setUser(data.user ?? null);
    } else {
      setLoggedIn(false);
      setCanEdit(false);
      setUser(null);
    }
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/auth/session', { cache: 'no-store' });
      apply(res.ok ? ((await res.json()) as SessionResponse) : null);
    } catch {
      apply(null);
    } finally {
      setLoading(false);
    }
  }, [apply]);

  const logout = useCallback(async () => {
    try {
      await fetch('/api/auth/logout', { method: 'POST' });
    } catch {
      // 网络失败也按已退出处理：用户体验上比卡在登录态更合理
    }
    apply(null);
  }, [apply]);

  // 初始值来自服务端；若服务端判定未登录（可能 Cookie 刚过期），静默复核一次
  useEffect(() => {
    if (!initialLoggedIn) {
      void refresh();
    }
  }, [initialLoggedIn, refresh]);

  const value = useMemo<SessionState>(
    () => ({ loading, loggedIn, canEdit, user, refresh, logout }),
    [loading, loggedIn, canEdit, user, refresh, logout],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  return useContext(SessionContext);
}
