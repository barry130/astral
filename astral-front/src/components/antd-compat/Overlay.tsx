'use client';

/**
 * antd 兼容层：弹层与切换类组件
 * Modal / Drawer / Tabs（含 TabPane 旧写法）
 */

import {
  Children,
  isValidElement,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { Loader2, X } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { cn } from '@/lib/utils';

/* ================================================================ Modal */

/**
 * 弹窗默认宽度（px）。
 * DialogContent 自带的 `sm:max-w-lg` 只有 512px，编辑类表单（多列表单、富文本说明、表格）过窄，
 * 因此兼容层统一放宽到 760px；调用方仍可用 `width={…}` 覆盖。
 */
const DEFAULT_MODAL_WIDTH_PX = 760;

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
  okType: _okType = 'primary',
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
  const [viewportWidth, setViewportWidth] = useState(0);

  // 移动端判定：视口 < 768px（与 Tailwind md 断点、globals.css 的 .compat-col-md-* 一致）。
  // 弹窗内边距从 px-5 降到 px-4，给窄屏省出 8px 可用宽度。
  useEffect(() => {
    const sync = () => setViewportWidth(window.innerWidth);
    sync();
    window.addEventListener('resize', sync);
    return () => window.removeEventListener('resize', sync);
  }, []);
  const isCompact = viewportWidth > 0 && viewportWidth < 768;

  // 统一套一层 min(期望宽度, 100vw - 2rem)：
  // 桌面端按调用方/默认宽度展示；手机上任何宽度都会被压到「视口 − 32px」，
  // 左右各留 16px 安全边距（否则 maxWidth 是内联样式，会盖掉 DialogContent 的
  // max-w-[calc(100%-2rem)] + w-full，弹窗会贴满整屏、圆角被裁掉）。
  const resolvedWidth =
    typeof width === 'number' || typeof width === 'string' ? width : DEFAULT_MODAL_WIDTH_PX;
  const widthCss = typeof resolvedWidth === 'number' ? `${resolvedWidth}px` : resolvedWidth;
  const maxWidth = `min(${widthCss}, calc(100vw - 2rem))`;
  const loading = confirmLoading || submitting;

  const handleOk = async () => {
    if (!onOk) return;
    const result = onOk();
    if (result && typeof (result as Promise<void>).then === 'function') {
      setSubmitting(true);
      try {
        await result;
      } catch (e) {
        // 兜底：onOk 返回的 Promise 失败时不要让 rejection 逃逸（onClick 不会消费返回值），
        // 业务错误提示仍由各调用方在 onOk 内部自行处理，这里只保证不产生未捕获异常。
        // 保留控制台输出，避免调试信息被静默吞掉。
        console.error('[Modal] onOk 未处理异常:', e);
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
      <Button
        variant={okButtonProps?.danger ? 'destructive' : 'default'}
        disabled={okButtonProps?.disabled || loading}
        onClick={handleOk}
      >
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
        // max-h + grid-rows 是「超高表单」的通用解药：DialogContent 是 grid 容器，
        // 不限制高度时内容多高弹窗就多高 —— 实测轻听「版本更新」表单 13 个纵向字段把弹窗撑到 1122px，
        // 在 900px 视口里上下都被截断，且 overflow 默认 visible ⇒ 无法滚动，
        // 底部「取消/确定」落在 top 958 处**点不到**（表单根本提交不了）。
        // 约束为视口高度内、把 body 放进可收缩的 1fr 行，头/脚各占 auto 行 ⇒ 头脚常驻、中间滚动。
        // 注意：不能加 overflow-hidden，否则 portal 进 dialog-content 的 Popover 弹层会被裁掉。
        className={cn(
          'max-h-[calc(100dvh-2rem)] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 p-0',
          className,
        )}
        style={{ maxWidth, zIndex: style?.zIndex, ...style, ...styles?.content }}
        onInteractOutside={(e) => {
          if (!maskClosable) e.preventDefault();
        }}
        onEscapeKeyDown={(e) => {
          if (!keyboard) e.preventDefault();
        }}
      >
        <div
          className={cn(
            'flex items-center justify-between gap-4 border-b border-[color:var(--color-border-light)] py-4',
            isCompact ? 'px-4' : 'px-6',
          )}
          style={styles?.header}
        >
          <DialogTitle className="text-[color:var(--color-text)] text-[16px] font-semibold">
            {title}
          </DialogTitle>
          <DialogPrimitive.Close
            aria-label="关闭"
            className="text-[color:var(--color-text-tertiary)] inline-flex size-8 cursor-pointer items-center justify-center rounded-full transition-colors hover:bg-[var(--color-bg-tint)] hover:text-[color:var(--color-text)]"
          >
            <X className="size-4" />
          </DialogPrimitive.Close>
        </div>
        {/* min-w-0 必需：DialogContent 是 grid 容器，子项默认 min-width:auto ⇒ 不可收缩，
            任何超宽内容（如大量标签的 TreeSelect）都会把 640px 的弹窗整体撑到上千像素并漏出页面。
            加 min-w-0 后弹窗宽度由自身 maxWidth 决定，超宽内容只在内部溢出/滚动。
            overflow-y-auto 让超高表单在弹窗内部滚动（配合上面的 max-h + 1fr 行）。 */}
        <div
          className={cn('min-w-0 overflow-y-auto py-5', isCompact ? 'px-4' : 'px-6')}
          style={{ ...bodyStyle, ...styles?.body }}
        >
          {children}
        </div>
        {resolvedFooter ? (
          <div
            className={cn(
              'flex flex-wrap items-center justify-end gap-2 border-t border-[color:var(--color-border-light)] bg-[var(--color-bg-tint)] py-3.5',
              isCompact ? 'px-4' : 'px-6',
            )}
          >
            {resolvedFooter}
          </div>
        ) : null}
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
            'data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 fixed inset-0 z-50 bg-black/50',
          )}
          onClick={() => maskClosable && onClose?.()}
        />
        <DialogPrimitive.Content
          data-slot="dialog-content"
          className={cn(
            'border-[color:var(--color-border-light)] bg-card fixed z-50 flex flex-col shadow-[0_28px_70px_-30px_rgba(16,32,26,0.35)] outline-none',
            PLACEMENT_CLS[placement] ?? PLACEMENT_CLS.right,
            'data-[state=open]:animate-in data-[state=closed]:animate-out',
            className,
          )}
          style={{
            // maxWidth 用 min() 兜底：手机上（含 1080px 宽的横屏手机）抽屉不会超出视口。
            ...(isVertical
              ? { height: size, maxHeight: '100%' }
              : { width: size, maxWidth: 'min(100%, 100vw)' }),
            ...style,
          }}
          onInteractOutside={(e) => {
            if (!maskClosable) e.preventDefault();
          }}
        >
          <div className="flex shrink-0 items-center justify-between gap-4 border-b border-[color:var(--color-border-light)] px-5 py-4">
            <DialogPrimitive.Title className="text-[color:var(--color-text)] text-[16px] font-semibold">
              {title}
            </DialogPrimitive.Title>
            <div className="flex items-center gap-2">
              {extra}
              {closable ? (
                <DialogPrimitive.Close
                  aria-label="关闭"
                  className="text-muted-foreground cursor-pointer rounded-sm opacity-70 hover:opacity-100"
                >
                  <X className="size-4" />
                </DialogPrimitive.Close>
              ) : null}
            </div>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4" style={bodyStyle}>
            {children}
          </div>
          {footer ? (
            <div className="flex shrink-0 items-center justify-end gap-2 border-t border-[color:var(--color-border-light)] bg-[var(--color-bg-tint)] px-5 py-3.5">
              {footer}
            </div>
          ) : null}
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
  /** 为 true 时即使未激活也立即挂载（antd 语义一致）；默认懒挂载 */
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
function TabPane(_props: {
  key?: unknown;
  tab?: ReactNode;
  children?: ReactNode;
  disabled?: boolean;
  closable?: boolean;
}) {
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
    const p = child.props as {
      tab?: ReactNode;
      children?: ReactNode;
      disabled?: boolean;
      tabKey?: string;
      closable?: boolean;
    };
    const key =
      (child.key !== null && child.key !== undefined ? String(child.key) : p.tabKey) ??
      String(out.length);
    out.push({
      key,
      label: p.tab,
      children: p.children,
      disabled: p.disabled,
      closable: p.closable,
    });
  });
  return out;
}

