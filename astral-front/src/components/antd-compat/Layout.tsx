'use client';

/**
 * antd 兼容层：布局类组件（Card / Space / Row / Col / Divider / Typography）
 */

import type { CSSProperties, ReactNode } from 'react';

import {
  Card as ShadcnCard,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { cn } from '@/lib/utils';

/* ============================== Card ============================== */

export interface CardProps {
  title?: ReactNode;
  extra?: ReactNode;
  /** 是否显示边框（false 时仅留阴影，与 antd 语义一致） */
  bordered?: boolean;
  hoverable?: boolean;
  size?: 'default' | 'small';
  className?: string;
  style?: CSSProperties;
  bodyStyle?: CSSProperties;
  headStyle?: CSSProperties;
  children?: ReactNode;
  onClick?: () => void;
}

export function Card({
  title,
  extra,
  bordered = true,
  hoverable,
  size = 'default',
  className,
  style,
  bodyStyle,
  children,
  onClick,
}: CardProps) {
  return (
    <ShadcnCard
      onClick={onClick}
      className={cn(
        'gap-0 py-0',
        size === 'small' && 'py-0',
        !bordered && 'border-transparent',
        hoverable && 'cursor-pointer transition-shadow hover:shadow-md',
        className,
      )}
      style={style}
    >
      {(title || extra) && (
        <CardHeader
          className={cn(
            'flex flex-row items-center justify-between gap-3 border-b border-border px-4',
            size === 'small' ? 'py-2.5' : 'py-3',
          )}
        >
          <CardTitle className={cn('text-[15px] font-semibold', size === 'small' && 'text-sm')}>{title}</CardTitle>
          {extra ? <div className="flex shrink-0 items-center gap-2">{extra}</div> : null}
        </CardHeader>
      )}
      <CardContent className={cn('px-4', size === 'small' ? 'py-3' : 'py-4')} style={bodyStyle}>
        {children}
      </CardContent>
    </ShadcnCard>
  );
}

/* ============================== Space ============================== */

export interface SpaceProps {
  /** 间距：数字（px）或 antd 尺寸名 */
  size?: number | 'small' | 'middle' | 'large';
  direction?: 'horizontal' | 'vertical';
  wrap?: boolean;
  align?: 'start' | 'end' | 'center' | 'baseline';
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

const SPACE_SIZE: Record<string, number> = { small: 8, middle: 16, large: 24 };

export function SpaceBase({
  size = 'small',
  direction = 'horizontal',
  wrap,
  align,
  className,
  style,
  children,
}: SpaceProps) {
  const gap = typeof size === 'number' ? size : (SPACE_SIZE[size] ?? 8);
  return (
    <div
      className={cn('flex', direction === 'vertical' ? 'flex-col' : 'flex-row', wrap && 'flex-wrap', className)}
      style={{
        gap,
        alignItems: align === 'center' ? 'center' : align === 'end' ? 'flex-end' : align === 'baseline' ? 'baseline' : 'center',
        ...style,
      }}
    >
      {children}
    </div>
  );
}

export interface SpaceCompactProps {
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

/** `Space.Compact`：子元素横向紧贴、圆角只保留首尾（antd 同形） */
export function SpaceCompact({ className, style, children }: SpaceCompactProps) {
  return (
    <div
      className={cn(
        'flex w-full items-stretch',
        '[&>*]:rounded-none [&>*:first-child]:rounded-l-md [&>*:last-child]:rounded-r-md',
        '[&>*+*]:ml-[-1px]',
        className,
      )}
      style={style}
    >
      {children}
    </div>
  );
}

/** antd 的 `Space` 命名空间：`Space.Compact` */
export const Space = Object.assign(SpaceBase, { Compact: SpaceCompact });

/* =============================== Row / Col =============================== */

export interface RowProps {
  /** 栅格间距：数字或 [水平, 垂直] */
  gutter?: number | [number, number];
  align?: 'top' | 'middle' | 'bottom';
  justify?: 'start' | 'end' | 'center' | 'space-between' | 'space-around';
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

/** 用 24 列 CSS Grid 模拟 antd Row（列宽由 .compat-col-* 的 grid-column 决定） */
export function Row({ gutter = 0, align, justify, className, style, children }: RowProps) {
  const [gx, gy] = typeof gutter === 'number' ? [gutter, 0] : gutter;
  return (
    <div
      className={cn('compat-row', className)}
      style={{
        columnGap: gx,
        rowGap: gy,
        alignItems: align === 'middle' ? 'center' : align === 'bottom' ? 'end' : 'start',
        justifyContent:
          justify === 'center'
            ? 'center'
            : justify === 'end'
              ? 'end'
              : justify === 'space-between'
                ? 'space-between'
                : justify === 'space-around'
                  ? 'space-around'
                  : 'start',
        ...style,
      }}
    >
      {children}
    </div>
  );
}

export interface ColProps {
  /** 24 栅格制跨度：数字或响应式对象 */
  span?: number;
  xs?: number | { span?: number };
  sm?: number | { span?: number };
  md?: number | { span?: number };
  lg?: number | { span?: number };
  xl?: number | { span?: number };
  /** 偏移（24 栅格制） */
  offset?: number;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

/** 取跨度数字（兼容 { span: n } 对象写法） */
const spanOf = (v: number | { span?: number } | undefined): number | undefined =>
  typeof v === 'number' ? v : v?.span;

/**
 * 栅格类名：宽度样式由 globals.css 的 .compat-col-* 规则提供
 * （静态类名，避免动态拼接导致样式缺失）
 */
const colClass = (bp: string, span: number | undefined): string => {
  if (!span) return '';
  const clamped = Math.min(24, Math.max(0, Math.round(span)));
  return bp ? `compat-col-${bp}-${clamped}` : `compat-col-${clamped}`;
};

export function Col({ className, style, children, ...rest }: ColProps) {
  const classes = [
    rest.span ? colClass('', rest.span) : 'compat-col-24',
    colClass('xs', spanOf(rest.xs)),
    colClass('sm', spanOf(rest.sm)),
    colClass('md', spanOf(rest.md)),
    colClass('lg', spanOf(rest.lg)),
    colClass('xl', spanOf(rest.xl)),
    rest.offset ? `compat-col-offset-${Math.min(24, Math.max(0, Math.round(rest.offset)))}` : '',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={cn('min-w-0', classes)} style={style}>
      {children}
    </div>
  );
}

/* ============================== Divider ============================== */

export interface DividerProps {
  type?: 'horizontal' | 'vertical';
  /** 文字位置（有 children 时生效） */
  orientation?: 'left' | 'center' | 'right';
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

export function Divider({ type = 'horizontal', className, style, children }: DividerProps) {
  if (type === 'vertical') {
    return <Separator orientation="vertical" className={cn('mx-2 h-4', className)} style={style} />;
  }
  if (!children) {
    return <Separator className={cn('my-4', className)} style={style} />;
  }
  return (
    <div className={cn('my-4 flex items-center gap-3 text-xs text-muted-foreground', className)} style={style}>
      <Separator className="flex-1" />
      <span className="shrink-0">{children}</span>
      <Separator className="flex-1" />
    </div>
  );
}

/* ============================== Typography ============================== */

export interface TypographyTitleProps {
  level?: 1 | 2 | 3 | 4 | 5;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
  type?: 'secondary' | 'success' | 'warning' | 'danger';
}

function Title({ level = 1, className, style, children }: TypographyTitleProps) {
  const sizeMap: Record<number, string> = {
    1: 'text-2xl font-bold',
    2: 'text-xl font-semibold',
    3: 'text-lg font-semibold',
    4: 'text-base font-semibold',
    5: 'text-sm font-semibold',
  };
  const Tag = `h${level}` as 'h1';
  return (
    <Tag className={cn(sizeMap[level], 'text-foreground', className)} style={style}>
      {children}
    </Tag>
  );
}

export interface TypographyTextProps {
  type?: 'secondary' | 'success' | 'warning' | 'danger';
  strong?: boolean;
  italic?: boolean;
  underline?: boolean;
  delete?: boolean;
  /** 代码样式 */
  code?: boolean;
  disabled?: boolean;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
  onClick?: () => void;
}

function Text({ type, strong, italic, underline, delete: del, code, disabled, className, style, children, onClick }: TypographyTextProps) {
  const cls = cn(
    type === 'secondary' && 'text-muted-foreground',
    type === 'success' && 'text-emerald-600 dark:text-emerald-400',
    type === 'warning' && 'text-amber-600 dark:text-amber-400',
    type === 'danger' && 'text-destructive',
    strong && 'font-semibold',
    italic && 'italic',
    underline && 'underline',
    del && 'line-through',
    disabled && 'cursor-not-allowed opacity-50',
    code && 'rounded bg-muted px-1.5 py-0.5 font-mono text-xs',
    className,
  );

  if (code) {
    return (
      <code className={cls} style={style} onClick={onClick}>
        {children}
      </code>
    );
  }

  return (
    <span className={cls} style={style} onClick={onClick}>
      {children}
    </span>
  );
}

export interface TypographyParagraphProps extends TypographyTextProps {
  ellipsis?: boolean;
}

function Paragraph({ ellipsis, className, children, ...rest }: TypographyParagraphProps) {
  return (
    <Text className={cn('block', ellipsis && 'truncate', className)} {...rest}>
      {children}
    </Text>
  );
}

export const Typography = { Title, Text, Paragraph };
