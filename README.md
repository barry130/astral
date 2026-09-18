# Astral 后台管理系统

企业级后台管理系统，提供用户管理、角色权限、菜单管理、数据字典、系统配置、Token 管理、日志审计、邮件服务、插件体系、序列生成、表结构管理、全端统计等核心功能，并内置「轻听音乐」（qt）与「反馈」（feedback）业务插件。

## 📖 项目简介

Astral 是一套基于 Spring Boot 3 + Next.js 14 的全栈后台管理系统，采用前后端分离架构，内置完善的 RBAC 权限模型、操作日志审计、Sa-Token 认证与可扩展插件体系，开箱即用。

### 核心功能

- 👥 **用户管理**：用户 CRUD、密码重置、状态切换、角色分配
- 🔐 **角色权限**：角色管理、权限树、RBAC 模型
- 🧭 **菜单管理**：前端菜单/路由维护，侧边栏动态渲染
- 📋 **数据字典**：字典类型/数据管理，支持前端联动
- ⚙️ **系统配置**：动态配置项管理
- 🎫 **Token 管理**：在线会话查看、吊销、踢人、清理过期
- 📧 **邮件服务**：多账户、模板、发送日志、插件发送授权
- 📝 **日志管理**：操作日志、登录日志，AOP 异步记录
- 🔌 **插件体系**：SPI 扩展 + 插件注册中心 + 前端导航注入，可在管理页启停
- 🎵 **轻听音乐插件（qt）**：App 用户体系、公告、版本更新、打卡、收藏（`/api/v1/app/**`）
- 💬 **反馈插件（feedback）**：用户反馈、管理员回复、站内消息通知
- 📊 **全端统计**：客户端上报采集、访问量/设备/接口指标、错误日志聚合
- 🔢 **序列生成**：5 种算法（Snowflake、号段、Redis、数据库、内存）
- 🗂️ **表结构管理**：查看/新建/编辑表结构、生成代码、生成 SQL、导出 JSON
- 📈 **系统监控**：CPU/内存/JVM 指标、Prometheus 集成

### 技术栈

| 层级 | 技术 |
|------|------|
| **后端框架** | Spring Boot 3.2.x（Java 21） |
| **认证授权** | Sa-Token |
| **ORM** | MyBatis-Plus 3.5.x |
| **数据库** | PostgreSQL（当前默认，见 `application.yml`）；驱动另含 MySQL、H2 可切换 |
| **缓存** | Redis（spring-data-redis；Redisson 仅用于可选的 Redis 序列生成器） |
| **API 文档** | SpringDoc OpenAPI 3 |
| **监控** | Spring Boot Actuator + Micrometer + Prometheus |
| **工具库** | Hutool 5.8.x |
| **前端框架** | Next.js 14 (App Router) + React 18 + TypeScript |
| **UI 组件** | Ant Design 5 |
| **构建工具** | Maven（后端）/ npm（前端） |

## 🚀 快速开始

### 环境要求

- JDK 21
- Maven 3.6+（无 wrapper，使用系统 mvn）
- Node.js 18+
- PostgreSQL（当前运行库）
- Redis（邮件验证码、Sa-Token 会话等依赖；未配置时相应功能不可用）

数据库与 Redis 连接统一由环境变量注入（`SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` / `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DB`），仓库内默认值指向 `localhost` 且不含任何真实凭据。

### 1. 编译后端

```bash
mvn clean install -DskipTests
```

### 2. 初始化数据库（仅首次，一条语句）

数据库迁移由 Flyway 接管：启动后端时自动按序应用 `db/migration/V*.sql` 并记录 `flyway_schema_history`。
唯一的手工步骤是建库；schema、表结构、种子数据、数据字典全部由 Flyway 完成。
存量库（已手工应用过旧版脚本）会自动基线化，跳过初始化脚本，无需登记、无需人工干预。

```bash
psql -c "CREATE DATABASE astral;"
```

