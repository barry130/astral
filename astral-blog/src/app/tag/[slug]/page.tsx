import type { Metadata } from 'next';
import Link from 'next/link';
import { EmptyState } from '@/components/empty-state';
import { IconChevronLeft } from '@/components/icons';
import { PageHeader } from '@/components/page-header';
import { Pagination } from '@/components/pagination';
import { PostList } from '@/components/post-list';
import { getPostPage, getTagList, type TagItem } from '@/lib/cms';

export const dynamic = 'force-dynamic';
const PAGE_SIZE = 10;
interface TagPageProps { params: Promise<{ slug: string }>; searchParams: Promise<{ page?: string }> }
function findTag(tags: TagItem[], key: string): TagItem | null { return tags.find((tag) => tag.slug === key || String(tag.id) === key) ?? null; }

export async function generateMetadata({ params }: TagPageProps): Promise<Metadata> {
  const { slug } = await params;
  return { title: `标签：${decodeURIComponent(slug)}` };
}

export default async function TagPage({ params, searchParams }: TagPageProps) {
  const { slug } = await params;
  const { page } = await searchParams;
  const pageNum = Math.max(1, Number.parseInt(page ?? '1', 10) || 1);
  const [tagsRes, postsRes] = await Promise.all([getTagList(), getPostPage({ pageNum, pageSize: PAGE_SIZE, tagSlug: slug })]);
  const matched = tagsRes.ok ? findTag(tagsRes.data, slug) : null;
  const heading = matched ? matched.name : decodeURIComponent(slug);
  const basePath = `/tag/${encodeURIComponent(slug)}`;

  return (
    <div className="page-shell py-8 sm:py-12">
      <Link href="/categories" className="inline-flex min-h-11 items-center gap-1.5 text-[12px] text-ink-soft hover:text-brand sm:min-h-0"><IconChevronLeft className="h-3.5 w-3.5" />全部分类与标签</Link>
      <div className="mt-3"><PageHeader eyebrow="标签档案" title={heading} description={matched ? `共 ${matched.postCount} 篇文章` : undefined} /></div>
      <div className="max-w-[920px]">
        {postsRes.ok ? (
          <>
            <PostList posts={postsRes.data.records} emptyTitle="该标签下暂无文章" emptyDescription="发布文章后将在这里展示。" />
            <div className="mt-10"><Pagination current={pageNum} pages={postsRes.data.pages} basePath={basePath} /></div>
          </>
        ) : <EmptyState title="文章暂时无法加载" description="后端服务不可达，请稍后刷新重试。" />}
      </div>
    </div>
  );
}
