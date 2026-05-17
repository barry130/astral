import { request, ApiResult } from './client';

/** 操作日志记录接口 */
export interface OperateLog {
  /** 日志ID */
  id: number;
  /** 操作用户ID */
  userId: number;
  /** 操作用户名 */
  username: string;
  /** 操作模块 */
  module: string;
  /** 操作类型 */
  operateType: string;
  /** 请求方法（GET/POST等） */
  requestMethod: string;
  /** 请求URL */
  requestUrl: string;
  /** 请求参数（JSON字符串） */
  requestParams: string;
  /** 响应结果（JSON字符串） */
  responseResult: string;
  /** 客户端IP */
  ip: string;
  /** IP归属地 */
  location: string;
  /** 执行耗时（毫秒） */
  executeTime: number;
  /** 状态：1成功/0失败 */
  status: number;
  /** 错误信息 */
  errorMsg: string;
  /** 创建时间 */
  createTime: string;
}

/** 登录日志记录接口 */
export interface LoginLog {
  /** 日志ID */
  id: number;
  /** 登录用户名 */
  username: string;
  /** 登录类型 */
  loginType: string;
  /** 登录IP */
  ip: string;
  /** IP归属地 */
  location: string;
  /** 状态：1成功/0失败 */
  status: number;
  /** 登录消息 */
  msg: string;
  /** 登录时间 */
  loginTime: string;
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

/** 日志管理相关API */
export const logApi = {
  /** 分页查询操作日志 */
  getOperateLogPage: (pageNum = 1, pageSize = 20, params?: Record<string, any>): Promise<ApiResult<PageResult<OperateLog>>> =>
    request.get('/api/v1/log/operate', { params: { pageNum, pageSize, ...params } }),

  /** 分页查询登录日志 */
  getLoginLogPage: (pageNum = 1, pageSize = 20, params?: Record<string, any>): Promise<ApiResult<PageResult<LoginLog>>> =>
    request.get('/api/v1/log/login', { params: { pageNum, pageSize, ...params } }),
};