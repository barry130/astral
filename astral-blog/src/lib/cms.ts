// astral 后端 CMS 公开 API 客户端（契约见 docs/CMS_PLUGIN.md「公开 API」一节）。
// 所有对后端的请求都在服务端完成（RSC fetch / Route Handler 转发），浏览器不直连后端，天然无 CORS 问题。
// HTTP200 + 合法 Result/code200 才是成功；显式404与可恢复的不可用状态分开返回。
// 页面不会把读失败伪装为合法空数据；认证读始终 no-store，React cache 仅在当前渲染去重。
import { cache } from 'react';
import { isRecord, parseResult, resultMessage, type ResultEnvelope } from '@/lib/result';
import { fetchBackend } from '@/lib/backend';
import { backendFetch, canViewContent, getSessionUser } from '@/lib/auth-server';
import {
  parseCategoryTree,
  parseCommentItem,
  parsePageData,
  parsePostDetail,
  parsePostSummary,
  parseSharedContent,
  parseTagList,
  parseWikiPage,
  parseWikiTree,
} from '@/lib/cms-validation';
export { getApiBase } from '@/lib/backend';

/** Only explicit backend missing-resource results may produce a page 404. */
export type ApiResult<T> =
  { ok: true; data: T } | { ok: false; error: string | null; notFound?: boolean };
export interface ReadOptions {
  anonymous?: boolean;
}

/** error === null 表示网络层失败（后端不可达等），非 null 表示后端返回了业务错误信息 */
const CMS_API = '/api/v1/app/cms';

/** 登录令牌 Cookie 名（与 astral 后端 Sa-Token 一致） */
const TOKEN_COOKIE = 'satoken';

/**
 * 转发登录 Cookie：公开接口虽然匿名可访问，但后端会按请求里的 `satoken`
 * 决定可见范围 —— 登录且具备 `admin:cms:view` 时私密内容也返回。
 * 因此这里把浏览器带来的令牌透传给后端，匿名访客没有该 Cookie 时就是普通的匿名请求。
 *
 * 动态 import `next/headers`：cms.ts 也会被客户端组件以 `import type` 引用（类型在编译期擦除），
 * 动态引入能保证这段只在服务端执行。
 */
async function authHeaders(options: ReadOptions): Promise<Record<string, string>> {
  if (options.anonymous || typeof window !== 'undefined') return {};
  try {
    const { cookies } = await import('next/headers');
    const token = (await cookies()).get(TOKEN_COOKIE)?.value;
    if (!token || /[\s;,\r\n]/.test(token) || !canViewContent(await getSessionUser())) return {};
    return { cookie: `${TOKEN_COOKIE}=${token}` };
  } catch {
    // Static analysis has no request cookies; no credentials may be invented.
    return {};
  }
}

async function cmsGet<T>(
  path: string,
  parseData: (raw: unknown) => T | null,
  options: ReadOptions = {},
): Promise<ApiResult<T>> {
  try {
    const res = await fetchBackend(`${CMS_API}${path}`, { headers: await authHeaders(options) });
    const body = parseResult(await res.json().catch(() => null));
    if (res.status !== 200 || !body || body.code !== 200) {
      return {
        ok: false,
        error: resultMessage(
          body?.code === 200 ? null : body,
          res.status === 200 ? '内容服务响应异常，请稍后重试。' : `HTTP ${res.status}`,
        ),
        notFound: res.status === 404 || (res.status === 200 && body?.code === 404),
      };
    }
    const data = parseData(body.data);
    return data === null
      ? { ok: false, error: '内容服务返回的数据格式异常，请稍后重试。', notFound: false }
      : { ok: true, data };
  } catch {
    return { ok: false, error: null, notFound: false };
  }
}

// ---------- 类型（与公开 API 契约对应） ----------

export interface PostTag {
  id: number;
  name: string;
  slug: string | null;
}

/** 文章所属分类（多分类契约；第一项为主分类） */
export interface PostCategory {
  id: number;
  name: string;
  slug: string | null;
}

/** 文章分页记录（列表不含 content） */
export interface PostSummary {
  id: number;
  title: string;
  slug: string | null;
  summary: string | null;
  cover: string | null;
  /**
   * 全部所属分类：后端多分类支持新增字段，第一项为主分类。
   * 老数据 / 旧版后端可能不返回该字段或返回空数组，
   * 展示统一走 lib/post.ts 的 resolvePostCategories() 回退到 categoryId + categoryName。
   */
  categories?: PostCategory[] | null;
  /** 主分类 id（保留，向后兼容） */
  categoryId: number | null;
  status: string;
  isTop: boolean;
  views: number;
  authorId: number | null;
  publishedAt: string | null;
  createTime: string | null;
  updateTime: string | null;
  /** 主分类名（保留，向后兼容） */
  categoryName: string | null;
  tags: PostTag[] | null;
}

