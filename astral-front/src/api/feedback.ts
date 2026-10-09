import { request, ApiResult } from './client';
import { dictApi, DictOption } from './dict';

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

/** 统一通知实体 */
export interface SysNotice {
  id?: number;
  /** 投放平台：逗号分隔（app-android / app-ios / app-windows / app-linux / app-macos / web），或 all=不限平台 */
  channel?: string;
  /** announce | feedback | request */
  noticeType?: string;
  /** 点对点目标用户ID（NULL=广播） */
  userId?: number;
  feedbackId?: number;
  /** 位掩码：1开屏 2通告栏 4消息中心 */
  display?: number;
  title?: string;
  content?: string;
  url?: string;
  isShow?: number;
  isTop?: number;
  dialogClosable?: number;
  firstLoginOnly?: number;
  marquee?: number;
  effectiveStart?: string;
  effectiveEnd?: string;
  versionMin?: number;
  versionMax?: number;
  audience?: string;
  createTime?: string;
  updateTime?: string;
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

/** 通知管理端 API */
export const messageAdminApi = {
  /** 分页（channel/noticeType/keyword/时间） */
  page: (params: Record<string, any>): Promise<ApiResult<FeedbackPage<SysNotice>>> =>
    request.get('/api/v1/admin/message/page', { params }),

  /** 发通知/公告 */
  create: (data: SysNotice): Promise<ApiResult<SysNotice>> =>
    request.post('/api/v1/admin/message', data),

  /** 编辑通知 */
  update: (id: number, data: SysNotice): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/message/${id}`, data),

  /** 删除通知 */
  delete: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/message/${id}`),
};

/** 反馈插件枚举字典编码（对应 dict-init.sql 中 feedback_status/feedback_type/notice_channel/notice_type/stat_platform） */
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

/** 通知类型文案：优先字典，miss 回退 */
export function noticeTypeLabel(v?: string): string {
  if (!v) return '-';
  const hit = dictCache.noticeType?.find((o) => o.value === v);
  return hit ? hit.label : (NOTICE_TYPE_TEXT_FALLBACK[v] || v);
}

/** 通知渠道文案：值为逗号分隔的平台集合，逐项翻译后拼接；优先字典，miss 回退 */
export function noticeChannelLabel(v?: string): string {
  if (!v) return '-';
  const parts = v
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((one) => {
      const hit = dictCache.platform?.find((o) => o.value === one)
        || dictCache.noticeChannel?.find((o) => o.value === one);
      return hit ? hit.label : (NOTICE_CHANNEL_TEXT_FALLBACK[one] || one);
    });
  return parts.length ? parts.join(' / ') : '-';
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
export function noticeTypeOptions(): DictOption[] {
  return dictCache.noticeType?.length
    ? dictCache.noticeType
    : Object.entries(NOTICE_TYPE_TEXT_FALLBACK).map(([value, label]) => ({ value, label }));
}
/** 通知渠道「不限平台」哨兵值：与后端 NoticeChannel.ALL 一致，不参与 stat_platform 字典 */
export const NOTICE_CHANNEL_ALL = 'all';

/** 通知渠道可选项：平台部分复用 stat_platform 字典（与统计/反馈同一份），末尾追加「全部平台」 */
export function noticeChannelOptions(): DictOption[] {
  const platforms = dictCache.platform?.length
    ? dictCache.platform
    : Object.entries(FEEDBACK_PLATFORM_TEXT_FALLBACK)
        .filter(([v]) => v.includes('-') || v === 'web')
        .map(([value, label]) => ({ value, label }));
  return [...platforms, { value: NOTICE_CHANNEL_ALL, label: '全部平台' }];
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
const NOTICE_TYPE_TEXT_FALLBACK: Record<string, string> = {
  announce: '公告',
  feedback: '反馈',
  request: '需求',
};
/**
 * 通知渠道兜底文案。新值 = 投放平台（与 stat_platform 同源）+ all 哨兵；
 * 旧值是改造前的「端」枚举 app / pc / web，存量行与老客户端参数仍可能是它们。
 */
const NOTICE_CHANNEL_TEXT_FALLBACK: Record<string, string> = {
  'app-android': 'Android',
  'app-ios': 'iOS',
  'app-windows': 'Windows',
  'app-linux': 'Linux',
  'app-macos': 'macOS',
  web: 'Web',
  all: '全部平台',
  app: 'App（旧）',
  pc: 'PC（旧）',
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
  web: 'Web',
  android: 'Android',
  ios: 'iOS',
  windows: 'Windows',
};

// 兼容旧导出名：仍可从字典/fallback 读，供未迁移的调用点使用
export const FEEDBACK_STATUS_TEXT: Record<string, string> = FEEDBACK_STATUS_TEXT_FALLBACK;
export const FEEDBACK_TYPE_TEXT: Record<string, string> = FEEDBACK_TYPE_TEXT_FALLBACK;
export const NOTICE_TYPE_TEXT: Record<string, string> = NOTICE_TYPE_TEXT_FALLBACK;
export const NOTICE_CHANNEL_TEXT: Record<string, string> = NOTICE_CHANNEL_TEXT_FALLBACK;
