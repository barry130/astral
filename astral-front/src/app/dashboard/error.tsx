'use client';

import { useEffect } from 'react';

import { Button } from '@/components/ui/button';

/**
 * 后台内层错误边界
 * <p>
 * 只替换 dashboard 内容区，侧边栏 / 顶栏 / 标签页保持可用，
 * 不像根级 error.tsx 那样整站替换，用户可以直接切到其它菜单继续用。
 * </p>
 */
export default function DashboardError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error('[dashboard/error] 页面异常:', error);
  }, [error]);

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 rounded-lg border border-border bg-card px-6 py-16 text-center">
      <h2 className="text-base font-semibold text-foreground">页面加载出错</h2>
      <p className="max-w-md text-sm text-muted-foreground">
        {error.message || '该页面渲染时发生异常，请重试。'}
      </p>
      {error.digest ? <p className="text-xs text-muted-foreground">错误编号：{error.digest}</p> : null}
      <Button onClick={() => reset()}>重试</Button>
    </div>
  );
}
