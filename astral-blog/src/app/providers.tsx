'use client';

import { Toaster } from '@/components/ui/sonner';

/**
 * 全局 Provider 容器。
 *
 * 博客前台只提供 Toast 能力（登录/编辑反馈用），不做主题切换 ——
 * 与 astral-front 的 'light' | 'dark' | 'system' 三态不同，这里固定为浅色，
 * 让 sonner.tsx 可以原样复用 astral-front 的实现（它依赖 useTheme()）。
 */
export function useTheme() {
  return { mode: 'light' as const };
}

export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <>
      {children}
      <Toaster />
    </>
  );
}
