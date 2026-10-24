import Link from 'next/link';
import { IconArrowUp } from '@/components/icons';

interface SiteFooterProps {
  /** 页脚文字，来自后端 site 设置的 footer_text，为空则不渲染该行 */
  footerText?: string;
  /** 站点名，来自后端 site 设置的 site_title */
  siteTitle?: string;
}

const FOOTER_LINKS = [
  { href: '/', label: '首页' },
  { href: '/wiki', label: 'Wiki' },
  { href: '/categories', label: '分类' },
];

/**
 * 页脚：单行收口。
 *
 * 站点名在顶栏已经出现过一次，页脚不再单独做品牌块重复三遍，
 * 只保留「版权 + 导航 + 回到顶部」一行；footer_text 有内容时才多一行说明。
 */
export function SiteFooter({ footerText, siteTitle }: SiteFooterProps) {
  const year = new Date().getFullYear();
  const title = siteTitle?.trim();
  const text = footerText?.trim();

  return (
    <footer className="mt-8 border-t border-line">
      <div className="page-shell py-7 sm:py-8">
        {text ? (
          <p className="mb-4 max-w-2xl text-[12.5px] leading-relaxed text-ink-soft">{text}</p>
        ) : null}

        <div className="flex flex-col gap-3 text-[11.5px] text-ink-faint sm:flex-row sm:items-center sm:justify-between">
          <p className="tabular-nums">
            © {year}
            {title ? ` ${title}` : null}
          </p>

          <div className="flex flex-wrap items-center gap-x-5">
            <nav
              aria-label="页脚导航"
              className="flex flex-wrap items-center gap-x-5 text-[12.5px] text-ink-soft"
            >
              {FOOTER_LINKS.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className="inline-flex min-h-11 items-center transition-colors hover:text-brand sm:min-h-0"
                >
                  {item.label}
                </Link>
              ))}
            </nav>
            <a
              href="#top"
              className="inline-flex min-h-11 items-center gap-1.5 text-[12.5px] text-ink-soft transition-colors hover:text-brand sm:min-h-0"
            >
              回到顶部
              <IconArrowUp className="h-3 w-3" />
            </a>
          </div>
        </div>
      </div>
    </footer>
  );
}