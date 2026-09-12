import { request } from './client';
import { fetchDictOptions } from './dict';

/** 单日设备统计概览 */
export interface DayOverview {
  date: string;
  newDevices: number;
  activeDevices: number;
  totalDevices: number;
  pv: number;
  visits: number;
  launches: number;
  avgDurationMs: number;
  errorCount: number;
}

/** 设备统计概览（今日 vs 昨日） */
export interface OverviewData {
  date: string;
  today: DayOverview;
  yesterday: DayOverview;
}

/** 24 小时趋势（补零，长度恒 24） */
export interface TrendData {
  hours: string[];
  today: number[];
  yesterday: number[];
}

/** 接口统计 Top 条目（兼容旧契约字段名） */
export interface ApiTopItem {
  apiPath: string;
  apiMethod: string;
  callCount: number;
  successCount: number;
  failureCount: number;
  avgTime: number;
  maxTime: number;
}

/** 接口统计当天全量汇总（与 Top N limit 无关） */
export interface ApiTopSummary {
  callCount: number;
  successCount: number;
  failureCount: number;
  successRate: string;
}

/** 接口统计 Top 榜结果（明细 + 当天全量汇总） */
export interface ApiTopResult {
  list: ApiTopItem[];
  summary: ApiTopSummary;
}

/** 单接口 24 小时调用趋势 */
export interface ApiTrendData {
  hours: string[];
  callCount: number[];
  avgMs: number[];
}

/** 错误明细条目 */
export interface StatErrorLogItem {
  id: number;
  fingerprint: string;
  errorType: string;
  message: string;
  stack?: string;
  page?: string;
  ut?: string;
  appVersion?: string;
  os?: string;
  model?: string;
  deviceId?: string;
  release?: string;
  ip?: string;
  occurTime: string;
  createTime: string;
}

/** 错误分组汇总条目 */
export interface ErrorSummaryItem {
  fingerprint: string;
  count: number;
  affectedDevices: number;
  errorType: string;
  sampleMessage: string;
  firstSeen: string;
  lastSeen: string;
  topAppVersion: string;
}

/** MyBatis-Plus 分页结构 */
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/** 平台选项（字典 stat_platform，dict-init.sql；AGENTS.md 规则 3：枚举值统一走字典） */
export const UT_DICT_CODE = 'stat_platform';

/** 平台选项兜底（字典接口不可用时使用） */
export const FALLBACK_UT_OPTIONS = [
  { value: 'app-android', label: 'Android' },
  { value: 'app-ios', label: 'iOS' },
  { value: 'app-windows', label: 'Windows' },
  { value: 'web', label: 'Web' },
];

/** “全部平台”固定项（ut=all 由后端识别为不加平台过滤） */
const UT_ALL_OPTION = { value: 'all', label: '全部平台' };

/**
 * 加载平台下拉选项：全部平台 + 字典 stat_platform
 * <p>字典为空或接口异常时回退内置选项，保证页面可用。</p>
 */
export async function loadUtOptions(): Promise<{ value: string; label: string }[]> {
  try {
    const map = await fetchDictOptions([UT_DICT_CODE]);
    const list = map[UT_DICT_CODE] || [];
    if (list.length > 0) {
      return [UT_ALL_OPTION, ...list];
    }
  } catch {
    // 字典接口不可用时回退内置选项
  }
  return [UT_ALL_OPTION, ...FALLBACK_UT_OPTIONS];
}

/** 平台值 → 文案（未知值回退展示原始值） */
export function utLabel(options: { value: string; label: string }[], value?: string | null): string {
  if (!value) return '-';
  if (value === 'all') return UT_ALL_OPTION.label;
  return options.find((o) => o.value === value)?.label ?? value;
}

/** 趋势指标选项 */
export const METRIC_OPTIONS = [
  { value: 'pv', label: '页面访问(PV)' },
  { value: 'visits', label: '访问次数' },
  { value: 'launches', label: '启动次数' },
  { value: 'errorCount', label: '错误次数' },
];

/** 设备统计概览 */
export const statApi = {
  getOverview: (date?: string, ut: string = 'all') =>
    request.get<OverviewData>('/api/v1/admin/stat/overview', { params: { date, ut } }),

  /** 指标 24 小时趋势（gran 本期固定 hour） */
  getTrend: (metric: string, date?: string, ut: string = 'all') =>
    request.get<TrendData>('/api/v1/admin/stat/trend', { params: { metric, date, ut, gran: 'hour' } }),

  /** 接口调用 Top 榜 */
  getApiTop: (limit: number = 10, date?: string) =>
    request.get<ApiTopResult>('/api/v1/admin/stat/api/top', { params: { limit, date } }),

  /** 单接口 24 小时趋势 */
  getApiTrend: (uri: string, method: string, date?: string) =>
    request.get<ApiTrendData>('/api/v1/admin/stat/api/trend', { params: { uri, method, date } }),

  /** 错误明细分页 */
  getErrorPage: (params: {
    pageNum?: number;
    pageSize?: number;
    errorType?: string;
    appVersion?: string;
    fingerprint?: string;
  }) => request.get<PageResult<StatErrorLogItem>>('/api/v1/admin/stat/error/page', { params }),

  /** 错误分组汇总 */
  getErrorSummary: (date?: string) =>
    request.get<ErrorSummaryItem[]>('/api/v1/admin/stat/error/summary', { params: { date } }),
};
