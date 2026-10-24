import Link from 'next/link';
import type { Metadata } from 'next';

import { EmptyState } from '@/components/empty-state';
import { IconArrowRight, IconCalendar, IconEye, IconPin } from '@/components/icons';
import { PostCategories } from '@/components/post-categories';
import { Toc } from '@/components/toc';
import { getSharedContent } from '@/lib/cms';
import { formatDate } from '@/lib/format';
import { renderMarkdownWithToc } from '@/lib/markdown';


/** 分享页永远回源：token 可能随时被撤销，不能缓存 */
export const dynamic = 'force-dynamic';

interface PageProps {
  params: Promise<{ token: string }>;
}

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { token } = await params;
  const res = await getSharedContent(token);
  if (!res.ok) {
    return { title: '分享链接不可用', robots: { index: false, follow: false } };
  }
  const title = res.data.post?.title ?? res.data.page?.title ?? '分享内容';
  return {
    title,
    // 分享链接是「一次授权」，不该被搜索引擎收录
    robots: { index: false, follow: false },
  };
}

/**
 * 分享链接阅读页：/share/{token}
 *
 * 与正常详情页的区别：
 * - 不展示评论（分享是只读的，避免匿名访客在别人分享的私密内容下留言）；
 * - 不展示编辑入口（访客通常没有登录态；即便有，也应在后台操作）；
 * - 顶部明确标注「通过分享链接访问」，并给出原站入口。
 *
 * 版式与详情页保持一致：宽屏下左侧「目录」列常驻，正文在右。
 * 分享对象多为长文，没有目录只能一路滚，反而不便阅读。
 */
export default async function SharePage({ params }: PageProps) {
  const { token } = await params;
  const res = await getSharedContent(token);

  if (!res.ok) {
    return (
      <div className="page-shell py-16">
        <EmptyState
          title="分享链接不可用"
          description={
            res.error === null
              ? '内容服务暂时不可用，请稍后再试。'
              : '这个分享链接可能已被撤销或已过期。'
          }
        />
        <div className="mt-6 text-center">
          <Link
            href="/"
            className="inline-flex items-center gap-1.5 text-[13px] font-medium text-brand hover:underline"
          >
            返回首页<IconArrowRight className="size-3.5" />
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
  // Wiki 页面沿用详情页的层级路径；分享访客没有站点导航上下文，面包屑只做定位不跳转
  const breadcrumb = page?.breadcrumb ?? [];
  // 与详情页同口径：没有任何 h1~h3 时不占左侧栏，正文改回居中单栏
  const hasToc = toc.length > 0;

  return (
    <div className="page-shell py-8 sm:py-10">
      <div
        className={
          hasToc
            ? 'grid gap-10 lg:grid-cols-[220px_minmax(0,1fr)] lg:gap-12'
            : 'mx-auto w-full max-w-[1100px]'
        }
      >
        {hasToc ? (
          <aside className="hidden lg:block">
            <div className="sticky top-24 max-h-[calc(100vh-8rem)] overflow-y-auto pr-1">
              <p className="mb-3 border-b border-line-soft pb-2 text-[11px] font-medium uppercase tracking-[0.14em] text-ink-faint">
                目录
              </p>
              <Toc items={toc} />
              <p className="mt-5 border-t border-line-soft pt-3 text-[11.5px] leading-relaxed text-ink-faint">
                通过分享链接访问，仅可阅读。
              </p>
            </div>
          </aside>
        ) : null}

        <div className="min-w-0">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-full bg-brand-wash px-2.5 py-1 text-[12px] font-medium text-brand ring-1 ring-brand-line">
              <span aria-hidden className="size-1.5 rounded-full bg-brand" />
              通过分享链接访问 · 只读
            </span>
            <span className="text-[12px] text-ink-faint">
              {expireAt ? `链接有效期至 ${formatDate(expireAt)}` : '长期有效'}
            </span>
          </div>

          <article className="paper">
            <header className="border-b border-line-soft pb-6">
              <div className="flex flex-wrap items-center gap-2.5">
                {isPost && post && <PostCategories post={post} variant="chip" />}
                {post?.isTop && (
                  <span className="inline-flex items-center gap-1 rounded-full bg-brand-wash px-2 py-0.5 text-[11.5px] font-medium text-brand">
                    <IconPin className="size-3" />置顶
                  </span>
                )}
              </div>

              {/* Wiki 页面：面包屑作为纯文本（不跳转，避免分享访客顺着链接浏览站内其他内容） */}
              {!isPost && breadcrumb.length > 0 ? (
                <nav aria-label="面包屑" className="mt-3">
                  <ol className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[12px] text-ink-soft">
                    <li>Wiki</li>
                    {breadcrumb.map((crumb) => (
                      <li key={crumb.id} className="flex items-center gap-x-1.5">
                        <span aria-hidden className="text-line-strong">
                          /
                        </span>
                        <span>{crumb.title}</span>
                      </li>
                    ))}
                  </ol>
                </nav>
              ) : null}

              <h1 className="mt-3 text-[26px] leading-[1.35] font-semibold tracking-tight text-ink sm:text-[clamp(30px,0.6vw+22px,38px)]">
                {title}
              </h1>

              <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[12.5px] text-ink-faint">
                {post?.publishedAt && (
                  <span className="inline-flex items-center gap-1.5">
                    <IconCalendar className="size-3.5" />
                    {formatDate(post.publishedAt)}
                  </span>
                )}
                {typeof post?.views === 'number' && (
                  <span className="inline-flex items-center gap-1.5">
                    <IconEye className="size-3.5" />
                    {post.views} 次阅读
                  </span>
                )}
                {!isPost && <span>Wiki 页面</span>}
              </div>
            </header>

            <div className="markdown mt-9" dangerouslySetInnerHTML={{ __html: html }} />
          </article>

          <div className="mt-6 text-center">
            <p className="mb-3 text-[12px] text-ink-faint">本页通过分享链接访问，仅供阅读。</p>
            <Link
              href="/"
              className="inline-flex items-center gap-1.5 text-[13px] font-medium text-brand hover:underline"
            >
              访问站点首页<IconArrowRight className="size-3.5" />
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
