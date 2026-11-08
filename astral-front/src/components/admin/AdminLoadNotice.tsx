'use client';

import { ErrorState } from '@/components/ErrorState';

export function AdminLoadingNotice({ label = '正在加载…' }: { label?: string }) {
  return (
    <p role="status" aria-live="polite" className="text-muted-foreground py-3 text-sm">
      {label}
    </p>
  );
}

export interface AdminOptionNoticeProps {
  error: string;
  onRetry: () => void;
}

export function AdminOptionNotice({ error, onRetry }: AdminOptionNoticeProps) {
  if (!error) return null;
  return (
    <ErrorState
      message={error}
      onRetry={onRetry}
      description="配置选项未加载成功，已有值保留原值；请重试后再提交。"
    />
  );
}

export interface AdminLoadNoticeProps {
  error: string;
  hasData: boolean;
  onRetry: () => void;
}

/** An error never masquerades as a successful empty list or silently relabels old rows. */
export function AdminLoadNotice({ error, hasData, onRetry }: AdminLoadNoticeProps) {
  if (!error) return null;
  return (
    <ErrorState
      message={error}
      onRetry={onRetry}
      description={
        hasData
          ? '当前请求失败，下面保留的是上次成功加载的数据，并非当前筛选结果。'
          : '尚未取得数据，请重试。'
      }
    />
  );
}
