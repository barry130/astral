'use client';

import { useEffect } from 'react';

import { Button } from '@/components/ui/button';

/**
 * 根级错误边界
 * <p>
 * 捕获 app 目录下未被更内层 error.tsx 处理的渲染/加载异常。
 * 没有它时任何渲染异常都会直接白屏，用户看不到任何可操作信息。
 * </p>
 */
export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // 只在控制台留痕便于排查，不向用户暴露堆栈
    console.error('[app/error] 页面异常:', error);
  }, [error]);

  return (
    <div className="flex min-h-[70vh] flex-col items-center justify-center gap-4 bg-background px-6 text-center">
      <h1 className="text-lg font-semibold text-foreground">页面加载出错</h1>
      <p className="max-w-md text-sm text-muted-foreground">
        {error.message || '页面渲染时发生异常，请重试。'}
      </p>
      {error.digest ? <p className="text-xs text-muted-foreground">错误编号：{error.digest}</p> : null}
      <Button onClick={() => reset()}>重试</Button>
    </div>
  );
}
