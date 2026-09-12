import { request, ApiResult } from './client';

export interface SysMenu {
  id: number;
  parentId: number;
  name: string;
  icon: string;
  path: string;
  permission: string;
  sort: number;
  visible: number;
  type: number;
  children?: SysMenu[];
}

export const menuApi = {
  getTree: (): Promise<ApiResult<SysMenu[]>> =>
    request.get('/api/v1/admin/system/menu/tree'),

  getList: (): Promise<ApiResult<SysMenu[]>> =>
    request.get('/api/v1/admin/system/menu/list'),

  getById: (id: number): Promise<ApiResult<SysMenu>> =>
    request.get(`/api/v1/admin/system/menu/${id}`),

  create: (menu: Partial<SysMenu>): Promise<ApiResult<SysMenu>> =>
    request.post('/api/v1/admin/system/menu', menu),

  update: (id: number, menu: Partial<SysMenu>): Promise<ApiResult<SysMenu>> =>
    request.put(`/api/v1/admin/system/menu/${id}`, menu),

  delete: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/system/menu/${id}`),
};