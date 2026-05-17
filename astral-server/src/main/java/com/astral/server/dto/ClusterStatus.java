package com.astral.server.dto;

import lombok.Data;

/**
 * 集群状态DTO
 * <p>用于表示集群的整体状态信息</p>
 */
@Data
public class ClusterStatus {
    /** 节点总数 */
    private Integer totalNodes;
    /** 在线节点数 */
    private Integer onlineNodes;
    /** 离线节点数 */
    private Integer offlineNodes;
    /** 过期节点数（TTL已到期） */
    private Integer expiredNodes;
    /** 集群是否启用 */
    private Boolean enabled;
    /** 运行模式：cluster-集群模式，standalone-单机模式 */
    private String mode;
}
