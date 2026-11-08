import type { Metadata } from 'next';
import Link from 'next/link';
import { ChevronRight as IconChevronRight } from 'lucide-react';
import { EmptyState } from '@/components/empty-state';
import { PageHeader } from '@/components/page-header';
import { WikiTree } from '@/components/wiki-tree';
import { getWikiTree, type WikiNode } from '@/lib/cms';
import { wikiHref } from '@/lib/links';

export const dynamic = 'force-dynamic';
export async function generateMetadata(): Promise<Metadata> {
  const result = await getWikiTree();
  return {
    title: 'Wiki',
    description: 'Wiki 知识库目录',
    alternates: { canonical: '/wiki' },
    ...(!result.ok ? { robots: { index: false, follow: false } } : {}),
  };
}

export default async function WikiIndexPage() {
  const res = await getWikiTree();
  return (
    <div className="page-shell py-8 sm:py-12">
      <PageHeader
        eyebrow="持续修订的知识档案"
        title="Wiki"
        description="从主题入口逐级查阅；页面保留原有层级，适合按路径深入阅读。"
      />
      {res.ok ? (
        res.data.length > 0 ? (
          <div className="border-ink border-t">
            {res.data.map((node) => {
              const children = node.children ?? [];
              return (
                <section
                  key={node.id}
                  aria-label={node.title}
                  className="border-line grid gap-4 border-b py-6 sm:grid-cols-[minmax(180px,0.38fr)_minmax(0,1fr)] sm:gap-10 sm:py-7"
                >
                  <div>
                    <h2 className="font-display text-ink text-[25px] leading-snug font-semibold">
                      <Link href={wikiHref(node)} className="hover:text-brand">
                        {node.title}
                      </Link>
                    </h2>
                    <p className="text-ink-faint mt-2 text-[11px] tabular-nums">
                      {countNodes(node)} 个页面
                    </p>
                  </div>
                  {children.length > 0 ? (
                    <WikiTree nodes={children} />
                  ) : (
                    <Link
                      href={wikiHref(node)}
                      className="group text-ink-soft hover:text-brand inline-flex min-h-11 items-center justify-between gap-3 text-[13px] sm:min-h-0 sm:justify-end"
                    >
                      打开页面
                      <IconChevronRight className="text-ink-faint group-hover:text-brand h-4 w-4" />
                    </Link>
                  )}
                </section>
              );
            })}
          </div>
        ) : (
          <EmptyState title="Wiki 暂无内容" description="后台发布页面后将在这里展示。" />
        )
      ) : (
        <EmptyState
          title="Wiki 目录暂时无法加载"
          description={res.error ?? '内容服务暂时不可用。'}
          retry
        />
      )}
    </div>
  );
}

function countNodes(node: WikiNode): number {
  return 1 + (node.children ?? []).reduce((total, child) => total + countNodes(child), 0);
}
