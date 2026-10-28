import { request, ApiResult } from './client';
import { dictApi } from './dict';
import type { DictOption } from './dict';

/**
 * 站内通知（`sys_notice`）类型 / API / 字典 / 标签 —— 从 `@/api/feedback` 切出的独立模块。
 *
 * <p>口径分工（见 `docs/NOTICE_FEEDBACK_SPLIT_PLAN.md` §4.6）：**通知是通道，反馈是业务**。
 * 通知只关心「广播（公告）还是点对点（通知）」，因此这里的类型**只有两档**：
 * `announce` = 公告、`notice` = 通知；历史值 `feedback` / `request` 作为别名一律归并显示为
 * 「通知」，通知侧不再出现「反馈」「需求」字样。反馈的「问题 / 需求」细分留在
 * `@/api/feedback` 的 `feedbackTypeLabel`（数据源 `sys_feedback.type` + `feedback_type` 字典）。</p>
 *
 * <p>请求路径与后端 `AdminNoticeController` 保持一致（`/api/v1/admin/message/**`，拆分前后不变）。</p>
 */

/** 通知分页结果（后端返回 records/total/size/current） */
export interface NoticePage<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages?: number;
}

/** 统一通知实体（sys_notice） */
export interface SysNotice {
  id?: number;
  /** 投放平台：逗号分隔（app-android / app-ios / app-windows / app-linux / app-macos / app-harmony / web），或 all=不限平台 */
  channel?: string;
  /** announce=公告（广播）｜ notice=通知（点对点）；历史值 feedback/request 归并显示为「通知」 */
  noticeType?: string;
  /** 点对点目标用户ID（NULL=广播） */
  userId?: number;
  feedbackId?: number;
  /** 通知来源事件，由服务端写入 */
  scene?: string;
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
  /** 开屏弹窗读秒（仅首次启动免责声明用；NULL/0 = 不读秒，确认键立即可点） */
  readSeconds?: number;
  /**
   * 条款版本号（仅首次启动免责声明用）。
   * 客户端把「开屏位 + 此字段非空」的公告认定为首次启动免责声明，并记住已接受的版本；
   * 值一变就再弹一次，用来实现「改了条款要重新确认」。
   */
  termsVersion?: string;
  effectiveStart?: string;
  effectiveEnd?: string;
  versionMin?: number;
  versionMax?: number;
  audience?: string;
  createTime?: string;
  updateTime?: string;
}

/** 通知类型：公告（广播，`user_id IS NULL`） */
export const NOTICE_TYPE_ANNOUNCE = 'announce';

/** 通知类型：通知（点对点站内信，`user_id` 非空） */
export const NOTICE_TYPE_NOTICE = 'notice';

/** 通知渠道「不限平台」哨兵值：与后端 `NoticeTargets.ALL` 一致，不参与平台字典 */
export const NOTICE_CHANNEL_ALL = 'all';

