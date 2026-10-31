'use client';

import type { ReactNode } from 'react';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';

/**
 * 带标题的区块卡片
 *
 * 页面内的「一块内容 + 标题 + 右上操作」统一用它；顶层列表页的卡片如果只是包一张表，
 * 用 DataTable / ResizableTable 自带容器即可，不必再包一层。
 */
export interface SectionCardProps {
  title?: ReactNode;
  description?: ReactNode;
  /** 标题右侧操作按钮 */
  actions?: ReactNode;
  children?: ReactNode;
  className?: string;
  headerClassName?: string;
  contentClassName?: string;
}

export function SectionCard({
  title,
  description,
  actions,
  children,
  className,
  headerClassName,
  contentClassName,
}: SectionCardProps) {
  const hasHeader = Boolean(title || description || actions);

  return (
    <Card className={className}>
      {hasHeader && (
        <CardHeader
          className={cn(
            'flex flex-row items-start justify-between gap-4 space-y-0',
            headerClassName,
          )}
        >
          <div className="min-w-0 flex-1 space-y-1.5">
            {title ? <CardTitle>{title}</CardTitle> : null}
            {description ? <CardDescription>{description}</CardDescription> : null}
          </div>
          {actions ? <div className="shrink-0">{actions}</div> : null}
        </CardHeader>
      )}
      <CardContent className={contentClassName}>{children}</CardContent>
    </Card>
  );
}
