import { request, ApiResult } from './client';
import type { PageResult } from './types';

/**
 * 消息中心平台侧 API（事件订阅规则 + 站内信门户收件箱）。
 *
 * <p>站内信（INAPP 渠道）投递落统一通知表 sys_notice（点对点 + 消息中心位 +
 * 服务端 read_time），与通知模块共用；广播通知/公告的管理走
 * `@/api/notice` 的 noticeAdminApi（/api/v1/admin/message），不在本文件重复。</p>
 *
 * <p>订阅规则是「事件 × 多渠道」：一条规则绑一个事件，规则下每个渠道各自配模板、
 * 启用态、每日限额（按收件人 / 全局）与平台定向，以及若干收件人
 * （固定值 / 事件载荷字段 / 角色）。后端按规则 id 整体覆盖式保存
 * （渠道删了就真删，收件人整体重建），前端不需要做 diff。</p>
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

/** 投递渠道（展示文案取字典 notify_delivery_channel） */
export type NotifyChannel = 'EMAIL' | 'SMS' | 'INAPP';

/** 收件人类型（展示文案取字典 notify_recipient_type） */
export type NotifyRecipientType = 'FIXED' | 'PAYLOAD_FIELD' | 'ROLE';

/** 每日限额口径（展示文案取字典 notify_limit_scope） */
export type NotifyLimitScope = 'RECIPIENT' | 'GLOBAL';

/** 事件载荷字段（变量补全 + PAYLOAD_FIELD 收件人的可选值） */
export interface NotifyPayloadField {
  name: string;
  description?: string;
}

/**
 * 通知事件定义（后端 NotifyEventRegistry 代码注册，管理端只读）。
 *
 * <p>`channels` 是该事件可投递的渠道，也是规则页渠道多选的取值范围；
 * `requiredChannels` 是「必达渠道」（如验证码必须保留 EMAIL），规则里不允许取消或停用；
 * `payloadFields` 用于变量自动补全。</p>
 */
export interface NotifyEventVo {
  code: string;
  name: string;
  description?: string;
  /** 该事件可投递的渠道 */
  channels?: string[];
  /** 必达渠道：不可取消、不可停用 */
  requiredChannels?: string[];
  /** 载荷字段名列表 */
  payloadKeys?: string[];
  /** 载荷字段（含说明） */
  payloadFields?: NotifyPayloadField[];
}

/** 规则下的收件人（读） */
export interface NotifyRuleRecipientVo {
  /** FIXED / PAYLOAD_FIELD / ROLE */
  type?: string;
  /** 固定值 / payload 字段名 / 角色编码 */
  value?: string;
}

/** 规则下的单渠道配置（读） */
export interface NotifyRuleChannelVo {
  id?: number;
  /** EMAIL / SMS / INAPP */
  channel?: string;
  /** 模板 ID：空 = 按「事件 × 渠道」自动匹配 */
  templateId?: number | null;
  /** 模板展示名（后端补全） */
  templateName?: string | null;
  /** 0/1 */
  enabled?: number;
  /** 每日限额，0 = 不限 */
  dailyLimit?: number;
  /** RECIPIENT 按收件人 / GLOBAL 全局 */
  limitScope?: string;
  /** 平台定向（逗号分隔，all = 全部） */
  platform?: string | null;
  recipients?: NotifyRuleRecipientVo[];
}

/** 订阅规则（读） */
export interface NotifyRuleVo {
  id?: number;
  ruleName?: string;
  eventCode?: string;
  /** 事件展示名（后端补全） */
  eventName?: string;
  remark?: string;
  /** 0/1 */
  enabled?: number;
  /** 各渠道平台的并集（列表展示用） */
  platforms?: string[];
  updateTime?: string;
  channels?: NotifyRuleChannelVo[];
}

/** 规则渠道可选的模板（后端按「事件 × 渠道」过滤，id 即保存时的 templateId） */
export interface NotifyTemplateOption {
  id: number;
  templateCode?: string;
  templateName?: string;
}

/** 规则分页查询条件 */
export interface NotifyRuleQuery {
  eventCode?: string;
  /** 0/1 */
  enabled?: number;
  /** 规则名模糊 */
  keyword?: string;
}

/** 规则收件人（写） */
export interface NotifyRuleRecipientSave {
  type: NotifyRecipientType;
  value: string;
}

/** 规则单渠道配置（写） */
export interface NotifyRuleChannelSave {
  channel: NotifyChannel;
  /** 空 = 按事件 + 渠道自动匹配模板 */
  templateId?: number | null;
  /** 0/1，缺省启用 */
  enabled?: number;
  /** 每日限额，0 = 不限 */
  dailyLimit?: number;
  /** 限额口径，缺省 RECIPIENT */
  limitScope?: NotifyLimitScope;
  /** 平台定向（逗号分隔） */
  platform?: string;
  recipients: NotifyRuleRecipientSave[];
}

/** 订阅规则保存体（新增/更新共用，整体覆盖） */
export interface NotifyRuleSaveDto {
  ruleName: string;
  eventCode: string;
  remark?: string;
  /** 0/1 */
  enabled?: number;
  channels: NotifyRuleChannelSave[];
}

/** 发布测试入参 */
export interface NotifyPublishRequest {
  eventCode: string;
  /** 事件上下文（payload 字段 → 值） */
  variables?: Record<string, string>;
  /** 非空时替代规则收件人试投 */
  recipients?: string[];
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
  /** 规则分页（渠道已聚合、模板名已补全） */
  page: (
    pageNum: number,
    pageSize: number,
    query?: NotifyRuleQuery,
  ): Promise<ApiResult<PageResult<NotifyRuleVo>>> =>
    request.get('/api/v1/admin/system/notify/rule/page', { params: { pageNum, pageSize, ...query } }),
  detail: (id: number): Promise<ApiResult<NotifyRuleVo>> =>
    request.get(`/api/v1/admin/system/notify/rule/${id}`),
  create: (data: NotifyRuleSaveDto): Promise<ApiResult<number>> =>
    request.post('/api/v1/admin/system/notify/rule', data),
  update: (id: number, data: NotifyRuleSaveDto): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/notify/rule/${id}`, data),
  remove: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/system/notify/rule/${id}`),
  /** 事件定义（渠道范围 / 必达渠道 / 变量补全） */
  events: (): Promise<ApiResult<NotifyEventVo[]>> =>
    request.get('/api/v1/admin/system/notify/event'),
  /**
   * 指定「事件 × 渠道」下可选的模板。
   *
   * <p>EMAIL / INAPP 查 sys_mail_template(scene=eventCode, channel)，SMS 查
   * sys_sms_template(event_code=eventCode)；返回的 id 就是规则保存用的 templateId。</p>
   */
  templates: (eventCode: string, channel: string): Promise<ApiResult<NotifyTemplateOption[]>> =>
    request.get('/api/v1/admin/system/notify/template', { params: { eventCode, channel } }),
  /** 发布测试：按启用规则逐条投递并逐规则回报 */
  publish: (data: NotifyPublishRequest): Promise<ApiResult<NotifyPublishResult[]>> =>
    request.post('/api/v1/admin/system/notify/publish', data),
};
