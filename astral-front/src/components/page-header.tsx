import * as React from 'react';

import { cn } from '@/lib/utils';

/**
 * 页面头部：标题 + 说明 + 右侧操作，全站统一。
 *
 * 之所以抽出来：早先各页面各写各的（有的 22px、有的 20px，有的一句话说明都没有），
 * 同一个后台里换页就换排版；统一到一处后，字阶、间距、操作区对齐都只改这里。
 *
 * @example
 * <PageHeader
 *   title="用户管理"
 *   description="维护后台账号、启用状态与角色归属"
 *   actions={<Button type="primary">新建用户</Button>}
 * />
 */
export function PageHeader({
  title,
  description,
  actions,
  eyebrow,
  className,
  children,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  /** 标题上方的极小字标签（分组名 / 分类），可不传 */
  eyebrow?: React.ReactNode;
  className?: string;
  /** 标题区下方的附加内容（如筛选条、分段页签） */
  children?: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        'flex flex-wrap items-start justify-between gap-x-6 gap-y-3',
        children && 'pb-1',
        className,
      )}
    >
      <div className="min-w-0 space-y-1.5">
        {eyebrow ? (
          <div className="text-[11px] font-semibold tracking-[0.14em] text-[color:var(--color-text-tertiary)] uppercase">
            {eyebrow}
          </div>
        ) : null}
        <h1 className="text-[23px] leading-tight font-semibold tracking-[-0.02em] text-[color:var(--color-text)]">
          {title}
        </h1>
        {description ? (
          <p className="max-w-3xl text-[13.5px] leading-relaxed text-[color:var(--color-text-tertiary)]">
            {description}
          </p>
        ) : null}
        {children}
      </div>
      {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  );
}