export interface PostDetail extends PostSummary {
  content: string;
}

export interface CategoryNode {
  id: number;
  name: string;
  slug: string | null;
  parentId: number | null;
  sort?: number | null;
  description?: string | null;
  postCount: number;
  children: CategoryNode[] | null;
}

export interface TagItem {
  id: number;
  name: string;
  slug: string | null;
  postCount: number;
}

export interface WikiNode {
  id: number;
  title: string;
  slug: string | null;
  status?: string;
  children: WikiNode[] | null;
}

export interface Crumb {
  id: number;
  title: string;
  slug: string | null;
}

export interface WikiPageDetail {
  id: number;
  title: string;
  slug: string | null;
  content: string;
  views?: number;
  status?: string;
  publishedAt?: string | null;
  updateTime?: string | null;
  children: Crumb[] | null;
  breadcrumb: Crumb[] | null;
}

export interface CommentItem {
  id: number;
  nickname: string;
  content: string;
  createTime: string | null;
}

/** 后端分页结构：MyBatis-Plus Page{records,total,size,current,pages} */
export interface PageData<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface CommentSubmitPayload {
  targetType: 'post' | 'page';
  targetId: number;
  nickname: string;
  email?: string;
  content: string;
}

// ---------- 具体接口 ----------

export interface PostQuery {
  pageNum?: number;
  pageSize?: number;
  categorySlug?: string;
  tagSlug?: string;
  keyword?: string;
}

export async function getPostPage(
  query: PostQuery = {},
  options: ReadOptions = {},
): Promise<ApiResult<PageData<PostSummary>>> {
  const sp = new URLSearchParams();
  sp.set('pageNum', String(query.pageNum ?? 1));
  sp.set('pageSize', String(query.pageSize ?? 10));
  if (query.categorySlug) sp.set('categorySlug', query.categorySlug);
  if (query.tagSlug) sp.set('tagSlug', query.tagSlug);
  if (query.keyword) sp.set('keyword', query.keyword);
  const pageNum = query.pageNum ?? 1;
  const pageSize = query.pageSize ?? 10;
  if (
    !Number.isSafeInteger(pageNum) ||
    pageNum < 1 ||
    pageNum > 2_147_483_647 ||
    !Number.isSafeInteger(pageSize) ||
    pageSize < 1 ||
    pageSize > 100
  )
    return { ok: false, error: '分页参数无效。' };
  return cmsGet(
    `/post/page?${sp.toString()}`,
    (raw) => {
      const parsed = parsePageData(raw, parsePostSummary);
      return parsed?.current === pageNum && parsed.size === pageSize ? parsed : null;
    },
    options,
  );
}

/**
 * 文章详情：纯数字按 id，否则按 slug；须 published（后端同时浏览量 +1）。
 * 用 React cache 包一层：generateMetadata 与页面正文在同一次请求里只回源一次，
 * 避免浏览量被重复 +1。
 */
export const getPost = cache(async (idOrSlug: string): Promise<ApiResult<PostDetail>> => {
  return cmsGet(`/post/${encodeURIComponent(idOrSlug)}`, parsePostDetail);
});

/** 分类树：分类页 metadata、分类页正文与站点侧栏在同一次请求内共用一次回源。 */
export const getCategoryTree = cache(async (): Promise<ApiResult<CategoryNode[]>> => {
  return cmsGet('/category/tree', parseCategoryTree);
});

/** 标签列表：标签页 metadata、标签页正文与站点侧栏在同一次请求内共用一次回源。 */
export const getTagList = cache(async (): Promise<ApiResult<TagItem[]>> => {
  return cmsGet('/tag/list', parseTagList);
});

/** Wiki 目录树（仅 published，children 嵌套）；页头导航与 Wiki 目录页在同一次请求内共用结果。 */
export const getWikiTree = cache(
  async (options: ReadOptions = {}): Promise<ApiResult<WikiNode[]>> => {
    return cmsGet('/page/tree', parseWikiTree, options);
  },
);

/** Wiki 页面详情：含 content、children（子页面）、breadcrumb（父链）；同请求内 metadata 与页面共用一次回源。 */
export const getWikiPage = cache(async (idOrSlug: string): Promise<ApiResult<WikiPageDetail>> => {
  return cmsGet(`/page/${encodeURIComponent(idOrSlug)}`, parseWikiPage);
});

