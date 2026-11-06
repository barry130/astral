import Link from 'next/link';
import type { PostSummary } from '@/lib/cms';
import { categoryHref } from '@/lib/links';
import { resolvePostCategories } from '@/lib/post';

interface PostCategoriesProps {
  post: Pick<PostSummary, 'categoryId' | 'categoryName' | 'categories'>;
  variant?: 'meta' | 'chip';
  className?: string;
}

/** 多分类链接；meta 用于目录，chip 用于文章刊头。 */
export function PostCategories({ post, variant = 'meta', className = '' }: PostCategoriesProps) {
  const categories = resolvePostCategories(post);
  if (categories.length === 0) return null;

  if (variant === 'chip') {
    return (
      <div className={`flex flex-wrap items-center gap-2 ${className}`}>
        {categories.map((category, index) => (
          <Link
            key={`${category.id}-${index}`}
            href={categoryHref(category)}
            className="inline-flex items-center border border-brand-line bg-brand-wash px-2.5 py-1 text-[11.5px] font-medium text-brand hover:border-brand/40 hover:text-brand-strong max-sm:min-h-11 max-sm:px-3.5"
          >
            {category.name}
          </Link>
        ))}
      </div>
    );
  }

  return (
    <div className={`flex flex-wrap items-center gap-x-3 gap-y-1 ${className}`}>
      {categories.map((category, index) => (
        <Link
          key={`${category.id}-${index}`}
          href={categoryHref(category)}
          className="relative z-10 text-[12px] font-medium text-brand hover:text-brand-strong max-sm:inline-flex max-sm:min-h-11 max-sm:items-center"
        >
          {category.name}
        </Link>
      ))}
    </div>
  );
}
