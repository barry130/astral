package com.astral.server.service.impl;

import com.astral.server.dto.ClusterNode;
import com.astral.server.dto.ClusterStatus;
import com.astral.server.service.ClusterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 集群服务降级实现
 * <p>当配置 astral.cluster.enabled=false 或未配置时启用</p>
 * <p>所有方法返回空值或空列表，表示单机模式下无集群功能</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "astral.cluster.enabled", havingValue = "false", matchIfMissing = true)
public class ClusterServiceFallback implements ClusterService {

    /**
     * 获取在线节点列表
     * <p>单机模式下无集群节点，返回空列表</p>
     *
     * @return 空列表
     */
    @Override
    public List<ClusterNode> getOnlineNodes() {
        return new ArrayList<>();
    }

    /**
     * 获取所有节点列表
     * <p>单机模式下无集群节点，返回空列表</p>
     *
     * @return 空列表
     */
    @Override
    public List<ClusterNode> getAllNodes() {
        return new ArrayList<>();
    }

    /**
     * 获取集群状态
     * <p>返回禁用状态的集群信息，模式为standalone</p>
     *
     * @return 禁用的集群状态
     */
    @Override
    public ClusterStatus getStatus() {
        ClusterStatus status = new ClusterStatus();
        status.setTotalNodes(0);
        status.setOnlineNodes(0);
        status.setOfflineNodes(0);
        status.setExpiredNodes(0);
        status.setEnabled(false);
        status.setMode("standalone");
        return status;
    }

    /**
     * 获取当前节点信息
     * <p>单机模式下无集群节点概念，返回null</p>
     *
     * @return null
     */
    @Override
    public ClusterNode getCurrentNode() {
        return null;
    }

    /**
     * 获取指定节点状态
     * <p>单机模式下无集群节点概念，返回null</p>
     *
     * @param nodeId 节点ID
     * @return null
     */
    @Override
    public ClusterNode getNodeStatus(String nodeId) {
        return null;
    }

    /**
     * 节点下线
     * <p>单机模式下不支持节点下线，记录警告日志</p>
     *
     * @param nodeId 节点ID
     */
    @Override
    public void offlineNode(String nodeId) {
        log.warn("Cluster not enabled, cannot offline node {}", nodeId);
    }

    /**
     * 注册节点
     * <p>单机模式下无需注册节点，空实现</p>
     */
    @Override
    public void registerNode() {
    }

    /**
     * 发送心跳
     * <p>单机模式下无需发送心跳，空实现</p>
     */
    @Override
    public void heartbeat() {
    }
}
