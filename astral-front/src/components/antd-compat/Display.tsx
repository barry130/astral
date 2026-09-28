'use client';

/**
 * antd 兼容层：展示类组件
 * Tag / Statistic / Timeline / Badge / Descriptions / Table
 */

import { Children, isValidElement, type CSSProperties, type ReactNode } from 'react';

import { DataTable } from '@/components/data-table/DataTable';
import type { DataTableColumn, DataTablePagination } from '@/components/data-table/types';
import { cn } from '@/lib/utils';

/* ================================================================ Tag */

/** antd 预设色板 → Tailwind 类（浅底 + 深字，兼顾暗色） */
const TAG_COLORS: Record<string, string> = {
  blue: 'border-blue-500/25 bg-blue-500/10 text-blue-600 dark:text-blue-400',
  geekblue: 'border-indigo-500/25 bg-indigo-500/10 text-indigo-600 dark:text-indigo-400',
  cyan: 'border-cyan-500/25 bg-cyan-500/10 text-cyan-600 dark:text-cyan-400',
  green: 'border-green-500/25 bg-green-500/10 text-green-600 dark:text-green-400',
  success: 'border-green-500/25 bg-green-500/10 text-green-600 dark:text-green-400',
  lime: 'border-lime-500/25 bg-lime-500/10 text-lime-600 dark:text-lime-400',
  gold: 'border-amber-500/25 bg-amber-500/10 text-amber-600 dark:text-amber-400',
  orange: 'border-orange-500/25 bg-orange-500/10 text-orange-600 dark:text-orange-400',
  volcano: 'border-red-500/25 bg-red-500/10 text-red-600 dark:text-red-400',
  red: 'border-red-500/25 bg-red-500/10 text-red-600 dark:text-red-400',
  error: 'border-red-500/25 bg-red-500/10 text-red-600 dark:text-red-400',
  magenta: 'border-pink-500/25 bg-pink-500/10 text-pink-600 dark:text-pink-400',
  pink: 'border-pink-500/25 bg-pink-500/10 text-pink-600 dark:text-pink-400',
  purple: 'border-purple-500/25 bg-purple-500/10 text-purple-600 dark:text-purple-400',
  default: 'border-border bg-muted text-muted-foreground',
  warning: 'border-amber-500/25 bg-amber-500/10 text-amber-600 dark:text-amber-400',
  processing: 'border-blue-500/25 bg-blue-500/10 text-blue-600 dark:text-blue-400',
};

