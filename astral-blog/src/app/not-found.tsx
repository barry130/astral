import Link from 'next/link';
import { IconArrowRight, IconSpark } from '@/components/icons';

/** 404：极简大字标 + 一条出口，不做插画 */
export default function NotFound() {
  return (
    <div className="page-shell flex flex-col items-center py-24 text-center sm:py-32">
      <span className="border-line bg-surface text-ink-faint shadow-card flex h-11 w-11 items-center justify-center rounded-full border">
        <IconSpark className="h-4 w-4" />
      </span>
      <p className="text-ink-faint mt-8 text-[64px] leading-none font-semibold tracking-[-0.04em] tabular-nums sm:text-[80px]">
        404
      </p>
      <h1 className="text-ink mt-5 text-xl font-semibold">页面不存在</h1>
      <p className="text-ink-soft mt-3 text-[13.5px]">未找到对应内容，请检查地址或返回首页。</p>
      <div className="mt-8 flex flex-wrap items-center justify-center gap-2.5">
        <Link
          href="/"
          className="bg-brand text-primary-foreground hover:bg-brand-strong inline-flex h-11 items-center gap-1.5 rounded-lg px-4 text-[13px] font-medium transition-colors sm:h-9"
        >
          返回首页
          <IconArrowRight className="h-3.5 w-3.5" />
        </Link>
        <Link
          href="/wiki"
          className="border-line bg-surface text-ink-soft hover:border-brand-line hover:text-brand inline-flex h-11 items-center rounded-lg border px-4 text-[13px] transition-colors sm:h-9"
        >
          浏览 Wiki
        </Link>
      </div>
    </div>
  );
}
