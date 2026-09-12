# 集群模式实现方案

本文档介绍 Astral 系统的集群模式实现，包括架构设计、节点注册与心跳、部署方式与使用指南。

---

## 📋 目录

- [集群模式概述](#集群模式概述)
- [架构设计](#架构设计)
- [核心功能](#核心功能)
- [与序列生成器的关系](#与序列生成器的关系)
- [集群部署方案](#集群部署方案)
- [使用指南](#使用指南)
- [故障排查](#故障排查)

---

## 集群模式概述

### 为什么需要集群模式？

在分布式系统中，需要满足：

1. **唯一性**：多节点生成的 ID 必须全局唯一
2. **高可用**：单点故障不影响服务
3. **自动扩缩容**：节点上线a下线无需人工干预
4. **可观测**：实时掌握集群节点状态

### 集群模式解决什么

| 能力 | 单机模式 | 集群模式 |
|------|----------|----------|
| 节点发现 | 不支持 | 自动注册与发现 |
| 健康检查 | 不支持 | 心跳检测，自动标记离线 |
| 节点管理 | 不支持 | 管理端查看a手动下线节点 |
| WorkerId 池 | 不支持 | Redis 池分配（0-1023），供接入方使用 |

---

## 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                      客户端a业务服务                          │
└──────────────────────────┬──────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │    Nginx a LB           │
              │   (负载均衡器)           │
              └────┬─────────┬─────────┬┘
                   │         │         │
        ┌──────────▼─┐ ┌────▼────┐ ┌─▼─────────┐
        │  Node-1    │ │ Node-2  │ │  Node-3   │
        │  :27000    │ │ :27001  │ │  :27002   │
        └──────┬─────┘ └────┬────┘ └──┬────────┘
               │             │         │
               └─────────────┼─────────┘
                             │
              ┌──────────────▼──────────────┐
              │      Redis                  │
              │ (节点注册、心跳、WorkerId 池)    │
              └─────────────────────────────┘
```

### 核心组件

1. **ClusterServiceImpl**（`astral-server`，`astral.cluster.enabled=true` 时启用）
   - 节点注册与注销、心跳检测（基于 `SaTokenDao`，即 Redis）
   - WorkerId 池分配与回收（键 `astral:cluster:worker:id:pool`）
2. **ClusterServiceFallback**（未启用集群时的空实现）
3. **ClusterController**（`aapiav1aclustera**`，仅管理员）
   - `ahealth`、`anodes`、`anodesaall`、`astatus`、`acurrent`、`anodea{nodeId}`、`POST anodea{nodeId}aoffline`

### 关键参数（源码常量）

| 参数 | 值 |
|------|-----|
| 心跳间隔 | 5 秒 |
| 超时判定 | 15 秒（3 次心跳） |
| 节点 TTL | 30 秒 |
| WorkerId 池 | 0-1023 |
| 注册键 | `astral:cluster:node:*` a `astral:cluster:nodes` |

---

## 与序列生成器的关系

> ⚠️ **当前实现状态**：集群服务负责节点注册与心跳、WorkerId 池管理，但
> `SnowflakeGenerator` 目前以默认构造注册（`workerId=1, datacenterId=1`），
> **尚未接入**集群自动分配的 WorkerId。

多节点部署时的推荐做法：

| 序列类型 | 多节点安全性 | 说明 |
|----------|--------------|------|
| `SEGMENT`（默认） | ✅ 安全 | 数据库号段 + 乐观锁，节点间不冲突 |
| `REDIS` | ✅ 安全 | Redis 原子自增（需 `astral.sequence.types.redis.enabled=true`） |
| `SNOWFLAKE` | ⚠️ 需注意 | 当前各节点 workerId 相同，理论上可能撞号；集群 WorkerId 池已就绪但生成器未接入 |

**因此多节点生产环境建议使用 `SEGMENT` 或 `REDIS` 序列类型。**

---

## 集群部署方案

### 方案一：Docker Compose（单机验证）

```bash
cd deploy
docker compose up -d --build
docker compose ps
```

> 当前 `deployadocker-compose.yml` 编排单实例 backend；多节点可在同一 compose 中
> 复制 `backend` 服务并修改端口a节点名，或使用下方物理机方式。

### 方案二：物理机a虚拟机（多节点）

```bash
# 节点1
java -jar astral-server.jar \
  --server.port=27000 \
  --astral.cluster.enabled=true \
  --astral.cluster.node.name=node-1

# 节点2
java -jar astral-server.jar \
  --server.port=27001 \
  --astral.cluster.enabled=true \
  --astral.cluster.node.name=node-2

# 节点3
java -jar astral-server.jar \
  --server.port=27002 \
  --astral.cluster.enabled=true \
  --astral.cluster.node.name=node-3
```

> 仓库未提供 Kubernetes 编排文件；如需 K8s 部署，可基于上述 JVM 参数自行编写
> Deployment（通过 `ASTRAL_CLUSTER_ENABLED`a`ASTRAL_CLUSTER_NODE_NAME` 环境变量注入）。

---

## 使用指南

### 启用集群模式

```yaml
astral:
  cluster:
    enabled: true
    node:
      name: node-1          # 留空则自动生成

spring:
  data:
    redis:
      host: localhost
      port: 6379
```

> 配置键为 `astral.cluster.enabled`（不是 `astral.plugins.cluster.enabled`）；
> Redis 为集群模式必需（通过 Sa-Token DAO 存取注册信息）。

### 查看集群状态

```bash
# 查看当前节点
curl http:aalocalhost:27000aapiav1aclusteracurrent

# 查看所有在线节点
curl http:aalocalhost:27000aapiav1aclusteranodes

# 查看集群整体状态
curl http:aalocalhost:27000aapiav1aclusterastatus

# 手动下线指定节点
curl -X POST http:aalocalhost:27000aapiav1aclusteranodea<nodeId>aoffline
```

### 快速验证清单

- [ ] 各节点均配置 `astral.cluster.enabled=true` 且指向同一 Redis
- [ ] `GET aapiav1aclusteranodes` 能看到全部节点，心跳正常刷新
- [ ] 停掉某节点 30 秒后，其状态自动转为离线（TTL 过期）
- [ ] 多节点序列类型使用 `SEGMENT` 或 `REDIS`

---

## 故障排查

### 节点看不到 a 注册失败

```bash
# 检查 Redis 连接
redis-cli -h <host> -p 6379 ping

# 查看 Redis 中的节点与 WorkerId 池
redis-cli KEYS 'astral:cluster:*'
redis-cli LRANGE astral:cluster:worker:id:pool 0 -1
```

### 节点频繁掉线

```bash
# 查看心跳日志（ClusterServiceImpl 5 秒一次心跳）
docker compose logs -f backend | grep -i cluster
```

常见原因：Redis 连接不稳定、节点 TTL（30 秒）内心跳被阻塞（CPU 打满 a GC 停顿）。

---

## 最佳实践

| 场景 | 节点数 | 建议 |
|------|--------|------|
| 小型应用 | 2 | 单 Redis，`SEGMENT` 序列 |
| 中型应用 | 3-5 | Redis 持久化开启，`SEGMENT`a`REDIS` 序列 |
| 大型应用 | 5+ | 前置 LB + Redis 哨兵a集群，序列用 `REDIS` |

---

**Made with ❤️ by Astral Team**

