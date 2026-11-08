import { request, type ApiResult } from './client';
import type { PageResult } from './types';
import { pickWriteFields } from './write-fields';

export interface SysConfig {
  id: number;
  configName: string;
  configKey: string;
  configValue: string;
  configType: number;
  description: string;
  createTime: string;
}
export type ConfigWrite = Partial<Omit<SysConfig, 'id' | 'createTime'>>;
export const configPayload = (values: ConfigWrite): ConfigWrite =>
  pickWriteFields(values, ['configName', 'configKey', 'configValue', 'configType', 'description']);
export const configApi = {
  getPage: (pageNum: number, pageSize: number, configName?: string) =>
    request.get<PageResult<SysConfig>>('/api/v1/admin/system/config/page', {
      params: { pageNum, pageSize, configName },
    }),
  create: (values: ConfigWrite): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/system/config', configPayload(values)),
  update: (id: number, values: ConfigWrite): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/config/${id}`, configPayload(values)),
  delete: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/system/config/${id}`),
};

export interface Role {
  id: number;
  roleCode: string;
  roleName: string;
  description: string;
  status: number;
  sort: number;
  isSuper?: number;
}
export type RoleWrite = Partial<
  Pick<Role, 'roleCode' | 'roleName' | 'description' | 'status' | 'sort'>
>;
export const rolePayload = (values: RoleWrite, creating = false): RoleWrite =>
  creating
    ? pickWriteFields(values, ['roleCode', 'roleName', 'description', 'status', 'sort'])
    : pickWriteFields(values, ['roleName', 'description', 'status', 'sort']);

