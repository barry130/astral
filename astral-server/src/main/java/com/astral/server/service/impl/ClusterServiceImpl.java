package com.astral.server.service.impl;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.server.dto.ClusterNode;
import com.astral.server.dto.ClusterStatus;
import com.astral.server.service.ClusterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 集群管理服务实现类
 * <p>基于SaTokenDao（Redis）实现集群节点注册、心跳、下线等功能</p>
 * <p>仅在配置 astral.cluster.enabled=true 时启用</p>
 * <p>使用Worker ID池（0-1023）为每个节点分配唯一的Snowflake Worker ID</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "astral.cluster.enabled", havingValue = "true")
public class ClusterServiceImpl implements ClusterService {

    /** 集群节点信息存储前缀 */
    private static final String CLUSTER_NODE_PREFIX = "astral:cluster:node:";
    /** 集群节点ID列表存储键 */
    private static final String CLUSTER_NODES_KEY = "astral:cluster:nodes";
    /** Worker ID池存储键，用于分配Snowflake算法的Worker ID */
    private static final String CLUSTER_WORKER_POOL_KEY = "astral:cluster:worker:id:pool";
    /** 心跳间隔（秒） */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 5;
    /** 节点超时时间（秒），超过此时间未收到心跳视为离线 */
    private static final long NODE_TIMEOUT_SECONDS = 15;
    /** 节点TTL时间（秒），用于Redis键自动过期 */
    private static final long NODE_TTL_SECONDS = 30;
    /** 日期时间格式化器 */
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Sa-Token DAO，用于Redis操作 */
    private final SaTokenDao saTokenDao;
    /** 定时任务调度器，用于发送心跳 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** JSON序列化器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 服务器端口 */
    @Value("${server.port:8080}")
    private int serverPort;

    /** 节点名称（可配置） */
    @Value("${astral.cluster.node.name:#{null}}")
    private String nodeName;

    /** 当前节点ID */
    private String currentNodeId;
    /** 当前节点分配的Worker ID */
    private Integer currentWorkerId;

