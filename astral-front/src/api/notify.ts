import { request, ApiResult } from './client';
import type { PageResult } from './types';

/**
 * 消息中心平台侧 API（订阅规则 + 站内信门户收件箱）。
 *
 * <p>站内信（INAPP 渠道）投递落统一通知表 sys_notice（点对点 + 消息中心位 +
 * 服务端 read_time），与 feedback 插件共用；广播通知/公告的管理走
 * `@/api/feedback` 的 messageAdminApi（/api/v1/admin/message），不在本文件重复。</p>
 */

/** 站内信行（sys_notice 点对点行的收件箱视图） */
export interface SysNotifyInapp {
  id?: number;
  userId?: number;
  title?: string;
  content?: string;
  /** 投递来源事件码 / manual / notice */
  scene?: string;
  readTime?: string | null;
  createTime?: string;
}

/** 事件订阅规则 */
export interface SysNotifyRule {
  id?: number;
  ruleName?: string;
  eventCode?: string;
  /** EMAIL / SMS / INAPP */
  channel?: string;
  templateId?: number;
  /** FIXED / PAYLOAD_FIELD / ROLE（按角色展开） */
  recipientType?: string;
  recipientValue?: string;
  /** INAPP 平台定向（sys_notice.channel，逗号分隔，默认 all） */
  platform?: string;
  enabled?: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 单条投递结果（发布事件逐规则回报） */
export interface NotifyPublishResult {
  ruleId?: number;
  ruleName?: string;
  channel?: string;
  recipient?: string;
  ok?: boolean;
  error?: string;
}

/** 站内信门户端（登录即可，收件箱归用户本人） */
export const inappApi = {
  myPage: (pageNum: number, pageSize: number): Promise<ApiResult<PageResult<SysNotifyInapp>>> =>
    request.get('/api/v1/all/notify/inapp/my/page', { params: { pageNum, pageSize } }),
  unreadCount: (): Promise<ApiResult<number>> =>
    request.get('/api/v1/all/notify/inapp/unread-count'),
  markRead: (id: number) => request.post(`/api/v1/all/notify/inapp/${id}/read`),
  markAllRead: () => request.post('/api/v1/all/notify/inapp/read-all'),
};

export const notifyRuleApi = {
  page: (pageNum: number, pageSize: number): Promise<ApiResult<PageResult<SysNotifyRule>>> =>
    request.get('/api/v1/admin/system/notify/rule/page', { params: { pageNum, pageSize } }),
  detail: (id: number): Promise<ApiResult<SysNotifyRule>> =>
    request.get(`/api/v1/admin/system/notify/rule/${id}`),
  create: (data: Partial<SysNotifyRule>) => request.post('/api/v1/admin/system/notify/rule', data),
  update: (id: number, data: Partial<SysNotifyRule>) => request.put(`/api/v1/admin/system/notify/rule/${id}`, data),
  remove: (id: number) => request.delete(`/api/v1/admin/system/notify/rule/${id}`),
  publish: (data: { eventCode: string; variables?: Record<string, string>; recipients?: string[] }): Promise<ApiResult<NotifyPublishResult[]>> =>
    request.post('/api/v1/admin/system/notify/publish', data),
};
