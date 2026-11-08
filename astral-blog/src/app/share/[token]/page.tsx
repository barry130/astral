import Link from 'next/link';
import type { Metadata } from 'next';
import { EmptyState } from '@/components/empty-state';
import { IconArrowRight, IconCalendar, IconEye, IconPin } from '@/components/icons';
import { PostCategories } from '@/components/post-categories';
import { Toc } from '@/components/toc';
import { getSharedContent } from '@/lib/cms';
import { formatCount, formatDate, formatDateTime } from '@/lib/format';
import { renderMarkdownWithToc } from '@/lib/markdown';

/** 分享页永远回源：token 可能随时被撤销，不能缓存。 */
export const dynamic = 'force-dynamic';
interface PageProps {
  params: Promise<{ token: string }>;
}

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { token } = await params;
  const res = await getSharedContent(token);
  if (!res.ok)
    return {
      title: '分享链接不可用',
      robots: { index: false, follow: false },
      referrer: 'no-referrer',
    };
  const title = res.data.post?.title ?? res.data.page?.title ?? '分享内容';
  return { title, robots: { index: false, follow: false }, referrer: 'no-referrer' };
}

/** 只读分享页沿用详情的刊头、限宽正文和右侧目录，不展示评论或编辑入口。 */
export default async function SharePage({ params }: PageProps) {
  const { token } = await params;
  const res = await getSharedContent(token);
  if (!res.ok) {
    return (
      <div className="page-shell py-16">
        <h1 className="font-display mb-6 text-center text-3xl font-semibold">
          {res.notFound ? '分享链接不可用' : '共享内容暂时无法加载'}
        </h1>
        <EmptyState
          title={res.notFound ? '找不到共享内容' : '请稍后重试'}
          description={
            res.notFound ? '这个链接没有对应的共享内容。' : (res.error ?? '内容服务暂时不可用。')
          }
          retry={!res.notFound}
        />
        <div className="mt-6 text-center">
          <Link
            href="/"
            className="text-brand inline-flex min-h-11 items-center gap-1.5 text-[13px] font-medium hover:underline"
          >
            返回首页
            <IconArrowRight className="size-3.5" />
          </Link>
        </div>
      </div>
    );
  }

  const { post, page, expireAt } = res.data;
  const isPost = post != null;
  const title = post?.title ?? page?.title ?? '';
  const content = post?.content ?? page?.content ?? '';
  const { html, toc } = renderMarkdownWithToc(content, { articleTitle: title });
  const breadcrumb = page?.breadcrumb ?? [];
  const hasToc = toc.length > 0;

  return (
    <div className="page-shell py-7 sm:py-10">
      <div className="border-brand bg-brand-wash/55 mb-5 flex flex-wrap items-center justify-between gap-2 border-l-2 px-4 py-3 text-[12px]">
        <span className="text-brand font-medium">通过分享链接访问 · 只读</span>
        <span className="text-ink-soft">
          {expireAt ? `链接有效期至 ${formatDateTime(expireAt)}` : '长期有效'}
        </span>
      </div>

      <article>
        <header className="border-ink border-y py-8 sm:py-10">
          <div className="flex flex-wrap items-center gap-2.5">
            {isPost && post ? (
              <PostCategories post={post} variant="chip" />
            ) : (
              <p className="eyebrow text-brand">知识库 / Wiki</p>
            )}
            {post?.isTop ? (
              <span className="border-brand-line bg-brand-wash text-brand inline-flex items-center gap-1 border px-2 py-0.5 text-[11px] font-medium">
                <IconPin className="size-3" />
                置顶
              </span>
            ) : null}
          </div>
          {!isPost && breadcrumb.length > 0 ? (
            <nav aria-label="面包屑" className="mt-4">
              <ol className="text-ink-soft flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px]">
                <li>Wiki</li>
                {breadcrumb.map((crumb) => (
                  <li key={crumb.id} className="flex items-center gap-x-2">
                    <span aria-hidden className="text-line-strong">
                      /
                    </span>
                    <span>{crumb.title}</span>
                  </li>
                ))}
              </ol>
            </nav>
          ) : null}
          <h1 className="font-display text-ink mt-5 max-w-[62rem] text-[36px] leading-[1.28] font-semibold tracking-[-0.018em] sm:text-[clamp(42px,4vw,54px)]">
            {title}
          </h1>
          {post?.summary ? (
            <p className="font-display text-ink-soft mt-5 max-w-[48rem] text-[18px] leading-[1.85] sm:text-[20px]">
              {post.summary}
            </p>
          ) : null}
          <div className="text-ink-soft mt-6 flex flex-wrap items-center gap-x-5 gap-y-2 text-[12px]">
            {post?.publishedAt ? (
              <span className="inline-flex items-center gap-1.5">
                <IconCalendar className="size-3.5" />
                {formatDate(post.publishedAt)}
              </span>
            ) : null}
            {typeof post?.views === 'number' ? (
              <span className="inline-flex items-center gap-1.5">
                <IconEye className="size-3.5" />
                {formatCount(post.views)} 次阅读
              </span>
            ) : null}
          </div>
        </header>

        <div
          className={`mt-9 sm:mt-11 ${hasToc ? 'grid gap-12 lg:grid-cols-[minmax(0,46rem)_minmax(180px,220px)] lg:justify-between' : ''}`}
        >
          <div className="markdown min-w-0" dangerouslySetInnerHTML={{ __html: html }} />
          {hasToc ? (
            <aside className="hidden lg:block">
              <div className="border-ink sticky top-24 max-h-[calc(100vh-8rem)] overflow-y-auto border-t pt-4 pr-1">
                <p className="font-display text-ink mb-4 text-[18px] font-semibold">本文目录</p>
                <Toc items={toc} />
                <p className="border-line text-ink-faint mt-5 border-t pt-3 text-[11.5px] leading-relaxed">
                  分享内容仅可阅读。
                </p>
              </div>
            </aside>
          ) : null}
        </div>
      </article>

      <div className="border-line mt-14 border-t pt-6 text-center">
        <p className="text-ink-faint mb-2 text-[12px]">本页通过分享链接访问，仅供阅读。</p>
        <Link
          href="/"
          className="text-brand inline-flex min-h-11 items-center gap-1.5 text-[13px] font-medium hover:underline"
        >
          访问站点首页
          <IconArrowRight className="size-3.5" />
        </Link>
      </div>
    </div>
  );
}
