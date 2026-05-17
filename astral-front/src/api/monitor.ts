import { request, ApiResult } from './client';

/** 系统监控数据接口 */
export interface SystemMonitorDTO {
  /** CPU使用率（百分比） */
  cpuUsage: number;
  /** 内存使用率（百分比） */
  memoryUsage: number;
  /** 磁盘使用率（百分比） */
  diskUsage: number;
  /** 系统运行时间（秒） */
  uptime: number;
  /** 总内存大小（字节） */
  totalMemory: number;
  /** 已使用内存（字节） */
  usedMemory: number;
  /** 可用内存（字节） */
  availableMemory: number;
  /** 线程数 */
  threadCount: number;
}

/** JVM监控数据接口 */
export interface JvmMonitorDTO {
  /** 堆内存已使用（字节） */
  heapUsed: number;
  /** 堆内存最大值（字节） */
  heapMax: number;
  /** 堆内存使用率（百分比） */
  heapUsage: number;
  /** 活跃线程数 */
  threadCount: number;
  /** GC执行次数 */
  gcCount: number;
  /** JVM运行时间（秒） */
  uptime: number;
  /** JDK版本 */
  jdkVersion: string;
  /** JVM名称 */
  jvmName: string;
}

/** 业务监控数据接口 */
export interface BusinessMonitorDTO {
  /** 序列生成总数 */
  sequenceGenerationTotal: number;
  /** 序列生成QPS（每秒查询率） */
  sequenceGenerationQps: number;
  /** 序列配置数量 */
  configCount: number;
}

/** 系统监控相关API */
export const monitorApi = {
  /** 获取系统资源信息（CPU/内存/磁盘） */
  getSystemInfo: (): Promise<ApiResult<SystemMonitorDTO>> =>
    request.get('/api/v1/monitor/system'),

  /** 获取JVM运行信息 */
  getJvmInfo: (): Promise<ApiResult<JvmMonitorDTO>> =>
    request.get('/api/v1/monitor/jvm'),

  /** 获取业务指标信息 */
  getBusinessInfo: (): Promise<ApiResult<BusinessMonitorDTO>> =>
    request.get('/api/v1/monitor/business'),
};
