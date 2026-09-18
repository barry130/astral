package com.astral.monitor.dto;

import lombok.Data;

/**
 * 接口统计 Top 数据传输对象（api/top）
 * <p>
 * 字段名与被删除的旧 /api/v1/statistics/api/top 契约保持一致，
 * 旧前端 statistics/page.tsx 直接兼容。
 * </p>
 */
@Data
public class ApiTopDTO {
    /** 接口路径 */
    private String apiPath;
    /** HTTP 方法 */
    private String apiMethod;
    /** 调用次数 */
    private Long callCount;
    /** 成功次数（status < 400） */
    private Long successCount;
    /** 失败次数（status >= 400） */
    private Long failureCount;
    /** 平均耗时（毫秒） */
    private Long avgTime;
    /** 最大耗时（毫秒） */
    private Long maxTime;
}
