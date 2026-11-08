import { isRecord } from '@/lib/result';
import { safeImageUrl } from '@/lib/url';
import type {
  CategoryNode,
  CommentItem,
  Crumb,
  PageData,
  PostCategory,
  PostDetail,
  PostSummary,
  PostTag,
  ShareContent,
  TagItem,
  WikiNode,
  WikiPageDetail,
} from '@/lib/cms';

class InvalidShape extends Error {}
const fail = (): never => {
  throw new InvalidShape();
};
const text = (raw: unknown, max = 1_000_000): string =>
  typeof raw === 'string' && raw.length <= max ? raw : fail();
const optionalText = (raw: unknown, max = 4096): string | null =>
  raw == null ? null : text(raw, max);
const integer = (raw: unknown, minimum = 0): number =>
  typeof raw === 'number' && Number.isSafeInteger(raw) && raw >= minimum ? raw : fail();
const optionalId = (raw: unknown): number | null => (raw == null ? null : integer(raw, 1));
const record = (raw: unknown): Record<string, unknown> => (isRecord(raw) ? raw : fail());
const array = (raw: unknown, max = 5000): unknown[] =>
  Array.isArray(raw) && raw.length <= max ? raw : fail();
const attempt = <T>(parser: () => T): T | null => {
  try {
    return parser();
  } catch {
    return null;
  }
};

function named(raw: unknown): PostCategory & PostTag {
  const value = record(raw);
  return {
    id: integer(value.id, 1),
    name: text(value.name, 256),
    slug: optionalText(value.slug, 128),
  };
}

function post(raw: unknown): PostSummary {
  const value = record(raw);
  const title = text(value.title, 256);
  if (!title.trim()) fail();
  if (
    value.isTop != null &&
    typeof value.isTop !== 'boolean' &&
    value.isTop !== 0 &&
    value.isTop !== 1
  )
    fail();
  const status = text(value.status, 64);
  if (!status) fail();
  return {
    id: integer(value.id, 1),
    title,
    status,
    slug: optionalText(value.slug, 128),
    summary: optionalText(value.summary, 512),
    cover: safeImageUrl(optionalText(value.cover, 512)),
    categories: value.categories == null ? null : array(value.categories).map(named),
    categoryId: optionalId(value.categoryId),
    categoryName: optionalText(value.categoryName, 256),
    tags: value.tags == null ? null : array(value.tags).map(named),
    isTop: value.isTop === true || value.isTop === 1,
    views: value.views == null ? 0 : integer(value.views),
    authorId: optionalId(value.authorId),
    publishedAt: optionalText(value.publishedAt, 64),
    createTime: optionalText(value.createTime, 64),
    updateTime: optionalText(value.updateTime, 64),
  };
}

export const parsePostSummary = (raw: unknown): PostSummary | null => attempt(() => post(raw));
export const parsePostDetail = (raw: unknown): PostDetail | null =>
  attempt(() => ({ ...post(raw), content: text(record(raw).content) }));

export function parsePageData<T>(
  raw: unknown,
  parseItem: (item: unknown) => T | null,
): PageData<T> | null {
  return attempt(() => {
    const value = record(raw);
    const records = array(value.records, 1000).map((item) => parseItem(item) ?? fail());
    const total = integer(value.total);
    const size = integer(value.size, 1);
    const current = integer(value.current, 1);
    const pages = integer(value.pages);
    if (
      pages !== Math.ceil(total / size) ||
      records.length > size ||
      records.length > total ||
      (current > pages && records.length > 0)
    )
      fail();
    return { records, total, size, current, pages };
  });
}

export function parseCategoryTree(raw: unknown): CategoryNode[] | null {
  return attempt(() => {
    let count = 0;
    const seen = new Set<number>();
    const branch = (items: unknown, depth: number): CategoryNode[] =>
      array(items).map((item) => {
        if (++count > 5000 || depth > 32) fail();
        const value = record(item);
        const id = integer(value.id, 1);
        if (seen.has(id)) fail();
        seen.add(id);
        return {
          id,
          name: text(value.name, 256),
          slug: optionalText(value.slug, 128),
          parentId: optionalId(value.parentId),
          postCount: value.postCount == null ? 0 : integer(value.postCount),
          description: optionalText(value.description),
          sort:
            typeof value.sort === 'number' && Number.isSafeInteger(value.sort) ? value.sort : null,
          children: value.children == null ? null : branch(value.children, depth + 1),
        };
      });
    return branch(raw, 0);
  });
}

export function parseTagList(raw: unknown): TagItem[] | null {
  return attempt(() =>
    array(raw).map((item) => {
      const value = record(item);
      return { ...named(value), postCount: value.postCount == null ? 0 : integer(value.postCount) };
    }),
  );
}

function crumb(raw: unknown): Crumb {
  const value = record(raw);
  return {
    id: integer(value.id, 1),
    title: text(value.title, 256),
    slug: optionalText(value.slug, 128),
  };
}

export function parseWikiTree(raw: unknown): WikiNode[] | null {
  return attempt(() => {
    let count = 0;
    const seen = new Set<number>();
    const branch = (items: unknown, depth: number): WikiNode[] =>
      array(items).map((item) => {
        if (++count > 5000 || depth > 32) fail();
        const value = record(item);
        const node = crumb(value);
        if (seen.has(node.id)) fail();
        seen.add(node.id);
        return {
          ...node,
          status: typeof value.status === 'string' ? value.status : undefined,
          children: value.children == null ? null : branch(value.children, depth + 1),
        };
      });
    return branch(raw, 0);
  });
}

export function parseWikiPage(raw: unknown): WikiPageDetail | null {
  return attempt(() => {
    const value = record(raw);
    return {
      ...crumb(value),
      content: text(value.content),
      status: value.status == null ? undefined : text(value.status, 64),
      views: value.views == null ? 0 : integer(value.views),
      publishedAt: optionalText(value.publishedAt, 64),
      updateTime: optionalText(value.updateTime, 64),
      children: value.children == null ? null : array(value.children).map(crumb),
      breadcrumb: value.breadcrumb == null ? null : array(value.breadcrumb, 33).map(crumb),
    };
  });
}

export function parseCommentItem(raw: unknown): CommentItem | null {
  return attempt(() => {
    const value = record(raw);
    // Explicit projection keeps email, IP, user agent and auth fields private.
    return {
      id: integer(value.id, 1),
      nickname: text(value.nickname, 64),
      content: text(value.content, 2000),
      createTime: optionalText(value.createTime, 64),
    };
  });
}

export function parseSharedContent(raw: unknown): ShareContent | null {
  return attempt(() => {
    const value = record(raw);
    if (value.targetType !== 'post' && value.targetType !== 'page') fail();
    const targetId = integer(value.targetId, 1);
    const post = value.targetType === 'post' ? (parsePostDetail(value.post) ?? fail()) : null;
    const page = value.targetType === 'page' ? (parseWikiPage(value.page) ?? fail()) : null;
    if ((post ?? page)?.id !== targetId) fail();
    return {
      targetType: value.targetType as 'post' | 'page',
      targetId,
      expireAt: optionalText(value.expireAt, 64),
      post,
      page,
    };
  });
}
