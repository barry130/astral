import { request, ApiResult } from './client';

/** 告警渠道 */
export interface AlertChannel {
  id: number;
  name: string;
  /** EMAIL / WEBHOOK */
  type: string;
  /** JSON 串：EMAIL {to}；WEBHOOK {url, secret?, header?} */
  config: string;
  enabled: number;
  remark?: string;
  createTime?: string;
}

/** 告警规则 */
export interface AlertRule {
  id: number;
  name: string;
  /** SERVER_ERROR_COUNT / CLIENT_ERROR_COUNT / HTTP_5XX_COUNT */
  metric: string;
  threshold: number;
  windowMinutes: number;
  channelId: number;
  channelName?: string;
  cooldownMinutes: number;
  enabled: number;
  lastFiredAt?: string;
  createTime?: string;
}

/** 告警触发记录 */
export interface AlertRecord {
  id: number;
  ruleId: number;
  ruleName?: string;
  channelId?: number;
  channelName?: string;
  title?: string;
  content?: string;
  metricValue?: number;
  /** SUCCESS / FAIL */
  status: string;
  errorMsg?: string;
  firedAt: string;
}

/** 告警指标选项 */
export const ALERT_METRICS = [
  { value: 'SERVER_ERROR_COUNT', label: '服务端 500 数（stat_error_log server）' },
  { value: 'CLIENT_ERROR_COUNT', label: '客户端错误数（stat_error_log client）' },
  { value: 'HTTP_5XX_COUNT', label: '5xx 请求数（stat_api_hourly）' },
] as const;

/** 告警管理 API（后端 /api/v1/admin/alert/**） */
export const alertApi = {
  // 渠道
  listChannels: (): Promise<ApiResult<AlertChannel[]>> =>
    request.get('/api/v1/admin/alert/channel/list'),
  createChannel: (data: Partial<AlertChannel>): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/alert/channel', data),
  updateChannel: (id: number, data: Partial<AlertChannel>): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/alert/channel/${id}`, data),
  deleteChannel: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/alert/channel/${id}`),
  testChannel: (id: number): Promise<ApiResult<void>> =>
    request.post(`/api/v1/admin/alert/channel/${id}/test`),

  // 规则
  listRules: (): Promise<ApiResult<AlertRule[]>> =>
    request.get('/api/v1/admin/alert/rule/list'),
  createRule: (data: Partial<AlertRule>): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/alert/rule', data),
  updateRule: (id: number, data: Partial<AlertRule>): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/alert/rule/${id}`, data),
  deleteRule: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/alert/rule/${id}`),

  // 触发记录
  pageRecords: (pageNum = 1, pageSize = 10, ruleId?: number): Promise<ApiResult<{ records: AlertRecord[]; total: number }>> =>
    request.get('/api/v1/admin/alert/record/page', { params: { pageNum, pageSize, ruleId } }),
};
