package com.astral.monitor.dto;

import lombok.Data;

/**
 * 业务监控数据传输对象
 * <p>
 * 封装业务级别的监控指标，由 {@link com.astral.monitor.service.BusinessMonitorService}
 * 生成并返回。
 * </p>
 * <p>
 * 说明：原 sequenceGenerationTotal / sequenceGenerationQps 取自进程内
 * {@code SequenceMetrics} 计数器，服务重启即清零，且序列生成本身是低频操作，
 * QPS 长期在 0~1 抖动，无监控价值，已移除；
 * cacheHitRatio 从未实现（项目无统一缓存层），一并移除。
 * </p>
 */
@Data
public class BusinessMonitorDTO {
    /** 序列配置数量 */
    private Long configCount;
    /** 当前并发处理中的 HTTP 请求数 */
    private Integer activeRequests;
}