export interface TagProps {
  color?: string;
  /** 自定义图标（antd 支持 icon 属性） */
  icon?: ReactNode;
  closable?: boolean;
  onClose?: () => void;
  bordered?: boolean;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

export function Tag({ color, icon, closable, onClose, bordered = true, className, style, children }: TagProps) {
  const preset = color ? TAG_COLORS[color] : undefined;
  const isHex = !!color && /^#|^rgb|^hsl/.test(color);

  return (
    <span
      className={cn(
        'inline-flex max-w-full items-center gap-1 rounded border px-1.5 py-0.5 text-xs leading-5',
        !bordered && 'border-transparent',
        preset ?? (color ? 'border-transparent' : 'border-border bg-muted text-muted-foreground'),
        className,
      )}
      style={
        isHex
          ? { backgroundColor: `${color}1a`, borderColor: `${color}40`, color, ...style }
          : style
      }
    >
      {icon}
      <span className="truncate">{children}</span>
      {closable ? (
        <button type="button" aria-label="删除" className="cursor-pointer opacity-60 hover:opacity-100" onClick={onClose}>
          ×
        </button>
      ) : null}
    </span>
  );
}

/* ================================================================ Statistic */

export interface StatisticProps {
  title?: ReactNode;
  value?: number | string | null;
  precision?: number;
  prefix?: ReactNode;
  suffix?: ReactNode;
  valueStyle?: CSSProperties;
  className?: string;
  style?: CSSProperties;
}

export function Statistic({ title, value, precision, prefix, suffix, valueStyle, className, style }: StatisticProps) {
  const display =
    typeof value === 'number'
      ? precision !== undefined
        ? value.toFixed(precision)
        : value.toLocaleString('zh-CN')
      : value ?? '-';

  return (
    <div className={cn('flex flex-col gap-1', className)} style={style}>
      {title ? <span className="text-xs text-muted-foreground">{title}</span> : null}
      <span className="flex items-baseline gap-1 text-xl font-semibold tabular-nums" style={valueStyle}>
        {prefix ? <span className="text-sm font-normal">{prefix}</span> : null}
        {display}
        {suffix ? <span className="text-sm font-normal text-muted-foreground">{suffix}</span> : null}
      </span>
    </div>
  );
}

/* ================================================================ Timeline */

export interface TimelineItem {
  children?: ReactNode;
  label?: ReactNode;
  color?: string;
  dot?: ReactNode;
}

export interface TimelineProps {
  items?: TimelineItem[];
  children?: ReactNode;
  /** 'left' | 'right' | 'alternate'（本项目仅用默认单列，其余降级为默认） */
  mode?: 'left' | 'right' | 'alternate';
  pending?: ReactNode;
  className?: string;
  style?: CSSProperties;
}

export function Timeline({ items, children, className, style }: TimelineProps) {
  const list: TimelineItem[] =
    items ??
    Children.toArray(children)
      .filter(isValidElement)
      .map((c) => (c.props as { children?: ReactNode; color?: string; label?: ReactNode }));

  return (
    <ul className={cn('relative space-y-4 pl-1', className)} style={style}>
      {list.map((item, i) => (
        <li key={i} className="relative flex gap-3">
          <span className="relative flex w-3 shrink-0 justify-center">
            <span
              className="mt-1.5 size-2 shrink-0 rounded-full ring-4 ring-background"
              style={{ backgroundColor: item.color === 'red' ? '#ef4444' : item.color === 'green' ? '#22c55e' : 'var(--color-text-tertiary, #a1a1aa)' }}
            />
            {i < list.length - 1 ? <span className="absolute top-4 bottom-[-1rem] w-px bg-border" /> : null}
          </span>
          <div className="min-w-0 flex-1 pb-1">
            {item.label ? <div className="text-xs text-muted-foreground">{item.label}</div> : null}
            <div className="text-sm">{item.children}</div>
          </div>
        </li>
      ))}
    </ul>
  );
}

/* ================================================================ Badge */

export interface BadgeProps {
  count?: number;
  overflowCount?: number;
  dot?: boolean;
  status?: 'success' | 'processing' | 'default' | 'error' | 'warning';
  text?: ReactNode;
  color?: string;
  offset?: [number, number];
  showZero?: boolean;
  size?: 'default' | 'small';
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

export function Badge({
  count,
  overflowCount = 99,
  dot,
  status,
  text,
  color,
  offset,
  showZero,
  size = 'default',
  className,
  style,
  children,
}: BadgeProps) {
  const hidden = !dot && (count === undefined || (count === 0 && !showZero));
  const display = count !== undefined && count > overflowCount ? `${overflowCount}+` : count;

  const statusColor: Record<string, string> = {
    success: 'bg-green-500',
    processing: 'bg-blue-500',
    error: 'bg-red-500',
    warning: 'bg-amber-500',
    default: 'bg-muted-foreground',
  };

  if (status !== undefined || (text !== undefined && !children)) {
    return (
      <span className={cn('inline-flex items-center gap-2 text-sm', className)} style={style}>
        <span className={cn('size-1.5 rounded-full', color ?? statusColor[status ?? 'default'])} />
        {text}
      </span>
    );
  }

  return (
    <span className={cn('relative inline-flex', className)} style={style}>
      {children}
      {!hidden ? (
        <span
          className={cn(
            'absolute -top-1.5 -right-1.5 z-[1] flex items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-medium leading-4 text-white',
            size === 'small' && 'text-[9px]',
            dot && 'size-2 p-0',
          )}
          style={offset ? { transform: `translate(${offset[0]}px, ${offset[1]}px)` } : undefined}
        >
          {dot ? null : display}
        </span>
      ) : null}
    </span>
  );
}

/* ================================================================ Descriptions */

export interface DescriptionsItem {
  key?: string | number;
  label?: ReactNode;
  children?: ReactNode;
  span?: number;
}

export interface DescriptionsProps {
  title?: ReactNode;
  items?: DescriptionsItem[];
  children?: ReactNode;
  column?: number | Record<string, number>;
  bordered?: boolean;
  size?: 'default' | 'middle' | 'small';
  layout?: 'horizontal' | 'vertical';
  labelStyle?: CSSProperties;
  contentStyle?: CSSProperties;
  className?: string;
  style?: CSSProperties;
}

export function Descriptions({
  title,
  items,
  children,
  column = 3,
  bordered,
  size = 'default',
  layout = 'horizontal',
  labelStyle,
  contentStyle,
  className,
  style,
}: DescriptionsProps) {
  const list: DescriptionsItem[] =
    items ??
    Children.toArray(children)
      .filter(isValidElement)
      .map((c) => c.props as DescriptionsItem);
  const cols = typeof column === 'number' ? column : 3;

  return (
    <div className={cn('w-full', className)} style={style}>
      {title ? <div className="mb-3 text-sm font-semibold">{title}</div> : null}
      <div
        className={cn('grid gap-x-4 gap-y-0', size === 'small' ? 'text-xs' : 'text-sm')}
        style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}
      >
        {list.map((item, i) => (
          <div
            key={item.key ?? i}
            className={cn(
              'flex min-w-0 gap-2 py-2',
              layout === 'vertical' ? 'flex-col gap-1' : 'items-baseline',
              bordered && 'border-b border-border',
            )}
            style={{ gridColumn: `span ${Math.min(item.span ?? 1, cols)} / span ${Math.min(item.span ?? 1, cols)}` }}
          >
            <span className={cn('shrink-0 text-muted-foreground', layout === 'horizontal' && 'w-24 text-right')} style={labelStyle}>
              {item.label}
            </span>
            <span className="min-w-0 flex-1 break-words" style={contentStyle}>
              {item.children}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

Descriptions.Item = function DescriptionsItemPlaceholder(_props: DescriptionsItem) {
  return null;
};

/* ================================================================ Table */

export interface TableProps<T = any> {
  columns?: Array<DataTableColumn<T>>;
  dataSource?: readonly T[];
  rowKey?: string | ((record: T) => string | number);
  loading?: boolean | { spinning?: boolean };
  pagination?: false | DataTablePagination;
  size?: 'small' | 'middle' | 'large';
  /** 与 antd 同形；仅消费 y（纵向滚动），x 由外层容器自适应 */
  scroll?: { x?: number | string | true; y?: number | string };
  locale?: { emptyText?: ReactNode };
  bordered?: boolean;
  className?: string;
  style?: CSSProperties;
  expandable?: {
    expandedRowRender?: (record: T, index: number) => ReactNode;
    expandedRowKeys?: Array<string | number>;
    onExpand?: (expanded: boolean, record: T) => void;
    rowExpandable?: (record: T) => boolean;
  };
  defaultExpandAllRows?: boolean;
  onRow?: (record: T, index: number) => Record<string, unknown>;
  /** 分页/排序变化（antd 同形：第一参为分页对象）；项目内仅使用分页信息 */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  onChange?: (pagination: any, filters?: any, sorter?: any, extra?: any) => void;
  rowClassName?: string | ((record: T, index: number) => string);
  title?: () => ReactNode;
  footer?: () => ReactNode;
  showHeader?: boolean;
}

/**
 * antd `Table` 的最小兼容实现：交给自研 DataTable 渲染。
 * 项目内 antd Table 仅 3 处使用（日志页 / 邮件日志页），能力足够。
 */
export function Table<T extends Record<string, any>>({
  columns = [],
  dataSource = [],
  rowKey,
  loading,
  pagination,
  size = 'middle',
  scroll,
  locale,
  bordered,
  className,
  style,
  expandable,
  defaultExpandAllRows,
  onRow,
  rowClassName,
  onChange,
}: TableProps<T>) {
  const scrollY = typeof scroll?.y === 'number' ? scroll.y : undefined;

  const resolveClassName = (record: T, index: number) =>
    typeof rowClassName === 'function' ? rowClassName(record, index) : rowClassName;

  // antd 的 pagination.onChange 与 Table.onChange 都会触发；这里统一包装，避免页面漏接
  const paginationProps: false | DataTablePagination =
    pagination === false
      ? false
      : {
          ...pagination,
          onChange: (page, pageSize) => {
            pagination?.onChange?.(page, pageSize);
            onChange?.({ current: page, pageSize, total: pagination?.total ?? 0 }, {}, {});
          },
        };

  return (
    <div className={className} style={style}>
      <DataTable<T>
        columns={columns}
        dataSource={[...dataSource]}
        rowKey={rowKey}
        loading={loading}
        pagination={paginationProps}
        size={size === 'small' ? 'small' : 'middle'}
        scrollY={scrollY}
        emptyText={locale?.emptyText}
        bordered={bordered}
        expandable={expandable}
        defaultExpandAllRows={defaultExpandAllRows}
        onRow={(record, index) => ({ ...onRow?.(record, index), className: resolveClassName(record, index) })}
      />
    </div>
  );
}

export { DataTable };
