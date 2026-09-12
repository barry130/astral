import { request, ApiResult } from './client';

/** 登录请求参数接口 */
export interface LoginRequest {
  /** 用户名 */
  username: string;
  /** 密码 */
  password: string;
}

/** 登录响应数据接口 */
export interface LoginResponse {
  /** 认证令牌 */
  token: string;
  /** 用户ID */
  userId: number;
  /** 用户名 */
  username: string;
  /** 用户昵称 */
  nickname: string;
  /** 用户头像URL */
  avatar: string;
  /** 用户角色列表 */
  roles: string[];
  /** 用户权限列表 */
  permissions: string[];
  /** 登录时间 */
  loginTime: string;
}

/** 认证相关API接口 */
export const authApi = {
  /** 用户登录 */
  login: (data: LoginRequest): Promise<ApiResult<LoginResponse>> =>
    request.post('/api/v1/all/auth/login', data),

  /** 用户登出 */
  logout: (): Promise<ApiResult<void>> =>
    request.post('/api/v1/all/auth/logout'),

  /** 获取当前用户信息 */
  getUserInfo: (): Promise<ApiResult<{ userId: number; username: string; isLogin: boolean }>> =>
    request.get('/api/v1/all/auth/info'),
};