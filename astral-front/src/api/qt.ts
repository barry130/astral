import { request, ApiResult } from './client';

/** 轻听插件统计概览 */
export interface QtOverview {
  userCount: number;
  dakaCount: number;
  noticeCount: number;
  updateCount: number;
}

/** 轻听用户 */
export interface QtUserInfo {
  id: number;
  username: string;
  nickname: string;
  email: string;
  avatar: string;
  role: string;
  state: number;
  createTime: string;
}

/** 轻听版本更新 */
export interface QtUpdate {
  id?: number;
  versionCode?: number;
  type?: number;
  versionName?: string;
  versionInfo?: string;
  updateType?: string;
  /** 直链下载地址（GitHub 时填原始 release 链接；历史 && 多链接已废弃） */
  downloadUrl?: string;
  channel?: string;
  /** 下载方式（已废弃保留，后台不再维护） */
  downloadMode?: string;
  /** 浏览器下载地址（可空，双链接之一） */
  browserUrl?: string;
  /** 直链是否为 GitHub 链接：1=是（参与加速拼接） 0=否 */
  isGithub?: number;
  isForce?: number;
  /** 是否发布：1 已发布(App端收到更新通知) / 0 未发布(仅本地测试) */
  isPublished?: number;
  fileSize?: number;
  md5?: string;
}

/** GitHub 加速节点（UPDATE_DESIGN.md） */
export interface QtGithubAccel {
  id?: number;
  /** 节点名称（如 ghfast） */
  name?: string;
  /** 加速前缀（最终地址 = prefixUrl + 原始链接直接拼接） */
  prefixUrl: string;
  /** 是否启用：1=启用 0=停用 */
  isShow?: number;
  /** 排序（探测顺序，小在前） */
  sort?: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 手动探活结果（仅展示，不写库） */
export interface QtGithubAccelProbe {
  id?: number;
  name?: string;
  prefixUrl?: string;
  /** 是否可用（HTTP 200/206） */
  alive: boolean;
  /** 探测耗时（毫秒） */
  latencyMs: number;
  /** 结果说明：HTTP 状态码 / 失败原因 */
  message?: string;
}

/** 轻听插件枚举值已从后端「数据字典」动态获取，不再前端写死。
 *  下拉选项统一通过 src/api/dict.ts 的 fetchDictOptions 按 dict_code 拉取：
 *  - qt_yes_no               通用是否(0/1)
 *  - qt_update_platform      版本更新平台
 *  - qt_update_type          版本更新提示方式
 *  - qt_update_channel       版本发布渠道
 *  - qt_update_publish       版本发布状态
 *
 *  注：公告相关字典（qt_notice_channel / qt_notice_audience）随公告管理页一并移除，
 *  App 端统一通知已迁移至反馈插件的 sys_notice（/dashboard/message）。
 */

/** 根据选项列表取展示文案，未匹配时回退原值 */
export function enumLabel(
  group: ReadonlyArray<{ value: number | string | undefined; label: string }>,
  value: number | string | null | undefined,
): string {
  if (value === null || value === undefined || value === '') return '-';
  const found = group.find((o) => String(o.value) === String(value));
  return found ? found.label : String(value);
}

/** 分页结果 */
export interface QtPage<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
}

