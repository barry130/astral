import { request, ApiResult } from './client';

/** 序列生成请求参数 */
export interface SequenceRequest {
  /** 业务键（如order_id、user_id） */
  bizKey: string;
  /** 序列类型（可选，不传则使用配置的默认类型） */
  type?: string;
}

/** 序列生成响应数据 */
export interface SequenceResponse {
  /** 业务键 */
  bizKey: string;
  /** 使用的序列类型 */
  type: string;
  /** 生成的序列号 */
  sequence: number;
  /** 生成时间戳 */
  timestamp: number;
}

/** 批量序列生成请求参数 */
export interface SequenceBatchRequest {
  /** 业务键 */
  bizKey: string;
  /** 序列类型 */
  type?: string;
  /** 生成数量 */
  count: number;
}

/** 序列类型定义 */
export interface SequenceType {
  /** 类型代码（如SNOWFLAKE、SEGMENT） */
  code: string;
  /** 类型描述 */
  description: string;
}

/** 序列历史记录 */
export interface SequenceHistory {
  /** 记录ID */
  id: number;
  /** 业务键 */
  bizKey: string;
  /** 序列类型 */
  sequenceType: string;
  /** 序列值 */
  sequenceValue: number;
  /** 创建时间 */
  createTime: string;
}

/** 序列生成相关API */
export const sequenceApi = {
  /** 生成下一个序列号 */
  next: (data: SequenceRequest): Promise<ApiResult<SequenceResponse>> =>
    request.post('/api/v1/all/sequence/next', data),

  /** 批量生成序列号 */
  batch: (data: SequenceBatchRequest): Promise<ApiResult<SequenceResponse>> =>
    request.post('/api/v1/all/sequence/batch', data),

  /** 获取所有支持的序列类型 */
  getTypes: (): Promise<ApiResult<SequenceType[]>> =>
    request.get('/api/v1/all/sequence/types'),

  /** 分页查询序列历史记录 */
  getHistoryPage: (pageNum = 1, pageSize = 20, bizKey?: string): Promise<ApiResult<any>> =>
    request.get('/api/v1/admin/sequence/history/page', { params: { pageNum, pageSize, bizKey } }),

  /** 获取最近的序列历史记录 */
  getHistoryRecent: (bizKey?: string, limit = 100): Promise<ApiResult<SequenceHistory[]>> =>
    request.get('/api/v1/admin/sequence/history/recent', { params: { bizKey, limit } }),
};