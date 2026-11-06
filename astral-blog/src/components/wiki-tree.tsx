import Link from 'next/link';
import type { WikiNode } from '@/lib/cms';
import { wikiHref } from '@/lib/links';

/** Wiki 目录树：层级用缩进和竖线表达，不使用卡片或胶囊。 */
export function WikiTree({ nodes }: { nodes: WikiNode[] }) {
  return <WikiBranch nodes={nodes} depth={0} />;
}

function WikiBranch({ nodes, depth }: { nodes: WikiNode[]; depth: number }) {
  return (
    <ul className={depth === 0 ? 'space-y-0.5' : 'ml-3 mt-0.5 space-y-0.5 border-l border-line pl-3'}>
      {nodes.map((node) => (
        <li key={node.id}>
          <Link href={wikiHref(node)} className="group flex min-h-11 items-center gap-2.5 px-2 py-2 text-[13.5px] text-ink-body transition-colors hover:bg-surface-muted/60 hover:text-brand sm:min-h-9 sm:text-[13px]">
            <span aria-hidden className="h-px w-2.5 shrink-0 bg-line-strong group-hover:bg-brand" />
            <span className="truncate" title={node.title}>{node.title}</span>
          </Link>
          {node.children && node.children.length > 0 ? <WikiBranch nodes={node.children} depth={depth + 1} /> : null}
        </li>
      ))}
    </ul>
  );
}