> 机制说明（基线行为、命名规范、新增变更流程）见 [db/migration/README.md](astral-server/src/main/resources/db/migration/README.md)。

### 3. 启动后端

```bash
mvn -pl astral-server spring-boot:run
# Windows 可直接运行 run-backend.bat（需先设置 JAVA_HOME 指向 JDK 21，端口 27000）
```

后端默认运行在 `http://localhost:27000`。数据库结构变更由 Flyway 在启动时自动应用 `db/migration/` 下的增量脚本。qt / feedback 插件的建表由各自的初始化器幂等完成。

### 4. 启动前端

```bash
cd astral-front
npm install
npm run dev
# Windows 可直接运行 run-frontend.bat
```

前端默认运行在 `http://localhost:3000`，自动将 `/api/*` 代理到后端 `http://localhost:27000`。

### 5. 访问系统

- **前端界面**：http://localhost:3000
  - 根路径 `/` 为项目介绍首页（核心功能、业务插件、技术栈），不再自动跳转
  - 登录页 `/login`、控制台 `/dashboard` 可从首页按钮进入
- **API 文档**：http://localhost:27000/swagger-ui.html
- **监控端点**：http://localhost:27000/actuator

### 6. 默认账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin | 管理员 |

> 密码以 BCrypt 存储，由迁移基线 `V1__baseline_schema.sql` 的种子数据写入；登录失败 5 次将锁定 15 分钟（内存态，重启后端可解除）。

## 🏛️ 项目结构

```
astral/
├── astral-common/             # 公共模块（工具类、统一返回、异常）
├── astral-schema/             # 表结构元数据（实体/schema 同步、代码生成引擎）
├── astral-dao/                # 数据访问层（MyBatis-Plus 实体 + Mapper）
├── astral-auth/               # 认证模块（Sa-Token 拦截器、RSA 登录加密、登录限流）
├── astral-log/                # 日志模块（AOP 操作日志、登录日志）
├── astral-monitor/            # 监控模块（Micrometer 指标）
├── astral-system/             # 系统管理（用户/角色/权限/菜单/字典/配置/Token/表结构/邮件）
├── astral-sequence/           # 序列生成核心（5 种生成器，系统必需插件）
├── astral-plugin-api/         # 插件 SPI（AstralPlugin、导航扩展、注册中心接口）
├── astral-plugin/             # 插件核心及内置插件（qt、feedback）
├── astral-server/             # Web 服务（Controller、配置、入口）
├── astral-front/              # 前端（Next.js 14 + Ant Design 5）
└── deploy/                    # Docker Compose 部署
```

## 📡 API 模块

### 系统管理（astral-system）

| 模块 | 路径 | 说明 |
|------|------|------|
| 用户管理 | `/api/v1/admin/system/user` | CRUD、密码重置、角色分配 |
| 角色管理 | `/api/v1/admin/system/role` | CRUD、权限分配 |
| 权限管理 | `/api/v1/admin/system/permission` | 树形 CRUD |
| 菜单管理 | `/api/v1/admin/system/menu` | 菜单/路由 CRUD |
| 数据字典 | `/api/v1/admin/system/dict` | 字典类型/数据 CRUD |
| 系统配置 | `/api/v1/admin/system/config` | 配置项 CRUD |
| Token 管理 | `/api/v1/admin/system/token` | 会话查看、吊销、踢人 |
| 表结构管理 | `/api/v1/admin/system/table-schema` | 查看/编辑、生成代码/SQL |
| 邮件账户 | `/api/v1/admin/system/mail/account` | 发件账户管理 |
| 邮件模板 | `/api/v1/admin/system/mail/template` | 模板管理 |
| 邮件日志 | `/api/v1/admin/system/mail/log` | 发送记录 |
| 邮件插件授权 | `/api/v1/admin/system/mail/plugin-auth` | 插件发送授权 |

### 认证（astral-auth）

