'use client';

import React, { type ReactNode } from 'react';
import { AlertCircle } from 'lucide-react';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';

/**
 * 统一错误状态
 *
 * 数据加载 / 提交失败时使用。传入 onRetry 会渲染「重试」按钮，
 * 让失败态不再是死胡同。容器 role="alert"，屏幕阅读器会朗读。
 */
export interface ErrorStateProps {
  /** 错误标题 */
  message?: ReactNode;
  /** 补充说明（可放错误码、接口名等细节） */
  description?: ReactNode;
  /** 提供时显示重试按钮 */
  onRetry?: () => void;
  /** 重试按钮文案 */
  retryText?: string;
  /** 是否展示类型图标 */
  showIcon?: boolean;
  /** 上下内边距（px） */
  padding?: number;
}

export function ErrorState({
  message = '加载失败，请稍后重试',
  description,
  onRetry,
  retryText = '重试',
  showIcon = true,
  padding = 12,
}: ErrorStateProps) {
  return (
    <div role="alert" style={{ paddingTop: padding, paddingBottom: padding }}>
      <Alert variant="destructive">
        {showIcon && <AlertCircle />}
        <AlertTitle>{message}</AlertTitle>
        {description ? <AlertDescription>{description}</AlertDescription> : null}
        {onRetry ? (
          <div className="col-start-2 mt-2">
            <Button size="sm" variant="outline" onClick={onRetry} aria-label={`重试：${retryText}`}>
              {retryText}
            </Button>
          </div>
        ) : null}
      </Alert>
    </div>
  );
}
