import Link from 'next/link';
import type { CategoryNode } from '@/lib/cms';
import { categoryHref } from '@/lib/links';

interface CategoryTreeListProps {
  nodes: CategoryNode[];
  /** 当前分类 id：命中时整行进高亮态（带左侧品牌细条） */
  activeId?: number | null;
}

/** 分类树（侧栏 / 分类页 / 分类详情侧栏共用），子级用发丝竖线缩进 */
export function CategoryTreeList({ nodes, activeId }: CategoryTreeListProps) {
  return <CategoryBranch nodes={nodes} depth={0} activeId={activeId ?? null} />;
}

function CategoryBranch({
  nodes,
  depth,
  activeId,
}: {
  nodes: CategoryNode[];
  depth: number;
  activeId: number | null;
}) {
  return (
    <ul
      className={
        depth === 0 ? 'space-y-0.5' : 'border-line-soft mt-0.5 ml-3 space-y-0.5 border-l pl-2.5'
      }
    >
      {nodes.map((node) => {
        const active = activeId !== null && node.id === activeId;
        return (
          <li key={node.id}>
            <Link
              href={categoryHref(node)}
              aria-current={active ? 'page' : undefined}
              className={`group/row flex min-h-11 items-center justify-between gap-2 px-2.5 py-1.5 text-[13px] transition-colors duration-150 sm:min-h-9 sm:text-[12.5px] ${
                active
                  ? 'bg-brand-wash text-brand font-medium'
                  : 'text-ink-body hover:bg-brand-wash/70 hover:text-brand'
              }`}
            >
              <span className="flex min-w-0 items-center gap-2">
                <span
                  aria-hidden
                  className={`h-3 w-[2px] shrink-0 rounded-full transition-colors duration-150 ${
                    active ? 'bg-brand' : 'group-hover/row:bg-brand/40 bg-transparent'
                  }`}
                />
                <span className="truncate" title={node.name}>
                  {node.name}
                </span>
              </span>
              {typeof node.postCount === 'number' ? (
                <span
                  className={`shrink-0 text-[10.5px] tabular-nums ${
                    active ? 'text-brand/70' : 'text-ink-faint'
                  }`}
                >
                  {node.postCount}
                </span>
              ) : null}
            </Link>
            {node.children && node.children.length > 0 ? (
              <CategoryBranch nodes={node.children} depth={depth + 1} activeId={activeId} />
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
