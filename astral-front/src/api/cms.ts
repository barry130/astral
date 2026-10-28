import { request, ApiResult } from './client';
import { fetchDictOptions, DictOption } from './dict';
import { clientHeaders } from '@/lib/client-info';

/**
 * CMS 内容管理插件 API（/api/v1/admin/cms）。
 * 契约见 docs/CMS_PLUGIN.md：响应统一 Result{code,errorCode,msg,data,timestamp}，
 * 分页为 MyBatis-Plus Page{records,total,size,current,pages}。
 */

// ==================== 通用类型 ====================

/** MyBatis-Plus 分页结果 */
export interface CmsPageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/** 树形节点公共结构 */
export interface CmsTreeNode {
  id: number;
  children?: CmsTreeNode[];
}

// ==================== 实体类型 ====================

/** 标签（列表附 postCount：已发布文章计数） */
export interface CmsTag {
  id: number;
  name?: string;
  slug?: string;
  postCount?: number;
  createTime?: string | null;
}

/** 分类（树形，附 postCount 与 children） */
export interface CmsCategory {
  id: number;
  name?: string;
  slug?: string;
  parentId?: number | null;
  sort?: number;
  description?: string;
  postCount?: number;
  children?: CmsCategory[];
}

/** 文章所属分类的轻量引用（`categories[0]` 与 `categoryId` 一致，即主分类） */
export interface CmsPostCategory {
  id: number;
  name: string;
  slug: string | null;
}

/** 文章 */
export interface CmsPost {
  id: number;
  title?: string;
  slug?: string;
  summary?: string;
  /** Markdown 正文 */
  content?: string;
  cover?: string;
  /** 主分类ID（冗余列，等于 categories[0]?.id，仅作向后兼容） */
  categoryId?: number | null;
  /** 列表/详情 JOIN 填充（主分类名，仅作向后兼容） */
  categoryName?: string | null;
  /** 全部分类（关系表；第一项为主分类，旧数据可能为空） */
  categories?: CmsPostCategory[] | null;
  /** draft | published | private | hidden（private = 仅登录且有 admin:cms:view 的前台可见） */
  status?: string;
  isTop?: boolean;
  views?: number;
  authorId?: number | null;
  publishedAt?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
  /** 列表/详情填充的标签集合（编辑页由 tags 推导 tagIds） */
  tags?: CmsTag[];
}

/** Wiki 页面（树形） */
export interface CmsWikiPage {
  id: number;
  title?: string;
  slug?: string;
  content?: string;
  parentId?: number | null;
  sort?: number;
  /** draft | published | private | hidden（private = 仅登录且有 admin:cms:view 的前台可见） */
  status?: string;
  views?: number;
  publishedAt?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
  children?: CmsWikiPage[];
}

/** 评论（管理端含 email/ip/userAgent，公开侧不返回） */
export interface CmsComment {
  id: number;
  /** post | page */
  targetType?: string;
  targetId?: number;
  /** 目标标题（JOIN 填充） */
  targetTitle?: string | null;
  nickname?: string;
  email?: string | null;
  content?: string;
  /** pending | approved | rejected */
  status?: string;
  ip?: string | null;
  userAgent?: string | null;
  createTime?: string | null;
}

// ==================== 分享链接 ====================

/**
 * 分享链接（`/api/v1/admin/cms/share`）。
 * 让「私密」内容以只读方式开放给未登录访客：可设过期时间、可随时撤销。
 * `targetTitle` / `url` / `expired` 均由服务端填充，前端不自行推导。
 */
export interface CmsShare {
  id: number;
  /** post 文章 | page 页面 */
  targetType: 'post' | 'page';
  targetId: number;
  token: string;
  /** null = 永久有效 */
  expireAt: string | null;
  remark: string | null;
  createBy: number | null;
  createTime: string | null;
  /** 目标内容标题（服务端填充） */
  targetTitle: string | null;
  /** 完整分享地址（服务端按站点设置 blog_url 拼装；未配置时为 null） */
  url: string | null;
  /** 是否已过期（服务端计算） */
  expired: boolean;
}

/** 创建分享链接请求体 */
export interface CmsShareCreateDto {
  targetType: 'post' | 'page';
  targetId: number;
  /** yyyy-MM-dd HH:mm:ss，留空/NULL 表示永久有效 */
  expireAt?: string | null;
  remark?: string;
}

