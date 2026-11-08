import { request, ApiResult } from './client';
import { dictApi, type DictOption } from './dict';
import { pickWriteFields } from './write-fields';

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
  userId?: number | null;
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
  readSeconds?: number | null;
  /**
   * 条款版本号（仅首次启动免责声明用）。
   * 客户端把「开屏位 + 此字段非空」的公告认定为首次启动免责声明，并记住已接受的版本；
   * 值一变就再弹一次，用来实现「改了条款要重新确认」。
   */
  termsVersion?: string | null;
  effectiveStart?: string | null;
  effectiveEnd?: string | null;
  versionMin?: number | null;
  versionMax?: number | null;
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

export type NoticeWrite = Partial<
  Pick<
    SysNotice,
    | 'channel'
    | 'noticeType'
    | 'userId'
    | 'display'
    | 'title'
    | 'content'
    | 'url'
    | 'isShow'
    | 'isTop'
    | 'dialogClosable'
    | 'firstLoginOnly'
    | 'marquee'
    | 'readSeconds'
    | 'termsVersion'
    | 'effectiveStart'
    | 'effectiveEnd'
    | 'versionMin'
    | 'versionMax'
    | 'audience'
  >
>;
export const noticeWritePayload = (values: NoticeWrite): NoticeWrite =>
  pickWriteFields(values, [
    'channel',
    'noticeType',
    'userId',
    'display',
    'title',
    'content',
    'url',
    'isShow',
    'isTop',
    'dialogClosable',
    'firstLoginOnly',
    'marquee',
    'readSeconds',
    'termsVersion',
    'effectiveStart',
    'effectiveEnd',
    'versionMin',
    'versionMax',
    'audience',
  ]);

/** 通知管理端 API：服务端派生事件、实体身份与时间戳不能进入编辑请求。 */
export const noticeAdminApi = {
  page: (params: Record<string, unknown>): Promise<ApiResult<NoticePage<SysNotice>>> =>
    request.get('/api/v1/admin/message/page', {
      params: pickWriteFields(params, [
        'pageNum',
        'pageSize',
        'channel',
        'noticeType',
        'keyword',
        'startDate',
        'endDate',
      ]),
    }),
  create: (data: NoticeWrite): Promise<ApiResult<SysNotice>> =>
    request.post('/api/v1/admin/message', noticeWritePayload(data)),
  update: (id: number, data: NoticeWrite): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/message/${id}`, noticeWritePayload(data)),
  delete: (id: number): Promise<ApiResult<void>> => request.delete(`/api/v1/admin/message/${id}`),
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
let noticeDictRevision = 0;

/** 拉取通知枚举字典并缓存（只拉 notice_channel / notice_type） */
export async function loadNoticeDicts(): Promise<Partial<NoticeDictCache>> {
  const ticket = ++noticeDictRevision;
  const results = await Promise.all(NOTICE_DICT_CODES.map((c) => dictApi.byCode(c)));
  const map: Record<string, DictOption[]> = {};
  results.forEach((res, i) => {
    if (res.code !== 200 || !Array.isArray(res.data) || !res.data.length)
      throw new Error(
        res.msg && res.code !== 200 ? res.msg : `通知字典缺失：${NOTICE_DICT_CODES[i]}`,
      );
    map[NOTICE_DICT_CODES[i]] = res.data.map((d) => ({
      value: String(d.dictValue),
      label: d.dictLabel,
    }));
  });
  const next = { noticeChannel: map.notice_channel, noticeType: map.notice_type };
  if (ticket === noticeDictRevision) noticeDictCache = next;
  return next;
}

/** 通知类型兜底文案：**只有两档**（`feedback` / `request` 等历史别名一律落到「通知」） */
export const NOTICE_TYPE_TEXT_FALLBACK: Record<string, string> = {
  announce: NOTICE_TYPE_ANNOUNCE,
  notice: NOTICE_TYPE_NOTICE,
};

/**
 * 通知渠道兜底文案。新值 = 投放平台（与 stat_platform 同源）+ all 哨兵；
 * 旧值是改造前的「端」枚举 app / pc / web，存量行与老客户端参数仍可能是它们。
 */
export const NOTICE_CHANNEL_TEXT_FALLBACK: Record<string, string> = Object.fromEntries(
  [
    'app-android',
    'app-ios',
    'app-windows',
    'app-linux',
    'app-macos',
    'app-harmony',
    'web',
    'all',
    'app',
    'pc',
  ].map((value) => [value, value]),
);

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
export function noticeTypeLabel(
  v?: string,
  options: ReadonlyArray<DictOption> = noticeDictCache.noticeType || [],
): string {
  if (!v) return '-';
  const key = v === 'feedback' || v === 'request' ? NOTICE_TYPE_NOTICE : v;
  return options.find((o) => o.value === key)?.label || key;
}

/** 通知渠道文案：值为逗号分隔的平台集合，逐项翻译后拼接；优先 notice_channel 字典，miss 回退 */
export function noticeChannelLabel(
  v?: string,
  options: ReadonlyArray<DictOption> = noticeDictCache.noticeChannel || [],
): string {
  if (!v) return '-';
  const parts = v
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((one) => {
      const hit = options.find((o) => o.value === one);
      return hit ? hit.label : one;
    });
  return parts.length ? parts.join(' / ') : '-';
}

/** 通知类型下拉：**固定两项**（公告 / 通知），字典只影响这两项的文案 */
export function noticeTypeOptions(options?: ReadonlyArray<DictOption>): DictOption[] {
  return [NOTICE_TYPE_ANNOUNCE, NOTICE_TYPE_NOTICE].map((value) => ({
    value,
    label: noticeTypeLabel(value, options),
  }));
}

/**
 * 通知渠道可选项：平台部分取自 `notice_channel` 字典（字典缺失时回退内置平台表），
 * 末尾追加「全部平台」（`all` 哨兵独占，不与具体平台同时出现）。
 */
export function noticeChannelOptions(
  options: ReadonlyArray<DictOption> = noticeDictCache.noticeChannel || [],
): DictOption[] {
  const platforms = options.filter((o) => o.value !== NOTICE_CHANNEL_ALL);
  return [
    ...platforms,
    { value: NOTICE_CHANNEL_ALL, label: noticeChannelLabel(NOTICE_CHANNEL_ALL, options) },
  ];
}
