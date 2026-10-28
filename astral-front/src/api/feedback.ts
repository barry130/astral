import { request, ApiResult } from './client';
import { dictApi } from './dict';
import type { DictOption } from './dict';
// 通知相关的类型 / API / 字典 / 标签已切到 `@/api/notice`（见文件末尾的 @deprecated 兼容别名）。
// 这里是「反馈」业务域：反馈单（sys_feedback）+ 反馈状态/类型/平台文案，不包含通知类型口径。
import {
  noticeAdminApi as noticeAdminApiOfNotice,
  loadNoticeDicts as loadNoticeDictsOfNotice,
  noticeTypeLabel as noticeTypeLabelOfNotice,
  noticeChannelLabel as noticeChannelLabelOfNotice,
  noticeTypeOptions as noticeTypeOptionsOfNotice,
  noticeChannelOptions as noticeChannelOptionsOfNotice,
  NOTICE_CHANNEL_ALL as NOTICE_CHANNEL_ALL_OF_NOTICE,
  NOTICE_TYPE_TEXT as NOTICE_TYPE_TEXT_OF_NOTICE,
  NOTICE_CHANNEL_TEXT as NOTICE_CHANNEL_TEXT_OF_NOTICE,
} from '@/api/notice';
import type { SysNotice as SysNoticeOfNotice } from '@/api/notice';

/** 反馈/通知分页结果 */
export interface FeedbackPage<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
}

/** 反馈实体 */
export interface Feedback {
  id?: number;
  userId?: number;
  /** 提交人用户名（JOIN sys_user 填充） */
  username?: string;
  /** 提交人邮箱（JOIN sys_user 填充） */
  email?: string;
  /** issue 问题 | request 需求 */
  type?: string;
  title?: string;
  content?: string;
  contact?: string;
  /** pending|received|resolved|published|deprecated */
  status?: string;
  isPublic?: boolean;
  device?: string;
  os?: string;
  appVersion?: string;
  platform?: string;
  ip?: string;
  createTime?: string;
  updateTime?: string;
}

/** 反馈回复 */
export interface FeedbackReply {
  id?: number;
  feedbackId?: number;
  userId?: number;
  content?: string;
  replyTime?: string;
  /** JOIN sys_user 填充 */
  nickname?: string;
  userType?: string;
}

/** 反馈统计看板 */
export interface FeedbackStat {
  total: number;
  pending: number;
  received: number;
  resolved: number;
  published: number;
  deprecated: number;
  todayNew: number;
  unreplied: number;
  byType: Array<{ type: string; count: number }>;
  last7Days: Array<{ date: string; count: number }>;
}

