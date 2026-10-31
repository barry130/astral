'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { ExternalLink } from 'lucide-react';

import { cmsApi } from '@/api/cms';
import { cn } from '@/lib/utils';

/** CMS 二级导航（侧边栏仅有 /dashboard/cms 单一入口，子页在此切换） */
const NAV_ITEMS = [
  { href: '/dashboard/cms/post', label: '文章' },
  { href: '/dashboard/cms/category', label: '分类' },
  { href: '/dashboard/cms/tag', label: '标签' },
  { href: '/dashboard/cms/page', label: 'Wiki 页面' },
  { href: '/dashboard/cms/comment', label: '评论' },
  { href: '/dashboard/cms/share', label: '分享' },
  { href: '/dashboard/cms/setting', label: '站点设置' },
] as const;

/**
 * 博客前台地址兜底：站点设置 blog_url 优先（部署后可改），
 * 其次构建期环境变量 NEXT_PUBLIC_BLOG_URL，最后本地开发默认 3001。
 */
const BLOG_URL_FALLBACK = process.env.NEXT_PUBLIC_BLOG_URL || 'http://localhost:3001';

export default function CmsLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const [blogUrl, setBlogUrl] = useState(BLOG_URL_FALLBACK);

  useEffect(() => {
    let alive = true;
    cmsApi
      .settingList()
      .then((res) => {
        const url = (res.data?.blog_url || '').trim();
        if (alive && url) setBlogUrl(url);
      })
      .catch(() => {
        // 设置加载失败时保留兜底地址，不影响导航
      });
    return () => {
      alive = false;
    };
  }, []);

  return (
    <div className="space-y-4">
      <nav className="flex flex-wrap items-center gap-1.5">
        {NAV_ITEMS.map((item) => {
          const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                'rounded-full px-3.5 py-1.5 text-[13px] transition-colors',
                active
                  ? 'font-semibold text-[var(--color-brand-text)] shadow-[0_1px_2px_rgba(16,32,26,0.1)] ring-1 ring-[color:var(--color-border-light)] bg-card'
                  : 'text-[color:var(--color-text-tertiary)] hover:bg-card/80 hover:text-[color:var(--color-text)]',
              )}
            >
              {item.label}
            </Link>
          );
        })}
        <a
          href={blogUrl}
          target="_blank"
          rel="noreferrer"
          className="ml-auto flex items-center gap-1 rounded-full px-3 py-1.5 text-[13px] text-[color:var(--color-text-tertiary)] transition-colors hover:bg-card hover:text-[color:var(--color-brand-text)]"
          title={blogUrl}
        >
          <ExternalLink className="size-3.5" />
          查看博客
        </a>
      </nav>
      {children}
    </div>
  );
}
