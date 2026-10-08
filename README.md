# Astral 后台管理系统

企业级后台管理系统，提供用户管理、角色权限、菜单管理、数据字典、系统配置、Token 管理、日志审计、邮件/短信服务、站内通知、告警管理、插件体系、对象存储、序列生成、表结构管理、全端统计等核心功能，并内置「轻听音乐」（qt）、「反馈」（feedback）与「对象存储」（storage）业务插件。

## 📖 项目简介

Astral 是一套基于 Spring Boot 4.1（Java 25）+ Next.js 16 的全栈后台管理系统，采用前后端分离架构，内置完善的 RBAC 权限模型、操作日志审计、Sa-Token 认证与可扩展插件体系，开箱即用。

### 核心功能

- 👥 **用户管理**：用户 CRUD、密码重置、状态切换、角色分配
- 🔐 **角色权限**：角色管理、权限树、RBAC 模型
- 🧭 **菜单管理**：前端菜单/路由维护，侧边栏动态渲染
- 📋 **数据字典**：字典类型/数据管理，支持前端联动
- ⚙️ **系统配置**：动态配置项管理
- 🎫 **Token 管理**：在线会话查看、吊销、踢人、清理过期
- 🧑‍💼 **个人中心**：管理端自助修改资料与密码（登录即可，无需权限码）
- 📧 **邮件服务**：多账户、模板、发送日志、插件发送授权
- 💬 **短信服务**：多渠道服务商、模板、发送日志
- 🔔 **站内通知**：通知规则/事件（`sys_notify_rule`）、站内信门户（收件箱、未读数、已读回执）
- 📝 **日志管理**：操作日志、登录日志，AOP 异步记录
- 🔌 **插件体系**：SPI 扩展 + 插件注册中心 + 前端导航注入，可在管理页启停
- 🎵 **轻听音乐插件（qt）**：App 用户体系、版本更新、打卡、收藏（`/api/v1/app/**`；公告由 feedback 插件统一提供）
- 💬 **反馈插件（feedback）**：用户反馈、管理员回复、站内消息通知
- 🗄️ **对象存储插件（storage）**：存储配置/文件/授权文件夹管理，浏览器/客户端预签名直传（Telegram Bot API、Cloudflare Worker、S3 兼容对象存储等渠道），签名 URL 下载
- 📊 **全端统计**：App 匿名批量上报采集、六类统计报表（访问量/设备/接口指标/错误日志等）
- 🚨 **告警管理**：告警渠道（邮件/Webhook）、规则、触发记录，后台引擎周期评估阈值
- 🔢 **序列生成**：5 种算法（Snowflake、号段、Redis、数据库、内存）
- 🗂️ **表结构管理**：查看/新建/编辑表结构、生成代码、生成 SQL、导出 JSON
- 📈 **系统监控**：CPU/内存/JVM 指标、Prometheus 集成

### 技术栈

| 层级 | 技术 |
|------|------|
| **后端框架** | Spring Boot 4.1.0（Spring Framework 7.0）+ Java 25 |
| **运行时** | JDK 25 + Tomcat 11；已开启虚拟线程（`spring.threads.virtual.enabled=true`） |
| **认证授权** | Sa-Token 1.46（`sa-token-spring-boot4-starter` + Redis 会话，RSA 传输加密） |
| **ORM** | MyBatis-Plus 3.5.17（`mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser`） |
| **数据库** | PostgreSQL（当前运行库，驱动 42.7.11）；MySQL 9.7 / H2 驱动已引入可切换 |
| **数据迁移** | Flyway 12.4（`spring-boot-flyway` + `flyway-database-postgresql`，启动自动应用） |
| **连接池** | HikariCP 7.0.2 |
| **缓存** | Redis（spring-data-redis）；本地缓存 Caffeine 3.2.4；Redisson 3.52 仅供可选的 Redis 序列生成器 |
| **API 文档** | SpringDoc OpenAPI 3.1（Swagger UI：`/swagger-ui.html`） |
| **监控** | Spring Boot Actuator + Micrometer + Prometheus |
| **工具库** | Hutool 5.8.47 |
| **前端框架** | Next.js 16.3.8（App Router + Turbopack）+ React 19.2 + TypeScript 5.6 |
| **UI 组件** | shadcn/ui（Radix UI）+ Tailwind CSS v4 + lucide-react |
| **图表** | ECharts 6（echarts-for-react） |
| **构建工具** | Maven 3.9+（后端，无 wrapper）/ npm（前端，`package-lock.json`） |

