# Astral 后台管理系统

企业级后台管理系统，提供用户管理、角色权限、数据字典、系统配置、Token 管理、日志审计、序列生成、表结构管理等核心功能。

## 📖 项目简介

Astral 是一套基于 Spring Boot 3 + Next.js 14 的全栈后台管理系统，采用前后端分离架构，内置完善的 RBAC 权限模型、操作日志审计、Sa-Token 认证、多数据库支持等能力，开箱即用。

### 核心功能

- 👥 **用户管理**：用户 CRUD、密码重置、状态切换、角色分配
- 🔐 **角色权限**：角色管理、权限树、RBAC 模型
- 📋 **数据字典**：字典类型/数据管理，支持前端联动
- ⚙️ **系统配置**：动态配置项管理
- 🎫 **Token 管理**：在线会话查看、吊销、踢人、清理过期
- 📝 **日志管理**：操作日志、登录日志，AOP 异步记录
- 🔢 **序列生成**：5 种算法（Snowflake、号段、Redis、数据库、内存）
- 🗂️ **表结构管理**：查看/新建/编辑表结构、生成代码、生成 SQL、导出 JSON
- 📊 **系统监控**：CPU/内存/JVM 指标、Prometheus 集成
- 🌐 **集群管理**：节点注册、心跳检测、WorkerId 自动分配

### 技术栈

| 层级 | 技术 |
|------|------|
| **后端框架** | Spring Boot 3.2.x |
| **认证授权** | Sa-Token |
| **ORM** | MyBatis-Plus 3.5.x |
| **数据库** | H2（默认）/ MySQL / PostgreSQL / Oracle / SQL Server |
| **缓存** | Redis（可选，Redisson） |
| **API 文档** | SpringDoc OpenAPI 3 |
| **监控** | Spring Boot Actuator + Micrometer + Prometheus |
| **工具库** | Hutool 5.8.x |
| **前端框架** | Next.js 14 + React 18 |
| **UI 组件** | Ant Design 5 |
| **构建工具** | Maven（后端）/ npm（前端） |

## 🚀 快速开始

### 环境要求

- JDK 21
- Maven 3.6+
- Node.js 18+

### 1. 编译后端

```bash
mvn clean install -DskipTests
```

### 2. 启动后端

```bash
mvn -pl astral-server spring-boot:run
```

后端默认运行在 `http://localhost:8080`，使用 H2 文件数据库，无需额外配置。

### 3. 启动前端

```bash
cd astral-front
npm install
npm run dev
```

前端默认运行在 `http://localhost:3000`，自动代理 `/api/*` 到后端。

### 4. 访问系统

- **前端界面**：http://localhost:3000
- **API 文档**：http://localhost:8080/swagger-ui.html
- **H2 控制台**：http://localhost:8080/h2-console
- **监控端点**：http://localhost:8080/actuator

### 5. 默认账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | 管理员 |

## 🏛️ 项目结构

```
astral/
├── astral-common/          # 公共模块（工具类、统一返回、异常）
├── astral-schema/          # 表结构元数据（代码生成引擎）
├── astral-dao/             # 数据访问层（MyBatis-Plus 实体 + Mapper）
├── astral-sequence/        # 序列生成核心（5 种生成器）
├── astral-log/             # 日志模块（AOP 操作日志、登录日志）
├── astral-auth/            # 认证模块（Sa-Token 拦截器、权限）
├── astral-monitor/         # 监控模块（Micrometer 指标）
├── astral-server/          # Web 服务（Controller、配置、入口）
├── astral-front/           # 前端（Next.js 14 + Ant Design）
├── sql/                    # 数据库初始化脚本
└── deploy/                 # Docker Compose + Kubernetes 部署
```

## 📡 API 模块

### 系统管理

