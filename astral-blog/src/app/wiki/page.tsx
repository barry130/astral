import type { Metadata } from 'next';
import Link from 'next/link';
import { ChevronRight as IconChevronRight } from 'lucide-react';
import { EmptyState } from '@/components/empty-state';
import { PageHeader } from '@/components/page-header';
import { WikiTree } from '@/components/wiki-tree';
import { getWikiTree } from '@/lib/cms';
import { wikiHref } from '@/lib/links';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = {
  title: 'Wiki',
  description: 'Wiki 知识库目录',
};

export default async function WikiIndexPage() {
  const res = await getWikiTree();

  return (
    <div className="page-shell py-10 sm:py-14">
      <PageHeader title="Wiki" description="按层级整理的知识库目录。" />
      {res.ok ? (
        res.data.length > 0 ? (
          // 每个根页面一张卡片，宽屏按多列铺开，避免内容挤在中间一长条
          <div className="grid gap-4 sm:grid-cols-2 2xl:grid-cols-3">
            {res.data.map((node) => {
              const children = node.children ?? [];
              // 没有子页面的根页面本身就是入口：整张卡片做成链接，不再留一行「暂无子页面」的空档
              if (children.length === 0) {
                return (
                  <Link
                    key={node.id}
                    href={wikiHref(node)}
                    className="paper group flex items-center justify-between gap-3 px-4 py-3.5 transition-colors hover:border-line-strong"
                  >
                    <span className="text-[13.5px] font-semibold text-ink transition-colors group-hover:text-brand">
                      {node.title}
                    </span>
                    <IconChevronRight className="h-4 w-4 shrink-0 text-ink-faint transition-colors group-hover:text-brand" />
                  </Link>
                );
              }
              return (
                <section key={node.id} aria-label={node.title} className="paper p-3">
                  {/* 卡片标题即根页面入口，子页面在下方缩进列出 */}
                  <h2 className="mb-1.5 border-b border-line-soft pb-2">
                    <Link
                      href={wikiHref(node)}
                      className="text-[13.5px] font-semibold text-ink transition-colors hover:text-brand"
                    >
                      {node.title}
                    </Link>
                  </h2>
                  <WikiTree nodes={children} />
                </section>
              );
            })}
          </div>
        ) : (
          <EmptyState title="Wiki 暂无内容" description="后台发布页面后将在这里展示。" />
        )
      ) : (
        <EmptyState title="Wiki 目录暂时无法加载" description="后端服务不可达，请稍后刷新重试。" />
      )}
    </div>
  );
}
