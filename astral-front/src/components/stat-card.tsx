'use client';

import type { ReactNode } from 'react';

import { Card, CardContent } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Sparkline } from '@/components/sparkline';
import { cn } from '@/lib/utils';

/* ------------------------------ 语义色调 ------------------------------ */

/** 数值色调：只用来表达状态，不要拿它当主题色用 */
export type StatTone = 'normal' | 'success' | 'warn' | 'danger';

export const STAT_TONE_TEXT: Record<StatTone, string> = {
  normal: 'text-[color:var(--color-text)]',
  success: 'text-emerald-600',
  warn: 'text-amber-600',
  danger: 'text-destructive',
};

export const STAT_TONE_BAR: Record<StatTone, string> = {
  normal: 'bg-primary',
  success: 'bg-emerald-500',
  warn: 'bg-amber-500',
  danger: 'bg-destructive',
};

/** 资源使用率 → 色调（越线才着色） */
export function usageTone(value: number, warn: number, danger: number): StatTone {
  if (value > danger) return 'danger';
  if (value > warn) return 'warn';
  return 'normal';
}

/** 比率型指标（成功率等）→ 色调（越高越好，阈值方向与使用率相反） */
export function ratioTone(value: number, good: number, warn: number): StatTone {
  if (value >= good) return 'success';
  if (value >= warn) return 'warn';
  return 'danger';
}

/* ------------------------------ KPI 卡 ------------------------------ */

export interface StatCardProps {
  /** 指标名 */
  label: string;
  /** 主数值（自己格式化好的字符串，或带单位的 ReactNode） */
  value: ReactNode;
  /** 右上角图标（圆形薄荷芯片） */
  icon?: ReactNode;
  /** 右上角自定义内容，传了就不渲染 icon 芯片 */
  extra?: ReactNode;
  tone?: StatTone;
  /** 0-100，传了才渲染底部进度条 */
  progress?: number;
  /** 数值下方说明（昨日数据、汇总值等） */
  sub?: ReactNode;
  /** 迷你走势采样（右下角 sparkline） */
  spark?: readonly number[];
  /** compact：小号卡（无图标/走势，用于二级页面指标条） */
  size?: 'default' | 'compact';
  className?: string;
}

/**
 * 通用 KPI 卡
 *
 * 全站唯一实现：仪表盘、数据统计、消息统计、轻听 API 等所有「一个大数字 + 说明」
 * 的指标卡都用它，不要再在页面里手写 Card + 数字 div。
 */
export function StatCard({
  label,
  value,
  icon,
  extra,
  tone = 'normal',
  progress,
  sub,
  spark,
  size = 'default',
  className,
}: StatCardProps) {
  const compact = size === 'compact';

  return (
    <Card className={cn('stat-card h-full', className)}>
      <CardContent className={cn('flex h-full flex-col px-5', compact && 'py-0')}>
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="text-[12.5px] font-medium text-[color:var(--color-text-tertiary)]">
              {label}
            </div>
            <div
              className={cn(
                'mt-2 leading-none font-semibold tracking-[-0.02em] tabular-nums',
                compact ? 'text-[24px]' : 'text-[30px]',
                STAT_TONE_TEXT[tone],
              )}
            >
              {value}
            </div>
          </div>
          {extra ??
            (icon ? (
              <div className="flex size-10 shrink-0 items-center justify-center rounded-full bg-[var(--color-brand-soft)]">
                <span className="inline-flex text-[var(--color-brand)] [&_svg]:size-5">{icon}</span>
              </div>
            ) : null)}
        </div>

        {compact ? (
          sub ? (
            <div className="mt-2 truncate text-[11.5px] text-[color:var(--color-text-tertiary)]">
              {sub}
            </div>
          ) : null
        ) : (
          <div className="mt-auto flex items-end justify-between gap-3 pt-4">
            <div className="min-w-0 flex-1">
              {progress !== undefined && (
                <Progress
                  value={Math.min(100, Math.max(0, progress))}
                  className="h-1.5"
                  indicatorClassName={cn(
                    'transition-[width] duration-300 ease-out',
                    STAT_TONE_BAR[tone],
                  )}
                />
              )}
              {sub && (
                <div className="mt-2 truncate text-[11.5px] text-[color:var(--color-text-tertiary)]">
                  {sub}
                </div>
              )}
            </div>
            <Sparkline values={spark ?? []} className="text-[var(--color-brand)] opacity-70" />
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/* ------------------------------ 浅底数值块 ------------------------------ */

export interface StatTileProps {
  label: ReactNode;
  value: ReactNode;
  hint?: ReactNode;
  className?: string;
  valueClassName?: string;
}

/** 卡内浅底分区里的「小标题 + 数值 + 备注」，用于次级指标（不要另起一张卡片） */
export function StatTile({ label, value, hint, className, valueClassName }: StatTileProps) {
  return (
    <div className={cn('rounded-xl bg-[var(--color-bg-tint)] p-4', className)}>
      <div className="text-muted-foreground mb-1 text-[13px]">{label}</div>
      <div
        className={cn('text-foreground text-lg font-semibold tabular-nums', valueClassName)}
        title={typeof value === 'string' ? value : undefined}
      >
        {value}
      </div>
      {hint && <div className="text-muted-foreground/80 text-xs">{hint}</div>}
    </div>
  );
}

/* ------------------------------ 比例条 ------------------------------ */

export interface RatioBarProps {
  /** 0-100 的百分比 */
  percent: number;
  tone?: StatTone;
  /** 自定义颜色（如按渠道区分），传了优先于 tone */
  color?: string;
  className?: string;
}

/** 细比例条：比手写 div + style width 更省事，圆角/轨道色跟随主题 */
export function RatioBar({ percent, tone = 'normal', color, className }: RatioBarProps) {
  const width = Math.min(100, Math.max(0, percent));
  return (
    <div className={cn('bg-muted h-1.5 w-full overflow-hidden rounded-full', className)}>
      <div
        className={cn(
          'h-full rounded-full transition-[width] duration-300',
          !color && STAT_TONE_BAR[tone],
        )}
        style={{ width: `${width}%`, background: color }}
      />
    </div>
  );
}