> 前端已从 Ant Design 全量迁移到 shadcn/ui + Tailwind v4（Next 16 起 `next lint` 已移除，故 `package.json` 无 lint 脚本）。

## 🚀 快速开始

### 环境要求

- JDK 25（Spring Boot 4.1 / Spring Framework 7 要求；低版本无法编译）
- Maven 3.9+（无 wrapper，使用系统 mvn）
- Node.js 24 LTS（与前端镜像 `node:24-alpine` 一致；Next.js 16 最低要求 20.9+）
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
# Windows 可直接运行 run-backend.bat（需先设置 JAVA_HOME 指向 JDK 25，端口 27000）
```

后端默认运行在 `http://localhost:27000`。数据库结构变更由 Flyway 在启动时自动应用 `db/migration/` 下的增量脚本。qt / feedback 插件的建表由各自的初始化器幂等完成；storage 等新模块的表结构已由 Flyway 迁移接管。

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

### 6. 初始账号

| 用户名 | 初始密码 | 角色 |
|--------|----------|------|
| admin | 与用户名相同（仅首次登录使用，登录后请立即修改） | 管理员 |

> 初始密码以 BCrypt 形式由迁移基线 `V20260914001__init.sql` 的种子数据写入；登录失败 5 次将锁定 15 分钟（内存态，重启后端可解除）。

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
├── astral-plugin/             # 插件核心及内置插件（qt、feedback、storage）
├── astral-server/             # Web 服务（Controller、配置、入口）
├── astral-front/              # 前端（Next.js 16 + React 19 + Tailwind v4 + shadcn/ui）
├── cloudflare/                # Cloudflare Worker（storage 直传回执等）
├── deploy/                    # Docker Compose 部署（本地构建 / Registry 拉取 / 一键更新）
├── docs/                      # 补充文档（备份、代码评审、编码规范）
└── scripts/                   # 打包、schema 生成等辅助脚本
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
| 短信服务商 | `/api/v1/admin/system/sms/provider` | 短信渠道账户管理 |
| 短信模板 | `/api/v1/admin/system/sms/template` | 短信模板管理 |
| 短信日志 | `/api/v1/admin/system/sms/log` | 发送记录 |
| 通知规则 | `/api/v1/admin/system/notify` | 通知规则/事件管理 |
| 站内信门户 | `/api/v1/all/notify/inapp` | 个人收件箱、未读数、已读回执 |

### 认证（astral-auth）

| 模块 | 路径 | 说明 |
|------|------|------|
| 登录 | `POST /api/v1/all/auth/login` | 用户名密码登录（RSA 加密传输） |
| 登出 | `POST /api/v1/all/auth/logout` | 退出登录 |
| 用户信息 | `GET /api/v1/all/auth/info` | 获取当前用户信息 |
| 个人中心 | `/api/v1/admin/profile` | 管理端自助修改资料与密码 |

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
| 系统监控 | `/api/v1/admin/monitor/**` | CPU/内存/JVM/数据库/Redis/业务指标 |
| 告警管理 | `/api/v1/admin/alert/**` | 告警渠道（邮件/Webhook）、规则、触发记录 |
| 统计报表 | `/api/v1/admin/stat/**` | 六类统计报表接口 |
| App 统计上报 | `/api/v1/app/stat/**` | 客户端匿名批量上报 |

