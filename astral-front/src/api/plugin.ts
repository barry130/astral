import { request, ApiResult } from './client';

/** 插件信息接口 */
export interface PluginInfo {
  pluginId: string;
  pluginName: string;
  version: string;
  description: string;
  enabled: boolean;
  required?: boolean;
}

/** 插件前端导航扩展接口 */
export interface NavExtension {
  pluginId: string;
  label: string;
  path: string;
  icon: string;
  parentPath?: string;
  sort?: number;
}

/** 获取插件前端导航扩展 */
export const getNavExtensions = async (): Promise<NavExtension[]> => {
  const res = await request.get<NavExtension[]>('/api/v1/admin/plugin/nav-extensions');
  return res.data ?? [];
};

/** 插件管理相关API */
export const pluginApi = {
  /** 获取所有插件 */
  getPluginList: (): Promise<ApiResult<PluginInfo[]>> =>
    request.get('/api/v1/admin/plugin/list'),

  /** 获取已启用插件 */
  getEnabledPlugins: (): Promise<ApiResult<PluginInfo[]>> =>
    request.get('/api/v1/admin/plugin/enabled'),

  /** 启用插件 */
  enablePlugin: (pluginId: string): Promise<ApiResult<void>> =>
    request.post(`/api/v1/admin/plugin/${pluginId}/enable`),

  /** 禁用插件 */
  disablePlugin: (pluginId: string): Promise<ApiResult<void>> =>
    request.post(`/api/v1/admin/plugin/${pluginId}/disable`),

  /** 获取前端导航扩展 */
  getNavExtensions: (): Promise<ApiResult<any[]>> =>
    request.get('/api/v1/admin/plugin/nav-extensions'),
};
