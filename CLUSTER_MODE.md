# 集群模式实现方案

本文档介绍 Astral 系统的集群模式实现，包括架构设计、WorkerId 自动分配、部署方案等。

---

## 📋 目录

- [集群模式概述](#集群模式概述)
- [架构设计](#架构设计)
- [核心功能](#核心功能)
- [集群部署方案](#集群部署方案)
- [使用指南](#使用指南)
- [故障排查](#故障排查)

---

## 集群模式概述

### 为什么需要集群模式？

在分布式系统中，需要满足：

1. **唯一性**：多节点生成的 ID 必须全局唯一
2. **高可用**：单点故障不影响服务
3. **自动扩缩容**：节点上线/下线无需人工干预
4. **负载均衡**：流量均匀分配到各节点

### 解决的问题

| 问题 | 单机模式 | 集群模式 |
|------|----------|----------|
| WorkerId 分配 | 手动配置，易冲突 | 自动分配，保证唯一 |
| 节点发现 | 不支持 | 自动注册与发现 |
| 健康检查 | 不支持 | 心跳检测，自动剔除故障节点 |
| 优雅上下线 | 不支持 | 自动注册/注销，WorkerId 回收 |

---

## 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                      客户端/业务服务                          │
└──────────────────────────┬──────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │    Nginx/K8s LB         │
              │   (负载均衡器)           │
              └────┬─────────┬─────────┬┘
                   │         │         │
        ┌──────────▼─┐ ┌────▼────┐ ┌─▼─────────┐
        │  Node-1    │ │ Node-2  │ │  Node-3   │
        │ WorkerId:1 │ │ WId:2   │ │  WId:3    │
        └──────┬─────┘ └────┬────┘ └──┬────────┘
               │             │         │
               └─────────────┼─────────┘
                             │
              ┌──────────────▼──────────────┐
              │      Redis Cluster          │
              │  (WorkerId池/心跳/注册)      │
              └─────────────────────────────┘
```

### 核心组件

1. **ClusterNodeManager**：集群节点管理器
   - 节点注册与注销
   - WorkerId 自动分配与回收
   - 心跳检测与健康检查

2. **SnowflakeGenerator**：雪花算法生成器
   - 自动从 ClusterNodeManager 获取 WorkerId

3. **ClusterController**：集群管理 API
   - 查看节点信息、在线列表、集群状态

---

## 核心功能

### 1. WorkerId 自动分配

优先级：配置指定 > 集群自动分配 > MAC 地址计算

```yaml
# 策略1：配置指定
astral:
  sequence:
    snowflake:
      worker-id: 1

# 策略2：集群自动分配（推荐）
astral:
  cluster:
    enabled: true

# 策略3：MAC 地址计算（降级方案）
# 不配置 worker-id，也不启用集群模式
```

### 2. 节点注册与发现

```
1. 生成 UUID 作为 NodeId
2. 从 WorkerId 池分配 WorkerId
3. 将节点信息写入 Redis（TTL: 30 秒）
4. 添加到节点列表
```

### 3. 心跳检测

- 心跳间隔：5 秒
- 超时判定：15 秒（3 次心跳）
- 节点 TTL：30 秒
- 清理间隔：30 秒

### 4. 优雅上下线

**上线**：应用启动 → 分配 WorkerId → 注册到 Redis → 启动心跳 → 开始服务

**下线**：接收 SIGTERM → 停止心跳 → 注销节点 → 释放 WorkerId → 退出

---

## 集群部署方案

### 方案一：Docker Compose（开发/测试）

```bash
cd deploy
docker-compose up -d
docker-compose ps
```

### 方案二：Kubernetes（生产）

```bash
kubectl apply -f deploy/kubernetes/
```

### 方案三：物理机/虚拟机

```bash
# 节点1
java -jar astral-server.jar \
  --server.port=8081 \
  --astral.cluster.enabled=true \
  --astral.cluster.node.name=node-1

# 节点2
java -jar astral-server.jar \
  --server.port=8082 \
  --astral.cluster.enabled=true \
  --astral.cluster.node.name=node-2
```

---

## 使用指南

### 启用集群模式

```yaml
astral:
  cluster:
    enabled: true
  sequence:
    default-type: snowflake

spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### 查看集群状态

```bash
# 查看当前节点
curl http://localhost:8081/api/v1/cluster/current

# 查看所有在线节点
curl http://localhost:8081/api/v1/cluster/nodes

# 查看集群整体状态
curl http://localhost:8081/api/v1/cluster/status
```

---

## 故障排查

### WorkerId 分配失败

```bash
# 查看当前在线节点
curl http://localhost:8080/api/v1/cluster/nodes

# 检查 Redis 中的 WorkerId 池
redis-cli LRANGE astral:cluster:worker:id:pool 0 -1
```

### 节点频繁掉线

```bash
# 检查 Redis 连接
redis-cli ping

# 查看心跳日志
tail -f logs/application.log | grep heartbeat
```

### 生成的 ID 重复

```bash
# 查看各节点的 WorkerId
curl http://localhost:8081/api/v1/cluster/current | jq '.data.workerId'
curl http://localhost:8082/api/v1/cluster/current | jq '.data.workerId'
```

确保所有节点都启用了集群模式：`astral.cluster.enabled=true`

---

## 最佳实践

| 场景 | 节点数 | 配置 | QPS |
|------|--------|------|-----|
| 小型应用 | 2 | 2C4G | 5 万+ |
| 中型应用 | 3 | 4C8G | 15 万+ |
| 大型应用 | 5+ | 8C16G | 50 万+ |

---

**Made with ❤️ by Astral Team**
