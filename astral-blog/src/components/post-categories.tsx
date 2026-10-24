import Link from 'next/link';
import type { PostSummary } from '@/lib/cms';
import { categoryHref } from '@/lib/links';
import { resolvePostCategories } from '@/lib/post';

interface PostCategoriesProps {
  post: Pick<PostSummary, 'categoryId' | 'categoryName' | 'categories'>;
  /** meta：列表卡片元信息行里的纯文字链接；chip：详情页标题上方的小胶囊 */
  variant?: 'meta' | 'chip';
  className?: string;
}

/**
 * 文章分类（支持多分类）。
 * 数据统一由 resolvePostCategories 归一：优先 categories 全量，缺失时回退主分类字段。
 * 列表卡片的标题链接用 stretched-link 覆盖整张卡，故此处需要抬高层级（z-10）保证可点。
 */
export function PostCategories({ post, variant = 'meta', className = '' }: PostCategoriesProps) {
  const categories = resolvePostCategories(post);
  if (categories.length === 0) return null;

  if (variant === 'chip') {
    return (
      <div className={`flex flex-wrap items-center gap-1.5 ${className}`}>
        {categories.map((category, index) => (
          <Link
            key={`${category.id}-${index}`}
            href={categoryHref(category)}
            className="inline-flex items-center rounded-full border border-brand-line/70 bg-brand-wash px-2.5 py-0.5 text-[11.5px] font-medium text-brand transition-colors hover:border-brand/35 hover:bg-brand/10 hover:text-brand-strong"
          >
            {category.name}
          </Link>
        ))}
      </div>
    );
  }

  return (
    <div className={`flex flex-wrap items-center gap-x-2.5 gap-y-1 ${className}`}>
      {categories.map((category, index) => (
        <Link
          key={`${category.id}-${index}`}
          href={categoryHref(category)}
            className="relative z-10 text-[11.5px] font-medium text-brand transition-colors hover:text-brand-strong max-sm:inline-flex max-sm:min-h-8 max-sm:items-center max-sm:px-1 max-sm:text-[12.5px]"
        >
          {category.name}
        </Link>
      ))}
    </div>
  );
}
