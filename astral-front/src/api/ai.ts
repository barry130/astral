import { request, ApiRequestConfig } from './client';

/**
 * AI 模块前端接口层。
 *
 * 契约来源：`astral-plugin` 的 `com.astral.ai.controller` 与 `com.astral.ai.dto`。
 * - 管理端配置：`/api/v1/admin/ai/**`（提供方、模型、路由、智能体、提示词、知识库、工具、额度、用量、请求、审计、总览）
 * - 管理端使用：`/api/v1/admin/ai/**`（智能体列表、会话、消息、事件、流、取消、反馈、动作）
 *
 * 所有枚举取值一律走数据字典（见 `/api/v1/admin/system/dict/data/byCode`），
 * 本文件只声明字段形状，不内置中文文案。
 */

/* ==================== 通用 ==================== */

/** 分页结果（后端部分接口直接返回 Map，此处统一建模） */
export interface PageResult<T> {
  items?: T[];
  total?: number;
  pageNum?: number;
  pageSize?: number;
}

/** 校验问题 */
export interface ValidationIssue {
  code?: string;
  level?: string;
  message?: string;
  field?: string;
}

/** 校验结果 */
export interface ValidationResult {
  valid?: boolean;
  issues?: ValidationIssue[];
}

/* ==================== 提供方 ==================== */

/** AI 提供方 */
export interface AiProvider {
  id?: number;
  providerCode?: string;
  name?: string;
  providerType?: string;
  baseUrl?: string;
  enabled?: boolean;
  secretRef?: string | null;
  /** 密钥来源：ENV / DB / NONE，用于提示管理员密钥是否真的配好了 */
  credentialSource?: string;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  maxRetries?: number;
  weight?: number;
  healthStatus?: string;
  lastHealthTime?: string;
  lastHealthMessage?: string;
  remark?: string;
  modelCount?: number;
  createTime?: string;
  updateTime?: string;
}

/** 提供方连通性测试结果 */
export interface ProviderTestResult {
  healthy?: boolean;
  message?: string;
  latencyMs?: number;
  healthStatus?: string;
}

/* ==================== 模型 ==================== */

