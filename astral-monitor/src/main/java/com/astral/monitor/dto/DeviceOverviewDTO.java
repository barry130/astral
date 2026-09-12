package com.astral.monitor.dto;

import lombok.Data;

/**
 * 设备统计概览（单日）数据传输对象（STATS_DESIGN.md §4.2 overview）
 */
@Data
public class DeviceOverviewDTO {
    /** 统计日期（yyyy-MM-dd） */
    private String date;
    /** 新增设备数（first_date = 当天） */
    private Long newDevices;
    /** 活跃设备数（last_date = 当天） */
    private Long activeDevices;
    /** 总设备数（first_date ≤ 当天的全表行数） */
    private Long totalDevices;
    /** 页面访问次数（PV） */
    private Long pv;
    /** 访问次数（冷启动计1次） */
    private Long visits;
    /** 启动次数 */
    private Long launches;
    /** 平均停留时长（毫秒，= total_duration_ms / max(visits,1)） */
    private Long avgDurationMs;
    /** JS/UTS 错误次数 */
    private Long errorCount;
}
