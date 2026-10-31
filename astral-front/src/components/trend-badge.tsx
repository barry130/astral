'use client';

import { TrendingDown, TrendingUp } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * 环比涨跌标签
 *
 * 统一「较昨日 +12.3%」这类展示：涨跌图标 + 百分比，颜色按好坏而不是按方向
 * （错误数变多属于变差，用 `invert` 反转配色）。
 *
 * 无对比基准时不编造百分比：昨日为 0 且今日有值显示「昨日无数据」，
 * 两边都为 0 显示「暂无数据」，差异小于 0.05% 显示「与昨日持平」。
 */
export interface TrendBadgeProps {
  current?: number | null;
  previous?: number | null;
  /** 指标越大越差时传 true（如错误次数、耗时），涨跌配色随之反转 */
  invert?: boolean;
  /** 前缀文案，默认「较昨日」 */
  label?: string;
  /** 差异小于该值视为持平（百分比） */
  threshold?: number;
  className?: string;
}

export function TrendBadge({
  current,
  previous,
  invert = false,
  label = '较昨日',
  threshold = 0.05,
  className,
}: TrendBadgeProps) {
  const cur = current ?? 0;
  const prev = previous ?? 0;

  if (prev <= 0) {
    return (
      <span className={cn('text-muted-foreground/70 text-xs', className)}>
        {cur > 0 ? '昨日无数据' : '暂无数据'}
      </span>
    );
  }

  const delta = ((cur - prev) / prev) * 100;
  if (Math.abs(delta) < threshold) {
    return <span className={cn('text-muted-foreground/70 text-xs', className)}>与昨日持平</span>;
  }

  const rising = delta > 0;
  const positive = invert ? !rising : rising;
  const Icon = rising ? TrendingUp : TrendingDown;

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 text-xs tabular-nums',
        positive ? 'text-emerald-600' : 'text-destructive',
        className,
      )}
    >
      <Icon className="size-3 shrink-0" />
      {label} {rising ? '+' : ''}
      {delta.toFixed(1)}%
    </span>
  );
}
