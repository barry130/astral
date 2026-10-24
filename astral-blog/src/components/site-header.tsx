import Link from 'next/link';
import { getWikiTree } from '@/lib/cms';
import { IconSpark } from '@/components/icons';
import { SessionMenu } from '@/components/session-menu';
import { SiteNav } from '@/components/site-nav';

/**
 * 顶栏：粘顶 + 半透明毛玻璃 + 发丝底边。
 * 站点名前的四角星标记是唯一的装饰元素，其余留白交给导航本身。
 *
 * 「页面」下拉的数据在这里拉（服务端）：导航是每个页面都要渲染的，
 * 失败时降级为空数组，下拉里只留「全部页面」，不影响正文。
 */
export async function SiteHeader({ siteTitle }: { siteTitle: string }) {
  const pagesRes = await getWikiTree();
  const pages = pagesRes.ok ? pagesRes.data : [];

  return (
    <header className="safe-top sticky top-0 z-40 border-b border-line/80 bg-canvas/85 backdrop-blur-xl">
      <div className="page-shell flex h-16 items-center justify-between gap-3 sm:gap-6">
        <Link href="/" className="group flex min-w-0 items-center gap-2.5">
          <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-[9px] border border-brand-line/70 bg-brand-wash transition-colors duration-200 group-hover:border-brand/30 group-hover:bg-brand/10">
            <IconSpark className="h-3.5 w-3.5 text-brand" />
          </span>
          <span className="truncate text-[14px] font-semibold tracking-[-0.01em] text-ink sm:text-[15px]">
            {siteTitle}
          </span>
        </Link>
        <div className="flex shrink-0 items-center gap-1 sm:gap-2">
          <SiteNav pages={pages} />
          <span aria-hidden className="ml-1 hidden h-4 w-px bg-line sm:block" />
          <SessionMenu />
        </div>
      </div>
    </header>
  );
}
