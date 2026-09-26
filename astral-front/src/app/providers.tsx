'use client';

import React, { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import { AuthProvider } from '@/context/AuthContext';
import { GlobalRequestLoading } from '@/components/GlobalRequestLoading';

/** 移动端断点（与 globals.css 的 @media 断点保持一致） */
const MOBILE_QUERY = '(max-width: 768px)';
/** 系统「深色外观」偏好 */
const SYSTEM_DARK_QUERY = '(prefers-color-scheme: dark)';
/** 主题持久化键 */
const THEME_STORAGE_KEY = 'astral:theme';

/** 品牌主色：石墨黑，与 globals.css 的 --color-brand 保持一致（极简现代风） */
const COLOR_PRIMARY = '#18181b';
/** 侧边栏底色：白（浅色模式）/ 容器同色（深色模式），配合右侧分隔线 */
const COLOR_SIDEBAR = '#ffffff';
/** 深色模式专用：石墨黑在深底上不可读，文字/链接需用更亮的灰 */
const COLOR_PRIMARY_TEXT_DARK = '#a1a1aa';
const COLOR_PRIMARY_DARK = '#52525b';
const COLOR_SIDEBAR_DARK = '#1a1e26';

type ThemeMode = 'light' | 'dark';

/** 主题类型（避免额外类型导入） */
type AppTheme = React.ComponentProps<typeof ConfigProvider>['theme'];
type ComponentTokens = NonNullable<NonNullable<AppTheme>['components']>;

interface ThemeContextType {
  /** 当前主题模式 */
  mode: ThemeMode;
  /** 设置主题模式（写入 localStorage 并应用到 <html data-theme>） */
  setMode: (mode: ThemeMode) => void;
  /** 切换明暗主题 */
  toggle: () => void;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

/** dayjs 中文语言包（日期选择器/时间显示本地化） */
dayjs.locale('zh-cn');

/** 从 localStorage / 系统偏好读取初始主题（SSR 阶段返回 light，避免水合不一致） */
const getInitialThemeMode = (): ThemeMode => {
  if (typeof window === 'undefined') return 'light';
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
    return window.matchMedia(SYSTEM_DARK_QUERY).matches ? 'dark' : 'light';
  } catch {
    return 'light';
  }
};

/* ============================ 浅色主题 ============================ */

const lightBaseToken = {
  colorPrimary: COLOR_PRIMARY,
  colorInfo: COLOR_PRIMARY,
  colorSuccess: '#16a34a',
  colorWarning: '#d97706',
  colorError: '#dc2626',
  borderRadius: 8,
  colorBgLayout: '#f7f7f8',
  colorBgContainer: '#ffffff',
  colorBgElevated: '#ffffff',
  colorText: '#18181b',
  colorTextSecondary: '#52525b',
  colorTextTertiary: '#71717a',
  colorBorder: '#e4e4e7',
  colorBorderSecondary: '#f0f0f1',
  colorSplit: '#f0f0f1',
  boxShadow: '0 1px 2px rgba(24, 24, 27, 0.04)',
  boxShadowSecondary: '0 4px 12px rgba(24, 24, 27, 0.05)',
  boxShadowTertiary: '0 1px 2px rgba(24, 24, 27, 0.04)',
  motionDurationMid: '0.15s',
};

const lightComponents: ComponentTokens = {
  Layout: {
    headerBg: '#ffffff',
    headerHeight: 64,
    headerPadding: '0 24px',
    siderBg: COLOR_SIDEBAR,
  },
  Menu: {
    itemHeight: 38,
    itemMarginInline: 10,
    itemBg: 'transparent',
    itemHoverBg: 'rgba(24, 24, 27, 0.04)',
    itemSelectedBg: 'rgba(24, 24, 27, 0.06)',
    itemSelectedColor: '#18181b',
    itemColor: '#52525b',
  },
  Table: {
    headerBg: '#fafafa',
    headerColor: '#71717a',
    borderColor: '#f0f0f1',
    cellPaddingBlock: 10,
    cellPaddingInline: 14,
    rowHoverBg: 'rgba(24, 24, 27, 0.03)',
  },
  Card: { paddingLG: 18, headerFontSize: 15, colorBorderSecondary: '#e4e4e7', borderRadiusLG: 10 },
  Button: { fontWeight: 500, boxShadow: '0 1px 2px rgba(24, 24, 27, 0.05)' },
  Modal: { borderRadiusLG: 12 },
  Drawer: { paddingLG: 18 },
  Tabs: { itemColor: '#71717a', itemActiveColor: COLOR_PRIMARY, itemSelectedColor: COLOR_PRIMARY, inkBarColor: COLOR_PRIMARY },
  Segmented: { trackBg: '#f0f0f1', itemSelectedBg: '#ffffff', itemSelectedColor: COLOR_PRIMARY },
  Tooltip: { borderRadius: 8 },
  Input: { activeShadow: '0 0 0 3px rgba(24, 24, 27, 0.08)', hoverBorderColor: COLOR_PRIMARY },
  Select: { optionSelectedBg: 'rgba(24, 24, 27, 0.05)' },
  Tag: { defaultBg: '#f7f7f8', defaultColor: '#52525b' },
  Empty: { colorTextDescription: '#a1a1aa' },
};

/* ============================ 深色主题 ============================ */

const darkBaseToken = {
  colorPrimary: COLOR_PRIMARY_DARK,
  colorInfo: COLOR_PRIMARY_DARK,
  colorLink: COLOR_PRIMARY_TEXT_DARK,
  colorSuccess: '#2fb57c',
  colorWarning: '#e0a82c',
  colorError: '#e0564f',
  borderRadius: 8,
  colorBgBase: '#12151b',
  colorBgLayout: '#12151b',
  colorBgContainer: '#1a1e26',
  colorBgElevated: '#1f242d',
  colorBgSpotlight: '#2a313d',
  colorText: '#e6e9ee',
  colorTextSecondary: '#9aa3b0',
  colorTextTertiary: '#8a94a1',
  colorTextQuaternary: '#6b7482',
  colorBorder: '#262c37',
  colorBorderSecondary: '#212731',
  colorSplit: '#212731',
  colorFillAlter: '#1f242d',
  colorFillSecondary: 'rgba(255, 255, 255, 0.06)',
  boxShadow: '0 1px 2px rgba(0, 0, 0, 0.34)',
  boxShadowSecondary: '0 4px 12px rgba(0, 0, 0, 0.42)',
  boxShadowTertiary: '0 1px 2px rgba(0, 0, 0, 0.34)',
  motionDurationMid: '0.15s',
};

const darkComponents: ComponentTokens = {
  Layout: {
    headerBg: '#1a1e26',
    headerHeight: 64,
    headerPadding: '0 24px',
    siderBg: COLOR_SIDEBAR_DARK,
  },
  Menu: {
    itemHeight: 38,
    itemMarginInline: 10,
    itemBg: 'transparent',
    itemHoverBg: 'rgba(255, 255, 255, 0.05)',
    itemSelectedBg: 'rgba(255, 255, 255, 0.09)',
    itemSelectedColor: '#e6e9ee',
    itemColor: '#9aa3b0',
  },
  Table: {
    headerBg: '#20252f',
    headerColor: '#9aa3b0',
    borderColor: '#232932',
    cellPaddingBlock: 10,
    cellPaddingInline: 14,
    rowHoverBg: 'rgba(255, 255, 255, 0.045)',
  },
  Card: { paddingLG: 18, headerFontSize: 15, colorBorderSecondary: '#262c37', borderRadiusLG: 10 },
  Button: { fontWeight: 500, boxShadow: '0 1px 2px rgba(0, 0, 0, 0.34)' },
  Modal: { borderRadiusLG: 12 },
  Drawer: { paddingLG: 18 },
  Tabs: { itemColor: '#9aa3b0', itemActiveColor: '#e6e9ee', itemSelectedColor: '#e6e9ee', inkBarColor: '#a1a1aa' },
  Segmented: { trackBg: '#1f242d', itemSelectedBg: '#2a313d', itemSelectedColor: '#e6e9ee' },
  Tooltip: { borderRadius: 8, colorBgSpotlight: '#2a313d' },
  Input: { activeShadow: '0 0 0 3px rgba(161, 161, 170, 0.14)', hoverBorderColor: '#a1a1aa' },
  Select: { optionSelectedBg: 'rgba(255, 255, 255, 0.08)' },
  Tag: { defaultBg: '#1f242d', defaultColor: '#9aa3b0' },
  Empty: { colorTextDescription: '#8a94a1' },
};

/** 移动端组件补丁：只覆盖尺寸类 token，配色仍随明暗主题 */
const mobileComponentPatches: ComponentTokens = {
  Layout: { headerHeight: 56, headerPadding: '0 12px' },
  Menu: { itemHeight: 38 },
  Table: { cellPaddingBlock: 7, cellPaddingInline: 8 },
  Card: { paddingLG: 12, headerFontSize: 14 },
  Drawer: { paddingLG: 14 },
  Tag: { fontSize: 12 },
};

/** 合并组件级 token：补丁按 key 浅合并，缺失的 key 保留基础值 */
const mergeComponentTokens = (base: ComponentTokens, patch: ComponentTokens): ComponentTokens => {
  if (Object.keys(patch).length === 0) return base;
  const merged = { ...base };
  (Object.keys(patch) as Array<keyof ComponentTokens>).forEach((key) => {
    const prev = merged[key];
    const next = patch[key];
    if (prev && next && typeof prev === 'object' && typeof next === 'object') {
      (merged as Record<PropertyKey, unknown>)[key] = { ...(prev as object), ...(next as object) };
    }
  });
  return merged;
};

/** 组装主题：明暗模式 × 桌面/移动端 */
const buildTheme = (mode: ThemeMode, isMobile: boolean): AppTheme => {
  const isDark = mode === 'dark';
  const token = isMobile
    ? { ...(isDark ? darkBaseToken : lightBaseToken), fontSize: 13, controlHeight: 32, controlHeightSM: 26, controlHeightLG: 36 }
    : { ...(isDark ? darkBaseToken : lightBaseToken), fontSize: 14, controlHeight: 34 };
  return {
    algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token,
    components: mergeComponentTokens(isDark ? darkComponents : lightComponents, isMobile ? mobileComponentPatches : {}),
  };
};

/**
 * 全局 Provider 组件
 * 明暗主题 + 视口断点驱动 Ant Design 主题 token；同时把 data-theme 写到 <html>
 * 供 globals.css 的 [data-theme="dark"] 选择器接管非 antd 样式
 */
export function Providers({ children }: { children: React.ReactNode }) {
  const [themeMode, setThemeMode] = useState<ThemeMode>(getInitialThemeMode);
  const [isMobile, setIsMobile] = useState(false);

  const appTheme = useMemo(() => buildTheme(themeMode, isMobile), [themeMode, isMobile]);

  /** 主题持久化 + 应用到 <html>（供 CSS 变量与选择器生效） */
  useEffect(() => {
    const root = document.documentElement;
    root.setAttribute('data-theme', themeMode);
    document.body.classList.toggle('dark', themeMode === 'dark');
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, themeMode);
    } catch {
      /* 隐私模式等场景下存储不可用，静默降级 */
    }
  }, [themeMode]);

  /** 视口断点：桌面/移动端 token 切换 */
  useEffect(() => {
    const mq = window.matchMedia(MOBILE_QUERY);
    const apply = () => setIsMobile(mq.matches);
    apply();
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, []);

  /** 跟随系统外观：仅在用户未显式选择主题时生效 */
  useEffect(() => {
    const mq = window.matchMedia(SYSTEM_DARK_QUERY);
    const apply = () => {
      try {
        if (!window.localStorage.getItem(THEME_STORAGE_KEY)) {
          setThemeMode(mq.matches ? 'dark' : 'light');
        }
      } catch {
        /* ignore */
      }
    };
    mq.addEventListener('change', apply);
    return () => mq.removeEventListener('change', apply);
  }, []);

  const themeValue = useMemo<ThemeContextType>(
    () => ({
      mode: themeMode,
      setMode: setThemeMode,
      toggle: () => setThemeMode((m) => (m === 'dark' ? 'light' : 'dark')),
    }),
    [themeMode],
  );

  return (
    <ThemeContext.Provider value={themeValue}>
      <AuthProvider>
        <ConfigProvider theme={appTheme} locale={zhCN}>
          {children}
          {/* 全局接口加载指示：覆盖所有页面（含登录页），由 axios 拦截器驱动 */}
          <GlobalRequestLoading />
        </ConfigProvider>
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
