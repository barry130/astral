'use client';

import { Toaster as Sonner, type ToasterProps } from 'sonner';

import { useTheme } from '@/app/providers';

/** 全局 Toast 容器：主题跟随项目明暗切换（data-theme），替代 antd message/notification */
const Toaster = ({ ...props }: ToasterProps) => {
  const { mode } = useTheme();

  return (
    <Sonner
      theme={mode}
      position="top-center"
      className="toaster group"
      style={
        {
          '--normal-bg': 'var(--color-popover)',
          '--normal-text': 'var(--color-popover-foreground)',
          '--normal-border': 'var(--color-border)',
        } as React.CSSProperties
      }
      {...props}
    />
  );
};

export { Toaster };
