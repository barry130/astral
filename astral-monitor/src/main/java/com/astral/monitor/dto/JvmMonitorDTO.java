package com.astral.monitor.dto;

import lombok.Data;

/**
 * JVM监控数据传输对象
 * <p>
 * 封装JVM级别的监控指标，包括堆内存、线程、GC、JDK版本等。
 * 由 {@link com.astral.monitor.service.JvmMonitorService} 生成并返回。
 * </p>
 */
@Data
public class JvmMonitorDTO {
    /** 堆内存已使用大小（字节） */
    private Long heapUsed;
    /** 堆内存最大大小（字节） */
    private Long heapMax;
    /** 堆内存使用率（百分比） */
    private Double heapUsage;
    /** 活跃线程数 */
    private Integer threadCount;
    /** 垃圾回收总次数 */
    private Integer gcCount;
    /** JVM运行时间（毫秒） */
    private Long uptime;
    /** JDK版本号 */
    private String jdkVersion;
    /** JVM名称 */
    private String jvmName;
}