// 文章分类的展示归一化。
// 后端正在支持「一篇文章属于多个分类」：文章对象新增 categories（第一项为主分类），
// 同时保留 categoryId / categoryName 以兼容老数据。列表与详情统一走这里，
// 保证「categories 缺失 / 为空 → 回退单分类」的规则只实现一次。

import type { PostCategory, PostSummary } from '@/lib/cms';

type PostCategorySource = Pick<PostSummary, 'categoryId' | 'categoryName' | 'categories'>;

/** 取出文章要展示的全部分类；无分类时返回空数组（调用方自行决定不渲染） */
export function resolvePostCategories(post: PostCategorySource): PostCategory[] {
  const list = post.categories;
  if (Array.isArray(list) && list.length > 0) {
    // 契约字段来自后端，做一次形状过滤，避免脏数据渲染出空链接
    const valid = list.filter(
      (item): item is PostCategory =>
        item != null &&
        typeof item.id === 'number' &&
        Number.isFinite(item.id) &&
        typeof item.name === 'string' &&
        item.name.trim().length > 0,
    );
    if (valid.length > 0) {
      return valid.map((item) => ({
        id: item.id,
        name: item.name.trim(),
        slug: typeof item.slug === 'string' && item.slug.length > 0 ? item.slug : null,
      }));
    }
  }

  // 回退：旧数据只有主分类字段
  if (typeof post.categoryId === 'number' && post.categoryId > 0 && post.categoryName?.trim()) {
    return [{ id: post.categoryId, name: post.categoryName.trim(), slug: null }];
  }

  return [];
}
