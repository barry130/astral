import { request } from './client';

/** 存储配置连通性测试结果（ConfigTestResp） */
export interface StorageConfigTestResult {
  healthStatus?: string;
  message?: string;
  botUsername?: string;
}

/** 下载链接（DownloadUrlView；永久链接不含 expiresAt） */
export interface StorageDownloadUrl {
  url: string;
  expiresAt?: number;
}

/** 永久公开链接（PermanentUrlView） */
export interface StoragePermanentUrl {
  url: string;
}

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
  /** CUSTOM_HTTP 渠道的乐观锁版本；其它 Provider 返回 null */
  configVersion?: number;
  isDefault?: number;
  healthStatus?: string;
  lastTestTime?: string;
  lastTestMessage?: string;
  remark?: string;
}

/** 绑定写入项（文件夹/授权共用）：priority 越小越优先，缺省 100 */
export interface StorageBindingReq {
  storageConfigId: number;
  priority?: number;
}

/** 绑定视图（管理端返回；停用配置也原样展示） */
export interface StorageBindingView {
  configId: number;
  configName?: string;
  providerType?: string;
  priority: number;
  configStatus?: string;
}

/** 调度策略（字典 storage_target_strategy：FAILOVER 按优先级 / ROUND_ROBIN 轮询） */
export type StorageTargetStrategy = 'FAILOVER' | 'ROUND_ROBIN';