// ==================== 请求/响应参数 ====================

export interface CmsPostPageParams {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: string;
  categoryId?: number;
  tagId?: number;
}

export interface CmsPostSaveDto {
  title: string;
  slug?: string;
  summary?: string;
  content: string;
  cover?: string;
  /**
   * 主分类（冗余字段）：新代码统一提交 `categoryIds`，并把这里填成 `categoryIds[0] ?? null`，
   * 后端据此维护主分类列（筛选、排序等旧逻辑仍读它）。
   */
  categoryId?: number | null;
  /** 多分类（新代码统一提交；为空数组或缺失时后端回退 `categoryId`） */
  categoryIds?: number[] | null;
  status: string;
  isTop?: boolean;
  tagIds?: number[];
}

export interface CmsCategorySaveDto {
  name: string;
  slug?: string;
  parentId?: number | null;
  sort?: number;
  description?: string;
}

export interface CmsTagSaveDto {
  name: string;
  slug?: string;
}

export interface CmsWikiPageSaveDto {
  title: string;
  slug?: string;
  content: string;
  parentId?: number | null;
  sort?: number;
  status: string;
}

export interface CmsCommentPageParams {
  pageNum?: number;
  pageSize?: number;
  status?: string;
  targetType?: string;
  keyword?: string;
}

// ==================== API ====================

const BASE = '/api/v1/admin/cms';

export const cmsApi = {
  // ---------- 文章 ----------
  postPage: (params: CmsPostPageParams): Promise<ApiResult<CmsPageResult<CmsPost>>> =>
    request.get(`${BASE}/post/page`, { params }),

  postDetail: (id: number): Promise<ApiResult<CmsPost>> =>
    request.get(`${BASE}/post/${id}`),

  createPost: (data: CmsPostSaveDto): Promise<ApiResult<number>> =>
    request.post(`${BASE}/post`, data),

  updatePost: (id: number, data: CmsPostSaveDto): Promise<ApiResult<void>> =>
    request.put(`${BASE}/post/${id}`, data),

  changePostStatus: (id: number, status: string): Promise<ApiResult<void>> =>
    request.put(`${BASE}/post/${id}/status`, { status }),

  deletePost: (id: number): Promise<ApiResult<void>> =>
    request.delete(`${BASE}/post/${id}`),

  // ---------- 分类 ----------
  categoryTree: (): Promise<ApiResult<CmsCategory[]>> =>
    request.get(`${BASE}/category/tree`),

  createCategory: (data: CmsCategorySaveDto): Promise<ApiResult<number>> =>
    request.post(`${BASE}/category`, data),

  updateCategory: (id: number, data: CmsCategorySaveDto): Promise<ApiResult<void>> =>
    request.put(`${BASE}/category/${id}`, data),

  deleteCategory: (id: number): Promise<ApiResult<void>> =>
    request.delete(`${BASE}/category/${id}`),

  // ---------- 标签 ----------
  tagList: (): Promise<ApiResult<CmsTag[]>> =>
    request.get(`${BASE}/tag/list`),

  createTag: (data: CmsTagSaveDto): Promise<ApiResult<number>> =>
    request.post(`${BASE}/tag`, data),

  updateTag: (id: number, data: CmsTagSaveDto): Promise<ApiResult<void>> =>
    request.put(`${BASE}/tag/${id}`, data),

  deleteTag: (id: number): Promise<ApiResult<void>> =>
    request.delete(`${BASE}/tag/${id}`),

  // ---------- Wiki 页面 ----------
  pageTree: (): Promise<ApiResult<CmsWikiPage[]>> =>
    request.get(`${BASE}/page/tree`),

  pageDetail: (id: number): Promise<ApiResult<CmsWikiPage>> =>
    request.get(`${BASE}/page/${id}`),

  createPage: (data: CmsWikiPageSaveDto): Promise<ApiResult<number>> =>
    request.post(`${BASE}/page`, data),

  updatePage: (id: number, data: CmsWikiPageSaveDto): Promise<ApiResult<void>> =>
    request.put(`${BASE}/page/${id}`, data),

  changePageStatus: (id: number, status: string): Promise<ApiResult<void>> =>
    request.put(`${BASE}/page/${id}/status`, { status }),

  deletePage: (id: number): Promise<ApiResult<void>> =>
    request.delete(`${BASE}/page/${id}`),

  // ---------- 评论 ----------
  commentPage: (params: CmsCommentPageParams): Promise<ApiResult<CmsPageResult<CmsComment>>> =>
    request.get(`${BASE}/comment/page`, { params }),

  auditComment: (id: number, status: 'approved' | 'rejected'): Promise<ApiResult<void>> =>
    request.put(`${BASE}/comment/${id}/audit`, { status }),

  deleteComment: (id: number): Promise<ApiResult<void>> =>
    request.delete(`${BASE}/comment/${id}`),

  // ---------- 站点设置 ----------
  settingList: (): Promise<ApiResult<Record<string, string>>> =>
    request.get(`${BASE}/setting/list`),

  saveSettings: (settings: Record<string, string>): Promise<ApiResult<void>> =>
    request.put(`${BASE}/setting`, { settings }),

  // ---------- 分享链接 ----------
  /** 全部分享链接（分享管理页） */
  shareList: (): Promise<ApiResult<CmsShare[]>> =>
    request.get(`${BASE}/share/list`),

  /** 某个内容的分享链接（文章/页面编辑页的分享面板） */
  shareListByTarget: (targetType: 'post' | 'page', targetId: number): Promise<ApiResult<CmsShare[]>> =>
    request.get(`${BASE}/share/list-by-target`, { params: { targetType, targetId } }),

  createShare: (data: CmsShareCreateDto): Promise<ApiResult<CmsShare>> =>
    request.post(`${BASE}/share`, data),

  /** 撤销单条分享链接 */
  revokeShare: (id: number): Promise<ApiResult<void>> =>
    request.delete(`${BASE}/share/${id}`),

  /** 撤销某个内容的全部链接，返回撤销条数 */
  revokeShareByTarget: (targetType: 'post' | 'page', targetId: number): Promise<ApiResult<number>> =>
    request.delete(`${BASE}/share/by-target`, { params: { targetType, targetId } }),
};

