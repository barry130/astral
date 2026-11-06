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
    <header className="safe-top sticky top-0 z-40 border-b border-line-strong/80 bg-canvas/95 backdrop-blur-md">
      <div className="page-shell flex h-[4.25rem] items-center justify-between gap-3 sm:gap-6">
        <Link href="/" className="group flex min-h-11 min-w-0 items-center gap-3">
          <span
            aria-hidden
            className="flex size-8 shrink-0 items-center justify-center border border-ink bg-ink font-display text-[19px] leading-none text-canvas transition-colors group-hover:bg-brand group-hover:border-brand"
          >
            {initial}
          </span>
          <span className="truncate font-display text-[19px] font-semibold tracking-[0.01em] text-ink">
            {siteTitle}
          </span>
        </Link>
        <div className="flex shrink-0 items-center gap-1 sm:gap-3">
          <SiteNav pages={pages} />
          <span aria-hidden className="hidden h-5 w-px bg-line-strong sm:block" />
          <SessionMenu />
        </div>
      </div>
    </header>
  );
}
