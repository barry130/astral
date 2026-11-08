import Link from 'next/link';
import { tagHref } from '@/lib/links';

interface TagChipProps {
  tag: { id: number; name: string; slug: string | null };
  count?: number;
  size?: 'sm' | 'md';
}

/** 标签采用轻量刊签；窄屏两种尺寸都保留 44px 触控高度。 */
export function TagChip({ tag, count, size = 'sm' }: TagChipProps) {
  const sizeClass =
    size === 'md'
      ? 'px-3 py-1 text-[12px] max-sm:min-h-11 max-sm:items-center max-sm:px-3.5 max-sm:text-[13px]'
      : 'px-2.5 py-[3px] text-[11px] max-sm:min-h-11 max-sm:items-center max-sm:px-3 max-sm:text-[12.5px]';

  return (
    <Link
      href={tagHref(tag)}
      className={`border-line text-ink-soft hover:border-brand-line hover:bg-brand-wash hover:text-brand inline-flex items-center gap-1.5 border bg-transparent transition-colors ${sizeClass}`}
    >
      <span>
        <span aria-hidden className="text-ink-faint mr-0.5">
          #
        </span>
        {tag.name}
      </span>
      {typeof count === 'number' ? (
        <span className="text-ink-faint text-[10.5px] tabular-nums">{count}</span>
      ) : null}
    </Link>
  );
}
