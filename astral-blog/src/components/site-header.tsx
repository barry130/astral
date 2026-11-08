import Link from 'next/link';
import { getWikiTree } from '@/lib/cms';
import { SessionMenu } from '@/components/session-menu';
import { SiteNav } from '@/components/site-nav';

/** 站点刊头：字标、栏目导航与账号入口，Wiki 数据失败不影响正文。 */
export async function SiteHeader({ siteTitle }: { siteTitle: string }) {
  const pagesRes = await getWikiTree();
  const pages = pagesRes.ok ? pagesRes.data : [];
  const initial = Array.from(siteTitle.trim())[0] ?? 'A';

  return (
    <header className="safe-top border-line-strong/80 bg-canvas/95 sticky top-0 z-40 border-b backdrop-blur-md">
      <div className="page-shell flex h-[4.25rem] items-center justify-between gap-3 sm:gap-6">
        <Link href="/" className="group flex min-h-11 min-w-0 items-center gap-3">
          <span
            aria-hidden
            className="border-ink bg-ink font-display text-canvas group-hover:bg-brand group-hover:border-brand flex size-8 shrink-0 items-center justify-center border text-[19px] leading-none transition-colors"
          >
            {initial}
          </span>
          <span className="font-display text-ink truncate text-[19px] font-semibold tracking-[0.01em]">
            {siteTitle}
          </span>
        </Link>
        <div className="flex shrink-0 items-center gap-1 sm:gap-3">
          <SiteNav pages={pages} failed={!pagesRes.ok} />
          <span aria-hidden className="bg-line-strong hidden h-5 w-px sm:block" />
          <SessionMenu />
        </div>
      </div>
    </header>
  );
}
