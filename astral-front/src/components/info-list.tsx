'use client';

import type { ReactNode } from 'react';

import { cn } from '@/lib/utils';

/**
 * 描述列表（label / value 成对展示）
 *
 * 详情卡、个人中心、抽屉里的「字段: 值」统一用它，不要每处手写 flex + span。
 */
export interface InfoListProps {
  children: ReactNode;
  className?: string;
  /** 行间距，默认 gap-3 */
  gap?: 'sm' | 'md';
}

export function InfoList({ children, className, gap = 'md' }: InfoListProps) {
  return (
    <div className={cn('flex flex-col', gap === 'sm' ? 'gap-2' : 'gap-3', className)}>
      {children}
    </div>
  );
}

export interface InfoRowProps {
  label: ReactNode;
  children: ReactNode;
  /**
   * between：标签左、值右对齐（详情卡，长值截断）
   * inline：标签定宽、值紧随其后（个人资料）
   */
  variant?: 'between' | 'inline';
  /** inline 变体的标签宽度，默认 w-28 */
  labelWidth?: string;
  className?: string;
}

export function InfoRow({
  label,
  children,
  variant = 'between',
  labelWidth = 'w-28',
  className,
}: InfoRowProps) {
  if (variant === 'inline') {
    return (
      <div className={cn('flex items-center gap-3', className)}>
        <span className={cn('text-muted-foreground shrink-0', labelWidth)}>{label}</span>
        <span className="min-w-0">{children}</span>
      </div>
    );
  }

  return (
    <div className={cn('flex items-center justify-between gap-4', className)}>
      <span className="text-muted-foreground shrink-0 text-[13px]">{label}</span>
      <span className="text-foreground min-w-0 truncate text-right text-sm font-medium">
        {children}
      </span>
    </div>
  );
}
