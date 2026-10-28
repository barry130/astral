import { request, ApiResult } from './client';

/** 消息模板（合并视图：一条记录 = 编码 + 绑定事件 + 已启用渠道集合） */
export interface MessageTemplate {
  templateCode: string;
  templateName: string;
  scene: string;
  sceneName?: string;
  channels: string[];
  subject?: string;
  content?: string;
  variables?: string;
  remark?: string;
  smsProviderTemplateCode?: string;
  smsSignName?: string;
  smsContentSample?: string;
  smsAuditStatus?: number;
  smsAuditRemark?: string;
  updateTime?: string;
}

export interface MessageTemplateSave {
  templateCode: string;
  templateName: string;
  scene: string;
  /** 改编码时传旧编码，后端据此删旧行 */
  originalCode?: string;
  /** 换绑定事件时传旧事件码 */
  originalScene?: string;
  channels: string[];
  subject?: string;
  content?: string;
  variables?: string;
  remark?: string;
  smsProviderTemplateCode?: string;
  smsSignName?: string;
  smsContentSample?: string;
  smsAuditStatus?: number;
  smsAuditRemark?: string;
}

/** 事件下拉：模板只能绑定代码注册过的事件，渠道受事件声明约束 */
export interface MessageTemplateEventOption {
  code: string;
  name: string;
  description?: string;
  channels: string[];
  payloadFields: string[];
}

export const messageTemplateApi = {
  list: (): Promise<ApiResult<MessageTemplate[]>> =>
    request.get('/api/v1/admin/system/message/template/list'),
  options: (): Promise<ApiResult<MessageTemplateEventOption[]>> =>
    request.get('/api/v1/admin/system/message/template/options'),
  save: (data: MessageTemplateSave) =>
    request.post('/api/v1/admin/system/message/template/save', data),
  remove: (templateCode: string, scene: string) =>
    request.delete('/api/v1/admin/system/message/template', { params: { templateCode, scene } }),
  preview: (content: string, variables: Record<string, string>) =>
    request.post('/api/v1/admin/system/message/template/preview', { content, variables }),
};

/** 渠道与短信报备状态的展示文案统一走数据字典（notify_delivery_channel / notify_sms_audit_status） */
