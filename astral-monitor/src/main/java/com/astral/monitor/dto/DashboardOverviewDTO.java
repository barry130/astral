package com.astral.monitor.dto;

import lombok.Data;

/**
 * 仪表盘总览数据传输对象
 * <p>
 * 首页仪表盘所需的全部数据一次返回，避免前端并发调用
 * {@code /system}、{@code /jvm}、{@code /business}、{@code /admin/stat/overview}、
 * {@code /admin/stat/api/top} 五个接口造成的重复鉴权与多次往返。
 * </p>
 * <p>
 * 各子对象直接复用既有 DTO，保持与明细接口相同的字段契约。
 * </p>
 */
@Data
public class DashboardOverviewDTO {
    /** 系统资源（CPU/内存/磁盘/运行时间） */
    private SystemMonitorDTO system;
    /** JVM 运行信息（堆内存/线程数/GC/JDK 版本） */
    private JvmMonitorDTO jvm;
    /** 业务规模（序列配置数/活跃连接数） */
    private BusinessMonitorDTO business;
    /** 今日设备与流量概览 */
    private DeviceOverviewDTO today;
    /** 昨日设备与流量概览（供首页做同比对比） */
    private DeviceOverviewDTO yesterday;
    /** 今日接口调用汇总（调用量/成功/失败/成功率） */
    private ApiTopSummaryDTO api;
    /** 数据库运行状况（连接池水位/版本/容量/探测耗时） */
    private DatabaseMonitorDTO database;
    /** Redis 运行状况（内存/key 数/命中率/客户端数） */
    private RedisMonitorDTO redis;
}
