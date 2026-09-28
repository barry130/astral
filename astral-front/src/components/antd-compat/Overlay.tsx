'use client';

/**
 * antd 兼容层：弹层与切换类组件
 * Modal / Drawer / Tabs（含 TabPane 旧写法）
 */

import { Children, isValidElement, useState, type CSSProperties, type ReactNode } from 'react';
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { Loader2, X } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { cn } from '@/lib/utils';

/* ================================================================ Modal */

export interface ModalProps {
  title?: ReactNode;
  open?: boolean;
  /** antd v4 兼容写法 */
  visible?: boolean;
  onOk?: () => void | Promise<void>;
  onCancel?: () => void;
  okText?: ReactNode;
  cancelText?: ReactNode;
  okType?: 'primary' | 'default' | 'dashed' | 'link' | 'text';
  confirmLoading?: boolean;
  okButtonProps?: { danger?: boolean; disabled?: boolean; loading?: boolean };
  cancelButtonProps?: { disabled?: boolean };
  width?: number | string;
  /** undefined = 默认[取消,确定]；null = 无页脚；其他 = 自定义 */
  footer?: ReactNode | null;
  destroyOnClose?: boolean;
  destroyOnHidden?: boolean;
  maskClosable?: boolean;
  keyboard?: boolean;
  centered?: boolean;
  className?: string;
  style?: CSSProperties;
  bodyStyle?: CSSProperties;
  styles?: { body?: CSSProperties; header?: CSSProperties; content?: CSSProperties };
  children?: ReactNode;
  zIndex?: number;
  afterClose?: () => void;
  forceRender?: boolean;
}

export function Modal({
  title,
  open,
  visible,
  onOk,
  onCancel,
  okText = '确定',
  cancelText = '取消',
  okType = 'primary',
  confirmLoading,
  okButtonProps,
  cancelButtonProps,
  width,
  footer,
  maskClosable = true,
  keyboard = true,
  className,
  style,
  bodyStyle,
  styles,
  children,
}: ModalProps) {
  const isOpen = open ?? visible ?? false;
  const [submitting, setSubmitting] = useState(false);

  const maxWidth = width === undefined ? undefined : typeof width === 'number' ? `${width}px` : width;
  const loading = confirmLoading || submitting;

  const handleOk = async () => {
    if (!onOk) return;
    const result = onOk();
    if (result && typeof (result as Promise<void>).then === 'function') {
      setSubmitting(true);
      try {
        await result;
      } finally {
        setSubmitting(false);
      }
    }
  };

  const defaultFooter = (
    <div className="mt-5 flex justify-end gap-2">
      <Button variant="outline" disabled={cancelButtonProps?.disabled} onClick={onCancel}>
        {cancelText}
      </Button>
      <Button variant={okButtonProps?.danger ? 'destructive' : 'default'} disabled={okButtonProps?.disabled || loading} onClick={handleOk}>
        {loading ? <Loader2 className="animate-spin" /> : null}
        {okText}
      </Button>
    </div>
  );

  const resolvedFooter = footer === undefined ? defaultFooter : footer;

  return (
    <Dialog open={isOpen} onOpenChange={(next) => (!next ? onCancel?.() : undefined)} modal>
      <DialogContent
        showCloseButton={false}
        className={cn('gap-0 p-0', className)}
        style={{ maxWidth, zIndex: style?.zIndex, ...style, ...styles?.content }}
        onInteractOutside={(e) => {
          if (!maskClosable) e.preventDefault();
        }}
        onEscapeKeyDown={(e) => {
          if (!keyboard) e.preventDefault();
        }}
      >
        <div className="flex items-center justify-between gap-4 border-b border-border px-5 py-3.5" style={styles?.header}>
          <DialogTitle className="text-[15px] font-semibold text-foreground">{title}</DialogTitle>
          <DialogPrimitive.Close
            aria-label="关闭"
            className="cursor-pointer rounded-sm text-muted-foreground opacity-70 transition-opacity hover:opacity-100"
          >
            <X className="size-4" />
          </DialogPrimitive.Close>
        </div>
        <div className="px-5 py-4" style={{ ...bodyStyle, ...styles?.body }}>
          {children}
        </div>
        {resolvedFooter ? <div className="px-5 pb-4">{resolvedFooter}</div> : null}
      </DialogContent>
    </Dialog>
  );
}

/* ================================================================ Drawer */

export interface DrawerProps {
  title?: ReactNode;
  open?: boolean;
  visible?: boolean;
  onClose?: () => void;
  width?: number | string;
  height?: number | string;
  placement?: 'top' | 'right' | 'bottom' | 'left';
  extra?: ReactNode;
  footer?: ReactNode;
  closable?: boolean;
  maskClosable?: boolean;
  destroyOnClose?: boolean;
  className?: string;
  style?: CSSProperties;
  bodyStyle?: CSSProperties;
  children?: ReactNode;
}

const PLACEMENT_CLS: Record<string, string> = {
  right: 'inset-y-0 right-0 h-full border-l data-[state=open]:slide-in-from-right',
  left: 'inset-y-0 left-0 h-full border-r data-[state=open]:slide-in-from-left',
  top: 'inset-x-0 top-0 w-full border-b data-[state=open]:slide-in-from-top',
  bottom: 'inset-x-0 bottom-0 w-full border-t data-[state=open]:slide-in-from-bottom',
};