/** 反馈管理端 API */
export const feedbackAdminApi = {
  /** 分页查询（status/type/keyword/startDate/endDate） */
  page: (params: Record<string, any>): Promise<ApiResult<FeedbackPage<Feedback>>> =>
    request.get('/api/v1/admin/feedback/page', { params }),

  /** 详情 */
  detail: (id: number): Promise<ApiResult<Feedback>> =>
    request.get(`/api/v1/admin/feedback/${id}`),

  /** 状态流转 */
  changeStatus: (id: number, status: string): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/feedback/${id}/status`, { status }),

  /** 公开切换 */
  changePublic: (id: number, isPublic: boolean): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/feedback/${id}/public`, { isPublic }),

  /** 软删 */
  delete: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/feedback/${id}`),

  /** 回复列表 */
  replies: (id: number): Promise<ApiResult<FeedbackReply[]>> =>
    request.get(`/api/v1/admin/feedback/${id}/replies`),

  /** 管理端回复 */
  reply: (feedbackId: number, content: string): Promise<ApiResult<FeedbackReply>> =>
    request.post('/api/v1/admin/feedback/reply', { feedbackId, content }),

  /** 统计看板 */
  stat: (): Promise<ApiResult<FeedbackStat>> =>
    request.get('/api/v1/admin/feedback/stat'),
};

/**
 * 反馈业务枚举字典编码（对应 dict-init.sql 中 feedback_status/feedback_type/stat_platform）。
 *
 * <p>`notice_channel` / `notice_type` 仍列在这里只为兼容历史调用（本函数的返回值类型仍保留
 * 这两格）；**通知模块已改用 `@/api/notice` 的 `loadNoticeDicts()` 与独立缓存**，
 * 通知页面不要再依赖本函数预热通知字典。</p>
 */
export const FEEDBACK_DICT_CODES = [
  'feedback_status',
  'feedback_type',
  'notice_channel',
  'notice_type',
  // 客户端平台与接口统计共用同一份字典（统一客户端系统头 X-App-Ut 的取值）
  'stat_platform',
] as const;

interface FeedbackDictCache {
  feedbackStatus: DictOption[];
  feedbackType: DictOption[];
  noticeChannel: DictOption[];
  noticeType: DictOption[];
  platform: DictOption[];
}

/** 字典缓存（页面挂载时 loadFeedbackDicts 预热） */
let dictCache: Partial<FeedbackDictCache> = {};

/** 拉取反馈插件枚举字典并缓存 */
export async function loadFeedbackDicts(): Promise<Partial<FeedbackDictCache>> {
  const map: Record<string, DictOption[]> = {};
  const results = await Promise.all(FEEDBACK_DICT_CODES.map((c) => dictApi.byCode(c)));
  results.forEach((res, i) => {
    map[FEEDBACK_DICT_CODES[i]] = (res.data || []).map((d) => ({ value: String(d.dictValue), label: d.dictLabel }));
  });
  dictCache = {
    feedbackStatus: map.feedback_status || [],
    feedbackType: map.feedback_type || [],
    noticeChannel: map.notice_channel || [],
    noticeType: map.notice_type || [],
    platform: map.stat_platform || [],
  };
  return dictCache;
}

/** 反馈状态状态机流转（纯前端逻辑，无字典对应，保留硬编码） */
export const FEEDBACK_STATUS_COLOR: Record<string, string> = {
  pending: 'orange',
  received: 'blue',
  resolved: 'green',
  published: 'purple',
  deprecated: 'default',
};

/** 反馈状态文案：优先字典，miss 回退 */
export function feedbackStatusLabel(v?: string): string {
  if (!v) return '-';
  const hit = dictCache.feedbackStatus?.find((o) => o.value === v);
  return hit ? hit.label : (FEEDBACK_STATUS_TEXT_FALLBACK[v] || v);
}

/** 反馈类型文案：优先字典，miss 回退 */
export function feedbackTypeLabel(v?: string): string {
  if (!v) return '-';
  const hit = dictCache.feedbackType?.find((o) => o.value === v);
  return hit ? hit.label : (FEEDBACK_TYPE_TEXT_FALLBACK[v] || v);
}

/**
 * 客户端平台文案：优先字典 stat_platform，miss 回退。
 *
 * 注意历史值：统一客户端系统头落地前，feedback.ts 发的是裸 `android` / `ios`
 * （`AppFeedbackController` 现在会把遗留头映射成 `app-android` / `app-ios`，
 * 但**存量行**仍是旧值），所以回退表同时收录新旧两套取值。
 */
export function feedbackPlatformLabel(v?: string): string {
  if (!v) return '-';
  const hit = dictCache.platform?.find((o) => o.value === v);
  if (hit) return hit.label;
  return FEEDBACK_PLATFORM_TEXT_FALLBACK[v] || v;
}

/** 选项列表（下拉用）：优先字典，miss 回退 */
export function feedbackStatusOptions(): DictOption[] {
  return dictCache.feedbackStatus?.length
    ? dictCache.feedbackStatus
    : Object.entries(FEEDBACK_STATUS_TEXT_FALLBACK).map(([value, label]) => ({ value, label }));
}
export function feedbackTypeOptions(): DictOption[] {
  return dictCache.feedbackType?.length
    ? dictCache.feedbackType
    : Object.entries(FEEDBACK_TYPE_TEXT_FALLBACK).map(([value, label]) => ({ value, label }));
}

/** 兜底文案（字典未加载/网络失败时的 fallback，与 dict-init.sql 一致） */
const FEEDBACK_STATUS_TEXT_FALLBACK: Record<string, string> = {
  pending: '提出',
  received: '已接收',
  resolved: '已解决',
  published: '已发布',
  deprecated: '已废弃',
};
const FEEDBACK_TYPE_TEXT_FALLBACK: Record<string, string> = {
  issue: '问题',
  request: '需求',
};
/**
 * 客户端平台兜底文案。新值 = 统一客户端系统头 X-App-Ut（与 stat_platform 字典同源）；
 * 旧值是 X-Platform 时代的裸 android / ios，存量反馈行仍是它们。
 */
const FEEDBACK_PLATFORM_TEXT_FALLBACK: Record<string, string> = {
  'app-android': 'Android',
  'app-ios': 'iOS',
  'app-windows': 'Windows',
  'app-linux': 'Linux',
  'app-macos': 'macOS',
  'app-harmony': 'HarmonyOS',
  web: 'Web',
  android: 'Android',
  ios: 'iOS',
  windows: 'Windows',
};

// 兼容旧导出名：仍可从字典/fallback 读，供未迁移的调用点使用
export const FEEDBACK_STATUS_TEXT: Record<string, string> = FEEDBACK_STATUS_TEXT_FALLBACK;
export const FEEDBACK_TYPE_TEXT: Record<string, string> = FEEDBACK_TYPE_TEXT_FALLBACK;

/* ────────────────────────────────────────────────────────────────────────────
 * 以下为**通知**相关导出的兼容别名（实现已整体搬到 `@/api/notice`）。
 *
 * 保留原因：仓库里可能还有未迁移的引用点（如分支/插件页面），直接删除会造成编译错误。
 * 新代码一律从 `@/api/notice` 导入；这些别名只做转发，不持有任何状态。
 * ──────────────────────────────────────────────────────────────────────────── */

/** @deprecated 通知实体已迁至 `@/api/notice`，请直接从 `@/api/notice` 导入 `SysNotice`。 */
export type SysNotice = SysNoticeOfNotice;

/** @deprecated 通知管理端 API 已迁至 `@/api/notice`，请改用 `noticeAdminApi`。 */
export const messageAdminApi = noticeAdminApiOfNotice;

/** @deprecated 通知字典加载已迁至 `@/api/notice`，请改用 `loadNoticeDicts`（独立的 noticeDictCache）。 */
export const loadNoticeDicts = loadNoticeDictsOfNotice;

/** @deprecated 通知类型文案已迁至 `@/api/notice`，请改用 `noticeTypeLabel`（两档口径：公告 / 通知）。 */
export const noticeTypeLabel = noticeTypeLabelOfNotice;

/** @deprecated 通知渠道文案已迁至 `@/api/notice`，请改用 `noticeChannelLabel`。 */
export const noticeChannelLabel = noticeChannelLabelOfNotice;

/** @deprecated 通知类型下拉已迁至 `@/api/notice`，请改用 `noticeTypeOptions`（只有公告 / 通知两项）。 */
export const noticeTypeOptions = noticeTypeOptionsOfNotice;

/** @deprecated 通知渠道下拉已迁至 `@/api/notice`，请改用 `noticeChannelOptions`。 */
export const noticeChannelOptions = noticeChannelOptionsOfNotice;

/** @deprecated 通知渠道「不限平台」哨兵已迁至 `@/api/notice`，请改用 `NOTICE_CHANNEL_ALL`。 */
export const NOTICE_CHANNEL_ALL = NOTICE_CHANNEL_ALL_OF_NOTICE;

/** @deprecated 通知类型 fallback 表已迁至 `@/api/notice`，请改用 `NOTICE_TYPE_TEXT`。 */
export const NOTICE_TYPE_TEXT: Record<string, string> = NOTICE_TYPE_TEXT_OF_NOTICE;

/** @deprecated 通知渠道 fallback 表已迁至 `@/api/notice`，请改用 `NOTICE_CHANNEL_TEXT`。 */
export const NOTICE_CHANNEL_TEXT: Record<string, string> = NOTICE_CHANNEL_TEXT_OF_NOTICE;
