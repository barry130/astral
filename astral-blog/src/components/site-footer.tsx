import Link from 'next/link';
import { IconArrowUp } from '@/components/icons';

interface SiteFooterProps {
  footerText?: string;
  siteTitle?: string;
}

const FOOTER_LINKS = [
  { href: '/', label: '首页' },
  { href: '/wiki', label: 'Wiki' },
  { href: '/categories', label: '分类' },
];

/** 出版后记式页脚：先收束站点身份，再保留导航与版权信息。 */
export function SiteFooter({ footerText, siteTitle }: SiteFooterProps) {
  const year = new Date().getFullYear();
  const title = siteTitle?.trim();
  const text = footerText?.trim();

  return (
    <footer className="mt-16 border-t border-ink sm:mt-20">
      <div className="page-shell py-9 sm:py-11">
        <div className="grid gap-7 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
          <div>
            {title ? <p className="font-display text-[25px] font-semibold text-ink">{title}</p> : null}
            {text ? <p className="mt-3 max-w-xl text-[13px] leading-7 text-ink-soft">{text}</p> : null}
          </div>
          <nav aria-label="页脚导航" className="flex flex-wrap items-center gap-x-6 text-[13px] text-ink-body">
            {FOOTER_LINKS.map((item) => (
              <Link key={item.href} href={item.href} className="inline-flex min-h-11 items-center hover:text-brand">
                {item.label}
              </Link>
            ))}
          </nav>
        </div>

        <div className="mt-8 flex flex-col gap-2 border-t border-line pt-4 text-[12px] text-ink-faint sm:flex-row sm:items-center sm:justify-between">
          <p className="tabular-nums">© {year}{title ? ` ${title}` : null} · 公开写作与知识归档</p>
          <a href="#top" className="inline-flex min-h-11 items-center gap-1.5 text-ink-soft hover:text-brand sm:min-h-0">
            回到顶部
            <IconArrowUp className="h-3 w-3" />
          </a>
        </div>
      </div>
    </footer>
  );
}