// ==================== 数据字典 ====================

/** CMS 插件字典编码（V20261022001__cms_dict.sql + V20261024001 分享/私密）：枚举文案一律由字典提供，不得硬编码 */
export const CMS_DICT_CODES = [
  'cms_content_status',
  'cms_comment_status',
  'cms_comment_target_type',
  'cms_share_target_type',
] as const;

export type CmsDictCode = (typeof CMS_DICT_CODES)[number];

type CmsDictCache = Partial<Record<CmsDictCode, DictOption[]>>;

/** 字典缓存（页面挂载时 loadCmsDicts 预热） */
let dictCache: CmsDictCache = {};

/** 拉取 CMS 插件枚举字典并缓存 */
export async function loadCmsDicts(): Promise<CmsDictCache> {
  const opts = await fetchDictOptions([...CMS_DICT_CODES]);
  dictCache = {
    cms_content_status: opts.cms_content_status || [],
    cms_comment_status: opts.cms_comment_status || [],
    cms_comment_target_type: opts.cms_comment_target_type || [],
    cms_share_target_type: opts.cms_share_target_type || [],
  };
  return dictCache;
}

/** 下拉选项（按字典编码） */
export function cmsDictOptions(code: CmsDictCode): DictOption[] {
  return dictCache[code] || [];
}

/** 字典文案：miss 回退展示原始值（未知值不丢信息） */
export function cmsDictLabel(code: CmsDictCode, value?: string | null): string {
  if (!value) return '-';
  const hit = dictCache[code]?.find((o) => o.value === value);
  return hit ? hit.label : value;
}

// ==================== 内容导出（文件流） ====================

/** 导出类型：单篇/单页 Markdown、全站 ZIP、全部文章合集 Markdown */
export type CmsExportKind = 'post' | 'page' | 'zip' | 'markdown';

/**
 * 导出请求路径。
 * 注意：这几个接口返回的是**文件流**而不是 Result 包装，不能用 `request.get`
 * （axios 拦截器会按 JSON 解析响应的 code 字段），必须走原生 fetch，见 `downloadCmsExport`。
 *
 * axios 实例的 `baseURL` 取自 `NEXT_PUBLIC_API_URL`（同源部署时为空串，跨域直连时是 API 域名），
 * 原生 fetch 不经过实例，这里显式拼上同一前缀，否则跨域部署会把导出请求打到前端域名上。
 *
 * @param kind 导出类型
 * @param id   单篇/单页导出时的内容 ID（zip / markdown 忽略）
 */
