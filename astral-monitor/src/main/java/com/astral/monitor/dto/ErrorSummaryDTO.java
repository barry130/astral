package com.astral.monitor.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 错误分组汇总数据传输对象（error/summary）
 * <p>
 * 按 fingerprint 分组的一天错误统计。
 * </p>
 */
@Data
public class ErrorSummaryDTO {
    /** 错误指纹 */
    private String fingerprint;
    /** 发生次数 */
    private Long count;
    /** 影响设备数 */
    private Long affectedDevices;
    /** 错误类型（js/network/biz） */
    private String errorType;
    /** 样例错误信息 */
    private String sampleMessage;
    /** 首次发生时间 */
    private LocalDateTime firstSeen;
    /** 最近发生时间 */
    private LocalDateTime lastSeen;
    /** 最常见 App 版本 */
    private String topAppVersion;
}
