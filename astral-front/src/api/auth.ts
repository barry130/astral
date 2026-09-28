import { request, ApiResult } from './client';

/** 登录请求参数接口 */
export interface LoginRequest {
  /** 用户名 */
  username: string;
  /** 密码 */
  password: string;
}

/** 登录响应数据接口（字段与后端 LoginResponse.java 保持一致） */
export interface LoginResponse {
  /** 认证令牌 */
  token: string;
  /** 用户ID（后端 Long id，注意不是 userId） */
  id: number;
  /** 用户名 */
  username: string;
  /** 用户昵称 */
  nickname: string;
  /** 用户类型：ADMIN 管理端 / APP 轻听 App 端 */
  userType?: string;
  /** 用户角色编码列表 */
  roles: string[];
  /** 用户权限编码列表 */
  permissions: string[];
}

/** 认证相关API接口 */
export const authApi = {
  /** 用户登录 */
  login: (data: LoginRequest): Promise<ApiResult<LoginResponse>> =>
    request.post('/api/v1/all/auth/login', data),

  /** 用户登出 */
  logout: (): Promise<ApiResult<void>> =>
    request.post('/api/v1/all/auth/logout'),

  /** 获取当前用户信息（后端 /api/v1/all/auth/info 返回的同样是 LoginResponse） */
  getUserInfo: (): Promise<ApiResult<LoginResponse>> =>
    request.get('/api/v1/all/auth/info'),
};