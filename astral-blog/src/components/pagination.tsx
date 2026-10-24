import Link from 'next/link';
import { IconChevronLeft, IconChevronRight } from '@/components/icons';

interface PaginationProps {
  /** 当前页码（从 1 开始） */
  current: number;
  /** 总页数 */
  pages: number;
  /** 不带查询串的基础路径，如 "/" 或 "/category/tech" */
  basePath: string;
  /** 需要随分页保留的其余查询参数（可选） */
  keepParams?: Record<string, string | undefined>;
}

/** 链接式分页（服务端组件，无客户端状态） */
export function Pagination({ current, pages, basePath, keepParams }: PaginationProps) {
  if (pages <= 1) return null;

  const hrefFor = (page: number) => {
    const sp = new URLSearchParams();
    for (const [key, value] of Object.entries(keepParams ?? {})) {
      if (value) sp.set(key, value);
    }
    if (page > 1) sp.set('page', String(page));
    const qs = sp.toString();
    return qs ? `${basePath}?${qs}` : basePath;
  };

  const windowSize = 5;
  let start = Math.max(1, current - Math.floor(windowSize / 2));
  const end = Math.min(pages, start + windowSize - 1);
  start = Math.max(1, end - windowSize + 1);
  const pageItems: number[] = [];
  for (let p = start; p <= end; p += 1) pageItems.push(p);

  // 移动端把热区撑到 44px（h-11），桌面端保持紧凑的 36px
  const base =
    'inline-flex h-11 min-w-11 items-center justify-center rounded-lg border text-[13px] transition-colors duration-150 sm:h-9 sm:min-w-9 sm:text-[12.5px]';

  const idleClass = `${base} border-line bg-surface text-ink-soft hover:border-brand-line hover:text-brand`;
  const navClass = `${base} gap-1 border-line bg-surface px-3 text-ink-soft hover:border-brand-line hover:text-brand`;

  return (
    <nav aria-label="分页" className="flex flex-wrap items-center justify-center gap-1.5">
      {current > 1 ? (
        <Link href={hrefFor(current - 1)} className={navClass} rel="prev" aria-label="上一页">
          <IconChevronLeft className="h-3.5 w-3.5" />
          上一页
        </Link>
      ) : null}
      {start > 1 ? (
        <>
          <Link href={hrefFor(1)} className={idleClass}>
            1
          </Link>
          {start > 2 ? <span className="px-1 text-[12px] text-ink-faint">…</span> : null}
        </>
      ) : null}
      {pageItems.map((page) =>
        page === current ? (
          <span
            key={page}
            aria-current="page"
            className={`${base} border-brand bg-brand font-medium text-white shadow-[0_6px_14px_-10px_rgb(3_105_161_/_0.9)]`}
          >
            {page}
          </span>
        ) : (
          <Link key={page} href={hrefFor(page)} className={idleClass}>
            {page}
          </Link>
        ),
      )}
      {end < pages ? (
        <>
          {end < pages - 1 ? <span className="px-1 text-[12px] text-ink-faint">…</span> : null}
          <Link href={hrefFor(pages)} className={idleClass}>
            {pages}
          </Link>
        </>
      ) : null}
      {current < pages ? (
        <Link href={hrefFor(current + 1)} className={navClass} rel="next" aria-label="下一页">
          下一页
          <IconChevronRight className="h-3.5 w-3.5" />
        </Link>
      ) : null}
    </nav>
  );
}
