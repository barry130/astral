'use client';

/**
 * antd 兼容层：反馈类组件
 * message / notification / Spin / Empty / Alert / Result / Tooltip / Popconfirm / Pagination
 */

import { Fragment, useState, type CSSProperties, type ReactNode } from 'react';
import { AlertCircle, CheckCircle2, Info, Loader2, TriangleAlert, XCircle, Inbox } from 'lucide-react';
import { toast } from 'sonner';

import { Alert as ShadcnAlert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Tooltip as ShadcnTooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { TablePagination } from '@/components/data-table/TablePagination';
import { cn } from '@/lib/utils';

/* ============================== message ============================== */

/** 消息提示：代理 sonner toast，保持 antd 的调用形状（第二参数为秒） */
export const message = {
  success: (content: ReactNode, duration?: number) => toast.success(content, { duration: (duration ?? 3) * 1000 }),
  error: (content: ReactNode, duration?: number) => toast.error(content, { duration: (duration ?? 3) * 1000 }),
  warning: (content: ReactNode, duration?: number) => toast.warning(content, { duration: (duration ?? 3) * 1000 }),
  info: (content: ReactNode, duration?: number) => toast.info(content, { duration: (duration ?? 3) * 1000 }),
  loading: (content: ReactNode) => toast.loading(content),
  destroy: () => toast.dismiss(),
};

/** notification：与 message 同源，右上角提示 */
export const notification = {
  success: (args: { message: ReactNode; description?: ReactNode }) =>
    toast.success(args.message, { description: args.description }),
  error: (args: { message: ReactNode; description?: ReactNode }) =>
    toast.error(args.message, { description: args.description }),
  warning: (args: { message: ReactNode; description?: ReactNode }) =>
    toast.warning(args.message, { description: args.description }),
  info: (args: { message: ReactNode; description?: ReactNode }) =>
    toast.info(args.message, { description: args.description }),
};

/* ================================ Spin ================================ */

export interface SpinProps {
  size?: 'small' | 'default' | 'large';
  spinning?: boolean;
  tip?: ReactNode;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

export function Spin({ size = 'default', spinning = true, className, style, children }: SpinProps) {
  const iconSize = size === 'small' ? 'size-4' : size === 'large' ? 'size-8' : 'size-5';
  const icon = <Loader2 className={cn(iconSize, 'animate-spin text-muted-foreground')} />;

  if (!children) {
    return spinning ? (
      <div className={cn('flex justify-center py-2', className)} style={style}>
        {icon}
      </div>
    ) : null;
  }

  return (
    <div className={cn('relative', className)} style={style}>
      {children}
      {spinning && (
        <div className="absolute inset-0 z-[2] flex items-center justify-center bg-background/60">{icon}</div>
      )}
    </div>
  );
}

/* ================================ Empty ================================ */

export interface EmptyProps {
  description?: ReactNode;
  image?: ReactNode;
  /** 与 antd 同形的占位常量（本项目统一改用内置图标，保留以兼容写法） */
  imageStyle?: CSSProperties;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}

export function Empty({ description = '暂无数据', image, className, style, children }: EmptyProps) {
  return (
    <div className={cn('flex flex-col items-center gap-3 py-8', className)} style={style}>
      {image ?? <Inbox className="size-8 text-muted-foreground/50" strokeWidth={1.5} aria-hidden />}
      <span className="text-sm text-muted-foreground">{description}</span>
      {children}
    </div>
  );
}

/** antd 内置插画常量占位（本项目渲染自定义图标，仅保证写法可编译） */
Empty.PRESENTED_IMAGE_SIMPLE = null;

/* ================================ Alert ================================ */

export interface AlertProps {
  type?: 'success' | 'info' | 'warning' | 'error';
  message?: ReactNode;
  description?: ReactNode;
  showIcon?: boolean;
  action?: ReactNode;
  closable?: boolean;
  className?: string;
  style?: CSSProperties;
}

export function Alert({
  type = 'info',
  message,
  description,
  showIcon = false,
  action,
  closable,
  className,
  style,
}: AlertProps) {
  const [closed, setClosed] = useState(false);
  if (closed) return null;

  const iconMap = {
    success: <CheckCircle2 />,
    info: <Info />,
    warning: <TriangleAlert />,
    error: <AlertCircle />,
  } as const;

  const variant = type === 'error' ? 'destructive' : type === 'warning' ? 'warning' : type === 'success' ? 'success' : 'default';

  return (
    <ShadcnAlert variant={variant} className={cn(closable && 'pr-8', className)} style={style}>
      {showIcon && iconMap[type]}
      {message ? <AlertTitle>{message}</AlertTitle> : null}
      {description ? <AlertDescription>{description}</AlertDescription> : null}
      {action ? <div className="col-start-2 mt-1">{action}</div> : null}
      {closable ? (
        <button
          type="button"
          aria-label="关闭"
          onClick={() => setClosed(true)}
          className="absolute right-2 top-2 cursor-pointer rounded-sm p-0.5 text-muted-foreground opacity-60 hover:opacity-100"
        >
          <XCircle className="size-3.5" />
        </button>
      ) : null}
    </ShadcnAlert>
  );
}

/* ================================ Result ================================ */

export interface ResultProps {
  status?: 'success' | 'error' | 'info' | 'warning' | '404' | '403' | '500';
  title?: ReactNode;
  subTitle?: ReactNode;
  extra?: ReactNode;
  className?: string;
  style?: CSSProperties;
}

export function Result({ status = 'info', title, subTitle, extra, className, style }: ResultProps) {
  const iconMap = {
    success: <CheckCircle2 className="size-12 text-emerald-500" strokeWidth={1.5} />,
    error: <XCircle className="size-12 text-destructive" strokeWidth={1.5} />,
    warning: <TriangleAlert className="size-12 text-amber-500" strokeWidth={1.5} />,
    info: <Info className="size-12 text-muted-foreground" strokeWidth={1.5} />,
    '404': <AlertCircle className="size-12 text-muted-foreground" strokeWidth={1.5} />,
    '403': <AlertCircle className="size-12 text-muted-foreground" strokeWidth={1.5} />,
    '500': <XCircle className="size-12 text-muted-foreground" strokeWidth={1.5} />,
  } as const;

  return (
    <div className={cn('flex flex-col items-center gap-3 py-12 text-center', className)} style={style}>
      {iconMap[status]}
      <div className="text-xl font-semibold text-foreground">{title}</div>
      {subTitle ? <div className="text-sm text-muted-foreground">{subTitle}</div> : null}
      {extra ? (
        <div className="mt-2 flex flex-wrap justify-center gap-3">
          {/* antd 的 extra 常写作数组（[<Button/>, <Button/>]），这里补上 key 避免 React 警告 */}
          {Array.isArray(extra)
            ? (extra as ReactNode[]).map((node, i) => <Fragment key={i}>{node}</Fragment>)
            : extra}
        </div>
      ) : null}
    </div>
  );
}

/* ================================ Tooltip ================================ */

export interface TooltipProps {
  title?: ReactNode;
  placement?: 'top' | 'bottom' | 'left' | 'right';
  children?: ReactNode;
  className?: string;
}

export function Tooltip({ title, placement = 'top', children, className }: TooltipProps) {
  if (!title) return <>{children}</>;
  return (
    <ShadcnTooltip>
      <TooltipTrigger asChild className={className}>
        {/* 用 span 包裹以支持非按钮子元素触发 */}
        <span className="inline-flex">{children}</span>
      </TooltipTrigger>
      <TooltipContent side={placement}>{title}</TooltipContent>
    </ShadcnTooltip>
  );
}

/* ============================== Popconfirm ============================== */

export interface PopconfirmProps {
  title?: ReactNode;
  description?: ReactNode;
  onConfirm?: () => void | Promise<void>;
  onCancel?: () => void;
  okText?: ReactNode;
  cancelText?: ReactNode;
  okButtonProps?: { danger?: boolean; loading?: boolean };
  disabled?: boolean;
  placement?: 'top' | 'bottom' | 'left' | 'right';
  children?: ReactNode;
}

export function Popconfirm({
  title,
  description,
  onConfirm,
  onCancel,
  okText = '确定',
  cancelText = '取消',
  okButtonProps,
  disabled,
  placement = 'top',
  children,
}: PopconfirmProps) {
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);

  const handleConfirm = async () => {
    setConfirming(true);
    try {
      await onConfirm?.();
      setOpen(false);
    } finally {
      setConfirming(false);
    }
  };

  return (
    <Popover open={disabled ? false : open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <span className="inline-flex">{children}</span>
      </PopoverTrigger>
      <PopoverContent side={placement} className="w-64">
        <div className="space-y-1">
          <div className="text-sm font-medium">{title}</div>
          {description ? <div className="text-xs text-muted-foreground">{description}</div> : null}
        </div>
        <div className="mt-3 flex justify-end gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              setOpen(false);
              onCancel?.();
            }}
          >
            {cancelText}
          </Button>
          <Button
            size="sm"
            variant={okButtonProps?.danger ? 'destructive' : 'default'}
            disabled={confirming}
            onClick={handleConfirm}
          >
            {confirming && <Loader2 className="animate-spin" />}
            {okText}
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/* ============================== Pagination ============================== */

export interface PaginationProps {
  current?: number;
  pageSize?: number;
  total?: number;
  onChange?: (page: number, pageSize: number) => void;
  onShowSizeChange?: (current: number, size: number) => void;
  showSizeChanger?: boolean;
  pageSizeOptions?: string[];
  showQuickJumper?: boolean;
  showTotal?: (total: number, range: [number, number]) => ReactNode;
  hideOnSinglePage?: boolean;
  size?: 'small' | 'default';
  className?: string;
  style?: CSSProperties;
}

export function Pagination({
  current = 1,
  pageSize = 10,
  total = 0,
  onChange,
  onShowSizeChange,
  showSizeChanger,
  pageSizeOptions,
  showQuickJumper,
  showTotal,
  hideOnSinglePage,
  size,
  className,
  style,
}: PaginationProps) {
  if (hideOnSinglePage && total <= pageSize) return null;
  return (
    <div className={cn(size === 'small' && 'text-xs', className)} style={style}>
      <TablePagination
        pagination={{
          current,
          pageSize,
          total,
          onChange,
          onShowSizeChange,
          showSizeChanger,
          pageSizeOptions,
          showQuickJumper,
          showTotal,
        }}
        total={total}
      />
    </div>
  );
}
