'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { ChevronDown, Menu } from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { wikiHref } from '@/lib/links';
import type { WikiNode } from '@/lib/cms';

// 导航仿原站（Typecho + Joe）：首页 / 分类 / 页面▾。
// 「页面」下拉对应 CMS 的 Wiki 页面（关于、轻听APP2.0 这些独立页），
// 迁站后侧边栏与顶栏的站点结构能一一对上；下拉里另留一个「全部页面」入口。
// 高亮需要当前路径，故这一小块是客户端组件；其余页面仍全部是服务端组件。
//
// 窄屏（< sm）不摊开横排链接：那会把站点名挤成省略号、每个热区也不足 44px。
// 改为收进一个菜单按钮，点开是同样的导航项（页面项直接平铺，省一层嵌套）。

interface NavItem {
  href: string;
  label: string;
  /** 当前路径命中哪一段导航（文章详情归属首页，标签归属分类） */
  match: (pathname: string) => boolean;
}

const NAV_ITEMS: NavItem[] = [
  {
    href: '/',
    label: '首页',
    match: (pathname) =>
      pathname === '/' || pathname.startsWith('/post/') || pathname.startsWith('/tag/'),
  },
  {
    href: '/categories',
    label: '分类',
    match: (pathname) => pathname === '/categories' || pathname.startsWith('/category/'),
  },
];

interface SiteNavProps {
  /** 顶层 Wiki 页面（后端 /page/tree），用于「页面」下拉 */
  pages?: WikiNode[];
}

export function SiteNav({ pages = [] }: SiteNavProps) {
  const pathname = usePathname();
  const [pagesOpen, setPagesOpen] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const pagesActive = pathname === '/wiki' || pathname.startsWith('/wiki/');

  // 路由切换后收起下拉，避免跳转后菜单还浮在页面上
  useEffect(() => {
    setPagesOpen(false);
    setMobileOpen(false);
  }, [pathname]);

  const linkClass = (active: boolean) =>
    `relative whitespace-nowrap rounded-md px-2.5 py-1.5 text-[13px] transition-colors sm:text-[13.5px] ${
      active ? 'text-ink' : 'text-ink-soft hover:text-ink'
    }`;

  const underline = (active: boolean) => (
    <span
      aria-hidden
      className={`absolute inset-x-2.5 -bottom-0.5 h-[1.5px] rounded-full bg-brand transition-opacity duration-200 ${
        active ? 'opacity-100' : 'opacity-0'
      }`}
    />
  );

  return (
    <>
      {/* 宽屏：横排链接 + 页面下拉 */}
      <nav aria-label="主导航" className="hidden shrink-0 items-center gap-0.5 sm:flex sm:gap-1">
        {NAV_ITEMS.map((item) => {
          const active = item.match(pathname);
          return (
            <Link
              key={item.href}
              href={item.href}
              aria-current={active ? 'page' : undefined}
              className={linkClass(active)}
            >
              {item.label}
              {underline(active)}
            </Link>
          );
        })}

        <DropdownMenu open={pagesOpen} onOpenChange={setPagesOpen}>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              aria-current={pagesActive ? 'page' : undefined}
              className={`inline-flex items-center gap-1 ${linkClass(pagesActive)}`}
            >
              页面
              <ChevronDown className="h-3 w-3 text-ink-faint" />
              {underline(pagesActive)}
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="min-w-[172px]">
            <PageItems pages={pages} />
          </DropdownMenuContent>
        </DropdownMenu>
      </nav>

      {/* 窄屏：收进一个菜单按钮，页面项平铺（不再套一层下拉） */}
      <DropdownMenu open={mobileOpen} onOpenChange={setMobileOpen}>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            aria-label="打开导航菜单"
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-line bg-surface text-ink-soft transition-colors active:bg-surface-muted sm:hidden"
          >
            <Menu className="h-4 w-4" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-48">
          {NAV_ITEMS.map((item) => (
            <DropdownMenuItem key={item.href} asChild>
              <Link
                href={item.href}
                aria-current={item.match(pathname) ? 'page' : undefined}
                className={`flex min-h-11 cursor-pointer items-center text-[13.5px] ${
                  item.match(pathname) ? 'font-medium text-brand' : 'text-ink-body'
                }`}
              >
                {item.label}
              </Link>
            </DropdownMenuItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuLabel className="text-[11px] font-normal uppercase tracking-[0.12em] text-ink-faint">
            页面
          </DropdownMenuLabel>
          <PageItems pages={pages} />
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}

/** 页面项列表（宽屏下拉与窄屏菜单共用），点击区域按移动端热区放大 */
function PageItems({ pages }: { pages: WikiNode[] }) {
  return (
    <>
      {pages.length > 0 ? (
        pages.map((page) => (
          <DropdownMenuItem key={page.id} asChild>
            <Link
              href={wikiHref(page)}
              className="flex min-h-11 cursor-pointer items-center text-[12.5px] text-ink-body focus:text-brand sm:min-h-0"
            >
              {page.title}
            </Link>
          </DropdownMenuItem>
        ))
      ) : (
        <DropdownMenuItem disabled className="text-[12.5px] text-ink-faint">
          暂无页面
        </DropdownMenuItem>
      )}
      <DropdownMenuItem asChild>
        <Link
          href="/wiki"
          className="flex min-h-11 cursor-pointer items-center text-[12.5px] text-ink-soft focus:text-brand sm:min-h-0"
        >
          全部页面
        </Link>
      </DropdownMenuItem>
    </>
  );
}
