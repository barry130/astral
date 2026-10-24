import Link from 'next/link';
import { tagHref } from '@/lib/links';

interface TagChipProps {
  tag: { id: number; name: string; slug: string | null };
  /** 可选的文章数，展示在标签名后 */
  count?: number;
  size?: 'sm' | 'md';
}

/** 标签胶囊：列表卡片、详情页文末、分类页、侧栏共用，只有两种尺寸 */
export function TagChip({ tag, count, size = 'sm' }: TagChipProps) {
  // 移动端热区：胶囊本身高度小，用小屏额外上下内边距把它撑到 ~36px，
  // 触控目标过小时手指很难点准（桌面端保持紧凑）。
  const sizeClass =
    size === 'md'
      ? 'px-3 py-1 text-[12px] max-sm:min-h-9 max-sm:items-center max-sm:px-3.5 max-sm:text-[13px]'
      : 'px-2.5 py-[3px] text-[11px] max-sm:min-h-8 max-sm:items-center max-sm:px-3 max-sm:text-[12.5px]';


  return (
    <Link
      href={tagHref(tag)}
      className={`inline-flex items-center gap-1.5 rounded-full border border-line bg-canvas/60 text-ink-soft transition-colors hover:border-brand-line hover:bg-brand-wash hover:text-brand ${sizeClass}`}
    >
      {tag.name}
      {typeof count === 'number' ? (
        <span className="text-[10.5px] tabular-nums text-ink-faint">{count}</span>
      ) : null}
    </Link>
  );
}
