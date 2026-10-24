import Link from 'next/link';
import type { WikiNode } from '@/lib/cms';
import { wikiHref } from '@/lib/links';

/** Wiki 目录树（/wiki 页与 Wiki 空态外的树状渲染共用），子级用发丝竖线缩进 */
export function WikiTree({ nodes }: { nodes: WikiNode[] }) {
  return <WikiBranch nodes={nodes} depth={0} />;
}

function WikiBranch({ nodes, depth }: { nodes: WikiNode[]; depth: number }) {
  return (
    <ul
      className={
        depth === 0
          ? 'space-y-0.5'
          : 'ml-3.5 mt-0.5 space-y-0.5 border-l border-line-soft pl-2.5'
      }
    >
      {nodes.map((node) => (
        <li key={node.id}>
          <Link
            href={wikiHref(node)}
            className="group flex min-h-11 items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13.5px] text-ink-body transition-colors duration-150 hover:bg-brand-wash/70 hover:text-brand sm:min-h-0 sm:text-[13px]"
          >
            <span
              aria-hidden
              className="h-1 w-1 shrink-0 rounded-full bg-line-strong transition-colors duration-150 group-hover:bg-brand/60"
            />
            <span className="truncate" title={node.title}>
              {node.title}
            </span>
          </Link>
          {node.children && node.children.length > 0 ? (
            <WikiBranch nodes={node.children} depth={depth + 1} />
          ) : null}
        </li>
      ))}
    </ul>
  );
}
