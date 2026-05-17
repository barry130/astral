import { request, ApiResult } from './client';

/** 序列配置实体接口 */
export interface SequenceConfig {
  /** 配置ID（新增时为空） */
  id?: number;
  /** 业务键（唯一标识） */
  bizKey: string;
  /** 序列类型（SNOWFLAKE/SEGMENT/REDIS/DATABASE/SIMPLE） */
  sequenceType: string;
  /** 是否启用 */
  enabled: boolean;
  /** 当前序列值 */
  currentValue?: number;
  /** 累计生成总数 */
  totalGenerate?: number;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;
}

/** 分页结果通用接口 */
export interface PageResult<T> {
  /** 当前页数据列表 */
  records: T[];
  /** 总记录数 */
  total: number;
  /** 每页大小 */
  size: number;
  /** 当前页码 */
  current: number;
  /** 总页数 */
  pages: number;
}

/** 序列配置管理相关API */
export const sequenceConfigApi = {
  /** 获取所有序列配置（不分页） */
  getAll: (): Promise<ApiResult<SequenceConfig[]>> =>
    request.get('/api/v1/sequence/configs'),

  /** 分页查询序列配置 */
  getPage: (pageNum = 1, pageSize = 20, params?: Record<string, any>): Promise<ApiResult<PageResult<SequenceConfig>>> =>
    request.get('/api/v1/sequence/configs/page', { params: { pageNum, pageSize, ...params } }),

  /** 根据业务键获取配置 */
  getByBizKey: (bizKey: string): Promise<ApiResult<SequenceConfig>> =>
    request.get(`/api/v1/sequence/configs/${bizKey}`),

  /** 创建新配置 */
  create: (data: SequenceConfig): Promise<ApiResult<SequenceConfig>> =>
    request.post('/api/v1/sequence/configs', data),

  /** 更新配置 */
  update: (id: number, data: SequenceConfig): Promise<ApiResult<SequenceConfig>> =>
    request.put(`/api/v1/sequence/configs/${id}`, data),

  /** 删除配置 */
  delete: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/sequence/configs/${id}`),

  /** 切换配置启用状态 */
  toggle: (id: number, enabled: boolean): Promise<ApiResult<void>> =>
    request.put(`/api/v1/sequence/configs/${id}/toggle`, null, { params: { enabled } }),
};