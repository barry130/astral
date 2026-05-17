package com.astral.monitor.dto;

import lombok.Data;

/**
 * 业务监控数据传输对象
 * <p>
 * 封装业务级别的监控指标，包括序列生成统计、配置数量等。
 * 由 {@link com.astral.monitor.service.BusinessMonitorService} 生成并返回。
 * </p>
 */
@Data
public class BusinessMonitorDTO {
    /** 序列生成总数 */
    private Long sequenceGenerationTotal;
    /** 序列生成QPS（每秒查询数） */
    private Double sequenceGenerationQps;
    /** 缓存命中率（百分比） */
    private Double cacheHitRatio;
    /** 活跃连接数 */
    private Integer activeConnections;
    /** 序列配置数量 */
    private Long configCount;
}