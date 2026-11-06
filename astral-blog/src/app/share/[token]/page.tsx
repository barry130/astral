import Link from 'next/link';
import type { Metadata } from 'next';
import { EmptyState } from '@/components/empty-state';
import { IconArrowRight, IconCalendar, IconEye, IconPin } from '@/components/icons';
import { PostCategories } from '@/components/post-categories';
import { Toc } from '@/components/toc';
import { getSharedContent } from '@/lib/cms';
import { formatDate } from '@/lib/format';
import { renderMarkdownWithToc } from '@/lib/markdown';

/** 分享页永远回源：token 可能随时被撤销，不能缓存。 */
export const dynamic = 'force-dynamic';
interface PageProps { params: Promise<{ token: string }> }

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { token } = await params;
  const res = await getSharedContent(token);
  if (!res.ok) return { title: '分享链接不可用', robots: { index: false, follow: false } };
  const title = res.data.post?.title ?? res.data.page?.title ?? '分享内容';
  return { title, robots: { index: false, follow: false } };
}

/** 只读分享页沿用详情的刊头、限宽正文和右侧目录，不展示评论或编辑入口。 */
export default async function SharePage({ params }: PageProps) {
  const { token } = await params;
  const res = await getSharedContent(token);
  if (!res.ok) {
    return (
      <div className="page-shell py-16">
        <EmptyState title="分享链接不可用" description={res.error === null ? '内容服务暂时不可用，请稍后再试。' : '这个分享链接可能已被撤销或已过期。'} />
        <div className="mt-6 text-center"><Link href="/" className="inline-flex min-h-11 items-center gap-1.5 text-[13px] font-medium text-brand hover:underline">返回首页<IconArrowRight className="size-3.5" /></Link></div>
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
      <div className="mb-5 flex flex-wrap items-center justify-between gap-2 border-l-2 border-brand bg-brand-wash/55 px-4 py-3 text-[12px]">
        <span className="font-medium text-brand">通过分享链接访问 · 只读</span>
        <span className="text-ink-soft">{expireAt ? `链接有效期至 ${formatDate(expireAt)}` : '长期有效'}</span>
      </div>

      <article>
        <header className="border-y border-ink py-8 sm:py-10">
          <div className="flex flex-wrap items-center gap-2.5">
            {isPost && post ? <PostCategories post={post} variant="chip" /> : <p className="eyebrow text-brand">知识库 / Wiki</p>}
            {post?.isTop ? <span className="inline-flex items-center gap-1 border border-brand-line bg-brand-wash px-2 py-0.5 text-[11px] font-medium text-brand"><IconPin className="size-3" />置顶</span> : null}
          </div>
          {!isPost && breadcrumb.length > 0 ? (
            <nav aria-label="面包屑" className="mt-4"><ol className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px] text-ink-soft"><li>Wiki</li>{breadcrumb.map((crumb) => <li key={crumb.id} className="flex items-center gap-x-2"><span aria-hidden className="text-line-strong">/</span><span>{crumb.title}</span></li>)}</ol></nav>
          ) : null}
          <h1 className="mt-5 max-w-[62rem] font-display text-[36px] font-semibold leading-[1.28] tracking-[-0.018em] text-ink sm:text-[clamp(42px,4vw,54px)]">{title}</h1>
          {post?.summary ? <p className="mt-5 max-w-[48rem] font-display text-[18px] leading-[1.85] text-ink-soft sm:text-[20px]">{post.summary}</p> : null}
          <div className="mt-6 flex flex-wrap items-center gap-x-5 gap-y-2 text-[12px] text-ink-soft">
            {post?.publishedAt ? <span className="inline-flex items-center gap-1.5"><IconCalendar className="size-3.5" />{formatDate(post.publishedAt)}</span> : null}
            {typeof post?.views === 'number' ? <span className="inline-flex items-center gap-1.5"><IconEye className="size-3.5" />{post.views} 次阅读</span> : null}
          </div>
        </header>

        <div className={`mt-9 sm:mt-11 ${hasToc ? 'grid gap-12 lg:grid-cols-[minmax(0,46rem)_minmax(180px,220px)] lg:justify-between' : ''}`}>
          <div className="markdown min-w-0" dangerouslySetInnerHTML={{ __html: html }} />
          {hasToc ? (
            <aside className="hidden lg:block"><div className="sticky top-24 max-h-[calc(100vh-8rem)] overflow-y-auto border-t border-ink pt-4 pr-1"><p className="mb-4 font-display text-[18px] font-semibold text-ink">本文目录</p><Toc items={toc} /><p className="mt-5 border-t border-line pt-3 text-[11.5px] leading-relaxed text-ink-faint">分享内容仅可阅读。</p></div></aside>
          ) : null}
        </div>
      </article>

      <div className="mt-14 border-t border-line pt-6 text-center">
        <p className="mb-2 text-[12px] text-ink-faint">本页通过分享链接访问，仅供阅读。</p>
        <Link href="/" className="inline-flex min-h-11 items-center gap-1.5 text-[13px] font-medium text-brand hover:underline">访问站点首页<IconArrowRight className="size-3.5" /></Link>
      </div>
    </div>
  );
}
