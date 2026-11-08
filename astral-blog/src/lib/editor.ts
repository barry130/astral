import { isRecord } from '@/lib/result';
import { safeImageUrl } from '@/lib/url';
import {
  parseCategoryTree,
  parsePostDetail,
  parseTagList,
  parseWikiPage,
  parseWikiTree,
} from '@/lib/cms-validation';
import type { CategoryNode, PostDetail, TagItem, WikiNode } from '@/lib/cms';

export const MAX_SAVE_BYTES = 1_100_000;
const boundedSave = (
  data: Record<string, unknown>,
): { ok: true; data: Record<string, unknown> } | { ok: false; error: string } =>
  new TextEncoder().encode(JSON.stringify(data)).byteLength <= MAX_SAVE_BYTES
    ? { ok: true, data }
    : { ok: false, error: '内容超过请求字节上限，请缩短正文后重试。' };

export type ContentKind = 'post' | 'page';
export type AdminPostRecord = PostDetail;
export interface AdminPageRecord {
  id: number;
  title: string;
  slug: string | null;
  content: string;
  status: string;
  parentId: number | null;
  sort: number | null;
  publishedAt: string | null;
}
export interface StatusOption {
  value: string;
  label: string;
}
export interface EditorMeta {
  categories: CategoryNode[];
  tags: TagItem[];
  pages: WikiNode[];
  statuses: StatusOption[];
}
export interface FlatOption {
  id: number;
  label: string;
  depth: number;
}

const id = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const int32 = (value: unknown): value is number =>
  typeof value === 'number' &&
  Number.isInteger(value) &&
  value >= -2_147_483_648 &&
  value <= 2_147_483_647;

export function parseEditorRecord(
  kind: ContentKind,
  raw: unknown,
): AdminPostRecord | AdminPageRecord | null {
  if (kind === 'post') return parsePostDetail(raw);
  const page = parseWikiPage(raw);
  if (
    !page ||
    !page.status ||
    !isRecord(raw) ||
    (raw.parentId != null && !id(raw.parentId)) ||
    (raw.sort != null && !int32(raw.sort))
  )
    return null;
  return {
    id: page.id,
    title: page.title,
    slug: page.slug,
    content: page.content,
    status: page.status,
    parentId: raw.parentId == null ? null : (raw.parentId as number),
    sort: raw.sort == null ? 0 : (raw.sort as number),
    publishedAt: page.publishedAt ?? null,
  };
}

export function parseStatusDictionary(raw: unknown): StatusOption[] | null {
  if (!Array.isArray(raw) || raw.length > 256) return null;
  const options: StatusOption[] = [];
  for (const item of raw) {
    if (
      !isRecord(item) ||
      typeof item.dictValue !== 'string' ||
      !item.dictValue ||
      item.dictValue.length > 64 ||
      typeof item.dictLabel !== 'string' ||
      !item.dictLabel.trim() ||
      item.dictLabel.length > 256
    )
      return null;
    if (item.status != null && item.status !== 1) continue;
    if (!options.some((option) => option.value === item.dictValue))
      options.push({ value: item.dictValue, label: item.dictLabel });
  }
  return options;
}

export function parseEditorMeta(raw: unknown): EditorMeta | null {
  if (!isRecord(raw) || !Array.isArray(raw.statuses) || raw.statuses.length > 256) return null;
  const categories = parseCategoryTree(raw.categories);
  const tags = parseTagList(raw.tags);
  const pages = parseWikiTree(raw.pages);
  const statuses: StatusOption[] = [];
  for (const item of raw.statuses) {
    if (
      !isRecord(item) ||
      typeof item.value !== 'string' ||
      !item.value ||
      item.value.length > 64 ||
      typeof item.label !== 'string' ||
      !item.label.trim() ||
      item.label.length > 256
    )
      return null;
    statuses.push({ value: item.value, label: item.label });
  }
  return categories && tags && pages ? { categories, tags, pages, statuses } : null;
}

export function flattenCategoryOptions(
  nodes: CategoryNode[],
  depth = 0,
  path: string[] = [],
): FlatOption[] {
  return nodes.flatMap((node) => {
    const next = [...path, node.name || `#${node.id}`];
    return [
      { id: node.id, label: next.join(' / '), depth },
      ...flattenCategoryOptions(node.children ?? [], depth + 1, next),
    ];
  });
}

