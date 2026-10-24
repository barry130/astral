// astral 后端 CMS 公开 API 客户端（契约见 docs/CMS_PLUGIN.md「公开 API」一节）。
// 所有对后端的请求都在服务端完成（RSC fetch / Route Handler 转发），浏览器不直连后端，天然无 CORS 问题。
// 任何失败（后端不可达、HTTP 非 2xx、业务 code != 200）都不抛异常，返回 { ok: false }，
// 页面据此渲染友好空态；构建期不发起必需数据请求，后端不可达也能正常 build。

const DEFAULT_API_BASE = 'http://localhost:27000';

/** 后端地址，末尾统一去掉斜杠，供 fetch 拼接 */
export function getApiBase(): string {
  return (process.env.CMS_API_BASE || DEFAULT_API_BASE).replace(/\/+$/, '');
}

/** astral 统一响应壳 Result{code,errorCode,message,data,timestamp}，code==200 成功 */
interface ResultEnvelope<T> {
  code: number;
  errorCode?: string | null;
  message?: string | null;
  data: T;
  timestamp?: number;
}

export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: string | null };

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
async function authHeaders(): Promise<Record<string, string>> {
  if (typeof window !== 'undefined') return {};
  try {
    const { cookies } = await import('next/headers');
    const token = (await cookies()).get(TOKEN_COOKIE)?.value;
    return token ? { cookie: `${TOKEN_COOKIE}=${token}` } : {};
  } catch {
    // 非请求上下文（构建期静态分析等）拿不到 cookies，按匿名处理
    return {};
  }
}

async function cmsGet<T>(path: string): Promise<ApiResult<T>> {
  try {
    const res = await fetch(`${getApiBase()}${CMS_API}${path}`, {
      // 全站数据页按动态渲染处理：每次请求回源，构建期不发请求
      cache: 'no-store',
      headers: { accept: 'application/json', ...(await authHeaders()) },
    });
    if (!res.ok) {
      return { ok: false, error: `HTTP ${res.status}` };
    }
    const body = (await res.json()) as ResultEnvelope<T>;
    if (body.code !== 200 || body.data == null) {
      return { ok: false, error: body.message ?? null };
    }
    return { ok: true, data: body.data };
  } catch {
    return { ok: false, error: null };
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

export async function getPostPage(query: PostQuery = {}): Promise<ApiResult<PageData<PostSummary>>> {
  const sp = new URLSearchParams();
  sp.set('pageNum', String(query.pageNum ?? 1));
  sp.set('pageSize', String(query.pageSize ?? 10));
  if (query.categorySlug) sp.set('categorySlug', query.categorySlug);
  if (query.tagSlug) sp.set('tagSlug', query.tagSlug);
  if (query.keyword) sp.set('keyword', query.keyword);
  return cmsGet<PageData<PostSummary>>(`/post/page?${sp.toString()}`);
}

/** 文章详情：纯数字按 id，否则按 slug；须 published（后端同时浏览量 +1） */
export async function getPost(idOrSlug: string): Promise<ApiResult<PostDetail>> {
  return cmsGet<PostDetail>(`/post/${encodeURIComponent(idOrSlug)}`);
}

export async function getCategoryTree(): Promise<ApiResult<CategoryNode[]>> {
  return cmsGet<CategoryNode[]>('/category/tree');
}

export async function getTagList(): Promise<ApiResult<TagItem[]>> {
  return cmsGet<TagItem[]>('/tag/list');
}

/** Wiki 目录树（仅 published，children 嵌套） */
export async function getWikiTree(): Promise<ApiResult<WikiNode[]>> {
  return cmsGet<WikiNode[]>('/page/tree');
}

/** Wiki 页面详情：含 content、children（子页面）、breadcrumb（父链） */
export async function getWikiPage(idOrSlug: string): Promise<ApiResult<WikiPageDetail>> {
  return cmsGet<WikiPageDetail>(`/page/${encodeURIComponent(idOrSlug)}`);
}

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
  return cmsGet<PageData<CommentItem>>(`/comment/page?${sp.toString()}`);
}

/**
 * 评论提交：仅供 Route Handler（app/api/comment/route.ts）在服务端转发使用。
 * 返回后端的原始 Result 包（业务失败时 code != 200，message 需透传给用户展示）。
 */
export interface CommentUpstreamResult {
  reachable: boolean;
  httpStatus: number;
  result: ResultEnvelope<unknown> | null;
}

export async function postCommentToBackend(payload: CommentSubmitPayload): Promise<CommentUpstreamResult> {
  try {
    const res = await fetch(`${getApiBase()}${CMS_API}/comment`, {
      method: 'POST',
      // 带登录态：私密内容只允许登录的管理员评论（后端 requirePublishedTarget 已按可见性校验）
      headers: { 'content-type': 'application/json', accept: 'application/json', ...(await authHeaders()) },
      body: JSON.stringify(payload),
      cache: 'no-store',
    });
    let result: ResultEnvelope<unknown> | null = null;
    try {
      result = (await res.json()) as ResultEnvelope<unknown>;
    } catch {
      // 后端返回非 JSON 时的兜底
      result = { code: res.status, message: `后端响应异常（HTTP ${res.status}）`, data: null };
    }
    return { reachable: true, httpStatus: res.status, result };
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
  const res = await cmsGet<Record<string, string>>('/setting');
  if (!res.ok) {
    return res;
  }
  const raw = res.data;
  const text = (key: string): string => raw[key] ?? '';
  // 开关：缺省按「开启」兜底，存在时只认 'true'（大小写不敏感，与后端 boolValue 口径一致）
  const flag = (key: string, fallback: boolean): boolean => {
    const value = raw[key];
    return value === undefined ? fallback : value.toLowerCase() === 'true';
  };
  return {
    ok: true,
    data: {
      siteTitle: text('site_title') || 'Astral Blog',
      siteDescription: text('site_description'),
      footerText: text('footer_text'),
      commentEnabled: flag('comment_enabled', true),
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

export async function getSharedContent(token: string): Promise<ApiResult<ShareContent>> {
  return cmsGet<ShareContent>(`/share/${encodeURIComponent(token)}`);
}