| 模块 | 路径 | 说明 |
|------|------|------|
| 用户管理 | `/api/v1/system/user` | CRUD、密码重置、角色分配 |
| 角色管理 | `/api/v1/system/role` | CRUD、权限分配 |
| 权限管理 | `/api/v1/system/permission` | 树形 CRUD |
| 数据字典 | `/api/v1/system/dict` | 字典类型/数据 CRUD |
| 系统配置 | `/api/v1/system/config` | 配置项 CRUD |
| Token 管理 | `/api/v1/system/token` | 会话查看、吊销、踢人 |
| 表结构管理 | `/api/v1/system/table-schema` | 查看/新建/编辑、生成代码/SQL |

### 认证

| 模块 | 路径 | 说明 |
|------|------|------|
| 登录 | `POST /api/v1/auth/login` | 用户名密码登录 |
| 登出 | `POST /api/v1/auth/logout` | 退出登录 |
| 用户信息 | `GET /api/v1/auth/info` | 获取当前用户信息 |

### 日志管理

| 模块 | 路径 | 说明 |
|------|------|------|
| 操作日志 | `GET /api/v1/log/operate` | 分页查询操作日志 |
| 登录日志 | `GET /api/v1/log/login` | 分页查询登录日志 |

### 序列管理

| 模块 | 路径 | 说明 |
|------|------|------|
| 获取序列 | `POST /api/v1/sequence/next` | 获取下一个序列号 |
| 批量获取 | `POST /api/v1/sequence/batch` | 批量获取序列号 |
| 序列配置 | `/api/v1/sequence/configs` | 序列配置 CRUD |
| 序列统计 | `/api/v1/sequence/statistics` | 使用统计查询 |

### 监控

| 模块 | 路径 | 说明 |
|------|------|------|
| 系统监控 | `GET /api/v1/monitor/system` | CPU/内存/磁盘 |
| JVM 监控 | `GET /api/v1/monitor/jvm` | 堆内存/GC/线程 |
| 业务指标 | `GET /api/v1/monitor/business` | QPS/响应时间/错误率 |

## ⚙️ 配置说明

核心配置位于 `astral-server/src/main/resources/application.yml`：

```yaml
# 数据库（默认 H2，开箱即用）
spring:
  datasource:
    url: jdbc:h2:file:./data/astral;AUTO_SERVER=TRUE
    username: sa
    password:

# 序列生成
astral:
  sequence:
    default-type: segment    # 默认号段模式
    default-step: 1000       # 号段步长

# 日志
  plugins:
    log:
      enabled: true          # 启用操作日志

# Sa-Token
sa-token:
  token-name: satoken
  timeout: 604800            # Token 有效期 7 天
```

### 切换到 MySQL

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/astral?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
```

### 启用 Redis

```yaml
astral:
  sequence:
    types:
      redis:
        enabled: true

spring:
  data:
    redis:
      host: localhost
      port: 6379
```

## 🧪 运行测试

```bash
# 所有测试
mvn test

# 单个模块
mvn test -pl astral-sequence

# 单个测试类
mvn -pl astral-sequence -Dtest=GeneratorFactoryTest test
```

## 🚀 部署

### Docker Compose

```bash
cd deploy
docker-compose up -d
```

### Kubernetes

```bash
kubectl apply -f deploy/kubernetes/
```

## 📚 详细文档

| 文档 | 说明 |
|------|------|
| [快速开始](QUICKSTART.md) | 环境搭建与运行 |
| [组件指南](COMPONENTS_GUIDE.md) | 日志、认证、监控、集群 |
| [Sa-Token 指南](SATOKEN_GUIDE.md) | 认证与权限管理 |
| [多数据库指南](MULTI_DATABASE_GUIDE.md) | 数据库切换与迁移 |
| [集群模式](CLUSTER_MODE.md) | 集群架构与部署 |
| [集群快速开始](CLUSTER_QUICKSTART.md) | 5 分钟搭建集群 |

## 🤝 贡献指南

1. Fork 本仓库
2. 创建特性分支（`git checkout -b feature/新功能`）
3. 提交更改（`git commit -m 'feat: 添加新功能'`）
4. 推送到分支（`git push origin feature/新功能`）
5. 提交 Pull Request

## 📄 许可证

MIT License

---

**Made with ❤️ by Astral Team**
