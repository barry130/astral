# 集群模式快速开始

5 分钟快速搭建 Astral 系统集群。

---

## 📋 前置条件

- Docker 20.10+
- Docker Compose 2.0+
- 至少 4GB 可用内存

---

## 🚀 方式一：Docker Compose 一键启动

### Step 1：进入部署目录

```bash
cd deploy
```

### Step 2：启动集群

```bash
docker-compose up -d
```

### Step 3：查看状态

```bash
docker-compose ps
```

### Step 4：测试集群

```bash
# 查看集群状态
curl http://localhost:8080/api/v1/cluster/status

# 查看在线节点
curl http://localhost:8080/api/v1/cluster/nodes

# 分别测试三个节点
curl http://localhost:8081/api/v1/cluster/current
curl http://localhost:8082/api/v1/cluster/current
curl http://localhost:8083/api/v1/cluster/current
```

### 停止集群

```bash
docker-compose down
```

---

## 💻 方式二：本地多实例模式

### Step 1：编译项目

```bash
mvn clean package -DskipTests
```

### Step 2：启动三个节点

**终端 1：**
```bash
java -jar astral-server/target/astral-server-1.0.0.jar \
  --server.port=8081 \
  --astral.cluster.enabled=true \
  --astral.cluster.node.name=node-1
```

**终端 2：**
```bash
java -jar astral-server/target/astral-server-1.0.0.jar \
  --server.port=8082 \
  --astral.cluster.enabled=true \
  --astral.cluster.node.name=node-2
```

**终端 3：**
```bash
java -jar astral-server/target/astral-server-1.0.0.jar \
  --server.port=8083 \
  --astral.cluster.enabled=true \
  --astral.cluster.node.name=node-3
```

### Step 3：测试

```bash
curl http://localhost:8081/api/v1/cluster/current
curl http://localhost:8082/api/v1/cluster/current
curl http://localhost:8083/api/v1/cluster/current
```

---

## ✅ 验证清单

- [ ] 所有节点都能访问 `/api/v1/cluster/current`
- [ ] 每个节点的 WorkerId 不同
- [ ] 节点状态为 ONLINE
- [ ] 不同节点生成的序列号唯一

---

## 🐛 常见问题

### Q1: 节点启动失败，报错 "Cluster initialization failed"

检查 Redis 是否运行：`redis-cli ping`

### Q2: WorkerId 重复

确保所有节点都设置了 `astral.cluster.enabled=true`

---

## 🎯 下一步

- 查看完整文档：[CLUSTER_MODE.md](CLUSTER_MODE.md)
- 查看 API 文档：http://localhost:8080/swagger-ui.html
