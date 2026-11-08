'use client';

import React, {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
} from 'react';
import {
  getServerThemeSnapshot,
  getThemeSnapshot,
  setThemePreference,
  subscribeThemePreference,
  type ThemeMode,
} from '@/lib/theme-preference';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';

import { AuthProvider } from '@/context/AuthContext';
import { GlobalRequestLoading } from '@/components/GlobalRequestLoading';
import { Toaster } from '@/components/ui/sonner';

/** 移动端断点（与 globals.css 的 @media 断点保持一致） */
const MOBILE_QUERY = '(max-width: 768px)';
/** dayjs 中文语言包（业务侧日期格式化本地化） */
dayjs.locale('zh-cn');

interface ThemeContextType {
  /** 当前主题模式 */
  mode: ThemeMode;
  /** 设置主题模式（写入 localStorage 并应用到 <html data-theme>） */
  setMode: (mode: ThemeMode) => void;
  /** 切换明暗主题 */
  toggle: () => void;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

/**
 * 全局 Provider 组件
 * 明暗主题状态写入 <html data-theme>，驱动 globals.css 的 CSS 变量
 * （Tailwind 语义令牌经 @theme inline 映射，明暗随 data-theme 翻转）。
 * antd ConfigProvider 已随 UI 迁移（shadcn/ui + Tailwind）移除。
 */
export function Providers({ children }: { children: React.ReactNode }) {
  const themeMode = useSyncExternalStore(
    subscribeThemePreference,
    getThemeSnapshot,
    getServerThemeSnapshot,
  );

  /** 应用外观不代表用户选择：不在此effect持久化系统推导值。 */
  useEffect(() => {
    const root = document.documentElement;
    root.setAttribute('data-theme', themeMode);
    root.classList.toggle('dark', themeMode === 'dark');
    document.body.classList.toggle('dark', themeMode === 'dark');
  }, [themeMode]);

  const themeValue = useMemo<ThemeContextType>(
    () => ({
      mode: themeMode,
      setMode: setThemePreference,
      toggle: () => setThemePreference(getThemeSnapshot() === 'dark' ? 'light' : 'dark'),
    }),
    [themeMode],
  );

  return (
    <ThemeContext.Provider value={themeValue}>
      <AuthProvider>
        {children}
        {/* 全局接口加载指示：覆盖所有页面（含登录页），由 axios 拦截器驱动 */}
        <GlobalRequestLoading />
        {/* 全局 Toast（替代 antd message / notification） */}
        <Toaster />
      </AuthProvider>
    </ThemeContext.Provider>
  );
}

/**
 * 使用主题上下文的 Hook
 * 必须在 <Providers> 内部使用
 */
export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within <Providers>');
  }
  return context;
}

/**
 * 视口是否处于移动端断点（≤768px），供布局组件消费。
 * 独立监听媒体查询，不依赖 Providers 内部状态。
 */
export function useIsMobile() {
  const [isMobile, setIsMobile] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia(MOBILE_QUERY);
    const apply = () => setIsMobile(mq.matches);
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, []);
  return isMobile;
}
