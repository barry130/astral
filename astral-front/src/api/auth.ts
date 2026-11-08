import { request, type ApiResult, type ApiRequestConfig } from './client';

/** 登录请求参数接口 */
export interface LoginRequest {
  username: string;
  password: string;
  /** 启用二次验证的账号必填；缺失时后端返回 AUTH010。 */
  totpCode?: string;
}

/** /auth/info 的身份快照，不包含可重新使用的认证凭据。 */
export interface SessionInfoResponse {
  id: number;
  username: string;
  nickname: string;
  userType?: string;
  roles: string[];
  permissions: string[];
  mustChangePassword?: number;
  /** Cookie 登录的双提交值；跨域页面读不到 API 域 Cookie，ADMIN 与 APP 均需要。 */
  csrfToken?: string | null;
}

/** 新登录保留原有调用方凭据契约，不能把整个响应存入用户上下文或持久化。 */
export interface LoginResponse extends SessionInfoResponse {
  token: string;
}

/** 用户上下文从认证响应生成身份快照。 */
export function sessionInfoFromResponse(source: SessionInfoResponse): SessionInfoResponse {
  if (
    !Array.isArray(source.roles) ||
    !source.roles.every((role) => typeof role === 'string') ||
    !Array.isArray(source.permissions) ||
    !source.permissions.every((permission) => typeof permission === 'string')
  ) {
    throw new Error('登录信息格式异常，请重试');
  }
  return {
    id: source.id,
    username: source.username,
    nickname: source.nickname,
    userType: source.userType,
    roles: [...source.roles],
    permissions: [...source.permissions],
    mustChangePassword: source.mustChangePassword,
    csrfToken: source.csrfToken,
  };
}

export const authApi = {
  login: (data: LoginRequest): Promise<ApiResult<LoginResponse>> =>
    request.post<LoginResponse>('/api/v1/all/auth/login', data),
  logout: (): Promise<ApiResult<void>> => request.post<void>('/api/v1/all/auth/logout'),
  getUserInfo: (config?: ApiRequestConfig): Promise<ApiResult<SessionInfoResponse>> =>
    request.get<SessionInfoResponse>('/api/v1/all/auth/info', config),
};
