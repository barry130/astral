import Link from 'next/link';
import { IconArrowRight, IconSpark } from '@/components/icons';

/** 404：极简大字标 + 一条出口，不做插画 */
export default function NotFound() {
  return (
    <div className="page-shell flex flex-col items-center py-24 text-center sm:py-32">
      <span className="flex h-11 w-11 items-center justify-center rounded-full border border-line bg-surface text-ink-faint shadow-card">
        <IconSpark className="h-4 w-4" />
      </span>
      <p className="mt-8 text-[64px] font-semibold leading-none tracking-[-0.04em] text-ink/15 tabular-nums sm:text-[80px]">
        404
      </p>
      <p className="mt-5 text-[13.5px] text-ink-soft">页面不存在，或内容已下线。</p>
      <div className="mt-8 flex flex-wrap items-center justify-center gap-2.5">
        <Link
          href="/"
          className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-brand px-4 text-[13px] font-medium text-white transition-colors hover:bg-brand-strong"
        >
          返回首页
          <IconArrowRight className="h-3.5 w-3.5" />
        </Link>
        <Link
          href="/wiki"
          className="inline-flex h-9 items-center rounded-lg border border-line bg-surface px-4 text-[13px] text-ink-soft transition-colors hover:border-brand-line hover:text-brand"
        >
          浏览 Wiki
        </Link>
      </div>
    </div>
  );
}
