'use client';

import type { ReactNode } from 'react';

import { cn } from '@/lib/utils';

/**
 * 列表页筛选条
 *
 * 两种用法：
 * 1. 只有筛选项：`<FilterBar><Input/><Select/></FilterBar>`
 * 2. 左侧筛选项 + 右侧操作：`<FilterBar actions={<Button>新建</Button>}><Input/></FilterBar>`
 *
 * 需要「左一组、右一组」但不想拆 actions 时用 `split`，等价于 `.filter-bar--split`。
 * 不要再写 `<div className="filter-bar" style={{ marginBottom: 16, display: 'flex', … }}>`。
 */
export interface FilterBarProps {
  /** 筛选项（Input / Select / DatePicker …） */
  children?: ReactNode;
  /** 右侧操作（Button 组），靠右对齐 */
  actions?: ReactNode;
  /** 子元素两端对齐（配合 children 内自带的两组内容） */
  split?: boolean;
  className?: string;
}

export function FilterBar({ children, actions, split = false, className }: FilterBarProps) {
  return (
    <div
      className={cn('filter-bar', (split || actions) && 'filter-bar--split', className ?? 'mb-4')}
    >
      {children}
      {actions ? <div className="page-toolbar ml-auto">{actions}</div> : null}
    </div>
  );
}
