import { request, ApiResult } from './client';
import { pickWriteFields } from './write-fields';

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

export type MenuWrite = Partial<Omit<SysMenu, 'id' | 'children'>>;

/** Shared by the editor and API boundary; server identity and nested entities are never writes. */
export const menuWritePayload = (values: MenuWrite): MenuWrite =>
  pickWriteFields(values, [
    'parentId',
    'name',
    'icon',
    'path',
    'permission',
    'sort',
    'visible',
    'type',
  ]);

export class MenuPartialSaveError extends Error {
  constructor(
    public readonly completedIds: number[],
    public readonly failedId: number,
    error: unknown,
  ) {
    super(
      `排序保存失败：已保存 ${completedIds.length} 条，菜单 ${failedId} 未确认保存，可能部分生效；请重新加载核对。${error instanceof Error ? error.message : ''}`,
    );
    this.name = 'MenuPartialSaveError';
  }
}

/** Deliberately sequential: the endpoint has no rollbackable batch transaction. */
export async function persistMenuOrder(
  updates: ReadonlyArray<{ id: number; parentId: number; sort: number }>,
): Promise<number> {
  const ids = new Set<number>();
  for (const update of updates) {
    if (
      !Number.isSafeInteger(update.id) ||
      update.id <= 0 ||
      ids.has(update.id) ||
      !Number.isSafeInteger(update.parentId) ||
      update.parentId < 0 ||
      !Number.isSafeInteger(update.sort) ||
      update.sort < 0
    )
      throw new Error('菜单排序数据无效或重复');
    ids.add(update.id);
  }
  const completedIds: number[] = [];
  for (const update of updates) {
    try {
      if (update.id <= 0) throw new Error('插件菜单不能写入');
      const res = await menuApi.update(update.id, { parentId: update.parentId, sort: update.sort });
      if (res.code !== 200) throw new Error(res.msg || '保存失败');
      completedIds.push(update.id);
    } catch (error: unknown) {
      throw new MenuPartialSaveError(completedIds, update.id, error);
    }
  }
  return completedIds.length;
}

export const menuApi = {
  getTree: (): Promise<ApiResult<SysMenu[]>> => request.get('/api/v1/admin/system/menu/tree'),

  getList: (): Promise<ApiResult<SysMenu[]>> => request.get('/api/v1/admin/system/menu/list'),

  getById: (id: number): Promise<ApiResult<SysMenu>> =>
    request.get(`/api/v1/admin/system/menu/${id}`),

  create: (menu: MenuWrite): Promise<ApiResult<SysMenu>> =>
    request.post('/api/v1/admin/system/menu', menuWritePayload(menu)),

  update: (id: number, menu: MenuWrite): Promise<ApiResult<SysMenu>> =>
    request.put(`/api/v1/admin/system/menu/${id}`, menuWritePayload(menu)),

  delete: (id: number): Promise<ApiResult<void>> =>
    request.delete(`/api/v1/admin/system/menu/${id}`),
};
