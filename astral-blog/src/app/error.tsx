'use client';

import Link from 'next/link';
import { useEffect } from 'react';

/**
 * 路由级错误边界：后端数据正常但页面渲染出错时，用户至少能重试或回到首页，
 * 而不是看到 Next.js 默认的英文错误页。
 */
export default function ErrorPage({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // 保留到浏览器控制台，便于线上排查；不要把 error.stack 渲染给用户。
    console.error(error);
  }, [error]);

  return (
    <div className="page-shell py-16">
      <p className="eyebrow text-brand">页面出错</p>
      <h1 className="font-display text-ink mt-4 text-[30px] font-semibold sm:text-[38px]">
        这一页暂时没有加载出来
      </h1>
      <p className="text-ink-soft mt-4 max-w-[38rem] text-[14px] leading-7">
        可能是临时故障。你可以重试，或回到首页继续浏览。
      </p>
      <div className="mt-8 flex flex-wrap gap-3">
        <button
          type="button"
          onClick={() => {
            reset();
            window.location.reload();
          }}
          className="bg-brand text-primary-foreground hover:bg-brand-strong inline-flex min-h-11 items-center justify-center px-5 text-[13px] font-medium transition-colors"
        >
          重试
        </button>
        <Link
          href="/"
          className="border-line-strong text-ink hover:border-brand hover:text-brand inline-flex min-h-11 items-center justify-center border px-5 text-[13px] font-medium transition-colors"
        >
          返回首页
        </Link>
      </div>
    </div>
  );
}
