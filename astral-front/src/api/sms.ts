import { request, ApiResult } from './client';
import type { PageResult } from './types';

/** 短信供应商 */
export interface SmsProvider {
  id?: number;
  providerName?: string;
  /** MOCK（本地测试）/ ALIYUN（阿里云短信） */
  providerType?: string;
  accessKey?: string;
  /** 出参永远为 null（后端抹除）；编辑留空 = 不修改原密钥 */
  accessSecret?: string;
  signName?: string;
  region?: string;
  endpoint?: string;
  enabled?: number;
  weight?: number;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 短信模板（供应商侧映射：正文在供应商备案，本地存映射与审核态） */
export interface SmsTemplate {
  id?: number;
  templateCode?: string;
  templateName?: string;
  /** 绑定的通知事件码（NotifyEventRegistry） */
  eventCode?: string;
  /** 供应商侧模板编码（如阿里云 SMS_123456789） */
  providerTemplateCode?: string;
  /** 空则回退供应商配置的签名 */
  signName?: string;
  /** 供应商备案正文留档（仅供核对，本地不渲染） */
  contentSample?: string;
  /** 字典 notify_sms_audit_status：0草稿/1审核中/2已通过/3已拒绝 */
  auditStatus?: number;
  auditRemark?: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 短信发送日志 */
export interface SmsLog {
  id?: number;
  providerId?: number;
  pluginId?: string;
  scene?: string;
  phone?: string;
  /** 变量 JSON 透传留档（正文在供应商侧） */
  content?: string;
  status?: number;
  errorMsg?: string;
  sendTime?: string;
}

export const smsApi = {
  // ===== 供应商 =====
  providerPage: (pageNum: number, pageSize: number): Promise<ApiResult<PageResult<SmsProvider>>> =>
    request.get('/api/v1/admin/system/sms/provider/page', { params: { pageNum, pageSize } }),
  providerDetail: (id: number): Promise<ApiResult<SmsProvider>> =>
    request.get(`/api/v1/admin/system/sms/provider/${id}`),
  providerTypes: (): Promise<ApiResult<string[]>> =>
    request.get('/api/v1/admin/system/sms/provider/types'),
  providerCreate: (data: Partial<SmsProvider>) =>
    request.post('/api/v1/admin/system/sms/provider', data),
  providerUpdate: (id: number, data: Partial<SmsProvider>) =>
    request.put(`/api/v1/admin/system/sms/provider/${id}`, data),
  providerDelete: (id: number) =>
    request.delete(`/api/v1/admin/system/sms/provider/${id}`),

  // ===== 模板 =====
  templatePage: (pageNum: number, pageSize: number): Promise<ApiResult<PageResult<SmsTemplate>>> =>
    request.get('/api/v1/admin/system/sms/template/page', { params: { pageNum, pageSize } }),
  templateDetail: (id: number): Promise<ApiResult<SmsTemplate>> =>
    request.get(`/api/v1/admin/system/sms/template/${id}`),
  templateCreate: (data: Partial<SmsTemplate>) =>
    request.post('/api/v1/admin/system/sms/template', data),
  templateUpdate: (id: number, data: Partial<SmsTemplate>) =>
    request.put(`/api/v1/admin/system/sms/template/${id}`, data),
  templateDelete: (id: number) =>
    request.delete(`/api/v1/admin/system/sms/template/${id}`),
  templateSend: (id: number, data: { phone: string; providerId?: number; variables?: Record<string, string> }) =>
    request.post(`/api/v1/admin/system/sms/template/${id}/send`, data),

  // ===== 日志 =====
  logPage: (params: {
    pageNum: number; pageSize: number;
    phone?: string; pluginId?: string; status?: number;
  }): Promise<ApiResult<PageResult<SmsLog>>> =>
    request.get('/api/v1/admin/system/sms/log/page', { params }),
};
