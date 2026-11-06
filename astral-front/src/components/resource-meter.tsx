'use client';

import { Progress } from '@/components/ui/progress';
import { Sparkline } from '@/components/sparkline';
import { STAT_TONE_BAR, STAT_TONE_TEXT, type StatTone } from '@/components/stat-card';
import { cn } from '@/lib/utils';

export interface ResourceMeterProps {
  label: string;
  value: string;
  /** 0–100；undefined 表示尚无采样，不渲染进度条。 */
  percent?: number;
  detail?: string;
  series?: readonly number[];
  tone?: StatTone;
  /** 深色主健康面板使用的反色排版。 */
  inverted?: boolean;
  className?: string;
}

/** 资源读数 + 进度 + 最近采样，适用于主机和运行时资源监控。 */
export function ResourceMeter({
  label,
  value,
  percent,
  detail,
  series,
  tone = 'normal',
  inverted = false,
  className,
}: ResourceMeterProps) {
  return (
    <div className={cn('min-w-0', className)}>
      <div className="flex items-baseline justify-between gap-3">
        <span
          className={cn(
            'text-xs font-medium',
            inverted ? 'text-[color:var(--color-nav-muted)]' : 'text-muted-foreground',
          )}
        >
          {label}
        </span>
        <span
          className={cn(
            'text-[22px] leading-none font-semibold tracking-[-0.025em] tabular-nums',
            inverted ? 'text-[color:var(--color-nav-text)]' : STAT_TONE_TEXT[tone],
          )}
        >
          {value}
        </span>
      </div>

      <div className="mt-4 flex h-8 items-end justify-between gap-3">
        <span
          className={cn(
            'min-w-0 truncate text-[11px]',
            inverted ? 'text-[color:var(--color-nav-muted)]' : 'text-muted-foreground',
          )}
        >
          {detail}
        </span>
        <Sparkline
          values={series}
          width={88}
          height={28}
          className={cn(
            inverted ? 'text-[color:var(--color-nav-accent)]' : 'text-[color:var(--color-brand)]',
            percent === undefined && 'opacity-20',
          )}
        />
      </div>

      {percent !== undefined && (
        <Progress
          value={percent}
          className={cn('mt-3 h-1', inverted && 'bg-white/10')}
          indicatorClassName={cn(
            'transition-[width] duration-300 ease-out',
            inverted && tone === 'normal' ? 'bg-[var(--color-nav-accent)]' : STAT_TONE_BAR[tone],
          )}
        />
      )}
    </div>
  );
}
