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
    <footer className="border-ink mt-16 border-t sm:mt-20">
      <div className="page-shell py-9 sm:py-11">
        <div className="grid gap-7 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
          <div>
            {title ? (
              <p className="font-display text-ink text-[25px] font-semibold">{title}</p>
            ) : null}
            {text ? (
              <p className="text-ink-soft mt-3 max-w-xl text-[13px] leading-7">{text}</p>
            ) : null}
          </div>
          <nav
            aria-label="页脚导航"
            className="text-ink-body flex flex-wrap items-center gap-x-6 text-[13px]"
          >
            {FOOTER_LINKS.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                className="hover:text-brand inline-flex min-h-11 items-center"
              >
                {item.label}
              </Link>
            ))}
          </nav>
        </div>

        <div className="border-line text-ink-faint mt-8 flex flex-col gap-2 border-t pt-4 text-[12px] sm:flex-row sm:items-center sm:justify-between">
          <p className="tabular-nums">
            © {year}
            {title ? ` ${title}` : null} · 公开写作与知识归档
          </p>
          <a
            href="#top"
            className="text-ink-soft hover:text-brand inline-flex min-h-11 items-center gap-1.5 sm:min-h-0"
          >
            回到顶部
            <IconArrowUp className="h-3 w-3" />
          </a>
        </div>
      </div>
    </footer>
  );
}
