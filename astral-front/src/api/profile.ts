import { request, ApiResult } from './client';

/** 我的资料 */
export interface ProfileInfo {
  id: number;
  username: string;
  nickname?: string;
  email?: string;
  userType?: string;
  loginIp?: string;
  loginTime?: string;
  pwdUpdateTime?: string;
  totpEnabled: boolean;
  mustChangePassword: boolean;
}

/** 在线会话 */
export interface ProfileSession {
  id: string;
  userId: number;
  username?: string;
  token?: string;
  expireTime?: string;
  loginIp?: string;
  status?: number;
  createTime?: string;
}

/** TOTP 绑定信息 */
export interface TotpSetupInfo {
  /** Base32 密钥（手动输入备份用） */
  secret: string;
  /** otpauth:// URI（前端渲染二维码） */
  otpauthUri: string;
}

/** 个人中心 API（后端 /api/v1/admin/profile/**，登录即可访问） */
export const profileApi = {
  /** 我的资料 */
  me: (): Promise<ApiResult<ProfileInfo>> =>
    request.get('/api/v1/admin/profile/me'),

  /** 修改密码（oldPassword/newPassword 均为 RSA 加密串，与登录同一加密通道） */
  changePassword: (oldPassword: string, newPassword: string): Promise<ApiResult<void>> =>
    request.put('/api/v1/admin/profile/password', { oldPassword, newPassword }),

  /** 我的在线会话 */
  sessions: (pageNum = 1, pageSize = 10): Promise<ApiResult<{ records: ProfileSession[]; total: number }>> =>
    request.get('/api/v1/admin/profile/sessions', { params: { pageNum, pageSize } }),

  /** 下线我的指定会话 */
  revokeSession: (token: string): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/profile/sessions/${encodeURIComponent(token)}`),

  /** 生成 TOTP 密钥（返回 secret + otpauth URI，绑定后未生效） */
  totpSetup: (): Promise<ApiResult<TotpSetupInfo>> =>
    request.post('/api/v1/admin/profile/totp/setup'),

  /** 确认启用 TOTP（6 位动态码校验） */
  totpEnable: (code: string): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/profile/totp/enable', { code }),

  /** 关闭 TOTP（需 RSA 加密的登录密码校验，字段复用 oldPassword） */
  totpDisable: (passwordEncrypted: string): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/profile/totp/disable', { oldPassword: passwordEncrypted }),
};
