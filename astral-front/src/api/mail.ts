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

export const mailApi = {
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