export function cmsExportUrl(kind: CmsExportKind, id?: number): string {
  const origin = process.env.NEXT_PUBLIC_API_URL || '';
  if (kind === 'zip') return `${origin}${BASE}/export/zip`;
  if (kind === 'markdown') return `${origin}${BASE}/export/markdown`;
  if (id == null) {
    throw new Error(`导出${kind === 'post' ? '文章' : '页面'}必须提供 ID`);
  }
  return `${origin}${BASE}/export/${kind}/${id}`;
}

/** 导出下载结果：失败时带回可直接展示的中文原因 */
export interface CmsExportResult {
  ok: boolean;
  error?: string;
}

/** 从 Content-Disposition 里取文件名；优先 RFC 5987 的 filename*（后端是 UTF-8 编码的中文名） */
function parseFilename(disposition: string | null, fallback: string): string {
  if (!disposition) return fallback;
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (utf8) {
    try {
      return decodeURIComponent(utf8[1]);
    } catch {
      // 编码异常时回退到下面的普通 filename
    }
  }
  // 前置 `;` 或字符串起点，避免把 `filename*=UTF-8''...` 里的星号当成普通文件名
  const plain = /(?:^|;)\s*filename="?([^";]+)"?/i.exec(disposition);
  return plain ? plain[1] : fallback;
}

/** 触发浏览器下载（用完立即释放 object URL，避免内存泄漏） */
function triggerDownload(blob: Blob, filename: string): void {
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = href;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // 部分浏览器在 click 返回后仍需要一个宏任务才能开始读 blob
  setTimeout(() => URL.revokeObjectURL(href), 0);
}

/**
 * 下载导出的文件（原生 fetch + blob + 触发浏览器下载）。
 *
 * 为什么不直接 `window.location.href`：接口失败时返回的是 JSON 错误体，
 * 浏览器会把它当文件/错误页打开，用户看不到任何可读提示。走 fetch 可以先判断
 * 状态码与响应体，再决定下载还是 `toast.error`。
 *
 * 鉴权：登录态在 HttpOnly Cookie `satoken` 里，同源请求浏览器自动携带；
 * 导出是 GET（安全方法），不参与 CSRF 双提交校验，因此无需额外请求头。
 * 统一客户端系统头仍按项目约定带上，便于服务端接口统计。
 *
 * @returns `{ok: true}` 表示已触发下载；`{ok: false, error}` 带回失败原因
 */
export async function downloadCmsExport(
  kind: CmsExportKind,
  id?: number,
): Promise<CmsExportResult> {
  try {
    const url = cmsExportUrl(kind, id);
    const fallback = `${kind}${id != null ? `-${id}` : ''}.${kind === 'zip' ? 'zip' : 'md'}`;
    const res = await fetch(url, {
      method: 'GET',
      // 同源请求必须显式带上凭据：跨域直连部署（NEXT_PUBLIC_API_URL 指向别的域名）时
      // 默认的 same-origin 策略不会带 Cookie，会拿回 401 的 JSON。
      credentials: 'include',
      headers: clientHeaders(),
    });
    if (!res.ok) {
      // 真实非 2xx 只剩传输层错误（404/413/500/503），这里统一尝试解析出可读消息，
      // 解析不出来就用状态码。
      const message = await readErrorMessage(res);
      return { ok: false, error: message || `导出失败（HTTP ${res.status}）` };
    }
    // 防御：CmsGlobalExceptionHandler 对业务异常返回 200 + JSON，此时不是文件流
    const contentType = res.headers.get('Content-Type') || '';
    if (contentType.includes('application/json')) {
      const message = await readErrorMessage(res);
      return { ok: false, error: message || '导出失败' };
    }
    const blob = await res.blob();
    triggerDownload(blob, parseFilename(res.headers.get('Content-Disposition'), fallback));
    return { ok: true };
  } catch (e) {
    return { ok: false, error: (e as Error).message || '导出失败，请检查网络连接' };
  }
}

/** 尽力从错误响应里读出后端的 msg（读不到返回空串） */
async function readErrorMessage(res: Response): Promise<string> {
  try {
    const data = (await res.json()) as { msg?: string } | null;
    return data?.msg || '';
  } catch {
    return '';
  }
}
