import { request, ApiResult } from './client';
import type { PageResult } from './types';

/** 邮箱账户 */
export interface MailAccount {
  id?: number;
  accountName?: string;
  smtpHost?: string;
  smtpPort?: number;
  username?: string;
  password?: string;
  fromAddr?: string;
  fromName?: string;
  sslEnable?: number;
  starttlsEnable?: number;
  enabled?: number;
  weight?: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 邮件模板 */
export interface MailTemplate {
  id?: number;
  templateCode?: string;
  templateName?: string;
  subject?: string;
  content?: string;
  variables?: string;
  scene?: string;
  /** 投递渠道（EMAIL / INAPP；SMS 模板在 sys_sms_template） */
  channel?: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 邮件发送记录 */
export interface MailLog {
  id?: number;
  accountId?: number;
  pluginId?: string;
  scene?: string;
  toEmail?: string;
  subject?: string;
  content?: string;
  status?: number;
  errorMsg?: string;
  sendTime?: string;
  createTime?: string;
}

/** 插件发信授权 */
export interface MailPluginAuth {
  id?: number;
  pluginId?: string;
  pluginName?: string;
  dailyLimit?: number;
  allowedScenes?: string;
  enabled?: number;
  createTime?: string;
  updateTime?: string;
}

/** 邮件统计概览 */
export interface MailStatistics {
  total?: number;
  success?: number;
  fail?: number;
  todayTotal?: number;
  todaySuccess?: number;
  todayFail?: number;
}

/** 通知事件的 payload 字段声明（模板里可用的 ${占位符}） */
export interface NotifyEventField {
  name: string;
  description?: string;
}

/** 通知事件定义（后端 NotifyEventRegistry 代码注册，管理端只读） */
export interface NotifyEventDef {
  code: string;
  name: string;
  description?: string;
  /** 事件携带的上下文字段，模板 variables 必须覆盖全部字段名 */
  payloadFields?: NotifyEventField[];
  /** 事件可投递的渠道（NotifyChannel 注册值） */
  channels?: string[];
  /** 站内信类型标签回退值（announce/feedback/request）；payload 的 noticeType meta 字段优先 */
  noticeType?: string;
}

export const mailApi = {
  // ===== 通知事件/渠道（代码注册表） =====
  notifyEventList: (): Promise<ApiResult<NotifyEventDef[]>> =>
    request.get('/api/v1/admin/system/notify/event/list'),
  notifyChannelList: (): Promise<ApiResult<string[]>> =>
    request.get('/api/v1/admin/system/notify/channel/list'),

  // ===== 邮箱账户 =====
  accountPage: (pageNum: number, pageSize: number) =>
    request.get('/api/v1/admin/system/mail/account/page', { params: { pageNum, pageSize } }),
  accountDetail: (id: number): Promise<ApiResult<MailAccount>> =>
    request.get(`/api/v1/admin/system/mail/account/${id}`),
  accountCreate: (data: Partial<MailAccount>) =>
    request.post('/api/v1/admin/system/mail/account', data),
  accountUpdate: (id: number, data: Partial<MailAccount>) =>
    request.put(`/api/v1/admin/system/mail/account/${id}`, data),
  accountDelete: (id: number) =>
    request.delete(`/api/v1/admin/system/mail/account/${id}`),
  accountToggle: (id: number, enabled: number) =>
    request.post(`/api/v1/admin/system/mail/account/${id}/toggle`, null, { params: { enabled } }),
  accountTest: (accountId: number, toEmail: string) =>
    request.post('/api/v1/admin/system/mail/account/test', null, { params: { accountId, toEmail } }),

  // ===== 邮件模板 =====
  templatePage: (pageNum: number, pageSize: number) =>
    request.get('/api/v1/admin/system/mail/template/page', { params: { pageNum, pageSize } }),
  templateDetail: (id: number): Promise<ApiResult<MailTemplate>> =>
    request.get(`/api/v1/admin/system/mail/template/${id}`),
  templateCreate: (data: Partial<MailTemplate>) =>
    request.post('/api/v1/admin/system/mail/template', data),
  templateUpdate: (id: number, data: Partial<MailTemplate>) =>
    request.put(`/api/v1/admin/system/mail/template/${id}`, data),
  templateDelete: (id: number) =>
    request.delete(`/api/v1/admin/system/mail/template/${id}`),
  templatePreview: (templateId: number, variables: Record<string, string>) =>
    request.post('/api/v1/admin/system/mail/template/preview', { templateId, variables }),
  templateSend: (id: number, data: { toEmail: string; accountId?: number; variables?: Record<string, string> }) =>
    request.post(`/api/v1/admin/system/mail/template/${id}/send`, data),

  // ===== 邮件日志 =====
  logPage: (params: {
    pageNum: number; pageSize: number;
    accountId?: number; pluginId?: string; toEmail?: string; status?: number;
    start?: string; end?: string;
  }): Promise<ApiResult<PageResult<MailLog>>> =>
    request.get('/api/v1/admin/system/mail/log/page', { params }),
  logStatistics: (): Promise<ApiResult<MailStatistics>> =>
    request.get('/api/v1/admin/system/mail/log/statistics'),

  // ===== 插件发信授权 =====
  pluginAuthList: (): Promise<ApiResult<MailPluginAuth[]>> =>
    request.get('/api/v1/admin/system/mail/plugin-auth/list'),
  pluginAuthCreate: (data: Partial<MailPluginAuth>) =>
    request.post('/api/v1/admin/system/mail/plugin-auth', data),
  pluginAuthUpdate: (id: number, data: Partial<MailPluginAuth>) =>
    request.put(`/api/v1/admin/system/mail/plugin-auth/${id}`, data),
  pluginAuthDelete: (id: number) =>
    request.delete(`/api/v1/admin/system/mail/plugin-auth/${id}`),
};
