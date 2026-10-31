'use client';

import { cn } from '@/lib/utils';

/**
 * 迷你走势线
 *
 * 纯内联 SVG，不引三方图表库 —— 只用于 KPI 卡右下角这类「看趋势不看细节」的位置；
 * 需要坐标轴/图例/tooltip 时用 echarts（见 statistics 页）。
 *
 * 采样点不足 2 个时画一条贴底的虚线占位，避免卡片右下角出现突兀空白。
 */
export interface SparklineProps {
  values?: readonly number[];
  className?: string;
  width?: number;
  height?: number;
  /** 描边粗细 */
  strokeWidth?: number;
}

export function Sparkline({
  values,
  className,
  width = 92,
  height = 30,
  strokeWidth = 1.5,
}: SparklineProps) {
  const list = values ?? [];

  if (list.length < 2) {
    return (
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className={cn('shrink-0 opacity-30', className)}
        style={{ width, height }}
        aria-hidden
        focusable="false"
      >
        <line
          x1="0"
          y1={height - 3}
          x2={width}
          y2={height - 3}
          stroke="currentColor"
          strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray="3 4"
        />
      </svg>
    );
  }

  const max = Math.max(...list);
  const min = Math.min(...list);
  const span = max - min || 1;
  const points = list
    .map((v, i) => {
      const x = (i / (list.length - 1)) * width;
      const y = height - 3 - ((v - min) / span) * (height - 6);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className={cn('shrink-0', className)}
      style={{ width, height }}
      aria-hidden
      focusable="false"
    >
      <polyline
        points={points}
        fill="none"
        stroke="currentColor"
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