export function Drawer({
  title,
  open,
  visible,
  onClose,
  width = 420,
  height,
  placement = 'right',
  extra,
  footer,
  closable = true,
  maskClosable = true,
  className,
  style,
  bodyStyle,
  children,
}: DrawerProps) {
  const isOpen = open ?? visible ?? false;
  const isVertical = placement === 'top' || placement === 'bottom';
  const size = isVertical ? (height ?? width) : width;

  return (
    <DialogPrimitive.Root open={isOpen} onOpenChange={(next) => (!next ? onClose?.() : undefined)}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          className={cn(
            'fixed inset-0 z-50 bg-black/50 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
          )}
          onClick={() => maskClosable && onClose?.()}
        />
        <DialogPrimitive.Content
          className={cn(
            'fixed z-50 flex flex-col border-border bg-card shadow-lg outline-none',
            PLACEMENT_CLS[placement] ?? PLACEMENT_CLS.right,
            'data-[state=open]:animate-in data-[state=closed]:animate-out',
            className,
          )}
          style={{ ...(isVertical ? { height: size } : { width: size }), ...style }}
          onInteractOutside={(e) => {
            if (!maskClosable) e.preventDefault();
          }}
        >
          <div className="flex shrink-0 items-center justify-between gap-4 border-b border-border px-4 py-3">
            <DialogPrimitive.Title className="text-[15px] font-semibold text-foreground">{title}</DialogPrimitive.Title>
            <div className="flex items-center gap-2">
              {extra}
              {closable ? (
                <DialogPrimitive.Close aria-label="关闭" className="cursor-pointer rounded-sm text-muted-foreground opacity-70 hover:opacity-100">
                  <X className="size-4" />
                </DialogPrimitive.Close>
              ) : null}
            </div>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4" style={bodyStyle}>
            {children}
          </div>
          {footer ? <div className="shrink-0 border-t border-border px-4 py-3">{footer}</div> : null}
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

/* ================================================================ Tabs */

export interface TabItem {
  key: string;
  label?: ReactNode;
  children?: ReactNode;
  disabled?: boolean;
  /** TabPane 旧写法用 */
  tab?: ReactNode;
  closable?: boolean;
  /** 允许 antd 的 forceRender 写法（本实现天然支持，占位即可） */
  forceRender?: boolean;
}

export interface TabsProps {
  items?: TabItem[];
  children?: ReactNode;
  activeKey?: string;
  defaultActiveKey?: string;
  onChange?: (key: string) => void;
  destroyInactiveTabPane?: boolean;
  destroyOnHidden?: boolean;
  type?: 'line' | 'card' | 'editable-card';
  size?: 'small' | 'middle' | 'large';
  tabPosition?: 'top' | 'bottom' | 'left' | 'right';
  tabBarExtraContent?: ReactNode;
  className?: string;
  style?: CSSProperties;
}

/** `<Tabs.TabPane>` 占位：仅承载 props，实际渲染由 Tabs 统一处理 */
function TabPane(_props: { key?: unknown; tab?: ReactNode; children?: ReactNode; disabled?: boolean; closable?: boolean }) {
  return null;
}
TabPane.displayName = 'TabPane';

/** 旧写法（children 为 TabPane）→ 统一的 items */
function normalizeItems(items: TabItem[] | undefined, children: ReactNode): TabItem[] {
  if (items && items.length) {
    return items.map((it) => ({ ...it, label: it.label ?? it.tab }));
  }
  const out: TabItem[] = [];
  Children.forEach(children, (child) => {
    if (!isValidElement(child)) return;
    const p = child.props as { tab?: ReactNode; children?: ReactNode; disabled?: boolean; tabKey?: string; closable?: boolean };
    const key = (child.key !== null && child.key !== undefined ? String(child.key) : p.tabKey) ?? String(out.length);
    out.push({ key, label: p.tab, children: p.children, disabled: p.disabled, closable: p.closable });
  });
  return out;
}

function TabsBase({
  items,
  children,
  activeKey,
  defaultActiveKey,
  onChange,
  destroyInactiveTabPane,
  destroyOnHidden,
  type = 'line',
  size = 'middle',
  className,
  style,
  tabBarExtraContent,
}: TabsProps) {
  const list = normalizeItems(items, children);
  const [inner, setInner] = useState(defaultActiveKey ?? list[0]?.key ?? '');
  const current = activeKey ?? inner;
  const active = list.find((t) => t.key === current) ?? list[0];
  const destroy = destroyInactiveTabPane || destroyOnHidden;
  const card = type === 'card' || type === 'editable-card';

  const select = (key: string) => {
    if (activeKey === undefined) setInner(key);
    onChange?.(key);
  };

  return (
    <div className={cn('w-full', className)} style={style}>
      <div role="tablist" className="flex items-center gap-1 border-b border-border">
        {list.map((tab) => {
          const on = tab.key === active?.key;
          return (
            <button
              key={tab.key}
              type="button"
              role="tab"
              aria-selected={on}
              disabled={tab.disabled}
              onClick={() => select(tab.key)}
              className={cn(
                'relative cursor-pointer whitespace-nowrap text-sm transition-colors',
                size === 'small' ? 'px-2.5 py-1.5' : 'px-3.5 py-2',
                tab.disabled && 'cursor-not-allowed opacity-40',
                card
                  ? cn('rounded-t-md border border-b-0 border-border', on ? 'bg-card font-medium text-foreground' : 'bg-muted/40 text-muted-foreground hover:text-foreground')
                  : on
                    ? 'font-medium text-foreground'
                    : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {tab.label}
              {!card && on ? <span className="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-primary" /> : null}
            </button>
          );
        })}
        {tabBarExtraContent ? <div className="ml-auto flex items-center gap-2 pb-1">{tabBarExtraContent}</div> : null}
      </div>

      {list.map((tab) => {
        const on = tab.key === active?.key;
        if (!on && destroy) return null;
        return (
          <div key={tab.key} role="tabpanel" hidden={!on} className={cn('pt-4', !on && 'hidden')}>
            {tab.children}
          </div>
        );
      })}
    </div>
  );
}

export const Tabs = Object.assign(TabsBase, { TabPane });
