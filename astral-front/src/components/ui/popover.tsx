'use client';

import * as React from 'react';
import * as PopoverPrimitive from '@radix-ui/react-popover';

import { cn } from '@/lib/utils';

function Popover({ ...props }: React.ComponentProps<typeof PopoverPrimitive.Root>) {
  return <PopoverPrimitive.Root data-slot="popover" {...props} />;
}

function PopoverTrigger({ ...props }: React.ComponentProps<typeof PopoverPrimitive.Trigger>) {
  return <PopoverPrimitive.Trigger data-slot="popover-trigger" {...props} />;
}

/**
 * 找到当前打开的 Dialog 内容节点（作为 portal 目标）。
 *
 * <p>为什么必须 portal 进 Dialog 而不是 body：Dialog 打开时 `react-remove-scroll`
 * 会接管页面滚动，**只放行 Dialog 子树内的滚动**；子树的判定依据是 DOM 祖先关系
 * （`handleScroll` 里 `locationCouldBeScrolled` 一路向上走，走到 `document.body` 就返回 false），
 * 对子树外的 wheel 事件直接 `preventDefault`。
 * Radix `Popover` 默认 portal 到 `body`，于是落在锁滚动子树**之外**，弹层里所有滚动都被吞掉 ——
 * 实测同一个容器：portal 到 body 时滚轮 `scrollTop` 恒为 0 且 `defaultPrevented=true`；
 * 挪进 `[data-slot="dialog-content"]` 后立刻变成 480 且不再被 preventDefault。
 *
 * <p>注意这**不是** `pointer-events` 问题（那是另一个坑，见下方 className 注释）：
 * 即使把 body 的 `pointer-events:none` 与 `overflow:hidden` 全部撤销，滚轮依然无效。
 */
function useDialogPortalContainer(): HTMLElement | null {
  const [container, setContainer] = React.useState<HTMLElement | null>(null);

  React.useEffect(() => {
    setContainer(document.querySelector<HTMLElement>('[data-slot="dialog-content"]'));
  }, []);

  return container;
}

function PopoverContent({
  className,
  align = 'center',
  sideOffset = 4,
  ...props
}: React.ComponentProps<typeof PopoverPrimitive.Content>) {
  const dialogContainer = useDialogPortalContainer();
  // 弹窗内：portal 到 Dialog 内容节点（进入锁滚动子树，滚动才生效）
  // 弹窗外：维持原来的 body portal
  const container = dialogContainer ?? undefined;

  return (
    <PopoverPrimitive.Portal container={container}>
      <PopoverPrimitive.Content
        data-slot="popover-content"
        align={align}
        sideOffset={sideOffset}
        className={cn(
          'z-50 w-72 rounded-md border border-border bg-popover p-4 text-popover-foreground shadow-md outline-hidden',
          // pointer-events-auto 仍然需要，但解决的是**另一个**问题（"点不动"）：
          // Radix Dialog 打开时通过 DismissableLayer 给 body 设 inline `pointer-events: none`，
          // 且只给「注册了的图层」恢复 auto（DialogOverlay / DialogContent / SelectContent /
          // DropdownMenuContent 都因此显式恢复）。Popover 默认 `modal={false}`，走
          // PopoverContentNonModal（disableOutsidePointerEvents: false）**不注册图层**，
          // 于是内容节点继承 none → 弹层按钮点不动。注意 Select/DropdownMenu 无此问题。
          'pointer-events-auto',
          'data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
          className,
        )}
        {...props}
      />
    </PopoverPrimitive.Portal>
  );
}

function PopoverAnchor({ ...props }: React.ComponentProps<typeof PopoverPrimitive.Anchor>) {
  return <PopoverPrimitive.Anchor data-slot="popover-anchor" {...props} />;
}

export { Popover, PopoverTrigger, PopoverContent, PopoverAnchor };