/** 已通过审核的评论分页（不含 email/ip/userAgent） */
export async function getCommentPage(
  targetType: 'post' | 'page',
  targetId: number,
  pageNum = 1,
  pageSize = 20,
): Promise<ApiResult<PageData<CommentItem>>> {
  const sp = new URLSearchParams();
  sp.set('targetType', targetType);
  sp.set('targetId', String(targetId));
  sp.set('pageNum', String(pageNum));
  sp.set('pageSize', String(pageSize));
  if (
    (targetType !== 'post' && targetType !== 'page') ||
    !Number.isSafeInteger(targetId) ||
    targetId < 1 ||
    !Number.isSafeInteger(pageNum) ||
    pageNum < 1 ||
    pageNum > 2_147_483_647 ||
    !Number.isSafeInteger(pageSize) ||
    pageSize < 1 ||
    pageSize > 100
  )
    return { ok: false, error: '评论分页参数无效。' };
  return cmsGet(`/comment/page?${sp.toString()}`, (raw) => {
    const parsed = parsePageData(raw, parseCommentItem);
    return parsed?.current === pageNum && parsed.size === pageSize ? parsed : null;
  });
}

/**
 * 评论提交：仅供 Route Handler（app/api/comment/route.ts）在服务端转发使用。
 * 返回后端的原始 Result 包（业务失败时 code != 200，msg 需透传给用户展示）。
 */
export interface CommentUpstreamResult {
  reachable: boolean;
  httpStatus: number;
  result: ResultEnvelope<unknown> | null;
}

export async function postCommentToBackend(
  payload: CommentSubmitPayload,
): Promise<CommentUpstreamResult> {
  try {
    const res = await backendFetch(`${CMS_API}/comment`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const result = parseResult(await res.json().catch(() => null));
    // Submitted comment data may include email/IP. The browser only needs the
    // business outcome; privacy fields must not be reflected in the response.
    return {
      reachable: true,
      httpStatus: res.status,
      result: result ? { ...result, data: null } : null,
    };
  } catch {
    return { reachable: false, httpStatus: 502, result: null };
  }
}

// ---------- 站点公开设置 ----------

/** 站点公开设置（后端 cms_setting 的公开子集，键已从下划线转驼峰） */
export interface SiteSettings {
  siteTitle: string;
  siteDescription: string;
  footerText: string;
  commentEnabled: boolean;
  commentNeedReview: boolean;
}

/**
 * 拉取站点公开设置：/setting 返回 { site_title, site_description, footer_text,
 * comment_enabled, comment_need_review }（不含 blog_url）。
 * 开关按字符串 'true' 判定；字段缺失或请求失败时用兜底值，不抛异常。
 */
export async function getSiteSettings(): Promise<ApiResult<SiteSettings>> {
  const res = await cmsGet('/setting', (raw) => (isRecord(raw) ? raw : null));
  if (!res.ok) {
    return res;
  }
  const raw = res.data;
  for (const key of ['site_title', 'site_description', 'footer_text']) {
    if (raw[key] != null && typeof raw[key] !== 'string')
      return { ok: false, error: '站点设置格式异常。' };
  }
  for (const key of ['comment_enabled', 'comment_need_review']) {
    if (raw[key] != null && typeof raw[key] !== 'boolean' && typeof raw[key] !== 'string')
      return { ok: false, error: '评论设置格式异常。' };
  }
  const text = (key: string): string => (typeof raw[key] === 'string' ? (raw[key] as string) : '');
  // 未配置时保守关闭评论；兼容后端布尔值与大小写不敏感的字符串。
  const flag = (key: string, fallback: boolean): boolean => {
    const value = raw[key];
    if (value === undefined || value === null) return fallback;
    if (typeof value === 'boolean') return value;
    return String(value).toLowerCase() === 'true';
  };
  return {
    ok: true,
    data: {
      siteTitle: text('site_title') || 'Astral Blog',
      siteDescription: text('site_description'),
      footerText: text('footer_text'),
      commentEnabled: flag('comment_enabled', false),
      commentNeedReview: flag('comment_need_review', true),
    },
  };
}

// ---------- 分享链接 ----------

/**
 * 按分享令牌读取内容（公开接口，无需登录）：
 * 有效 token 可只读访问目标内容，**包括私密文章/页面**（分享即临时授权）。
 * token 无效或过期时后端返回 code=404，这里表现为 `{ ok: false, error: '...' }`。
 */
export interface ShareContent {
  targetType: 'post' | 'page';
  targetId: number;
  expireAt: string | null;
  post: PostDetail | null;
  page: WikiPageDetail | null;
}

export const getSharedContent = cache(async (token: string): Promise<ApiResult<ShareContent>> => {
  return cmsGet(`/share/${encodeURIComponent(token)}`, parseSharedContent, { anonymous: true });
});
