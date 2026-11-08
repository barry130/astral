'use client';

import {
  createContext,
  useContext,
  useState,
  useEffect,
  useMemo,
  useCallback,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { authApi, type LoginResponse, type SessionInfoResponse } from '@/api/auth';
import { setCsrfToken, clearCsrfToken } from '@/api/client';
import { AuthSession } from './auth-session-store';

interface AuthContextType {
  user: SessionInfoResponse | null;
  /** 兼容旧消费方，和 user 使用同一个不含认证 token 的快照。 */
  userInfo: SessionInfoResponse | null;
  isLogin: boolean;
  /** 新登录响应保留原有 token/mustChangePassword 调用方契约，不进入身份快照。 */
  login: (username: string, password: string, totpCode?: string) => Promise<LoginResponse>;
  logout: () => Promise<void>;
  loading: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session] = useState(() => new AuthSession({ ...authApi, setCsrfToken, clearCsrfToken }));
  const { user, loading } = useSyncExternalStore(
    session.subscribe,
    session.getSnapshot,
    session.getServerSnapshot,
  );

  useEffect(() => session.connect().disconnect, [session]);

  const login = useCallback(
    (username: string, password: string, totpCode?: string) =>
      session.login({ username, password, totpCode }),
    [session],
  );
  const logout = useCallback(() => session.logout(), [session]);
  const authValue = useMemo(
    () => ({ user, userInfo: user, isLogin: !!user, login, logout, loading }),
    [user, login, logout, loading],
  );

  return <AuthContext.Provider value={authValue}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}