/** 通知管理端 API */
export const noticeAdminApi = {
  /** 分页（channel/noticeType/keyword/时间） */
  page: (params: Record<string, any>): Promise<ApiResult<NoticePage<SysNotice>>> =>
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

/**
 * 通知自身枚举字典编码。
 *
 * <p>只拉这两份：`notice_type`（公告 / 通知）与 `notice_channel`（投放平台 + all 哨兵）。
 * 平台取值与 `stat_platform` 同源，但**通知侧不再依赖 `stat_platform` 字典**——
 * 此前渠道下拉要同时预热 feedback 的 5 份字典（含统计平台），通知模块独立后只需自己的两份。</p>
 */
export const NOTICE_DICT_CODES = ['notice_channel', 'notice_type'] as const;

interface NoticeDictCache {
  noticeChannel: DictOption[];
  noticeType: DictOption[];
}

/** 通知字典缓存（页面挂载时 loadNoticeDicts 预热；与 feedback 模块的缓存相互独立） */
let noticeDictCache: Partial<NoticeDictCache> = {};

/** 拉取通知枚举字典并缓存（只拉 notice_channel / notice_type） */
export async function loadNoticeDicts(): Promise<Partial<NoticeDictCache>> {
  const map: Record<string, DictOption[]> = {};
  const results = await Promise.all(NOTICE_DICT_CODES.map((c) => dictApi.byCode(c)));
  results.forEach((res, i) => {
    map[NOTICE_DICT_CODES[i]] = (res.data || []).map((d) => ({ value: String(d.dictValue), label: d.dictLabel }));
  });
  noticeDictCache = {
    noticeChannel: map.notice_channel || [],
    noticeType: map.notice_type || [],
  };
  return noticeDictCache;
}

/** 通知类型兜底文案：**只有两档**（`feedback` / `request` 等历史别名一律落到「通知」） */
export const NOTICE_TYPE_TEXT_FALLBACK: Record<string, string> = {
  announce: '公告',
  notice: '通知',
};

/**
 * 通知渠道兜底文案。新值 = 投放平台（与 stat_platform 同源）+ all 哨兵；
 * 旧值是改造前的「端」枚举 app / pc / web，存量行与老客户端参数仍可能是它们。
 */
export const NOTICE_CHANNEL_TEXT_FALLBACK: Record<string, string> = {
  'app-android': 'Android',
  'app-ios': 'iOS',
  'app-windows': 'Windows',
  'app-linux': 'Linux',
  'app-macos': 'macOS',
  'app-harmony': 'HarmonyOS',
  web: 'Web',
  all: '全部平台',
  app: 'App（旧）',
  pc: 'PC（旧）',
};

/** 兼容旧导出名（历史上由 `@/api/feedback` 暴露，语义同上的 fallback 表） */
export const NOTICE_TYPE_TEXT: Record<string, string> = NOTICE_TYPE_TEXT_FALLBACK;
export const NOTICE_CHANNEL_TEXT: Record<string, string> = NOTICE_CHANNEL_TEXT_FALLBACK;

/**
 * 通知类型文案：优先 `notice_type` 字典，miss 回退兜底表。
 *
 * <p>**两档口径**：`announce` → 公告；其余一切（`notice` / 历史 `feedback` / `request` / 未知值）
 * → 通知。历史别名行不受字典影响（即便字典里 `feedback`/`request` 仍有残留也不显示「反馈」「需求」），
 * 展示层只认「广播 / 点对点」这一层语义。</p>
 */
export function noticeTypeLabel(v?: string): string {
  if (!v) return '-';
  const key = v === NOTICE_TYPE_ANNOUNCE ? NOTICE_TYPE_ANNOUNCE : NOTICE_TYPE_NOTICE;
  const hit = noticeDictCache.noticeType?.find((o) => o.value === key);
  return hit ? hit.label : (NOTICE_TYPE_TEXT_FALLBACK[key] || NOTICE_TYPE_TEXT_FALLBACK[NOTICE_TYPE_NOTICE]);
}

/** 通知渠道文案：值为逗号分隔的平台集合，逐项翻译后拼接；优先 notice_channel 字典，miss 回退 */
export function noticeChannelLabel(v?: string): string {
  if (!v) return '-';
  const parts = v
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((one) => {
      const hit = noticeDictCache.noticeChannel?.find((o) => o.value === one);
      return hit ? hit.label : (NOTICE_CHANNEL_TEXT_FALLBACK[one] || one);
    });
  return parts.length ? parts.join(' / ') : '-';
}

/** 通知类型下拉：**固定两项**（公告 / 通知），字典只影响这两项的文案 */
export function noticeTypeOptions(): DictOption[] {
  return [NOTICE_TYPE_ANNOUNCE, NOTICE_TYPE_NOTICE].map((value) => ({ value, label: noticeTypeLabel(value) }));
}

/**
 * 通知渠道可选项：平台部分取自 `notice_channel` 字典（字典缺失时回退内置平台表），
 * 末尾追加「全部平台」（`all` 哨兵独占，不与具体平台同时出现）。
 */
export function noticeChannelOptions(): DictOption[] {
  const platforms = noticeDictCache.noticeChannel?.length
    ? noticeDictCache.noticeChannel.filter((o) => o.value !== NOTICE_CHANNEL_ALL)
    : Object.entries(NOTICE_CHANNEL_TEXT_FALLBACK)
        .filter(([v]) => v.includes('-') || v === 'web')
        .map(([value, label]) => ({ value, label }));
  return [...platforms, { value: NOTICE_CHANNEL_ALL, label: '全部平台' }];
}
