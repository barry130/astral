import { request, ApiResult, ApiRequestConfig } from './client';
import type { DayOverview, ApiTopSummary } from './statistics';

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

/**
 * 业务监控数据接口
 * <p>原 sequenceGenerationTotal / sequenceGenerationQps 为进程内计数器（重启清零、QPS 长期在 0~1 抖动），
 * 无监控价值已随仪表盘改版移除。</p>
 */
export interface BusinessMonitorDTO {
  /** 序列配置数量 */
  configCount: number;
  /** 当前活跃请求线程数；后端容器非 Tomcat 或线程池不可用时为 null */
  activeConnections: number | null;
}

/** 仪表盘总览数据接口（一次请求返回首页全部所需数据） */
export interface DashboardOverviewDTO {
  /** 系统资源（CPU/内存/磁盘/运行时间） */
  system: SystemMonitorDTO;
  /** JVM 运行信息（堆内存/线程数/GC/JDK 版本） */
  jvm: JvmMonitorDTO;
  /** 业务规模（序列配置数/活跃连接数） */
  business: BusinessMonitorDTO;
  /** 今日设备与流量概览 */
  today: DayOverview;
  /** 昨日设备与流量概览（供同比对比） */
  yesterday: DayOverview;
  /** 今日接口调用汇总（调用量/成功/失败/成功率） */
  api: ApiTopSummary;
}

/** 系统监控相关API */
export const monitorApi = {
  /** 获取仪表盘总览（首页专用聚合接口）；options 透传给 request，轮询时传 { silent: true } */
  getDashboard: (options?: ApiRequestConfig): Promise<ApiResult<DashboardOverviewDTO>> =>
    request.get('/api/v1/admin/monitor/dashboard', options),

  /** 获取系统资源信息（CPU/内存/磁盘）；options 透传给 request，轮询时传 { silent: true } */
  getSystemInfo: (options?: ApiRequestConfig): Promise<ApiResult<SystemMonitorDTO>> =>
    request.get('/api/v1/admin/monitor/system', options),

  /** 获取JVM运行信息；options 透传给 request，轮询时传 { silent: true } */
  getJvmInfo: (options?: ApiRequestConfig): Promise<ApiResult<JvmMonitorDTO>> =>
    request.get('/api/v1/admin/monitor/jvm', options),

  /** 获取业务指标信息；options 透传给 request，轮询时传 { silent: true } */
  getBusinessInfo: (options?: ApiRequestConfig): Promise<ApiResult<BusinessMonitorDTO>> =>
    request.get('/api/v1/admin/monitor/business', options),
};
