package com.astral.server.service;

import com.astral.server.dto.ClusterNode;
import com.astral.server.dto.ClusterStatus;

import java.util.List;

/**
 * 集群管理服务接口
 * <p>提供集群节点管理、健康检查、节点注册与心跳等功能</p>
 */
public interface ClusterService {

    /**
     * 获取当前在线的节点列表
     *
     * @return 在线节点列表
     */
    List<ClusterNode> getOnlineNodes();

    /**
     * 获取所有节点列表（包含在线和离线）
     *
     * @return 全部节点列表
     */
    List<ClusterNode> getAllNodes();

    /**
     * 获取集群整体状态
     *
     * @return 集群状态信息
     */
    ClusterStatus getStatus();

    /**
     * 获取当前节点信息
     *
     * @return 当前节点信息
     */
    ClusterNode getCurrentNode();

    /**
     * 获取指定节点的状态
     *
     * @param nodeId 节点ID
     * @return 节点信息，不存在时返回null
     */
    ClusterNode getNodeStatus(String nodeId);

    /**
     * 将指定节点下线
     *
     * @param nodeId 节点ID
     */
    void offlineNode(String nodeId);

    /**
     * 注册当前节点到集群
     * <p>节点启动时调用，将自身信息写入集群注册表</p>
     */
    void registerNode();

    /**
     * 发送心跳
     * <p>定期调用以维持节点在线状态，更新最后心跳时间</p>
     */
    void heartbeat();
}
