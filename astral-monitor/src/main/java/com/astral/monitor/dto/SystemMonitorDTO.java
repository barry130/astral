package com.astral.monitor.dto;

import lombok.Data;

/**
 * 系统监控数据传输对象
 * <p>
 * 封装系统级别的监控指标，包括CPU、内存、磁盘、线程数、运行时间等。
 * 由 {@link com.astral.monitor.service.SystemMonitorService} 生成并返回。
 * </p>
 */
@Data
public class SystemMonitorDTO {
    /** CPU使用率（百分比） */
    private Double cpuUsage;
    /** 内存使用率（百分比） */
    private Double memoryUsage;
    /** 磁盘使用率（百分比） */
    private Double diskUsage;
    /** 总内存大小（字节） */
    private Long totalMemory;
    /** 已使用内存大小（字节） */
    private Long usedMemory;
    /** 可用内存大小（字节） */
    private Long availableMemory;
    /** 活跃线程数 */
    private Integer threadCount;
    /** 堆内存已使用大小（字节） */
    private Long heapUsed;
    /** 堆内存最大大小（字节） */
    private Long heapMax;
    /** 应用运行时间（毫秒） */
    private Long uptime;
}