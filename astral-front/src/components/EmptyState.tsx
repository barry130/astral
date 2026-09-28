'use client';

import React, { type ReactNode } from 'react';
import { InboxIcon } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * 统一空状态
 *
 * 所有列表 / 抽屉 / 弹窗在「无数据」时使用，保证文案层级、图标、操作按钮风格一致。
 */
export interface EmptyStateProps {
  /** 主文案 */
  description: ReactNode;
  /** 辅助说明（灰字，可选） */
  hint?: ReactNode;
  /** 语义图标：默认使用收件箱图标，与 image 二选一 */
  icon?: ReactNode;
  /** 完全自定义插画（优先于 icon） */
  image?: ReactNode;
  /** 底部操作（如「新建」按钮） */
  action?: ReactNode;
  /** 上下内边距（px） */
  padding?: number;
  /** 屏幕阅读器名称；不传时取 description 的字符串值 */
  ariaLabel?: string;
}

export function EmptyState({
  description,
  hint,
  icon,
  image,
  action,
  padding = 32,
  ariaLabel,
}: EmptyStateProps) {
  const label = ariaLabel ?? (typeof description === 'string' ? description : undefined);

  return (
    <div
      role="status"
      aria-label={label}
      className="flex flex-col items-center"
      style={{ paddingTop: padding, paddingBottom: padding }}
    >
      {image ? (
        <div className="mb-3">{image}</div>
      ) : icon ? (
        <div className="mb-3 text-muted-foreground" aria-hidden>
          {icon}
        </div>
      ) : (
        <InboxIcon className="mb-3 size-8 text-muted-foreground/60" strokeWidth={1.5} aria-hidden />
      )}

      <div className="max-w-[420px] text-center">
        <div className="text-[13.5px] leading-relaxed text-muted-foreground">{description}</div>
        {hint ? <div className="mt-1 text-[12.5px] leading-relaxed text-muted-foreground/70">{hint}</div> : null}
      </div>

      {action ? <div className="mt-3 flex flex-wrap justify-center gap-2">{action}</div> : null}
    </div>
  );
}
