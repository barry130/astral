import { request, ApiResult } from './client';
import type { PageResult } from './types';

/**
 * 消息统计（三渠道统一视图）。
 *
 * <p>后端把邮件日志（sys_mail_log）、短信日志（sys_sms_log）、站内信（sys_notice）
 * 聚合成一个「消息」概念：EMAIL / SMS / INAPP 三个渠道共用同一组筛选条件，
 * 概览、趋势、明细三者的口径因此永远一致。站内信没有失败态，失败数恒为 0。</p>
 */

/** 投递渠道（展示文案取数据字典 notify_delivery_channel） */
export type MessageChannel = 'EMAIL' | 'SMS' | 'INAPP';

/**
 * 统计/明细的公共筛选条件（三个接口共用）。
 *
 * <p>时间范围对 EMAIL/SMS 用 send_time、对 INAPP 用 create_time；
 * 时间格式固定 `yyyy-MM-dd HH:mm:ss`。</p>
 */
export interface MessageStatisticsQuery {
  /** 起始时间（含），yyyy-MM-dd HH:mm:ss */
  start?: string;
  /** 结束时间（含），yyyy-MM-dd HH:mm:ss */
  end?: string;
  /** 投递渠道 EMAIL / SMS / INAPP；空 = 全部 */
  channel?: string;
  /** 场景（事件码，精确匹配） */
  scene?: string;
  /** 1 成功 / 0 失败；站内信恒为成功，传 0 时站内信不参与统计 */
  status?: number;
  /** 发送方插件；站内信固定归为 feedback */
  pluginId?: string;
  /** 关键字：模糊匹配收件人 / 标题 / 主题 / 正文 */
  keyword?: string;
  /** 趋势窗口天数：默认 7，上限 90（仅趋势接口使用） */
  days?: number;
  /** 页码，从 1 开始（仅明细接口使用） */
  pageNum?: number;
  /** 每页条数，上限 200（仅明细接口使用） */
  pageSize?: number;
}

/** 单渠道计数 */
export interface ChannelStat {
  /** EMAIL / SMS / INAPP */
  channel?: string;
  total: number;
  success: number;
  fail: number;
}

/** 单场景计数（scene 为事件码，库里为空时归为 unknown） */
export interface SceneStat {
  scene?: string;
  total: number;
  success: number;
  fail: number;
}

/** 单插件计数（pluginId，库里为空时归为 unknown） */
export interface PluginStat {
  pluginId?: string;
  total: number;
  success: number;
  fail: number;
}

/**
 * 统计概览。
 *
 * <p>所有数字都产自同一组筛选条件下的同一个行集，因此 total/success/fail 与
 * channels、scenes、plugins 三个 breakdown 必然自洽（各 breakdown 之和 = total）。</p>
 */
export interface MessageStatistics {
  /** 命中筛选的投递总数 */
  total?: number;
  /** 成功数 */
  success?: number;
  /** 失败数（站内信恒为 0） */
  fail?: number;
  /** 成功率 0-100，保留 1 位小数 */
  successRate?: number;
  /** 今日投递数 */
  todayTotal?: number;
  /** 今日成功数 */
  todaySuccess?: number;
  /** 今日失败数 */
  todayFail?: number;
  /** 按渠道聚合，顺序固定 EMAIL / SMS / INAPP */
  channels?: ChannelStat[];
  /** 按场景（事件码）聚合，按总量倒序 */
  scenes?: SceneStat[];
  /** 按发送方插件聚合，按总量倒序 */
  plugins?: PluginStat[];
}

/** 趋势单点：某一天 × 某渠道（稠密网格，无数据的天也会有一行 0） */
export interface TrendPoint {
  /** 日期 yyyy-MM-dd */
  date: string;
  /** EMAIL / SMS / INAPP */
  channel?: string;
  total: number;
  success: number;
  /** 站内信恒为 0 */
  fail: number;
}

/** 单条投递明细（三渠道合并行） */
export interface MessageDelivery {
  /** 源表主键（跨渠道不唯一，仅用作前端 key） */
  id?: number;
  /** EMAIL / SMS / INAPP */
  channel?: string;
  /** 场景（事件码），为空时后端归为 unknown */
  scene?: string;
  /** 收件人：邮箱 / 手机号 / 用户 ID / all（站内信广播） */
  recipient?: string;
  /** 标题：邮件主题，或短信/站内信正文截断 */
  title?: string;
  /** 1 成功 / 0 失败（站内信恒为 1） */
  status?: number;
  /** 状态文案：成功 / 失败 */
  statusText?: string;
  /** 失败原因（成功时为空） */
  errorMsg?: string;
  /** 发送时间 yyyy-MM-dd HH:mm:ss */
  sendTime?: string;
  /** 发送方插件 */
  pluginId?: string;
}

const BASE = '/api/v1/admin/system/message/statistics';

/** 消息统计 API（邮件 + 短信 + 站内信合并） */
export const messageStatisticsApi = {
  /** 概览：总量/成功率/今日 + 渠道/场景/插件三个 breakdown */
  overview: (params: MessageStatisticsQuery): Promise<ApiResult<MessageStatistics>> =>
    request.get(`${BASE}/overview`, { params }),

  /** 按天 × 渠道趋势（稠密网格，days 默认 7、上限 90） */
  trend: (params: MessageStatisticsQuery): Promise<ApiResult<TrendPoint[]>> =>
    request.get(`${BASE}/trend`, { params }),

  /** 合并分页明细（三渠道按发送时间倒序合并后切页） */
  list: (params: MessageStatisticsQuery): Promise<ApiResult<PageResult<MessageDelivery>>> =>
    request.get(`${BASE}/list`, { params }),
};
