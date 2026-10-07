import { request, ApiResult } from './client';

/** 登录请求参数接口 */
export interface LoginRequest {
  /** 用户名 */
  username: string;
  /** 密码 */
  password: string;
  /** TOTP 动态验证码：启用二次验证的账号必填（缺失时后端返回 errorCode=AUTH010） */
  totpCode?: string;
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
  /** 是否需要强制改密：1=是（管理端登录后必须先到个人中心修改密码） */
  mustChangePassword?: number;
  /**
   * 管理端 CSRF 双提交令牌（仅管理端返回）。
   *
   * 后端同时把它写在非 HttpOnly 的 `astral_csrf` Cookie 里，但**跨域直连**部署时
   * 页面所在域读不到 API 域的 Cookie，因此响应体里也带一份，由 client.ts 缓存在内存。
   */
  csrfToken?: string;
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