/** Exclude the edited node's subtree, not its ancestors or unrelated siblings. */
export function getPageParentOptions(nodes: WikiNode[], targetId: number): FlatOption[] {
  const excluded = new Set<number>();
  const collect = (node: WikiNode): void => {
    excluded.add(node.id);
    node.children?.forEach(collect);
  };
  const locate = (items: WikiNode[]): void => {
    for (const node of items) {
      if (node.id === targetId) collect(node);
      else locate(node.children ?? []);
    }
  };
  locate(nodes);
  const flatten = (items: WikiNode[], depth = 0, path: string[] = []): FlatOption[] =>
    items.flatMap((node) => {
      const next = [...path, node.title || `#${node.id}`];
      return excluded.has(node.id)
        ? []
        : [
            { id: node.id, label: next.join(' / '), depth },
            ...flatten(node.children ?? [], depth + 1, next),
          ];
    });
  return flatten(nodes);
}

export function validateContentSave(
  kind: ContentKind,
  raw: unknown,
  targetId?: number,
): { ok: true; data: Record<string, unknown> } | { ok: false; error: string } {
  if (!isRecord(raw)) return { ok: false, error: '保存内容格式无效。' };
  const title = typeof raw.title === 'string' ? raw.title.trim() : '';
  const content = typeof raw.content === 'string' ? raw.content : '';
  const status = typeof raw.status === 'string' ? raw.status.trim() : '';
  if (!title || title.length > 256) return { ok: false, error: '标题必填且不能超过 256 字。' };
  if (!content.trim() || content.length > 1_000_000)
    return { ok: false, error: '正文必填且不能超过 100 万字符。' };
  if (!status || status.length > 64) return { ok: false, error: '请选择有效内容状态。' };
  const optional = (key: string, max: number): string | null | false => {
    if (raw[key] == null) return null;
    return typeof raw[key] === 'string' && raw[key].length <= max ? raw[key].trim() || null : false;
  };
  const slug = optional('slug', 128);
  const publishedAt = optional('publishedAt', 64);
  if (slug === false || publishedAt === false)
    return { ok: false, error: 'URL 别名不能超过 128 字，发布时间格式必须有效。' };
  if (publishedAt) {
    if (!/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(publishedAt))
      return { ok: false, error: '发布时间格式应为 yyyy-MM-dd HH:mm:ss。' };
    const parsed = new Date(`${publishedAt.replace(' ', 'T')}Z`);
    if (
      !Number.isFinite(parsed.getTime()) ||
      parsed.toISOString().slice(0, 19).replace('T', ' ') !== publishedAt
    )
      return { ok: false, error: '发布时间无效。' };
  }
  const base = { title, content, status, slug, publishedAt };
  if (kind === 'page') {
    if (
      (raw.parentId != null && !id(raw.parentId)) ||
      raw.parentId === targetId ||
      (raw.sort != null && !int32(raw.sort))
    )
      return { ok: false, error: '父页面或排序值无效。' };
    return boundedSave({ ...base, parentId: raw.parentId ?? null, sort: raw.sort ?? 0 });
  }
  const summary = optional('summary', 512);
  const cover = optional('cover', 512);
  if (summary === false || cover === false || (cover && !safeImageUrl(cover)))
    return { ok: false, error: '摘要与封面不能超过 512 字，封面必须是有效图片 URL。' };
  if (raw.isTop != null && typeof raw.isTop !== 'boolean')
    return { ok: false, error: '置顶值无效。' };
  const ids = (value: unknown): number[] | null => {
    if (value == null) return [];
    return Array.isArray(value) && value.length <= 1000 && value.every(id)
      ? [...new Set(value)]
      : null;
  };
  const categoryIds = ids(raw.categoryIds);
  const tagIds = ids(raw.tagIds);
  if (!categoryIds || !tagIds || (raw.categoryId != null && !id(raw.categoryId)))
    return { ok: false, error: '分类或标签 ID 无效。' };
  return boundedSave({
    ...base,
    summary,
    cover,
    isTop: raw.isTop ?? false,
    categoryIds,
    categoryId: categoryIds[0] ?? raw.categoryId ?? null,
    tagIds,
  });
}
