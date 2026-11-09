import { request, ApiResult } from './client';
import { pickWriteFields } from './write-fields';

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

/** 事件下拉：绑定代码注册事件；声明渠道用于提示，允许预配置其他已注册渠道。 */
export interface MessageTemplateEventOption {
  code: string;
  name: string;
  description?: string;
  channels: string[];
  payloadFields: string[];
}

export function parseTemplateVariables(raw?: string): string[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw || '[]');
  } catch {
    throw new Error('变量声明必须是 JSON 字符串数组');
  }
  if (!Array.isArray(parsed) || parsed.some((key: unknown) => typeof key !== 'string'))
    throw new Error('变量声明必须是 JSON 字符串数组');
  return parsed.filter((key: unknown): key is string => typeof key === 'string');
}

export function mergeTemplateVariables(
  raw: string | undefined,
  texts: ReadonlyArray<string | undefined>,
): string {
  const used = texts.flatMap((text) =>
    text ? Array.from(text.matchAll(/\$\{([A-Za-z0-9_.-]+)\}/g), (match) => match[1]) : [],
  );
  return JSON.stringify([...new Set([...parseTemplateVariables(raw), ...used])]);
}

export function messageTemplatePayload(values: MessageTemplateSave): MessageTemplateSave {
  const payload = pickWriteFields(values, [
    'templateCode',
    'templateName',
    'scene',
    'originalCode',
    'originalScene',
    'channels',
    'subject',
    'content',
    'variables',
    'remark',
    'smsProviderTemplateCode',
    'smsSignName',
    'smsContentSample',
    'smsAuditStatus',
    'smsAuditRemark',
  ]);
  if (
    typeof payload.templateCode !== 'string' ||
    typeof payload.templateName !== 'string' ||
    typeof payload.scene !== 'string' ||
    !Array.isArray(payload.channels) ||
    !payload.channels.length ||
    payload.channels.some((channel) => typeof channel !== 'string')
  )
    throw new Error('模板字段或渠道不完整');
  if (payload.channels.some((channel) => channel === 'EMAIL' || channel === 'INAPP')) {
    payload.variables = mergeTemplateVariables(payload.variables, [
      payload.subject,
      payload.content,
    ]);
  } else {
    delete payload.subject;
    delete payload.content;
    delete payload.variables;
  }
  return {
    ...payload,
    templateCode: payload.templateCode,
    templateName: payload.templateName,
    scene: payload.scene,
    channels: [...payload.channels],
  };
}

/** Capture preview input at intent time; no editor state or timer participates in the request. */
export async function previewMessageTemplate(
  input: Pick<MessageTemplate, 'content' | 'variables'>,
  fields: ReadonlyArray<string> = [],
): Promise<string> {
  const content = input.content;
  if (!content?.trim()) throw new Error('模板正文为空');
  const keys = [
    ...new Set([
      ...fields,
      ...parseTemplateVariables(mergeTemplateVariables(input.variables, [content])),
    ]),
  ];
  const variables: Record<string, string> = Object.fromEntries(keys.map((key) => [key, '示例值']));
  const response = await messageTemplateApi.preview(content, variables);
  if (response.code !== 200) throw new Error(response.msg || '渲染预览失败');
  if (typeof response.data !== 'string') throw new Error('模板预览数据缺失');
  return response.data;
}

export const messageTemplateApi = {
  /** 模板合并视图列表 */
  list: (): Promise<ApiResult<MessageTemplate[]>> =>
    request.get<MessageTemplate[]>('/api/v1/admin/system/message/template/list'),
  /** 事件下拉（事件由后端代码注册，前端只读） */
  options: (): Promise<ApiResult<MessageTemplateEventOption[]>> =>
    request.get<MessageTemplateEventOption[]>('/api/v1/admin/system/message/template/options'),
  /** 保存（新增/更新同一入口）；失败抛业务错误，由调用处提示 */
  save: (data: MessageTemplateSave): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/system/message/template/save', messageTemplatePayload(data)),
  /** 按 编码×事件 删除 */
  remove: (templateCode: string, scene: string): Promise<ApiResult<void>> =>
    request.delete('/api/v1/admin/system/message/template', {
      params: { templateCode, scene },
    }),
  /** 预览渲染：返回渲染后的 HTML 文本，不是对象 */
  preview: (content: string, variables: Record<string, string>): Promise<ApiResult<string>> =>
    // Literal placeholder keys must survive axios config merging (including own __proto__ keys).
    request.post<string>(
      '/api/v1/admin/system/message/template/preview',
      JSON.stringify({ content, variables }),
    ),
};

/** 渠道与短信报备状态的展示文案统一走数据字典（notify_delivery_channel / notify_sms_audit_status） */