| 模块 | 路径 | 说明 |
|------|------|------|
| 登录 | `POST /api/v1/all/auth/login` | 用户名密码登录（RSA 加密传输） |
| 登出 | `POST /api/v1/all/auth/logout` | 退出登录 |
| 用户信息 | `GET /api/v1/all/auth/info` | 获取当前用户信息 |

### 日志（astral-log）

| 模块 | 路径 | 说明 |
|------|------|------|
| 操作日志 | `/api/v1/admin/log/operate_log` | 分页查询操作日志 |
| 登录日志 | `/api/v1/admin/log/login_log` | 分页查询登录日志 |

### 序列（astral-sequence）

| 模块 | 路径 | 说明 |
|------|------|------|
| 获取序列 | `POST /api/v1/all/sequence/next` | 获取下一个序列号（有限流） |
| 批量获取 | `POST /api/v1/all/sequence/batch` | 批量获取序列号 |
| 序列配置 | `/api/v1/admin/sequence/configs` | 序列配置 CRUD |
| 号段/历史/统计 | `/api/v1/admin/sequence/segment` `/history` `/statistics` | 运行数据查询 |

### 监控与统计

| 模块 | 路径 | 说明 |
|------|------|------|
| 系统监控 | `/api/v1/admin/monitor/**` | CPU/内存/JVM/业务指标 |

### 插件与其他

| 模块 | 路径 | 说明 |
|------|------|------|
| 插件管理 | `/api/v1/admin/plugin` | 插件列表、启停、导航扩展 |
| 轻听音乐 App | `/api/v1/app/user/**`(新) `/api/v1/app/**`（旧 `/api/v1/user/**` 保留废弃） | App 端接口（Bearer Token） |
| 轻听音乐后台 | `/api/v1/admin/qt/**` | 管理端（宿主 Sa-Token） |

## ⚙️ 配置说明

核心配置位于 `astral-server/src/main/resources/application.yml`：

```yaml
# 数据库（当前为 PostgreSQL；MySQL/H2 驱动已引入，可切换）
# 生产/部署通过环境变量注入，仓库内只保留 localhost 安全默认值，不含真实凭据
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/astral?currentSchema=astral}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:}

  sql:
    init:
      # 结构变更由 Flyway 接管（db/migration/），sql.init 保持关闭
      mode: never

# 插件开关（未配置的插件默认开启）
astral:
  plugins:
    enabled: true
    qt:
      enabled: ${QT_PLUGIN_ENABLED:true}
    feedback:
      enabled: ${FEEDBACK_PLUGIN_ENABLED:true}

  sequence:
    default-type: segment    # 默认号段模式
    default-step: 1000       # 号段步长

# Sa-Token
sa-token:
  token-name: satoken
  timeout: 259200            # Token 有效期 3 天
```

### 启用 Redis 序列生成器

```yaml
astral:
  sequence:
    default-type: redis
    types:
      redis:
        enabled: true   # RedisGenerator 按 @ConditionalOnProperty 条件加载
```

> Redis 本身由 `spring.data.redis.*` 配置；Redisson 自动装配默认被排除，仅 Redis 序列生成器需要。

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

```bash
cd deploy
cp .env.example .env     # 填写数据库 / Redis 真实连接信息
docker compose up -d --build
```

`deploy/` 内含 `docker-compose.yml`（前端 3000 / 后端 27000，PostgreSQL 与 Redis 复用服务器已有实例）；
更多细节见 [DEPLOY_GUIDE.md](DEPLOY_GUIDE.md)。

## 📚 详细文档

| 文档 | 说明 |
|------|------|
| [组件指南](COMPONENTS_GUIDE.md) | 日志、认证（含 Sa-Token）、监控 |
| [集成指南](INTEGRATION_GUIDE.md) | 与宿主系统/前端集成的步骤清单 |
| [插件开发指南](PLUGIN_GUIDE.md) | 插件 SPI、建表、导航扩展 |
| [部署指南](DEPLOY_GUIDE.md) | Docker Compose 与环境变量 |
| [接口文档](API.md) | 全量 Controller 接口清单 |

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