export interface PermissionNode {
  id: number | null;
  permissionCode: string;
  permissionName: string;
  parentId: number;
  type: number;
  domain?: string;
  children?: PermissionNode[];
}
export const roleApi = {
  getPage: (pageNum: number, pageSize: number) =>
    request.get<PageResult<Role>>('/api/v1/admin/system/role/page', {
      params: { pageNum, pageSize },
    }),
  getAll: (): Promise<ApiResult<Role[]>> => request.get('/api/v1/admin/system/role/all'),
  create: (values: RoleWrite): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/system/role', rolePayload(values, true)),
  update: (id: number, values: RoleWrite): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/role/${id}`, rolePayload(values)),
  delete: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/system/role/${id}`),
  getPermissions: (id: number): Promise<ApiResult<PermissionNode[]>> =>
    request.get(`/api/v1/admin/system/role/${id}/permissions`),
  assignPermissions: (id: number, permissionIds: number[]): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/role/${id}/permissions`, { permissionIds }),
};

export interface PermissionRow {
  id: number;
  domain?: string;
  permissionCode: string;
  permissionName: string;
  type?: number;
  url?: string;
  method?: string;
  icon?: string;
  parentId?: number;
  sort?: number;
  status?: number;
  remark?: string;
}
export type PermissionWrite = Partial<Omit<PermissionRow, 'id' | 'remark'>>;
export const permissionPayload = (values: PermissionWrite): PermissionWrite =>
  pickWriteFields(values, [
    'domain',
    'permissionCode',
    'permissionName',
    'type',
    'url',
    'method',
    'icon',
    'parentId',
    'sort',
    'status',
  ]);

/** The leading admin/user/all segment is a side, not the permission domain. */
export function permissionDomain(record: Pick<PermissionRow, 'domain' | 'permissionCode'>): string {
  if (record.domain?.trim()) return record.domain.trim();
  const parts = (record.permissionCode || '').split(':');
  return (['admin', 'user', 'all'].includes(parts[0]) ? parts[1] : parts[0]) || 'system';
}

export function groupPermissionRows(rows: PermissionRow[]): PermissionNode[] {
  const groups = new Map<string, PermissionNode[]>();
  for (const row of rows) {
    if (!row.permissionCode || row.permissionCode.includes('*')) continue;
    const domain = permissionDomain(row);
    const children = groups.get(domain) || [];
    children.push({ ...row, parentId: row.parentId ?? 0, type: row.type ?? 0 });
    groups.set(domain, children);
  }
  return [...groups]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([domain, children]) => ({
      id: null,
      permissionCode: `domain:${domain}`,
      permissionName: domain,
      parentId: 0,
      type: 0,
      children: children.sort(
        (a, b) => a.type - b.type || a.permissionCode.localeCompare(b.permissionCode),
      ),
    }));
}

export function paginateAdminRecords<T>(
  rows: ReadonlyArray<T>,
  query: { current: number; pageSize: number },
): PageResult<T> {
  const size =
    Number.isFinite(query.pageSize) && query.pageSize > 0
      ? Math.max(1, Math.min(1000, Math.trunc(query.pageSize)))
      : 10;
  const pages = Math.ceil(rows.length / size);
  const requestedPage = Number.isFinite(query.current) ? Math.trunc(query.current) : 1;
  const current = Math.max(1, Math.min(requestedPage, pages || 1));
  return {
    records: rows.slice((current - 1) * size, current * size),
    current,
    size,
    total: rows.length,
    pages,
  };
}

export interface AdminDictType {
  id: number;
  dictCode: string;
  dictName: string;
  dataType: string | null;
  jdbcType: string | null;
  dataLength: number | null;
  description: string | null;
  status: number;
}
export interface AdminDictData {
  id: number;
  dictTypeId: number;
  dictLabel: string;
  dictValue: string;
  dictSort: number;
  cssClass?: string | null;
  listClass?: string | null;
  isDefault: number;
  status: number;
  description?: string | null;
}
export type DictTypeWrite = Partial<Omit<AdminDictType, 'id'>>;
export type DictDataWrite = Partial<Omit<AdminDictData, 'id'>>;
export const dictTypePayload = (values: DictTypeWrite): DictTypeWrite =>
  pickWriteFields(values, [
    'dictCode',
    'dictName',
    'dataType',
    'jdbcType',
    'dataLength',
    'description',
    'status',
  ]);
export const dictDataPayload = (values: DictDataWrite): DictDataWrite =>
  pickWriteFields(values, [
    'dictTypeId',
    'dictLabel',
    'dictValue',
    'dictSort',
    'cssClass',
    'listClass',
    'isDefault',
    'status',
    'description',
  ]);
export function paginateDictTypes(
  rows: AdminDictType[],
  query: { current: number; pageSize: number; keyword: string },
): PageResult<AdminDictType> {
  const keyword = query.keyword.trim().toLowerCase();
  return paginateAdminRecords(
    rows.filter((row) =>
      [row.dictCode, row.dictName].some((value) =>
        String(value || '')
          .toLowerCase()
          .includes(keyword),
      ),
    ),
    query,
  );
}
/** Admin CRUD only; the shared dictionary option reader is deliberately unchanged. */
export const adminDictApi = {
  getAllTypes: (): Promise<ApiResult<AdminDictType[]>> =>
    request.get('/api/v1/admin/system/dict/type/all'),
  createType: (values: DictTypeWrite): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/system/dict/type', dictTypePayload(values)),
  updateType: (id: number, values: DictTypeWrite): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/dict/type/${id}`, dictTypePayload(values)),
  deleteType: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/system/dict/type/${id}`),
  getDataPage: (
    pageNum: number,
    pageSize: number,
    dictTypeId: number,
  ): Promise<ApiResult<PageResult<AdminDictData>>> => {
    if (!Number.isSafeInteger(dictTypeId) || dictTypeId <= 0)
      throw new Error('请先选择有效的字典类型');
    return request.get('/api/v1/admin/system/dict/data/page', {
      params: { pageNum, pageSize, dictTypeId },
    });
  },
  createData: (values: DictDataWrite): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/system/dict/data', dictDataPayload(values)),
  updateData: (id: number, values: DictDataWrite): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/dict/data/${id}`, dictDataPayload(values)),
  deleteData: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/system/dict/data/${id}`),
};

export async function loadDictDataPage(
  pageNum: number,
  pageSize: number,
  dictTypeId: number,
): Promise<PageResult<AdminDictData>> {
  const result = await adminDictApi.getDataPage(pageNum, pageSize, dictTypeId);
  const page = result.data;
  if (result.code !== 200) throw new Error(result.msg || '加载字典数据失败');
  if (!page || !Array.isArray(page.records) || !Number.isSafeInteger(page.total) || page.total < 0)
    throw new Error('字典分页数据缺失');
  if (
    page.records.some(
      (row) =>
        !row || !Number.isSafeInteger(row.id) || row.id <= 0 || row.dictTypeId !== dictTypeId,
    )
  )
    throw new Error('字典数据所属类型或标识不匹配');
  if (
    (page.current !== undefined && (!Number.isSafeInteger(page.current) || page.current <= 0)) ||
    (page.size !== undefined && (!Number.isSafeInteger(page.size) || page.size <= 0))
  )
    throw new Error('字典分页参数无效');
  return page;
}

/** RoleController.page has no keyword contract; this pagination is explicitly client-side. */
export function paginateRoles(
  rows: Role[],
  query: { current: number; pageSize: number; keyword: string },
): PageResult<Role> {
  const keyword = query.keyword.trim().toLowerCase();
  const filtered = rows.filter((row) =>
    [row.roleCode, row.roleName, row.description].some((value) =>
      String(value || '')
        .toLowerCase()
        .includes(keyword),
    ),
  );
  return paginateAdminRecords(filtered, query);
}

export const permissionApi = {
  getTree: (groupBy?: 'domain'): Promise<ApiResult<PermissionNode[]>> =>
    request.get('/api/v1/admin/system/permission/tree', { params: groupBy ? { groupBy } : {} }),
  /** Client filtering needs every page, not a silent first-1000 cap. */
  async getAll(): Promise<PermissionRow[]> {
    const rows = new Map<number, PermissionRow>();
    let pageNum = 1;
    let expectedTotal = 0;
    do {
      const response = await request.get<PageResult<PermissionRow>>(
        '/api/v1/admin/system/permission/page',
        { params: { pageNum, pageSize: 1000 } },
      );
      if (response.code !== 200) throw new Error(response.msg || '获取权限列表失败');
      const page = response.data;
      if (
        !page ||
        !Array.isArray(page.records) ||
        !Number.isSafeInteger(page.total) ||
        page.total < 0
      ) {
        throw new Error('权限分页数据缺失或无效，请重试');
      }
      if (pageNum === 1) expectedTotal = page.total;
      const previous = rows.size;
      for (const row of page.records) {
        if (!Number.isSafeInteger(row.id) || row.id <= 0) throw new Error('权限定义标识无效');
        rows.set(row.id, row);
      }
      if (rows.size === previous && rows.size < expectedTotal) {
        throw new Error('权限列表分页未返回完整数据，请重试');
      }
      pageNum += 1;
    } while (rows.size < expectedTotal);
    return [...rows.values()];
  },
  create: (values: PermissionWrite): Promise<ApiResult<void>> =>
    request.post('/api/v1/admin/system/permission', permissionPayload(values)),
  update: (id: number, values: PermissionWrite): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/permission/${id}`, permissionPayload(values)),
  delete: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/system/permission/${id}`),
};

export interface SysUser {
  id: number;
  username: string;
  nickname: string;
  email: string;
  phone: string;
  avatar?: string;
  status: number;
  statusReason?: string;
  userType?: string;
  password?: string;
  createTime: string;
  updateTime: string;
}
export type UserProfileWrite = Partial<Pick<SysUser, 'nickname' | 'email' | 'phone' | 'avatar'>>;
export type UserCreateWrite = UserProfileWrite &
  Partial<Pick<SysUser, 'username' | 'password' | 'userType' | 'status'>>;
export const userProfilePayload = (values: UserProfileWrite): UserProfileWrite =>
  pickWriteFields(values, ['nickname', 'email', 'phone', 'avatar']);
export type UserEditorWrite = UserProfileWrite & { userType?: string };

export class UserPartialSaveError extends Error {
  readonly profileSaved = true;
  constructor(
    public readonly userId: number,
    public readonly profile: UserProfileWrite,
    error: unknown,
  ) {
    super(
      `基本资料已保存；用户类型变更未确认，请刷新核对。${error instanceof Error ? error.message : ''}`,
    );
    this.name = 'UserPartialSaveError';
  }
}

/** Two endpoints are not an atomic transaction; expose the confirmed first write on second-step failure. */
export async function saveUserProfile(
  id: number,
  values: UserEditorWrite,
  originalType: string | undefined,
  canChangeType: boolean,
) {
  const requestedType = pickWriteFields(values, ['userType']).userType;
  const changed = !!requestedType && requestedType !== originalType;
  if (changed && !canChangeType) throw new Error('用户类型变更仅限超级管理员');
  const profile = userProfilePayload(values);
  const result = await userApi.update(id, profile);
  if (result.code !== 200) throw new Error(result.msg || '基本资料保存失败');
  if (changed) {
    try {
      const typeResult = await userApi.changeType(id, requestedType!);
      if (typeResult.code !== 200) throw new Error(typeResult.msg || '用户类型变更失败');
    } catch (error: unknown) {
      throw new UserPartialSaveError(id, profile, error);
    }
  }
  return { profile, userType: changed ? requestedType : originalType, typeChanged: changed };
}

export const userApi = {
  exportCsv: (params: { username?: string; userType?: string }): Promise<Blob> =>
    request.getBlob('/api/v1/admin/system/user/export', {
      mediaTypes: ['text/csv'],
      params: pickWriteFields(params, ['username', 'userType']),
    }),
  getPage: (pageNum: number, pageSize: number, params?: Record<string, unknown>) =>
    request.get<PageResult<SysUser>>('/api/v1/admin/system/user/page', {
      params: { pageNum, pageSize, ...params },
    }),
  create: (values: UserCreateWrite): Promise<ApiResult<void>> =>
    request.post(
      '/api/v1/admin/system/user',
      pickWriteFields(values, [
        'username',
        'password',
        'nickname',
        'email',
        'phone',
        'avatar',
        'userType',
        'status',
      ]),
    ),
  update: (id: number, values: UserProfileWrite): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/user/${id}`, userProfilePayload(values)),
  delete: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/system/user/${id}`),
  resetPassword: (id: number, password: string): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/user/${id}/password`, { password }),
  updateStatus: (id: number, status: number, reason?: string): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/user/${id}/status`, null, {
      params: { status, reason: reason || undefined },
    }),
  changeType: (id: number, userType: string): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/user/${id}/type`, { userType }),
  kickOut: (id: number): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/user/${id}/kick`),
  assignRoles: (id: number, roleIds: number[]): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/user/${id}/roles`, { roleIds }),
  getUserRoles: (id: number): Promise<ApiResult<Role[]>> =>
    request.get(`/api/v1/admin/system/user/${id}/roles`),
};

export interface Token {
  /** SHA-256 session fingerprint, not a reusable authentication credential. */
  id: string;
  userId: number;
  username: string;
  token?: string;
  expireTime: string;
  loginIp: string;
  device?: string;
  status: number;
  createTime: string;
}
export const tokenApi = {
  getPage: (pageNum: number, pageSize: number, params?: { userId?: number; status?: number }) =>
    request.get<PageResult<Token>>('/api/v1/admin/system/token/page', {
      params: { pageNum, pageSize, ...params },
    }),
  revoke: (id: string): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/token/${encodeURIComponent(id)}/revoke`),
  kickOut: (userId: number): Promise<ApiResult<void>> =>
    request.put(`/api/v1/admin/system/token/user/${userId}/kick`),
  cleanExpired: (): Promise<ApiResult<void>> =>
    request.delete('/api/v1/admin/system/token/expired'),
  revokeAll: (): Promise<ApiResult<void>> => request.put('/api/v1/admin/system/token/revoke-all'),
};