/** AI 模型 */
export interface AiModel {
  id?: number;
  providerId?: number;
  providerName?: string;
  modelCode?: string;
  modelName?: string;
  modelKind?: string;
  supportsStream?: boolean;
  supportsTools?: boolean;
  supportsJson?: boolean;
  supportsVision?: boolean;
  maxContextTokens?: number;
  maxOutputTokens?: number;
  inputPrice?: number;
  outputPrice?: number;
  currency?: string;
  enabled?: boolean;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/* ==================== 路由 ==================== */

/** 模型路由（智能体版本引用它，避免直接绑定某个模型） */
export interface AiRoute {
  id?: number;
  routeCode?: string;
  name?: string;
  providerId?: number;
  providerName?: string;
  modelId?: number;
  modelCode?: string;
  fallbackModelId?: number;
  fallbackModelCode?: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
  extraParametersJson?: string | null;
  enabled?: boolean;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/* ==================== 智能体 ==================== */

/** AI 智能体 */
export interface AiAgent {
  id?: number;
  agentCode?: string;
  name?: string;
  agentType?: string;
  description?: string;
  icon?: string;
  status?: string;
  clientTypes?: string[];
  entryConfig?: string | null;
  permissionCode?: string | null;
  clientPermissionCodes?: string | null;
  quotaPolicyId?: number | null;
  publishedVersionId?: number | null;
  publishedVersionNo?: number | null;
  allowUserContext?: boolean;
  allowFileContext?: boolean;
  conversationEnabled?: boolean;
  feedbackEnabled?: boolean;
  sort?: number;
  versionCount?: number;
  draftVersionCount?: number;
  createTime?: string;
  updateTime?: string;
}

/** 智能体版本（快照不可变，只有草稿可改） */
export interface AiAgentVersion {
  id?: number;
  agentId?: number;
  versionNo?: number;
  versionName?: string;
  status?: string;
  providerRouteId?: number | null;
  routeName?: string;
  systemPromptVersionId?: number | null;
  promptName?: string;
  clientTypesSnapshot?: string;
  entryConfigSnapshot?: string;
  contextPolicyJson?: string | null;
  responseSchemaJson?: string | null;
  toolPolicyJson?: string | null;
  knowledgePolicyJson?: string | null;
  modelParametersJson?: string | null;
  safetyPolicyJson?: string | null;
  quotaOverrideJson?: string | null;
  validationSummaryJson?: string | null;
  publishedBy?: number | null;
  publishedAt?: string | null;
  rollbackFromVersionId?: number | null;
  toolIds?: number[];
  knowledgeSourceIds?: number[];
  createTime?: string;
  updateTime?: string;
}

/** 版本上的工具绑定 */
export interface AiAgentTool {
  id?: number;
  agentVersionId?: number;
  toolId?: number;
  toolCode?: string;
  toolName?: string;
  riskLevel?: string;
  effectiveRiskLevel?: string;
  approvalPolicy?: string;
  enabled?: boolean;
  sort?: number;
}

/** 版本上的知识源绑定 */
export interface AiAgentKnowledge {
  id?: number;
  agentVersionId?: number;
  sourceId?: number;
  sourceName?: string;
  sourceType?: string;
  enabled?: boolean;
  priority?: number;
  retrievalTopK?: number;
  minScore?: number;
  citationPolicy?: string;
}

/* ==================== 提示词 ==================== */

/** 提示词模板 */
export interface AiPromptTemplate {
  id?: number;
  promptCode?: string;
  name?: string;
  scene?: string;
  description?: string;
  status?: string;
  publishedVersionId?: number | null;
  publishedVersionNo?: number | null;
  versionCount?: number;
  createTime?: string;
  updateTime?: string;
}

/** 提示词版本 */
export interface AiPromptVersion {
  id?: number;
  templateId?: number;
  versionNo?: number;
  status?: string;
  systemPrompt?: string | null;
  userPromptTemplate?: string | null;
  variablesJson?: string | null;
  changelog?: string | null;
  publishedBy?: number | null;
  publishedAt?: string | null;
  createTime?: string;
  updateTime?: string;
}

/* ==================== 知识库 ==================== */

/** 知识源 */
export interface AiKnowledgeSource {
  id?: number;
  sourceCode?: string;
  name?: string;
  sourceType?: string;
  description?: string;
  pathPattern?: string;
  enabled?: boolean;
  syncMode?: string;
  status?: string;
  lastSyncTime?: string;
  documentCount?: number;
  chunkCount?: number;
  createTime?: string;
  updateTime?: string;
}

/** 知识文档 */
export interface AiKnowledgeDocument {
  id?: number;
  sourceId?: number;
  docKey?: string;
  title?: string;
  path?: string;
  section?: string;
  version?: string;
  contentHash?: string;
  status?: string;
  chunkCount?: number;
  indexedAt?: string;
  createTime?: string;
  updateTime?: string;
}

/** 索引任务 */
export interface AiIndexJob {
  id?: number;
  sourceId?: number;
  jobType?: string;
  status?: string;
  totalCount?: number;
  processedCount?: number;
  failedCount?: number;
  startedAt?: string;
  finishedAt?: string;
  errorSummary?: string;
}

/* ==================== 工具 ==================== */

/** 已登记工具（handlerKey 必须对应代码里的处理器，后台只能改风险/确认/状态） */
export interface AiTool {
  id?: number;
  toolCode?: string;
  name?: string;
  description?: string;
  handlerKey?: string;
  clientTypes?: string[];
  permissionCodes?: string[];
  riskLevel?: string;
  approvalPolicy?: string;
  argumentsSchema?: string | null;
  resultSchema?: string | null;
  sideEffects?: string | null;
  status?: string;
  /** 代码里是否真的存在该 handlerKey；缺失时该工具不可用 */
  handlerAvailable?: boolean;
  createTime?: string;
  updateTime?: string;
}

/* ==================== 额度 / 用量 ==================== */

/** 额度策略 */
export interface AiQuotaPolicy {
  id?: number;
  policyCode?: string;
  name?: string;
  scopeType?: string;
  agentId?: number | null;
  roleId?: number | null;
  userId?: number | null;
  dailyRequests?: number;
  monthlyRequests?: number;
  dailyInputTokens?: number;
  monthlyInputTokens?: number;
  dailyOutputTokens?: number;
  monthlyOutputTokens?: number;
  maxInputChars?: number;
  maxContextTokens?: number;
  maxOutputTokens?: number;
  maxConcurrent?: number;
  monthlyCostLimit?: number;
  enabled?: boolean;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

/** 用量明细 */
export interface AiUsage {
  id?: number;
  requestId?: number;
  userId?: number;
  agentId?: number;
  agentName?: string;
  providerId?: number;
  modelId?: number;
  modelCode?: string;
  inputTokens?: number;
  outputTokens?: number;
  estimatedCost?: number;
  currency?: string;
  status?: string;
  createTime?: string;
}

/** 用量趋势点 */
export interface AiUsageTrendPoint {
  date?: string;
  requestCount?: number;
  inputTokens?: number;
  outputTokens?: number;
}

/** 用量汇总 */
export interface AiUsageSummary {
  requestCount?: number;
  inputTokens?: number;
  outputTokens?: number;
  estimatedCost?: number;
  currency?: string;
  trend?: AiUsageTrendPoint[];
}

/* ==================== 请求 / 审计 / 总览 ==================== */

/** 请求记录（列表视图不含正文） */
export interface AiRequest {
  id?: number;
  conversationId?: number;
  agentId?: number;
  agentName?: string;
  agentVersionId?: number;
  agentVersionNo?: number;
  userId?: number;
  clientType?: string;
  status?: string;
  errorCode?: string;
  errorSummary?: string;
  tokenIn?: number;
  tokenOut?: number;
  durationMs?: number;
  startedAt?: string;
  finishedAt?: string;
  createTime?: string;
}

/** 工具调用记录 */
export interface AiToolCall {
  id?: number;
  requestId?: number;
  toolCode?: string;
  toolName?: string;
  riskLevel?: string;
  status?: string;
  argumentsJson?: string | null;
  resultJson?: string | null;
  errorMessage?: string;
  approvalStatus?: string;
  approvedBy?: number;
  approvedAt?: string;
  startedAt?: string;
  finishedAt?: string;
}

/** 请求事件（管理端） */
export interface AiRequestEvent {
  sequence?: number;
  type?: string;
  dataJson?: string | null;
  createdAt?: string;
}

/** 管理端消息视图（正文字段为字符串，块与引用是 JSON 串） */
export interface AiAdminMessage {
  id?: number;
  conversationId?: number;
  requestId?: number;
  role?: string;
  content?: string;
  contentType?: string;
  status?: string;
  blocksJson?: string | null;
  citationsJson?: string | null;
  modelName?: string;
  inputTokens?: number;
  outputTokens?: number;
  createTime?: string;
}

/** 会话审计视图（跨用户，带 agentCode / userId，用于后台排查） */
export interface AiConversationAudit {
  id?: number;
  agentId?: number;
  agentCode?: string;
  agentName?: string;
  userId?: number;
  clientType?: string;
  title?: string;
  status?: string;
  messageCount?: number;
  pinned?: boolean;
  lastMessageTime?: string;
  createTime?: string;
}

/** 请求详情 */
export interface AiRequestDetail {
  request?: AiRequest;
  inputSnapshot?: string | null;
  contextSnapshot?: string | null;
  configurationSnapshot?: string | null;
  knowledgeSnapshot?: string | null;
  toolPolicySnapshot?: string | null;
  safetyPolicySnapshot?: string | null;
  toolCalls?: AiToolCall[];
  events?: AiRequestEvent[];
  messages?: AiAdminMessage[];
}

/** 配置审计 */
export interface AiAudit {
  id?: number;
  action?: string;
  targetType?: string;
  targetId?: string;
  operatorId?: number;
  operatorName?: string;
  detail?: string;
  clientIp?: string;
  createTime?: string;
}

/** 提供方健康摘要 */
export interface AiProviderHealth {
  id?: number;
  name?: string;
  healthStatus?: string;
  healthMessage?: string;
  lastHealthTime?: string;
}

/** 智能体摘要 */
export interface AiAgentBrief {
  id?: number;
  agentCode?: string;
  name?: string;
  agentType?: string;
  status?: string;
  publishedVersionNo?: number;
  clientTypes?: string[];
}

/** 总览 */
export interface AiOverview {
  pluginEnabled?: boolean;
  schemaReady?: boolean;
  secretKeyConfigured?: boolean;
  providerCount?: number;
  enabledProviderCount?: number;
  modelCount?: number;
  routeCount?: number;
  agentCount?: number;
  publishedAgentCount?: number;
  promptCount?: number;
  knowledgeSourceCount?: number;
  toolCount?: number;
  todayRequestCount?: number;
  runningRequestCount?: number;
  failedRequestCount?: number;
  todayInputTokens?: number;
  todayOutputTokens?: number;
  providerHealth?: AiProviderHealth[];
  agents?: AiAgentBrief[];
}

/* ==================== 会话 / 消息 / 事件（使用侧） ==================== */

/** 智能体卡片 */
export interface AiAgentCard {
  agentCode?: string;
  name?: string;
  agentType?: string;
  description?: string;
  icon?: string;
  entryConfig?: string;
  capabilities?: AiCapabilities;
}

/** 智能体能力（前端据此决定是否显示历史、反馈、文件上传、动作按钮） */
export interface AiCapabilities {
  conversationEnabled?: boolean;
  feedbackEnabled?: boolean;
  fileContextEnabled?: boolean;
  userContextEnabled?: boolean;
  citationRequired?: boolean;
  musicActionsEnabled?: boolean;
  clientType?: string;
}

/** 会话 */
export interface AiConversation {
  id?: number;
  agentCode?: string;
  agentName?: string;
  title?: string;
  status?: string;
  messageCount?: number;
  pinned?: boolean;
  lastMessageTime?: string;
  createTime?: string;
}

/** 回答块 */
export interface AiBlock {
  type?: string;
  title?: string;
  data?: Record<string, unknown>;
}

/** 引用（答疑答案必须可核验） */
export interface AiCitation {
  sourceId?: number;
  sourceType?: string;
  title?: string;
  path?: string;
  section?: string;
  version?: string;
  matchedText?: string;
}

/** 消息 */
export interface AiMessage {
  id?: number;
  conversationId?: number;
  requestId?: number;
  role?: string;
  content?: string;
  contentType?: string;
  status?: string;
  blocks?: AiBlock[];
  citations?: AiCitation[];
  modelName?: string;
  createTime?: string;
}

/** 消息分页 */
export interface AiMessagePage {
  items?: AiMessage[];
  nextCursor?: number | null;
  hasMore?: boolean;
}

/** 发送消息结果 */
export interface AiSendResult {
  requestId?: number;
  userMessageId?: number;
  assistantMessageId?: number;
  status?: string;
  stream?: boolean;
}

/** 流式事件 */
export interface AiEvent {
  requestId?: number;
  sequence?: number;
  type?: string;
  data?: Record<string, unknown>;
  createdAt?: string;
}

/** 事件批次（轮询 / 断线续传用，after 为已消费的最大 sequence） */
export interface AiEventBatch {
  requestId?: number;
  status?: string;
  events?: AiEvent[];
  nextSequence?: number;
  terminal?: boolean;
}

/** 动作回执 */
export interface AiActionResult {
  [key: string]: unknown;
}

/* ==================== 管理端：提供方 / 模型 / 路由 ==================== */

export const aiProviderApi = {
  list: () => request.get<AiProvider[]>('/api/v1/admin/ai/providers'),
  create: (data: Partial<AiProvider> & { apiKey?: string }) =>
    request.post<AiProvider>('/api/v1/admin/ai/providers', data),
  update: (id: number, data: Partial<AiProvider> & { apiKey?: string }) =>
    request.put<AiProvider>(`/api/v1/admin/ai/providers/${id}`, data),
  remove: (id: number) => request.delete<void>(`/api/v1/admin/ai/providers/${id}`),
  test: (id: number) => request.post<ProviderTestResult>(`/api/v1/admin/ai/providers/${id}/test`),
};

export const aiModelApi = {
  list: (providerId?: number) =>
    request.get<AiModel[]>('/api/v1/admin/ai/models', { params: { providerId } }),
  create: (data: Partial<AiModel>) => request.post<AiModel>('/api/v1/admin/ai/models', data),
  update: (id: number, data: Partial<AiModel>) =>
    request.put<AiModel>(`/api/v1/admin/ai/models/${id}`, data),
  remove: (id: number) => request.delete<void>(`/api/v1/admin/ai/models/${id}`),
};

export const aiRouteApi = {
  list: () => request.get<AiRoute[]>('/api/v1/admin/ai/routes'),
  create: (data: Partial<AiRoute>) => request.post<AiRoute>('/api/v1/admin/ai/routes', data),
  update: (id: number, data: Partial<AiRoute>) =>
    request.put<AiRoute>(`/api/v1/admin/ai/routes/${id}`, data),
  remove: (id: number) => request.delete<void>(`/api/v1/admin/ai/routes/${id}`),
};

/* ==================== 管理端：智能体与版本 ==================== */

export const aiAgentApi = {
  list: () => request.get<AiAgent[]>('/api/v1/admin/ai/agents'),
  detail: (id: number) => request.get<AiAgent>(`/api/v1/admin/ai/agents/${id}`),
  create: (data: Partial<AiAgent>) => request.post<AiAgent>('/api/v1/admin/ai/agents', data),
  update: (id: number, data: Partial<AiAgent>) =>
    request.put<AiAgent>(`/api/v1/admin/ai/agents/${id}`, data),
  remove: (id: number) => request.delete<void>(`/api/v1/admin/ai/agents/${id}`),
  disable: (id: number) => request.post<AiAgent>(`/api/v1/admin/ai/agents/${id}/disable`),
  enable: (id: number) => request.post<AiAgent>(`/api/v1/admin/ai/agents/${id}/enable`),

  versions: (id: number) => request.get<AiAgentVersion[]>(`/api/v1/admin/ai/agents/${id}/versions`),
  createVersion: (id: number, data: Partial<AiAgentVersion>) =>
    request.post<AiAgentVersion>(`/api/v1/admin/ai/agents/${id}/versions`, data),
  version: (versionId: number) =>
    request.get<AiAgentVersion>(`/api/v1/admin/ai/agent-versions/${versionId}`),
  updateVersion: (versionId: number, data: Partial<AiAgentVersion>) =>
    request.put<AiAgentVersion>(`/api/v1/admin/ai/agent-versions/${versionId}`, data),
  validateVersion: (versionId: number) =>
    request.post<ValidationResult>(`/api/v1/admin/ai/agent-versions/${versionId}/validate`),
  publishVersion: (versionId: number) =>
    request.post<AiAgentVersion>(`/api/v1/admin/ai/agent-versions/${versionId}/publish`),
  rollback: (id: number, versionId: number) =>
    request.post<AiAgentVersion>(`/api/v1/admin/ai/agents/${id}/rollback`, { versionId }),

  versionTools: (versionId: number) =>
    request.get<AiAgentTool[]>(`/api/v1/admin/ai/agent-versions/${versionId}/tools`),
  replaceTools: (versionId: number, toolIds: number[]) =>
    request.put<AiAgentTool[]>(`/api/v1/admin/ai/agent-versions/${versionId}/tools`, { toolIds }),

  versionKnowledge: (versionId: number) =>
    request.get<AiAgentKnowledge[]>(`/api/v1/admin/ai/agent-versions/${versionId}/knowledge`),
  /** 后端直接接收数组，元素为 { sourceId, enabled, priority, retrievalTopK, minScore, citationPolicy } */
  replaceKnowledge: (versionId: number, bindings: Partial<AiAgentKnowledge>[]) =>
    request.put<AiAgentKnowledge[]>(
      `/api/v1/admin/ai/agent-versions/${versionId}/knowledge`,
      bindings,
    ),
};

/* ==================== 管理端：提示词 ==================== */

export const aiPromptApi = {
  list: () => request.get<AiPromptTemplate[]>('/api/v1/admin/ai/prompts'),
  detail: (id: number) => request.get<AiPromptTemplate>(`/api/v1/admin/ai/prompts/${id}`),
  create: (data: Partial<AiPromptTemplate>) =>
    request.post<AiPromptTemplate>('/api/v1/admin/ai/prompts', data),
  update: (id: number, data: Partial<AiPromptTemplate>) =>
    request.put<AiPromptTemplate>(`/api/v1/admin/ai/prompts/${id}`, data),
  versions: (id: number) =>
    request.get<AiPromptVersion[]>(`/api/v1/admin/ai/prompts/${id}/versions`),
  createVersion: (id: number, data: Partial<AiPromptVersion>) =>
    request.post<AiPromptVersion>(`/api/v1/admin/ai/prompts/${id}/versions`, data),
  updateVersion: (versionId: number, data: Partial<AiPromptVersion>) =>
    request.put<AiPromptVersion>(`/api/v1/admin/ai/prompt-versions/${versionId}`, data),
  publishVersion: (versionId: number) =>
    request.post<AiPromptVersion>(`/api/v1/admin/ai/prompt-versions/${versionId}/publish`),
};

/* ==================== 管理端：知识库 ==================== */

export const aiKnowledgeApi = {
  listSources: () => request.get<AiKnowledgeSource[]>('/api/v1/admin/ai/knowledge/sources'),
  source: (id: number) =>
    request.get<AiKnowledgeSource>(`/api/v1/admin/ai/knowledge/sources/${id}`),
  createSource: (data: Partial<AiKnowledgeSource>) =>
    request.post<AiKnowledgeSource>('/api/v1/admin/ai/knowledge/sources', data),
  updateSource: (id: number, data: Partial<AiKnowledgeSource>) =>
    request.put<AiKnowledgeSource>(`/api/v1/admin/ai/knowledge/sources/${id}`, data),
  documents: (sourceId: number) =>
    request.get<AiKnowledgeDocument[]>(`/api/v1/admin/ai/knowledge/sources/${sourceId}/documents`),
  saveDocument: (sourceId: number, data: Partial<AiKnowledgeDocument>) =>
    request.post<AiKnowledgeDocument>(
      `/api/v1/admin/ai/knowledge/sources/${sourceId}/documents`,
      data,
    ),
  removeDocument: (documentId: number) =>
    request.delete<void>(`/api/v1/admin/ai/knowledge/documents/${documentId}`),
  sync: (sourceId: number) =>
    request.post<AiIndexJob>(`/api/v1/admin/ai/knowledge/sources/${sourceId}/sync`),
  indexJob: (jobId: number) =>
    request.get<AiIndexJob>(`/api/v1/admin/ai/knowledge/index-jobs/${jobId}`),
};

/* ==================== 管理端：工具 ==================== */

export const aiToolApi = {
  list: () => request.get<AiTool[]>('/api/v1/admin/ai/tools'),
  sync: () => request.post<{ changed?: number }>('/api/v1/admin/ai/tools/sync'),
  update: (id: number, data: Partial<AiTool>) =>
    request.put<AiTool>(`/api/v1/admin/ai/tools/${id}`, data),
  approve: (toolCallId: number, approved: boolean, comment?: string) =>
    request.post<unknown>(`/api/v1/admin/ai/tool-calls/${toolCallId}/approve`, {
      approved,
      comment,
    }),
};

/* ==================== 管理端：额度 / 用量 / 请求 / 审计 / 总览 ==================== */

export const aiQuotaApi = {
  list: () => request.get<AiQuotaPolicy[]>('/api/v1/admin/ai/quotas'),
  create: (data: Partial<AiQuotaPolicy>) =>
    request.post<AiQuotaPolicy>('/api/v1/admin/ai/quotas', data),
  update: (id: number, data: Partial<AiQuotaPolicy>) =>
    request.put<AiQuotaPolicy>(`/api/v1/admin/ai/quotas/${id}`, data),
  remove: (id: number) => request.delete<void>(`/api/v1/admin/ai/quotas/${id}`),
};

export const aiUsageApi = {
  list: (params: {
    userId?: number;
    agentId?: number;
    from?: string;
    to?: string;
    pageNum?: number;
    pageSize?: number;
  }) => request.get<AiUsage[]>('/api/v1/admin/ai/usage', { params }),
  summary: (params: { userId?: number; agentId?: number; from?: string; to?: string }) =>
    request.get<AiUsageSummary>('/api/v1/admin/ai/usage/summary', { params }),
};

export const aiRequestApi = {
  list: (params: {
    status?: string;
    agentId?: number;
    userId?: number;
    clientType?: string;
    from?: string;
    to?: string;
    pageNum?: number;
    pageSize?: number;
  }) => request.get<PageResult<AiRequest>>('/api/v1/admin/ai/requests', { params }),
  detail: (id: number, content?: boolean) =>
    request.get<AiRequestDetail>(`/api/v1/admin/ai/requests/${id}`, { params: { content } }),
  /**
   * 全会话审计列表（跨用户）。
   *
   * 注意路径是 `conversation-audits` 而不是 `conversations`——后者是「使用侧」接口，
   * 只返回当前登录人自己的会话；审计要看所有人的会话，两者权限模型不同。
   */
  conversations: (params: {
    agentId?: number;
    userId?: number;
    clientType?: string;
    pageNum?: number;
    pageSize?: number;
  }) =>
    request.get<PageResult<AiConversationAudit>>('/api/v1/admin/ai/conversation-audits', {
      params,
    }),
  /** 会话正文（需要 `admin:ai:conversation:content:view`，后端会记审计日志） */
  conversationMessages: (id: number, beforeId?: number, limit?: number) =>
    request.get<AiAdminMessage[]>(`/api/v1/admin/ai/conversation-audits/${id}/messages`, {
      params: { beforeId, limit },
    }),
};

export const aiAuditApi = {
  list: (pageNum?: number, pageSize?: number) =>
    request.get<AiAudit[]>('/api/v1/admin/ai/audit', { params: { pageNum, pageSize } }),
};

export const aiOverviewApi = {
  get: () => request.get<AiOverview>('/api/v1/admin/ai/overview'),
};

/* ==================== 使用侧：管理助手 / 答疑助手共用 ==================== */

/**
 * 管理端“使用”接口。
 *
 * `agentCode` 决定实际跑哪个智能体（`admin_assistant` / `admin_qa`），
 * 前端不写死任何模型、提示词或工具——这些都来自后端已发布版本。
 */
export const aiChatApi = {
  /** 当前用户可见的智能体卡片 */
  assistants: () => request.get<AiAgentCard[]>('/api/v1/admin/ai/assistants'),
  /** 智能体能力（会话、反馈、文件上下文、引用、动作） */
  capabilities: (agentCode: string) =>
    request.get<AiCapabilities>('/api/v1/admin/ai/capabilities', { params: { agentCode } }),

  conversations: (agentCode: string) =>
    request.get<AiConversation[]>('/api/v1/admin/ai/conversations', { params: { agentCode } }),
  createConversation: (agentCode: string, title?: string) =>
    request.post<AiConversation>('/api/v1/admin/ai/conversations', { agentCode, title }),
  conversation: (id: number) => request.get<AiConversation>(`/api/v1/admin/ai/conversations/${id}`),
  renameConversation: (id: number, title: string) =>
    request.put<AiConversation>(`/api/v1/admin/ai/conversations/${id}`, { title }),
  removeConversation: (id: number) => request.delete<void>(`/api/v1/admin/ai/conversations/${id}`),
  messages: (id: number, beforeId?: number, limit?: number) =>
    request.get<AiMessagePage>(`/api/v1/admin/ai/conversations/${id}/messages`, {
      params: { beforeId, limit },
    }),

  /** 发送消息：立即返回 requestId，正文通过事件流/轮询补齐 */
  send: (
    conversationId: number,
    data: {
      content: string;
      clientMessageId?: string;
      context?: Record<string, unknown>;
      idempotencyKey?: string;
    },
    config?: ApiRequestConfig,
  ) =>
    request.post<AiSendResult>(
      `/api/v1/admin/ai/conversations/${conversationId}/messages`,
      data,
      config,
    ),

  /** 拉取事件（after 传已消费的最大 sequence，实现断线续传） */
  events: (requestId: number, after?: number, limit?: number) =>
    request.get<AiEventBatch>(`/api/v1/admin/ai/requests/${requestId}/events`, {
      params: { after, limit },
      silent: true,
    }),
  cancel: (requestId: number, reason?: string) =>
    request.post<unknown>(`/api/v1/admin/ai/requests/${requestId}/cancel`, { reason }),
  feedback: (
    messageId: number,
    data: { feedbackType: string; reasonCode?: string; comment?: string },
  ) => request.post<unknown>(`/api/v1/admin/ai/messages/${messageId}/feedback`, data),
  executeAction: (data: {
    actionId: string;
    actionType: string;
    success: boolean;
    message?: string;
  }) => request.post<AiActionResult>('/api/v1/admin/ai/actions/execute', data),
};

/**
 * SSE 订阅地址。
 *
 * 注意：`EventSource` 无法携带自定义请求头，而本系统认证走 HttpOnly Cookie + CSRF 头，
 * 因此这里只提供 URL；页面实际使用 `fetch` + `ReadableStream` 手动消费（见 ChatPanel），
 * 以便带上 CSRF 与客户端标识头。
 */
export const aiStreamUrl = (requestId: number, after?: number): string =>
  `/api/v1/admin/ai/requests/${requestId}/stream${after ? `?after=${after}` : ''}`;

/* ==================== 使用侧：App（轻听 App / 轻听 PC） ==================== */

/**
 * 客户端音乐助手接口（`/api/v1/app/ai/**`）。
 *
 * 轻听 App（qt-uniappx）与轻听 PC（qt-pc）调用完全相同的端点与数据结构，
 * 只是 `clientType` 由服务端依据请求头判定，客户端不传、也不能伪造。
 */
export const aiAppApi = {
  agents: () => request.get<AiAgentCard[]>('/api/v1/app/ai/agents'),
  capabilities: (agentCode: string) =>
    request.get<AiCapabilities>('/api/v1/app/ai/capabilities', { params: { agentCode } }),
  conversations: (agentCode: string) =>
    request.get<AiConversation[]>('/api/v1/app/ai/conversations', { params: { agentCode } }),
  createConversation: (agentCode: string, title?: string) =>
    request.post<AiConversation>('/api/v1/app/ai/conversations', { agentCode, title }),
  conversation: (id: number) => request.get<AiConversation>(`/api/v1/app/ai/conversations/${id}`),
  removeConversation: (id: number) => request.delete<void>(`/api/v1/app/ai/conversations/${id}`),
  messages: (id: number, beforeId?: number, limit?: number) =>
    request.get<AiMessagePage>(`/api/v1/app/ai/conversations/${id}/messages`, {
      params: { beforeId, limit },
    }),
  send: (
    conversationId: number,
    data: {
      content: string;
      clientMessageId?: string;
      context?: Record<string, unknown>;
      idempotencyKey?: string;
    },
    config?: ApiRequestConfig,
  ) =>
    request.post<AiSendResult>(
      `/api/v1/app/ai/conversations/${conversationId}/messages`,
      data,
      config,
    ),
  events: (requestId: number, after?: number, limit?: number) =>
    request.get<AiEventBatch>(`/api/v1/app/ai/requests/${requestId}/events`, {
      params: { after, limit },
      silent: true,
    }),
  cancel: (requestId: number, reason?: string) =>
    request.post<unknown>(`/api/v1/app/ai/requests/${requestId}/cancel`, { reason }),
  feedback: (
    messageId: number,
    data: { feedbackType: string; reasonCode?: string; comment?: string },
  ) => request.post<unknown>(`/api/v1/app/ai/messages/${messageId}/feedback`, data),
  executeAction: (data: {
    actionId: string;
    actionType: string;
    success: boolean;
    message?: string;
  }) => request.post<AiActionResult>('/api/v1/app/ai/actions/execute', data),
};

/**
 * 音乐候选上报结构。
 *
 * 音乐搜索始终在客户端多音源里完成，服务端只做排序与解释，
 * 因此 `MusicCandidate` 由客户端构造后随消息 context 一起提交。
 */
export interface MusicCandidate {
  sourceId?: string;
  songId?: string;
  name?: string;
  artist?: string;
  album?: string;
  duration?: number;
  coverUrl?: string;
  quality?: string;
  extra?: Record<string, unknown>;
}
