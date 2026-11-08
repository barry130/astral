'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { requestJson } from '@/lib/client-request';
import { isRecord, parseResult, resultMessage } from '@/lib/result';
import { parseSessionSnapshot, type SessionSnapshot, type SessionUserView } from '@/lib/session';

export type SessionUser = SessionUserView;
export interface LogoutResult {
  localLoggedOut: boolean;
  upstreamRevoked: boolean;
  message: string;
}
interface SessionState extends SessionSnapshot {
  loading: boolean;
  error: string | null;
  /** True only when the latest read confirmed a signed-in session. */
  refresh: () => Promise<boolean>;
  logout: () => Promise<LogoutResult>;
}
const ANONYMOUS: SessionSnapshot = { loggedIn: false, canEdit: false, user: null };
const SessionContext = createContext<SessionState>({
  ...ANONYMOUS,
  loading: false,
  error: null,
  refresh: async () => false,
  logout: async () => ({
    localLoggedOut: false,
    upstreamRevoked: false,
    message: '会话尚未初始化。',
  }),
});

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
  const [snapshot, setSnapshot] = useState<SessionSnapshot>(
    () =>
      parseSessionSnapshot({
        loggedIn: initialLoggedIn,
        canEdit: initialCanEdit,
        user: initialUser,
      }) ?? ANONYMOUS,
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(true);
  const generation = useRef(0);
  const readController = useRef<AbortController | null>(null);
  const logoutController = useRef<AbortController | null>(null);
  const logoutPending = useRef<Promise<LogoutResult> | null>(null);

  const refresh = useCallback(async (): Promise<boolean> => {
    if (logoutPending.current || !mounted.current) return false;
    const current = ++generation.current;
    readController.current?.abort();
    const controller = new AbortController();
    readController.current = controller;
    setLoading(true);
    try {
      const { response, body } = await requestJson('/api/auth/session', {
        signal: controller.signal,
      });
      if (!mounted.current || current !== generation.current) return false;
      const next = response.ok ? parseSessionSnapshot(body) : null;
      if (!next) throw new Error(resultMessage(body, '会话暂时无法确认，请稍后重试。'));
      setSnapshot(next);
      setError(null);
      return next.loggedIn;
    } catch (failure) {
      if (mounted.current && current === generation.current) {
        // A transient failure is not proof that cookies were removed. Preserve
        // the identity for honest feedback but fail closed for editing.
        setSnapshot((previous) => ({ ...previous, canEdit: false }));
        setError(failure instanceof Error ? failure.message : '会话暂时无法确认。');
      }
      return false;
    } finally {
      if (mounted.current && current === generation.current) setLoading(false);
    }
  }, []);

  const logout = useCallback((): Promise<LogoutResult> => {
    if (logoutPending.current) return logoutPending.current;
    const current = ++generation.current;
    readController.current?.abort();
    const controller = new AbortController();
    logoutController.current = controller;
    setLoading(true);
    setSnapshot((previous) => ({ ...previous, canEdit: false }));
    const operation = (async (): Promise<LogoutResult> => {
      try {
        const { response, body: raw } = await requestJson('/api/auth/logout', {
          method: 'POST',
          signal: controller.signal,
        });
        const body = parseResult(raw);
        const data = isRecord(body?.data) ? body.data : null;
        if (!body || data?.localLoggedOut !== true)
          throw new Error(resultMessage(raw, '退出请求未确认，请重试。'));
        const upstreamRevoked = response.ok && body.code === 200 && data.upstreamRevoked === true;
        const message = resultMessage(
          body,
          upstreamRevoked ? '已退出登录' : '已退出本站，但后端令牌撤销未确认。',
        );
        if (mounted.current && current === generation.current) {
          setSnapshot(ANONYMOUS);
          setError(upstreamRevoked ? null : message);
        }
        return { localLoggedOut: true, upstreamRevoked, message };
      } catch (failure) {
        const message = failure instanceof Error ? failure.message : '退出请求未确认，请重试。';
        if (mounted.current && current === generation.current) setError(message);
        return { localLoggedOut: false, upstreamRevoked: false, message };
      } finally {
        logoutPending.current = null;
        if (mounted.current && current === generation.current) setLoading(false);
      }
    })();
    logoutPending.current = operation;
    return operation;
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      generation.current += 1;
      readController.current?.abort();
      logoutController.current?.abort();
    };
  }, []);
  useEffect(() => {
    if (!initialLoggedIn) void refresh();
  }, [initialLoggedIn, refresh]);
  useEffect(() => {
    const recheck = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    document.addEventListener('visibilitychange', recheck);
    return () => document.removeEventListener('visibilitychange', recheck);
  }, [refresh]);

  const value = useMemo<SessionState>(
    () => ({ ...snapshot, loading, error, refresh, logout }),
    [snapshot, loading, error, refresh, logout],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  return useContext(SessionContext);
}
