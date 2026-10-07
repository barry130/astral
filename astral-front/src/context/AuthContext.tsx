'use client';

import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { authApi, LoginResponse } from '@/api/auth';
import { setCsrfToken, clearCsrfToken } from '@/api/client';

/** 认证上下文类型定义 */
interface AuthContextType {
  /** 当前登录用户信息 */
  user: LoginResponse | null;
  /** 用户信息（与user相同，保持向后兼容） */
  userInfo: LoginResponse | null;
  /** 是否已登录 */
  isLogin: boolean;
  /** 登录方法（totpCode：启用二次验证的账号传入；成功返回登录响应，供调用方读取 mustChangePassword） */
  login: (username: string, password: string, totpCode?: string) => Promise<LoginResponse>;
  /** 登出方法 */
  logout: () => Promise<void>;
  /** 加载状态（初始化时检查登录状态） */
  loading: boolean;
}

/** 创建认证上下文，初始值为undefined */
const AuthContext = createContext<AuthContextType | undefined>(undefined);

/**
 * 认证提供者组件
 * 管理用户登录状态，提供全局认证上下文
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  /** 用户信息状态 */
  const [user, setUser] = useState<LoginResponse | null>(null);
  /** 加载状态，初始化时为true以检查已有登录状态 */
  const [loading, setLoading] = useState(true);

  /**
   * 组件挂载时恢复登录态。
   *
   * 认证令牌存在 HttpOnly Cookie（`satoken`）里，JS 读不到 —— 也就不需要
   * 「先判断有没有 token 再请求」：有没有凭据由浏览器决定，直接问一次 /info，
   * 401 就说明未登录（拦截器只在 /dashboard 前缀下跳登录页，不会把落地页访客弹走）。
   */
  useEffect(() => {
    authApi.getUserInfo()
      .then((res) => {
        if (res.code === 200) {
          // 类型已与后端对齐，无需再用 as any 掩盖
          // 顺带接住后端随 /info 下发的 CSRF 令牌：跨域直连部署下页面读不到 API 域的
          // astral_csrf Cookie，没有这一步所有写请求都会被 403（AUTH013）拦下。
          setCsrfToken(res.data?.csrfToken);
          setUser(res.data);
        }
      })
      .catch(() => {
        // 未登录 / 令牌过期：Cookie 由后端在登出或过期时处理，前端无需（也无法）清理
        clearCsrfToken();
        setUser(null);
      })
      .finally(() => setLoading(false));
  }, []);

  /**
   * 用户登录：调用API，成功后保存用户信息。
   *
   * 令牌由后端写入 HttpOnly `satoken` Cookie，响应体里的 token 字段仅作兼容保留；
   * 前端不再接触令牌明文 —— 这正是本次改造的目的（此前存 localStorage，XSS 可直接读走）。
   */
  const login = async (username: string, password: string, totpCode?: string) => {
    const res = await authApi.login({ username, password, totpCode });
    if (res.code === 200) {
      // 登录响应体里的 csrfToken 是管理端写请求的必备凭据（见 client.ts 的说明）
      setCsrfToken(res.data?.csrfToken);
      setUser(res.data);
      return res.data;
    }
    throw new Error(res.message);
  };

  /** 用户登出：调用API清理服务端session（后端同时清除认证 Cookie），再清空本地用户信息 */
  const logout = async () => {
    try {
      await authApi.logout();
    } finally {
      // 令牌与 CSRF 指纹一同失效，缓存里的旧值不能再留给下一次会话
      clearCsrfToken();
      setUser(null);
    }
  };

  return (
    <AuthContext.Provider value={{ user, userInfo: user, isLogin: !!user, login, logout, loading }}>
      {children}
    </AuthContext.Provider>
  );
}

/**
 * 使用认证上下文的Hook
 * 必须在AuthProvider内部使用
 */
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
}