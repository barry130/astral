package com.astral.server.dto;

import lombok.Data;

/**
 * 集群节点信息DTO
 * <p>用于表示集群中一个节点的详细信息</p>
 */
@Data
public class ClusterNode {
    /** 节点唯一标识（UUID） */
    private String nodeId;
    /** Snowflake Worker ID（0-1023），用于分布式ID生成 */
    private Integer workerId;
    /** 节点IP地址 */
    private String ipAddress;
    /** 节点服务端口 */
    private Integer port;
    /** 节点名称（可配置，默认node-前8位UUID） */
    private String nodeName;
    /** 节点状态：ONLINE-在线，OFFLINE-离线，EXPIRED-过期 */
    private String status;
    /** 最后一次心跳时间 */
    private String lastHeartbeat;
    /** 节点注册时间 */
    private String registerTime;
    /** 是否为当前节点 */
    private Boolean isCurrent;
}