function TabsBase({
  items,
  children,
  activeKey,
  defaultActiveKey,
  onChange,
  // destroyInactiveTabPane/destroyOnHidden：懒挂载实现里页签只挂一次且保留，
  // antd 的「切走即销毁」语义与 keep-alive 冲突，这里有意忽略（保持状态不丢）
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
  const card = type === 'card' || type === 'editable-card';
  /** 已访问过的页签（keep-alive 集合）：懒挂载后保留状态，避免切换丢失 */
  const mountedKeysRef = useRef<Set<string>>(new Set());

  const select = (key: string) => {
    if (activeKey === undefined) setInner(key);
    onChange?.(key);
  };

  return (
    <div className={cn('w-full', className)} style={style}>
      <div className="flex w-full flex-wrap items-center gap-3">
        <div
          role="tablist"
          className={cn(
            'tabs-scroll flex w-fit items-center gap-1',
            // 卡片型页签保留贴边外观；普通页签改成 B 端常见的分段胶囊
            card
              ? 'border-border border-b'
              : 'rounded-full bg-[var(--color-bg-tint)] p-1',
          )}
        >
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
                  // inline-flex + items-center：Tailwind preflight 把 svg 设为 display:block，
                  // 页签标签里若写成 <span><Icon /> 文案</span>，图标会独占一行把文案挤下去
                  'inline-flex shrink-0 cursor-pointer items-center gap-1.5 whitespace-nowrap transition-colors',
                  size === 'small' ? 'text-[12.5px]' : 'text-[13px]',
                  tab.disabled && 'cursor-not-allowed opacity-40',
                  card
                    ? cn(
                        'border-border rounded-t-xl border border-b-0',
                        size === 'small' ? 'px-3 py-1.5' : 'px-4 py-2',
                        on
                          ? 'bg-card text-[color:var(--color-text)] font-medium'
                          : 'bg-[var(--color-bg-tint)] text-[color:var(--color-text-tertiary)] hover:text-[color:var(--color-text)]',
                      )
                    : cn(
                        'rounded-full',
                        size === 'small' ? 'px-3 py-1' : 'px-3.5 py-1.5',
                        on
                          ? 'bg-card font-medium text-[color:var(--color-text)] shadow-[0_1px_2px_rgba(16,32,26,0.08)]'
                          : 'text-[color:var(--color-text-tertiary)] hover:text-[color:var(--color-text)]',
                      ),
                )}
              >
                {tab.label}
              </button>
            );
          })}
        </div>
        {tabBarExtraContent ? (
          <div className="ml-auto flex items-center gap-2">{tabBarExtraContent}</div>
        ) : null}
      </div>

      {(() => {
        // 懒挂载 + keep-alive：未访问过的页签不挂载（不发请求），访问过的保留状态。
        // 旧实现把所有页签同时挂载只靠 hidden 隐藏——页签一多（如 AI 配置 11 个），
        // 每个面板的 useEffect 全部触发，首屏瞬间发出几十个请求。
        const everMounted = mountedKeysRef.current;
        const activeKeyNow = active?.key;
        if (activeKeyNow !== undefined && !everMounted.has(activeKeyNow)) {
          everMounted.add(activeKeyNow);
        }
        return list.map((tab) => {
          const on = tab.key === activeKeyNow;
          if (!on && !everMounted.has(tab.key) && !tab.forceRender) return null;
          return (
            <div key={tab.key} role="tabpanel" hidden={!on} className={cn('pt-4', !on && 'hidden')}>
              {tab.children}
            </div>
          );
        });
      })()}
    </div>
  );
}

export const Tabs = Object.assign(TabsBase, { TabPane });
