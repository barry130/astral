import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { EmptyState } from '@/components/empty-state';
import { IconChevronLeft } from '@/components/icons';
import { PageHeader } from '@/components/page-header';
import { Pagination } from '@/components/pagination';
import { PostList } from '@/components/post-list';
import { getPostPage, getTagList, type TagItem } from '@/lib/cms';
import { tagHref } from '@/lib/links';
import { pageHref, parsePageNumber } from '@/lib/page-input';

export const dynamic = 'force-dynamic';
const PAGE_SIZE = 10;
interface TagPageProps {
  params: Promise<{ slug: string }>;
  searchParams: Promise<{ page?: string | string[] }>;
}
function findTag(tags: TagItem[], key: string): TagItem | null {
  return tags.find((tag) => tag.slug === key || String(tag.id) === key) ?? null;
}

export async function generateMetadata({ params, searchParams }: TagPageProps): Promise<Metadata> {
  const page = parsePageNumber((await searchParams).page);
  const { slug } = await params;
  const tagsRes = await getTagList();
  const matched = tagsRes.ok ? findTag(tagsRes.data, slug) : null;
  const title = `标签：${matched ? matched.name : slug}`;
  const description = matched ? `共 ${matched.postCount} 篇文章` : undefined;
  return {
    title,
    description,
    alternates: { canonical: pageHref(tagHref(matched ?? { id: 0, slug }), page ?? 1) },
    ...(!tagsRes.ok || !matched || page === null
      ? { robots: { index: false, follow: false } }
      : {}),
    openGraph: { type: 'website', title, description },
  };
}

export default async function TagPage({ params, searchParams }: TagPageProps) {
  const { slug } = await params;
  const { page } = await searchParams;
  const pageNum = parsePageNumber(page);
  if (pageNum === null) notFound();
  const [tagsRes, postsRes] = await Promise.all([
    getTagList(),
    getPostPage({ pageNum, pageSize: PAGE_SIZE, tagSlug: slug }),
  ]);
  if (postsRes.ok && pageNum > Math.max(1, postsRes.data.pages)) notFound();
  const matched = tagsRes.ok ? findTag(tagsRes.data, slug) : null;
  // 标签列表能取到却找不到该 slug：无效地址返回 404；标签接口失败时仍允许页面打开。
  if (tagsRes.ok && !matched) notFound();
  const heading = matched ? matched.name : slug;
  const basePath = tagHref(matched ?? { id: 0, slug });

  return (
    <div className="page-shell py-8 sm:py-12">
      <Link
        href="/categories"
        className="text-ink-soft hover:text-brand inline-flex min-h-11 items-center gap-1.5 text-[12px] sm:min-h-0"
      >
        <IconChevronLeft className="h-3.5 w-3.5" />
        全部分类与标签
      </Link>
      <div className="mt-3">
        <PageHeader
          eyebrow="标签档案"
          title={heading}
          description={matched ? `共 ${matched.postCount} 篇文章` : undefined}
        />
      </div>
      <div className="max-w-[920px]">
        {postsRes.ok ? (
          <>
            <PostList
              posts={postsRes.data.records}
              emptyTitle="该标签下暂无文章"
              emptyDescription="发布文章后将在这里展示。"
            />
            <div className="mt-10">
              <Pagination current={pageNum} pages={postsRes.data.pages} basePath={basePath} />
            </div>
          </>
        ) : (
          <EmptyState
            title="文章暂时无法加载"
            description={postsRes.error ?? '内容服务暂时不可用。'}
            retry
          />
        )}
      </div>
    </div>
  );
}
