package com.astral.monitor.dto;

import lombok.Data;

/**
 * 数据库运行状况数据传输对象
 * <p>
 * 连接池水位取自 HikariCP 的 {@code HikariPoolMXBean}，产品信息与探测耗时取自一次真实查询。
 * 未配置数据源时各字段保持 null，前端按「不可用」降级展示。
 * </p>
 */
@Data
public class DatabaseMonitorDTO {
    /** 连通性：true 正常 / false 异常 / null 未探测（无数据源） */
    private Boolean available;
    /** 数据库产品名（如 PostgreSQL） */
    private String productName;
    /** 数据库产品版本（如 16.4） */
    private String productVersion;
    /** 驱动名称与版本 */
    private String driverInfo;
    /** 数据库当前占用的磁盘空间（字节）；方言不支持时为 null */
    private Long databaseSize;
    /** 一次完整查询往返耗时（毫秒，含从池中取连接）；探测失败为 null */
    private Long pingMs;
    /** 连接池类型名（如 HikariDataSource） */
    private String poolName;
    /** 正在被业务占用的连接数 */
    private Integer activeConnections;
    /** 空闲连接数 */
    private Integer idleConnections;
    /** 池中连接总数 */
    private Integer totalConnections;
    /** 连接池上限 */
    private Integer maxConnections;
    /** 正在等待连接的线程数 */
    private Integer waitingThreads;
}
