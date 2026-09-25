import { request } from './client';

/** 存储配置（providerOptions 为 JSON 字符串；TELEGRAM 只存非敏感信息，R2/S3 的 secretAccessKey 返回时打码为 ******） */
export interface StorageConfig {
  id?: number;
  name?: string;
  providerType?: string;
  chatId?: string;
  workerBaseUrl?: string;
  providerOptions?: string | null;
  maxFileSize?: number;
  status?: string;
  isDefault?: number;
  healthStatus?: string;
  lastTestTime?: string;
  lastTestMessage?: string;
  remark?: string;
}

/** 存储文件夹 */
export interface StorageFolder {
  id?: number;
  parentId?: number;
  folderName?: string;
  folderPath?: string;
  storageConfigId?: number;
  ownerType?: string;
  ownerId?: string;
  visibility?: string;
  status?: string;
  /** 文件夹级上传策略 JSON（null = 未配置，走默认行为） */
  uploadPolicy?: string | null;
}

/** 文件夹上传策略（对应后端 UploadPolicyService.PolicySpec） */
export interface FolderUploadPolicy {
  requireLogin?: boolean;
  minSizeBytes?: number;
  maxSizeBytes?: number;
  allowedMimes?: string[];
  allowedExtensions?: string[];
  dailyUploadLimit?: number;
  forceVisibility?: string;
  verifyContent?: string;
  maxPixels?: number;
}

/** 文件夹授权行 */
export interface FolderPermRow {
  subjectType: string;
  subjectId: string;
  permissions: string;
}

/** 存储文件 */
export interface StorageFile {
  id?: number;
  publicId?: string;
  folderId?: number;
  storageConfigId?: number;
  providerType?: string;
  originalName?: string;
  contentType?: string;
  sizeBytes?: number;
  contentVersion?: number;
  visibility?: string;
  status?: string;
  uploaderType?: string;
  uploaderId?: string;
  createTime?: string;
}

/** 存储任务 */
export interface StorageTask {
  id?: number;
  taskType?: string;
  fileId?: number;
  retryCount?: number;
  status?: string;
  errorMessage?: string;
  createTime?: string;
}

/** 存储审计 */
export interface StorageAudit {
  id?: number;
  action?: string;
  subjectType?: string;
  subjectId?: string;
  targetType?: string;
  targetId?: string;
  detail?: string;
  result?: string;
  createTime?: string;
}

export interface StoragePageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
}

export interface TicketView {
  uploadUrl: string;
  method: string;
  formField?: string;
  uploadId: string;
  expiresAt: number;
  /** 仅 UPYUN 表单直传非空：multipart POST 时随 policy/authorization 一并提交 */
  form?: { policy: string; authorization: string };
}

/** 对象存储系配置的 provider_options 结构（各 Provider 取所需字段；密钥编辑时留空表示保持不变） */
export interface S3ProviderOptions {
  endpoint?: string;
  region?: string;
  bucket?: string;
  accessKeyId?: string;
  secretAccessKey?: string;
  secretId?: string;
  secretKey?: string;
  accessKeySecret?: string;
  operator?: string;
  password?: string;
  tokenKey?: string;
  publicBaseUrl?: string;
}

/** 用户侧「我的文件夹」（含当前用户权限集合：ALL 或逗号分隔权限名） */
export interface MyStorageFolder {
  id: number;
  folderName: string;
  folderPath: string;
  visibility: string;
  status: string;
  storageConfigId: number;
  myPermissions: string;
}

export const storageApi = {
  // 配置
  listConfigs: () => request.get('/api/v1/admin/plugin/storage/configs'),
  createConfig: (data: {
    name: string; providerType?: string; chatId?: string; workerBaseUrl?: string;
    providerOptions?: S3ProviderOptions; maxFileSize?: number; remark?: string;
  }) => request.post('/api/v1/admin/plugin/storage/configs', data),
  updateConfig: (id: number, data: {
    chatId?: string; workerBaseUrl?: string; providerOptions?: S3ProviderOptions;
    maxFileSize?: number; remark?: string; status?: string;
  }) => request.put(`/api/v1/admin/plugin/storage/configs/${id}`, data),
  deleteConfig: (id: number) => request.delete(`/api/v1/admin/plugin/storage/configs/${id}`),
  testConfig: (id: number) => request.post(`/api/v1/admin/plugin/storage/configs/${id}/test`),
  setDefaultConfig: (id: number) => request.post(`/api/v1/admin/plugin/storage/configs/${id}/default`),

  // 文件夹与授权
  listFolders: () => request.get('/api/v1/admin/plugin/storage/folders'),
  createFolder: (data: { parentId?: number; folderName: string; configId?: number; visibility?: string; policy?: FolderUploadPolicy }) =>
    request.post('/api/v1/admin/plugin/storage/folders', data),
  updateFolder: (id: number, data: { folderName?: string; visibility?: string; status?: string; configId?: number; policy?: FolderUploadPolicy }) =>
    request.put(`/api/v1/admin/plugin/storage/folders/${id}`, data),
  deleteFolder: (id: number) => request.delete(`/api/v1/admin/plugin/storage/folders/${id}`),
  getFolderPermissions: (id: number) => request.get(`/api/v1/admin/plugin/storage/folders/${id}/permissions`),
  saveFolderPermissions: (id: number, rows: FolderPermRow[]) =>
    request.put(`/api/v1/admin/plugin/storage/folders/${id}/permissions`, { rows }),

  // 文件
  pageFiles: (params: { current?: number; size?: number; folderId?: number; keyword?: string }) =>
    request.get('/api/v1/admin/plugin/storage/files', { params }),
  deleteFile: (publicId: string) => request.delete(`/api/v1/admin/plugin/storage/files/${publicId}`),

  // 任务与审计
  pageTasks: (params: { current?: number; size?: number }) =>
    request.get('/api/v1/admin/plugin/storage/tasks', { params }),
  pageAudit: (params: { current?: number; size?: number }) =>
    request.get('/api/v1/admin/plugin/storage/audit', { params }),

  // 上传（用户端）
  listMyFolders: () => request.get('/api/v1/all/storage/folders'),
  myFolderFiles: (folderId: number, params: { current?: number; size?: number }) =>
    request.get(`/api/v1/all/storage/folders/${folderId}/files`, { params }),
  deleteMyFile: (publicId: string) => request.delete(`/api/v1/all/storage/files/${publicId}`),
  changeFileVisibility: (publicId: string, value: string) =>
    request.put(`/api/v1/all/storage/files/${publicId}/visibility`, null, { params: { value } }),
  uploadTicket: (data: { folderId: number; fileName: string; contentType: string; sizeBytes: number }) =>
    request.post('/api/v1/all/storage/upload-ticket', data),
  downloadUrl: (publicId: string) =>
    request.post(`/api/v1/all/storage/files/${publicId}/download-url`),
  permanentUrl: (publicId: string) =>
    request.post(`/api/v1/all/storage/files/${publicId}/permanent-url`),
  registerUpload: (uploadId: string) =>
    request.post('/api/v1/all/storage/files/register', { uploadId }),
};