/** 轻听插件管理 API（后台 Admin，需宿主登录态） */
export const qtAdminApi = {
  overview: (): Promise<ApiResult<QtOverview>> =>
    request.get('/api/v1/admin/qt/overview'),

  users: (pageNum = 1, pageSize = 10, keyword?: string): Promise<ApiResult<QtPage<QtUserInfo>>> =>
    request.get('/api/v1/admin/qt/users', { params: { pageNum, pageSize, keyword } }),

  changeUserState: (id: number, state: number): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/qt/users/${id}/state`, null, { params: { state } }),

  updates: (pageNum = 1, pageSize = 10): Promise<ApiResult<QtPage<QtUpdate>>> =>
    request.get('/api/v1/admin/qt/updates', { params: { pageNum, pageSize } }),

  createUpdate: (data: QtUpdate): Promise<ApiResult<QtUpdate>> =>
    request.post('/api/v1/admin/qt/updates', data),

  updateUpdate: (id: number, data: QtUpdate): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/qt/updates/${id}`, data),

  deleteUpdate: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/qt/updates/${id}`),
};

/** 音源包产物条目（artifacts 是「当前生效全集」，客户端按 path 比 version 决定要不要重下） */
export interface QtSourceArtifact {
  path: string;
  version?: number;
  url?: string;
}

/** 音源包发布记录（SOURCE_UPDATE_DESIGN §五） */
export interface QtSourceRelease {
  id?: number;
  /** 版本号（yyyyMMddNN，后端生成，请求体不送） */
  sourceVersionCode?: number;
  /** 版本名（yyyy.MM.dd.N，后端派生） */
  sourceVersionName?: string;
  /** 适用平台（1101/1102/1103） */
  platforms: number[];
  /** 按平台准入的应用版本号（平台缺省或空数组=不限制） */
  appVersionCodes?: Record<string, number[]>;
  hostApiVersion?: number;
  channel?: string;
  notes?: string;
  artifacts?: QtSourceArtifact[];
  rollbackTo?: number | null;
  bad?: boolean;
  published?: boolean;
  publishedAt?: string;
  createTime?: string;
}

/** 装机分布统计行（版本×平台×结果聚合） */
export interface QtSourceStatRow {
  source_version_code: number;
  platform: number;
  result: string;
  cnt: number;
}

/** 音源包管理 API（后台 Admin，需宿主登录态） */
export const sourceReleaseApi = {
  list: (
    pageNum = 1,
    pageSize = 10,
    params?: { platform?: number; channel?: string; published?: boolean; full?: number },
  ): Promise<ApiResult<QtPage<QtSourceRelease>>> =>
    request.get('/api/v1/admin/qt/source-releases', { params: { pageNum, pageSize, ...params } }),

  stats: (): Promise<ApiResult<QtSourceStatRow[]>> =>
    request.get('/api/v1/admin/qt/source-releases/stats'),

  /** 新建：后端生成版本号并随响应返回（先拿号 → 上传文件 → 回填 artifacts → 发布） */
  create: (data: QtSourceRelease): Promise<ApiResult<QtSourceRelease>> =>
    request.post('/api/v1/admin/qt/source-releases', data),

  /** 编辑：artifacts 按 path 合并，只传变更项，其余自动继承上一版 */
  update: (id: number, data: QtSourceRelease): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/qt/source-releases/${id}`, data),

  publish: (id: number): Promise<ApiResult<void>> =>
    request.post(`/api/v1/admin/qt/source-releases/${id}/publish`),

  unpublish: (id: number): Promise<ApiResult<void>> =>
    request.post(`/api/v1/admin/qt/source-releases/${id}/unpublish`),

  markBad: (id: number, rollbackTo?: number): Promise<ApiResult<void>> =>
    request.post(`/api/v1/admin/qt/source-releases/${id}/bad`, null, { params: { rollbackTo } }),

  remove: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/qt/source-releases/${id}`),
};

/** GitHub 加速节点管理 API（后台 Admin，需宿主登录态；UPDATE_DESIGN.md §2.2） */
export const githubAccelApi = {
  /** 分页列表（直接查库，不走缓存） */
  list: (pageNum = 1, pageSize = 20): Promise<ApiResult<QtPage<QtGithubAccel>>> =>
    request.get('/api/v1/admin/qt/github-accels', { params: { pageNum, pageSize } }),

  /** 新增节点 → 自动刷新缓存 */
  create: (data: QtGithubAccel): Promise<ApiResult<QtGithubAccel>> =>
    request.post('/api/v1/admin/qt/github-accels', data),

  /** 编辑节点 → 自动刷新缓存 */
  update: (id: number, data: QtGithubAccel): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/qt/github-accels/${id}`, data),

  /** 删除节点 → 自动刷新缓存 */
  remove: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/qt/github-accels/${id}`),

  /** 手动失效缓存（清空 + 重新加载） */
  evictCache: (): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/qt/github-accels/cache/evict'),

  /** 手动探活：并发探测所有启用节点，仅展示不写库 */
  probe: (): Promise<ApiResult<QtGithubAccelProbe[]>> =>
    request.post('/api/v1/admin/qt/github-accels/probe'),
};
