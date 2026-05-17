'use client';

import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { authApi, LoginResponse } from '@/api/auth';

/** 认证上下文类型定义 */
interface AuthContextType {
  /** 当前登录用户信息 */
  user: LoginResponse | null;
  /** 用户信息（与user相同，保持向后兼容） */
  userInfo: LoginResponse | null;
  /** 是否已登录 */
  isLogin: boolean;
  /** 登录方法 */
  login: (username: string, password: string) => Promise<void>;
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

  /** 组件挂载时检查localStorage中是否有token，若有则验证用户信息 */
  useEffect(() => {
    const token = localStorage.getItem('token');
    if (token) {
      authApi.getUserInfo()
        .then((res) => {
          if (res.code === 200) {
            setUser(res.data as any);
          }
        })
        .catch(() => {
          // token无效或过期，清除本地存储
          localStorage.removeItem('token');
        })
        .finally(() => setLoading(false));
    } else {
      setLoading(false);
    }
  }, []);

  /** 用户登录：调用API，成功后保存token和用户信息 */
  const login = async (username: string, password: string) => {
    const res = await authApi.login({ username, password });
    if (res.code === 200) {
      const token = res.data.token;
      localStorage.setItem('token', token);
      setUser(res.data);
    } else {
      throw new Error(res.message);
    }
  };

  /** 用户登出：调用API清理服务端session，清除本地token和用户信息 */
  const logout = async () => {
    try {
      await authApi.logout();
    } finally {
      localStorage.removeItem('token');
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