### 插件与其他

| 模块 | 路径 | 说明 |
|------|------|------|
| 插件管理 | `/api/v1/admin/plugin` | 插件列表、启停、导航扩展 |
| 轻听音乐 App | `/api/v1/app/user/**` `/api/v1/app/**` | App 端接口（Bearer Token） |
| 轻听音乐后台 | `/api/v1/admin/qt/**` | 管理端（宿主 Sa-Token） |
| 反馈 | `/api/v1/admin/feedback` `/api/v1/app/feedback` | 用户反馈与管理员回复 |
| 站内消息 | `/api/v1/admin/message` `/api/v1/app/message` | 反馈插件的消息通知 |
| 存储管理 | `/api/v1/admin/plugin/storage/**` | 存储配置/文件/授权文件夹管理 |
| 直传凭证 | `/api/v1/all/storage/**` | 用户端换取短时上传凭证、Worker 回执登记 |

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
    storage:
      enabled: ${STORAGE_PLUGIN_ENABLED:true}
      # 单文件上限（字节）：公共 Bot API 下载上限 20MiB，超过将无法再次下载
      max-file-size-bytes: ${STORAGE_MAX_FILE_SIZE:20971520}
      upload-ticket-ttl-seconds: 600
      # 三把 HMAC 密钥全部经环境变量注入，禁止写入仓库
      upload-ticket-key: ${STORAGE_UPLOAD_TICKET_KEY:}
      origin-shared-secret: ${STORAGE_ORIGIN_SHARED_SECRET:}
      download-signing-key: ${STORAGE_DOWNLOAD_SIGNING_KEY_V1:}

  sequence:
    default-type: segment    # 默认号段模式
    default-step: 1000       # 号段步长

# Sa-Token
sa-token:
  token-name: satoken
  timeout: 259200            # Token 有效期 3 天
  is-read-header: true       # 轻听 App / 脚本：显式 satoken 请求头
  is-read-cookie: true       # 管理台：HttpOnly Cookie（防 XSS 窃取，写请求需带 X-CSRF-Token）

# 管理端认证 Cookie（仅管理台；轻听 App 走请求头不受影响）
astral:
  auth:
    cookie-secure: false     # 生产 HTTPS 必须 true（application-prod.yml 默认已 true），本地 http 开发须 false
    cookie-same-site: Strict # 前后端拆到不同站点时才需放宽为 None（此时 cookie-secure 必须 true）
```

> **认证模型**：管理台令牌存 HttpOnly Cookie，JS 读不到；代价是需 CSRF 双提交防护
> （`astral_csrf` Cookie ↔ `X-CSRF-Token` 头）。详见 [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md#token-管理httponly-cookie--csrf-双提交)。

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

`deploy/` 内含 `docker-compose.yml`（前端 3000 / 后端 27000，PostgreSQL 与 Redis 复用服务器已有实例）、`docker-compose.registry.yml`（从镜像仓库拉取的 overlay）、`update.sh`（一键拉取镜像并重建）与 `nginx-reverse-proxy.example.conf`（反向代理示例）；
更多细节见 [DEPLOY_GUIDE.md](DEPLOY_GUIDE.md)。

## 📚 详细文档

| 文档 | 说明 |
|------|------|
| [组件指南](COMPONENTS_GUIDE.md) | 日志、认证（含 Sa-Token）、监控 |
| [集成指南](INTEGRATION_GUIDE.md) | 与宿主系统/前端集成的步骤清单 |
| [插件开发指南](PLUGIN_GUIDE.md) | 插件 SPI、建表、导航扩展 |
| [部署指南](DEPLOY_GUIDE.md) | Docker Compose 与环境变量 |
| [接口文档](API.md) | 全量 Controller 接口清单 |
| [存储需求说明书](STORAGE_REQUIREMENTS.md) | 对象存储插件的需求与设计（多渠道直传、签名 URL） |

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

