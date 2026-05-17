import { request, ApiResult } from './client';

/** 集群节点信息接口 */
export interface ClusterNode {
  /** 节点唯一标识 */
  nodeId: string;
  /** 工作节点ID（用于雪花算法） */
  workerId: number;
  /** 节点IP地址 */
  ipAddress: string;
  /** 节点端口 */
  port: number;
  /** 节点名称 */
  nodeName: string;
  /** 节点状态：在线/离线/过期 */
  status: 'ONLINE' | 'OFFLINE' | 'EXPIRED';
  /** 最后心跳时间 */
  lastHeartbeat: string;
  /** 注册时间 */
  registerTime: string;
  /** 是否为当前节点 */
  isCurrent: boolean;
}

/** 集群整体状态接口 */
export interface ClusterStatus {
  /** 总节点数 */
  totalNodes: number;
  /** 在线节点数 */
  onlineNodes: number;
  /** 离线节点数 */
  offlineNodes: number;
  /** 过期节点数 */
  expiredNodes: number;
}

/** 集群管理相关API */
export const clusterApi = {
  /** 获取所有在线节点 */
  getOnlineNodes: (): Promise<ApiResult<ClusterNode[]>> =>
    request.get('/api/v1/cluster/nodes'),

  /** 获取所有节点（包含离线和过期） */
  getAllNodes: (): Promise<ApiResult<ClusterNode[]>> =>
    request.get('/api/v1/cluster/nodes/all'),

  /** 获取集群整体状态统计 */
  getStatus: (): Promise<ApiResult<ClusterStatus>> =>
    request.get('/api/v1/cluster/status'),

  /** 获取当前节点信息 */
  getCurrentNode: (): Promise<ApiResult<ClusterNode>> =>
    request.get('/api/v1/cluster/current'),

  /** 获取指定节点状态 */
  getNodeStatus: (nodeId: string): Promise<ApiResult<ClusterNode>> =>
    request.get(`/api/v1/cluster/node/${nodeId}`),

  /** 手动下线指定节点 */
  offlineNode: (nodeId: string): Promise<ApiResult<void>> =>
    request.post(`/api/v1/cluster/node/${nodeId}/offline`),
};