    /**
     * 初始化方法
     * <p>Spring容器启动时自动注册节点并启动心跳定时任务</p>
     */
    @PostConstruct
    public void init() {
        registerNode();
        // 每5秒发送一次心跳
        scheduler.scheduleAtFixedRate(this::heartbeat, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 销毁方法
     * <p>Spring容器关闭时自动将当前节点下线并关闭调度器</p>
     */
    @PreDestroy
    public void destroy() {
        if (currentNodeId != null) {
            offlineNode(currentNodeId);
        }
        scheduler.shutdown();
    }

    /**
     * 注册当前节点到集群
     * <p>生成唯一节点ID，从Worker ID池分配Worker ID，将节点信息写入Redis</p>
     */
    @Override
    public void registerNode() {
        // 生成唯一节点ID（去掉横杠的UUID）
        currentNodeId = UUID.randomUUID().toString().replace("-", "");
        // 从Worker ID池分配一个Worker ID（用于Snowflake算法）
        currentWorkerId = allocateWorkerId();

        String ip = getLocalIp();
        String name = nodeName != null ? nodeName : "node-" + currentNodeId.substring(0, 8);

        ClusterNode node = new ClusterNode();
        node.setNodeId(currentNodeId);
        node.setWorkerId(currentWorkerId);
        node.setIpAddress(ip);
        node.setPort(serverPort);
        node.setNodeName(name);
        node.setStatus("ONLINE");
        node.setLastHeartbeat(LocalDateTime.now().format(DT_FMT));
        node.setRegisterTime(LocalDateTime.now().format(DT_FMT));
        node.setIsCurrent(true);

        try {
            // 将节点信息序列化后存入Redis，设置TTL
            String nodeJson = objectMapper.writeValueAsString(node);
            saTokenDao.set(CLUSTER_NODE_PREFIX + currentNodeId, nodeJson, NODE_TTL_SECONDS);

            // 将节点ID添加到节点列表中
            List<String> nodeIds = getNodeIds();
            if (!nodeIds.contains(currentNodeId)) {
                nodeIds.add(currentNodeId);
                saveNodeIds(nodeIds);
            }

            log.info("Cluster node registered: id={}, name={}, workerId={}", currentNodeId, name, currentWorkerId);
        } catch (Exception e) {
            log.error("Failed to register cluster node", e);
        }
    }

    /**
     * 发送心跳
     * <p>刷新节点信息的TTL，更新最后心跳时间和状态为ONLINE</p>
     */
    @Override
    public void heartbeat() {
        if (currentNodeId == null) return;

        try {
            String key = CLUSTER_NODE_PREFIX + currentNodeId;
            String nodeJson = saTokenDao.get(key);
            if (nodeJson != null) {
                // 刷新TTL，防止节点信息过期
                saTokenDao.set(key, nodeJson, NODE_TTL_SECONDS);

                // 更新心跳时间和状态
                ClusterNode node = objectMapper.readValue(nodeJson, ClusterNode.class);
                node.setLastHeartbeat(LocalDateTime.now().format(DT_FMT));
                node.setStatus("ONLINE");
                saTokenDao.set(key, objectMapper.writeValueAsString(node), NODE_TTL_SECONDS);
            }
        } catch (Exception e) {
            log.warn("Heartbeat failed for node {}", currentNodeId, e);
        }
    }

    /**
     * 获取在线节点列表
     * <p>过滤所有节点中状态为ONLINE的节点</p>
     *
     * @return 在线节点列表
     */
    @Override
    public List<ClusterNode> getOnlineNodes() {
        return getAllNodes().stream()
                .filter(n -> "ONLINE".equals(n.getStatus()))
                .toList();
    }

    /**
     * 获取所有节点列表
     * <p>遍历节点ID列表，从Redis读取每个节点的详细信息</p>
     *
     * @return 全部节点列表（包含在线、离线、过期状态）
     */
    @Override
    public List<ClusterNode> getAllNodes() {
        List<ClusterNode> nodes = new ArrayList<>();
        List<String> nodeIds = getNodeIds();

        for (String nodeId : nodeIds) {
            try {
                String nodeJson = saTokenDao.get(CLUSTER_NODE_PREFIX + nodeId);
                if (nodeJson != null) {
                    ClusterNode node = objectMapper.readValue(nodeJson, ClusterNode.class);
                    node.setIsCurrent(nodeId.equals(currentNodeId));

                    // 检查节点是否过期（TTL <= 0表示即将或已经过期）
                    long timeout = saTokenDao.getTimeout(CLUSTER_NODE_PREFIX + nodeId);
                    if (timeout <= 0) {
                        node.setStatus("EXPIRED");
                    }

                    nodes.add(node);
                } else {
                    // 节点信息不存在，标记为离线
                    ClusterNode offlineNode = new ClusterNode();
                    offlineNode.setNodeId(nodeId);
                    offlineNode.setStatus("OFFLINE");
                    offlineNode.setIsCurrent(nodeId.equals(currentNodeId));
                    nodes.add(offlineNode);
                }
            } catch (Exception e) {
                log.warn("Failed to read node {}", nodeId, e);
            }
        }

        return nodes;
    }

    /**
     * 获取集群整体状态
     * <p>统计在线、离线、过期节点数量</p>
     *
     * @return 集群状态信息
     */
    @Override
    public ClusterStatus getStatus() {
        List<ClusterNode> allNodes = getAllNodes();
        long online = allNodes.stream().filter(n -> "ONLINE".equals(n.getStatus())).count();
        long offline = allNodes.stream().filter(n -> "OFFLINE".equals(n.getStatus())).count();
        long expired = allNodes.stream().filter(n -> "EXPIRED".equals(n.getStatus())).count();

        ClusterStatus status = new ClusterStatus();
        status.setTotalNodes(allNodes.size());
        status.setOnlineNodes((int) online);
        status.setOfflineNodes((int) offline);
        status.setExpiredNodes((int) expired);
        status.setEnabled(true);
        status.setMode("cluster");
        return status;
    }

    /**
     * 获取当前节点信息
     *
     * @return 当前节点信息，未注册时返回null
     */
    @Override
    public ClusterNode getCurrentNode() {
        if (currentNodeId == null) return null;
        try {
            String nodeJson = saTokenDao.get(CLUSTER_NODE_PREFIX + currentNodeId);
            if (nodeJson != null) {
                ClusterNode node = objectMapper.readValue(nodeJson, ClusterNode.class);
                node.setIsCurrent(true);
                return node;
            }
        } catch (Exception e) {
            log.warn("Failed to read current node", e);
        }
        return null;
    }

    /**
     * 获取指定节点的状态
     *
     * @param nodeId 节点ID
     * @return 节点信息，不存在时返回null
     */
    @Override
    public ClusterNode getNodeStatus(String nodeId) {
        try {
            String nodeJson = saTokenDao.get(CLUSTER_NODE_PREFIX + nodeId);
            if (nodeJson != null) {
                ClusterNode node = objectMapper.readValue(nodeJson, ClusterNode.class);
                node.setIsCurrent(nodeId.equals(currentNodeId));
                return node;
            }
        } catch (Exception e) {
            log.warn("Failed to read node {}", nodeId, e);
        }
        return null;
    }

    /**
     * 将指定节点下线
     * <p>从Redis删除节点信息，从节点列表移除，如果是当前节点则释放Worker ID</p>
     *
     * @param nodeId 节点ID
     */
    @Override
    public void offlineNode(String nodeId) {
        try {
            // 删除节点信息
            saTokenDao.delete(CLUSTER_NODE_PREFIX + nodeId);

            // 从节点列表中移除
            List<String> nodeIds = getNodeIds();
            nodeIds.remove(nodeId);
            saveNodeIds(nodeIds);

            // 如果是当前节点，释放Worker ID
            if (nodeId.equals(currentNodeId)) {
                releaseWorkerId(currentWorkerId);
                currentNodeId = null;
                currentWorkerId = null;
            }

            log.info("Cluster node offline: {}", nodeId);
        } catch (Exception e) {
            log.error("Failed to offline node {}", nodeId, e);
        }
    }

    /**
     * 获取所有节点ID列表
     *
     * @return 节点ID列表
     */
    private List<String> getNodeIds() {
        try {
            String json = saTokenDao.get(CLUSTER_NODES_KEY);
            if (json != null) {
                return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            }
        } catch (Exception e) {
            log.warn("Failed to read node list", e);
        }
        return new ArrayList<>();
    }

    /**
     * 保存节点ID列表到Redis
     *
     * @param nodeIds 节点ID列表
     */
    private void saveNodeIds(List<String> nodeIds) {
        try {
            // 节点列表的TTL设为节点TTL的2倍，确保节点信息过期后列表也能清理
            saTokenDao.set(CLUSTER_NODES_KEY, objectMapper.writeValueAsString(nodeIds), NODE_TTL_SECONDS * 2);
        } catch (Exception e) {
            log.error("Failed to save node list", e);
        }
    }

    /**
     * 从Worker ID池分配一个Worker ID
     * <p>Worker ID范围为0-1023，用于Snowflake算法生成唯一ID</p>
     *
     * @return 分配的Worker ID
     */
    private Integer allocateWorkerId() {
        try {
            String poolKey = CLUSTER_WORKER_POOL_KEY;
            String poolJson = saTokenDao.get(poolKey);
            List<Integer> pool;

            if (poolJson == null) {
                // 初始化Worker ID池（0-1023）
                pool = new ArrayList<>();
                for (int i = 0; i < 1024; i++) {
                    pool.add(i);
                }
            } else {
                pool = objectMapper.readValue(poolJson, objectMapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
            }

            if (pool.isEmpty()) {
                // Worker ID池耗尽，随机分配一个ID（可能产生冲突）
                log.warn("Worker ID pool exhausted, assigning random ID");
                return (int) (Math.random() * 1024);
            }

            // 从池头取出一个Worker ID
            Integer workerId = pool.remove(0);
            saTokenDao.set(poolKey, objectMapper.writeValueAsString(pool), NODE_TTL_SECONDS * 2);
            return workerId;
        } catch (Exception e) {
            log.error("Failed to allocate worker ID", e);
            return (int) (Math.random() * 1024);
        }
    }

    /**
     * 释放Worker ID回池中
     * <p>节点下线时将Worker ID归还到池中，供其他节点使用</p>
     *
     * @param workerId 要释放的Worker ID
     */
    private void releaseWorkerId(Integer workerId) {
        if (workerId == null) return;
        try {
            String poolKey = CLUSTER_WORKER_POOL_KEY;
            String poolJson = saTokenDao.get(poolKey);
            List<Integer> pool;

            if (poolJson != null) {
                pool = objectMapper.readValue(poolJson, objectMapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
            } else {
                pool = new ArrayList<>();
            }

            // 仅当Worker ID不在池中时才添加（避免重复）
            if (!pool.contains(workerId)) {
                pool.add(workerId);
                saTokenDao.set(poolKey, objectMapper.writeValueAsString(pool), NODE_TTL_SECONDS * 2);
            }
        } catch (Exception e) {
            log.error("Failed to release worker ID {}", workerId, e);
        }
    }

    /**
     * 获取本机IP地址
     *
     * @return IP地址，获取失败时返回127.0.0.1
     */
    private String getLocalIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