/** 存储文件夹 */
export interface StorageFolder {
  id?: number;
  parentId?: number;
  folderName?: string;
  folderPath?: string;
  ownerType?: string;
  ownerId?: string;
  visibility?: string;
  status?: string;
  /** 文件夹级上传策略 JSON（null = 未配置，走默认行为） */
  uploadPolicy?: string | null;
  /** 调度策略：FAILOVER 按优先级故障转移 / ROUND_ROBIN 同层轮询 */
  storageStrategy?: string;
  /** 目标版本（绑定/策略/授权/状态/可见性变更后由后端递增） */
  targetVersion?: number;
  /** 绑定的存储配置（priority 升序；空列表 = 禁止上传，无默认回落） */
  configs?: StorageBindingView[];
  /** 只读解析结果：解析失败（无绑定/渠道停用）时为 null */
  resolvedConfigId?: number;
  resolvedProviderType?: string;
  resolvedTargetName?: string;
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

/** 文件夹授权行（configs 限定该主体上传使用的配置，须为文件夹绑定子集，空 = 跟随文件夹绑定） */
export interface FolderPermRow {
  subjectType: string;
  subjectId: string;
  permissions: string;
  configs?: StorageBindingReq[];
}

/** 授权视图（GET folders/{id}/permissions 返回） */
export interface FolderPermView {
  id: number;
  subjectType: string;
  subjectId: string;
  permissions: string;
  expiresTime?: string | null;
  configs: StorageBindingView[];
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
  myPermissions: string;
  parentId?: number | null;
  /** 绑定的存储配置 id 列表（priority 升序；空 = 未绑定，禁止上传） */
  bindingConfigIds?: number[];
  /** 目标版本（绑定/策略/授权/状态/可见性变更后由后端递增） */
  targetVersion?: number | null;
  /** 只读解析结果（无绑定/渠道停用时为 null，此时上传会被拒绝） */
  resolvedConfigId?: number | null;
  resolvedProviderType?: string | null;
  resolvedTargetName?: string | null;
}

export interface FileManagerQuery {
  current?: number;
  size?: number;
  folderId?: number;
  keyword?: string;
  category?: string;
  visibility?: string;
  sort?: string;
}

export interface FileBatchRequest {
  publicIds: string[];
  action: 'move' | 'delete' | 'visibility';
  folderId?: number;
  visibility?: string;
}

export interface FileBatchResult {
  succeeded: string[];
  failed: { publicId: string; message: string }[];
}

/** 两步上传操作状态（字典 storage_upload_state）；只有 COMMITTED 代表上传完成 */
export type UploadOperationState =
  | 'CREATED'
  | 'RECEIVING'
  | 'SENDING'
  | 'REMOTE_SUCCEEDED'
  | 'COMMITTED'
  | 'FAILED'
  | 'EXPIRED'
  | 'UNKNOWN_OUTCOME';

/** 创建上传意图结果（POST /api/v1/all/storage/upload-operations） */
export interface UploadOperation {
  uploadId: string;
  publicId: string;
  state: string;
  canSendContent: boolean;
  contentPath: string;
  maxBytes: number;
  intentTtlSeconds: number;
}

/** 正文 PUT 结果（UploadContentResp）；contentAccepted=false 表示重复提交、服务端未读正文，应转 GET 查询 */
export interface UploadContentResult {
  contentAccepted: boolean;
  operation: UploadOperationStatus;
  httpStatus?: number | null;
  safeCode?: string | null;
}

/** 上传操作查询结果（UploadOperationView）；HTTP 200 只代表请求被受理，不代表上传成功 */
export interface UploadOperationStatus {
  uploadId: string;
  publicId: string;
  state: string;
  canSendContent: boolean;
  contentPath: string;
  maxBytes: number;
  folderId?: number;
  originalName?: string;
  contentType?: string;
  expectedSize?: number;
  /** 仅在 COMMITTED 后非空 */
  fileId?: string;
  /** 固定安全码，不含上游正文与凭据 */
  safeFailureCode?: string;
  intentExpiresAt?: number;
  sendDeadline?: number;
  quotaCharge?: string;
  quotaDay?: string;
}

// ==================== CUSTOM_HTTP 渠道（设计 v1.1 §4） ====================

/** 认证方式：NONE 无认证；HEADER/QUERY/FORM 需 name+valueTemplate；BASIC 需双密钥 */
export type HttpAuthMode = 'NONE' | 'HEADER' | 'QUERY' | 'FORM' | 'BASIC';

/** 认证画像；valueTemplate 用 ${secret:name} 引用命名凭据 */
export interface HttpAuthSpec {
  mode: string;
  name?: string;
  valueTemplate?: string;
  usernameSecret?: string;
  passwordSecret?: string;
}

/** multipart 正文定义；mode 固定 MULTIPART */
export interface HttpBodySpec {
  mode: string;
  fileField: string;
  fileNameTemplate?: string;
  fields?: Record<string, string>;
}

/** 响应成功条件（1–8 条，全部满足才算成功） */
export interface HttpConditionSpec {
  pointer: string;
  op: string;
  type?: string;
  value?: string;
}

/** 响应解析与成功判定；urlPointer 必填 */
export interface HttpResponseSpec {
  success: HttpConditionSpec[];
  urlPointer: string;
  thumbPointer?: string;
  idPointer?: string;
  sizePointer?: string;
  sizeUnit?: string;
  widthPointer?: string;
  heightPointer?: string;
  numericStringPointers?: string[];
  allowedUrlOrigins: string[];
}

/** 渠道级限额：只能收紧应用硬上限（单文件 5 MiB，HttpUploadProfile.HARD_MAX_BYTES） */
export interface HttpLimitsSpec {
  maxBytes: number;
  allowedMimes: string[];
}

export interface HttpTimeoutsSpec {
  attemptMs: number;
}

/** 上传画像：只描述协议，不含任何凭据明文 */
export interface HttpUploadProfile {
  schemaVersion: number;
  presetId?: string;
  presetVersion?: number;
  endpoint: string;
  method: string;
  query?: Record<string, string>;
  headers?: Record<string, string>;
  auth: HttpAuthSpec;
  body: HttpBodySpec;
  response: HttpResponseSpec;
  limits: HttpLimitsSpec;
  timeouts: HttpTimeoutsSpec;
}

/** 密钥变更判别联合：KEEP 不动 / SET 带明文 / CLEAR 清除；非 SET 不得携带 value */
export interface HttpSecretChange {
  op: 'KEEP' | 'SET' | 'CLEAR';
  value?: string;
}

/** 读出侧只告知是否已配置，永不返回明文 */
export interface HttpSecretView {
  name: string;
  configured: boolean;
  updatedAt?: string;
}

export interface HttpChannelSummary {
  id: number;
  name: string;
  providerType: string;
  status: string;
  configVersion: number;
  isDefault?: number;
  healthStatus?: string;
  lastTestTime?: string;
  lastTestMessage?: string;
  remark?: string;
  createBy?: string;
  createTime?: string;
  updateBy?: string;
  updateTime?: string;
}

export interface HttpChannelView extends HttpChannelSummary {
  profile: HttpUploadProfile;
  configuredSecrets: HttpSecretView[];
  missingSecrets: string[];
  configError?: string;
}

export interface HttpChannelCreateRequest {
  name: string;
  profile: HttpUploadProfile;
  secretChanges?: Record<string, HttpSecretChange>;
  status?: string;
  remark?: string;
}

/** 更新必须带 expectedVersion（冲突 STORAGE038） */
export interface HttpChannelUpdateRequest extends HttpChannelCreateRequest {
  expectedVersion: number;
}

/** 离线校验入参：不读写数据库、不联网 */
export interface HttpChannelValidateRequest {
  profile: HttpUploadProfile;
  secretChanges?: Record<string, HttpSecretChange>;
  status?: string;
}

export interface HttpChannelValidation {
  valid: boolean;
  profile: HttpUploadProfile;
  configuredSecrets: HttpSecretView[];
  missingSecrets: string[];
}

export interface HttpChannelTestResult {
  success: boolean;
  safeCode?: string;
  safeMessage?: string;
  httpStatus?: number;
  remoteSizeBytes?: number;
  channelId?: number;
  configVersion?: number;
  requestId?: string;
  testedAt?: string;
}

/** 部署侧批准目标（只读，管理接口不能扩大） */
export interface HttpApprovedTarget {
  origin: string;
  path?: string;
  urlOrigins: string[];
}

/** 存储概览计数（GET /admin/plugin/storage/overview；configCount 全部配置，enabledConfigCount 启用，downConfigCount 健康探测异常） */
export interface StorageOverview {
  fileCount: number;
  folderCount: number;
  configCount: number;
  enabledConfigCount: number;
  downConfigCount: number;
  operator?: string | null;
}

export const storageApi = {
  // 概览
  getOverview: () => request.get<StorageOverview>('/api/v1/admin/plugin/storage/overview'),
  // 配置
  listConfigs: () => request.get<StorageConfig[]>('/api/v1/admin/plugin/storage/configs'),
  createConfig: (data: {
    name: string;
    providerType?: string;
    chatId?: string;
    workerBaseUrl?: string;
    providerOptions?: S3ProviderOptions;
    maxFileSize?: number;
    remark?: string;
  }) => request.post<StorageConfig>('/api/v1/admin/plugin/storage/configs', data),
  updateConfig: (
    id: number,
    data: {
      chatId?: string;
      workerBaseUrl?: string;
      providerOptions?: S3ProviderOptions;
      maxFileSize?: number;
      remark?: string;
      status?: string;
    },
  ) => request.put<StorageConfig>(`/api/v1/admin/plugin/storage/configs/${id}`, data),
  deleteConfig: (id: number) => request.delete<null>(`/api/v1/admin/plugin/storage/configs/${id}`),
  testConfig: (id: number) =>
    request.post<StorageConfigTestResult>(`/api/v1/admin/plugin/storage/configs/${id}/test`),
  setDefaultConfig: (id: number) =>
    request.post<null>(`/api/v1/admin/plugin/storage/configs/${id}/default`),

  // 文件夹与授权
  listFolders: () => request.get<StorageFolder[]>('/api/v1/admin/plugin/storage/folders'),
  // 绑定：configs 为全量替换（null = 不改动，空列表 = 清空绑定即禁止上传）；strategy 缺省 FAILOVER
  createFolder: (data: {
    parentId?: number;
    folderName: string;
    visibility?: string;
    policy?: FolderUploadPolicy;
    storageStrategy?: string;
    configs?: StorageBindingReq[];
  }) => request.post<StorageFolder>('/api/v1/admin/plugin/storage/folders', data),
  updateFolder: (
    id: number,
    data: {
      folderName?: string;
      visibility?: string;
      status?: string;
      policy?: FolderUploadPolicy;
      storageStrategy?: string;
      configs?: StorageBindingReq[];
    },
  ) => request.put<StorageFolder>(`/api/v1/admin/plugin/storage/folders/${id}`, data),
  deleteFolder: (id: number) => request.delete<null>(`/api/v1/admin/plugin/storage/folders/${id}`),
  getFolderPermissions: (id: number) =>
    request.get<FolderPermView[]>(`/api/v1/admin/plugin/storage/folders/${id}/permissions`),
  saveFolderPermissions: (id: number, rows: FolderPermRow[]) =>
    request.put<null>(`/api/v1/admin/plugin/storage/folders/${id}/permissions`, { rows }),

  // 文件
  pageFiles: (params: { current?: number; size?: number; folderId?: number; keyword?: string }) =>
    request.get<StoragePageResult<StorageFile>>('/api/v1/admin/plugin/storage/files', { params }),
  deleteFile: (publicId: string) =>
    request.delete<null>(`/api/v1/admin/plugin/storage/files/${publicId}`),

  // 任务与审计
  pageTasks: (params: { current?: number; size?: number }) =>
    request.get<StoragePageResult<StorageTask>>('/api/v1/admin/plugin/storage/tasks', { params }),
  pageAudit: (params: { current?: number; size?: number }) =>
    request.get<StoragePageResult<StorageAudit>>('/api/v1/admin/plugin/storage/audit', { params }),

  // 上传（用户端）
  listMyFolders: () => request.get<MyStorageFolder[]>('/api/v1/all/storage/folders'),
  myFolderFiles: (folderId: number, params: { current?: number; size?: number }) =>
    request.get<StoragePageResult<StorageFile>>(`/api/v1/all/storage/folders/${folderId}/files`, {
      params,
    }),
  deleteMyFile: (publicId: string) => request.delete<null>(`/api/v1/all/storage/files/${publicId}`),
  changeFileVisibility: (publicId: string, value: string) =>
    request.put<StorageFile>(`/api/v1/all/storage/files/${publicId}/visibility`, null, {
      params: { value },
    }),
  uploadTicket: (data: {
    folderId: number;
    fileName: string;
    contentType: string;
    sizeBytes: number;
  }) => request.post<TicketView>('/api/v1/all/storage/upload-ticket', data),
  downloadUrl: (publicId: string) =>
    request.post<StorageDownloadUrl>(`/api/v1/all/storage/files/${publicId}/download-url`),
  permanentUrl: (publicId: string) =>
    request.post<StoragePermanentUrl>(`/api/v1/all/storage/files/${publicId}/permanent-url`),
  registerUpload: (uploadId: string) =>
    request.post<StorageFile>('/api/v1/all/storage/files/register', { uploadId }),

  // 共用文件管理；后台写入沿用 admin:storage:edit，用户端按文件夹授权。
  managerFiles: (params: FileManagerQuery, admin = false) =>
    request.get<StoragePageResult<StorageFile>>(
      admin ? '/api/v1/admin/plugin/storage/manager/files' : '/api/v1/all/storage/files',
      { params },
    ),
  managerUpdate: (
    publicId: string,
    data: { originalName?: string; folderId?: number },
    admin = false,
  ) =>
    request.put<StorageFile>(
      `${admin ? '/api/v1/admin/plugin' : '/api/v1/all'}/storage/files/${publicId}`,
      data,
    ),
  managerBatch: (data: FileBatchRequest, admin = false) =>
    request.post<FileBatchResult>(
      `${admin ? '/api/v1/admin/plugin' : '/api/v1/all'}/storage/files/batch`,
      data,
    ),
  managerUrl: (publicId: string, permanent = false, admin = false) =>
    request.post<StorageDownloadUrl | StoragePermanentUrl>(
      `${admin ? '/api/v1/admin/plugin' : '/api/v1/all'}/storage/files/${publicId}/${permanent ? 'permanent-url' : 'download-url'}`,
    ),
  managerTicket: (
    data: { folderId: number; fileName: string; contentType: string; sizeBytes: number },
    admin = false,
  ) =>
    request.post<TicketView>(
      `${admin ? '/api/v1/admin/plugin' : '/api/v1/all'}/storage/upload-ticket`,
      data,
    ),
  createMyFolder: (data: { parentId?: number; folderName: string }) =>
    request.post<StorageFolder>('/api/v1/all/storage/folders', data),
  renameMyFolder: (id: number, folderName: string) =>
    request.put<StorageFolder>(`/api/v1/all/storage/folders/${id}`, { folderName }),
  deleteMyFolder: (id: number) => request.delete<null>(`/api/v1/all/storage/folders/${id}`),

  // 两步上传（设计 v1.1 §7）：POST/PUT 返回 200 只代表请求被受理，只有 state=COMMITTED 才算完成
  // 字段名对齐后端 UploadOperationDtos.UploadCreateReq（contentType，不是 mimeType）
  createUploadOperation: (
    folderId: number,
    fileName: string,
    contentType: string,
    sizeBytes: number,
    sha256: string,
    idempotencyKey: string,
  ) =>
    request.post<UploadOperation>(
      '/api/v1/all/storage/upload-operations',
      { folderId, fileName, contentType, sizeBytes, sha256 },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    ),
  putUploadContent: (
    uploadId: string,
    content: Blob | ArrayBuffer,
    contentType: string,
    onProgress?: (percent: number) => void,
  ) =>
    request.put<UploadContentResult>(
      `/api/v1/all/storage/upload-operations/${uploadId}/content`,
      content,
      {
        // 正文 PUT 单独放宽超时（服务端 90s 截止 + 登记），其余元数据请求保持全局 30s
        timeout: 120_000,
        headers: { 'Content-Type': contentType },
        onUploadProgress: (event) => {
          if (!onProgress || !event.total) return;
          onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)));
        },
      },
    ),
  getUploadOperation: (uploadId: string, silent = false) =>
    request.get<UploadOperationStatus>(`/api/v1/all/storage/upload-operations/${uploadId}`, {
      silent,
    }),

  // CUSTOM_HTTP 渠道（/api/v1/admin/plugin/storage/http-configs）：凭据只走 KEEP/SET/CLEAR
  listHttpChannels: () =>
    request.get<HttpChannelSummary[]>('/api/v1/admin/plugin/storage/http-configs'),
  listApprovedTargets: () =>
    request.get<HttpApprovedTarget[]>('/api/v1/admin/plugin/storage/http-configs/approved-targets'),
  getHttpChannel: (id: number) =>
    request.get<HttpChannelView>(`/api/v1/admin/plugin/storage/http-configs/${id}`),
  createHttpChannel: (data: HttpChannelCreateRequest) =>
    request.post<HttpChannelView>('/api/v1/admin/plugin/storage/http-configs', data),
  updateHttpChannel: (id: number, data: HttpChannelUpdateRequest) =>
    request.put<HttpChannelView>(`/api/v1/admin/plugin/storage/http-configs/${id}`, data),
  deleteHttpChannel: (id: number) =>
    request.delete<null>(`/api/v1/admin/plugin/storage/http-configs/${id}`),
  validateHttpChannel: (data: HttpChannelValidateRequest) =>
    request.post<HttpChannelValidation>('/api/v1/admin/plugin/storage/http-configs/validate', data),
  testHttpChannel: (id: number) =>
    request.post<HttpChannelTestResult>(`/api/v1/admin/plugin/storage/http-configs/${id}/test`),
};
