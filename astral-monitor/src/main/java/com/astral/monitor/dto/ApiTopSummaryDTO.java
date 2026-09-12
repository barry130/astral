package com.astral.monitor.dto;

import lombok.Data;

/**
 * 接口统计当天全量汇总（与 Top N limit 无关）
 * <p>
 * 字段为当天<b>全部</b>接口的聚合：总调用次数/成功次数/失败次数/成功率。
 * 用于前端顶部汇总卡片，避免 Top10 / Top20 显示值不一致。
 * </p>
 */
@Data
public class ApiTopSummaryDTO {
    /** 当天全部接口总调用次数 */
    private Long callCount;
    /** 当天全部接口成功次数（status < 400） */
    private Long successCount;
    /** 当天全部接口失败次数（status >= 400） */
    private Long failureCount;
    /** 当天全部接口成功率（0-100，两位小数） */
    private String successRate;

    public ApiTopSummaryDTO() {
    }

    public ApiTopSummaryDTO(Long callCount, Long successCount, Long failureCount) {
        this.callCount = callCount;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.successRate = callCount != null && callCount > 0
                ? String.format("%.2f", (successCount.doubleValue() / callCount.doubleValue()) * 100)
                : "0.00";
    }
}
