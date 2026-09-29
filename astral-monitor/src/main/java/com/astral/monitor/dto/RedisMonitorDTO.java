package com.astral.monitor.dto;

import lombok.Data;

/**
 * Redis 运行状况数据传输对象
 * <p>
 * 取自 Redis {@code INFO} 与 {@code DBSIZE} 命令。未配置 Redis 时各字段保持 null，
 * 前端按「不可用」降级展示。
 * </p>
 */
@Data
public class RedisMonitorDTO {
    /** 连通性：true 正常 / false 异常 / null 未探测（无连接工厂） */
    private Boolean available;
    /** Redis 版本（如 7.2.4） */
    private String version;
    /** 运行模式：standalone / cluster / sentinel */
    private String mode;
    /** 服务端运行时长（秒） */
    private Long uptimeSeconds;
    /** 当前使用的库索引（对应 spring.data.redis.database） */
    private Integer databaseIndex;
    /** 当前库的 key 总数 */
    private Long totalKeys;
    /** 已连接客户端数 */
    private Integer connectedClients;
    /** 已用内存（字节） */
    private Long usedMemory;
    /** 内存使用峰值（字节） */
    private Long usedMemoryPeak;
    /** 最大可用内存（字节）；0 表示未限制 */
    private Long maxMemory;
    /** 内存碎片率，1.0 附近为健康 */
    private Double memFragmentationRatio;
    /** 缓存命中率（0-100）；无读写记录时为 null */
    private Double hitRate;
    /** 命中次数 */
    private Long keyspaceHits;
    /** 未命中次数 */
    private Long keyspaceMisses;
    /** 每秒处理命令数 */
    private Long instantaneousOpsPerSec;
    /** 探测耗时（毫秒）；探测失败为 null */
    private Long pingMs;
}
