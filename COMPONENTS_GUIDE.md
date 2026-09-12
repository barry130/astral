# Astral 组件指南

本文档介绍 Astral 后台管理系统的各模块功能与使用方法。

---

## 📦 模块概览

| 模块 | 说明 |
|------|------|
| astral-server | Web/API 层（控制器、集群管理、入口） |
| astral-common | 公共工具（统一返回、异常、常量） |
| astral-dao | 数据访问（MyBatis-Plus 实体 + Mapper） |
| astral-schema | 表结构元数据（实体同步、代码生成引擎） |
| astral-auth | 认证授权（Sa-Token、RSA 登录加密、登录限流） |
| astral-log | 日志服务（操作日志、登录日志） |
| astral-monitor | 监控服务（系统/JVM/业务指标） |
| astral-system | 系统管理（用户/角色/权限/菜单/字典/配置/Token/表结构/邮件） |
| astral-sequence | 序列生成（5 种算法，系统必需插件） |
| astral-plugin-api / astral-plugin | 插件 SPI / 注册中心 |
| astral-plugin-demo / qt | 示例 / 轻听音乐插件 |

---

## 1. 日志服务（astral-log）

### 功能

- 操作日志自动记录（基于 AOP 注解）
- 登录日志记录
- 异步写入，不阻塞主流程
- REST API 查询接口

### 数据库表

- `sys_operate_log` — 操作日志表
- `sys_login_log` — 登录日志表

### 使用方式

在 Controller 方法上添加 `@OperateLog` 注解：

```java
@OperateLog("创建用户")
@PostMapping
public Result createUser(@RequestBody User user) {
    return Result.success(userService.create(user));
}
```

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/log/operate_log` | 查询操作日志 |
| GET | `/api/v1/admin/log/login_log` | 查询登录日志 |

---

## 2. 认证授权（astral-auth）

### 功能

- Sa-Token 认证（登录态、路由拦截、权限/角色检查）
- 登录密码 RSA 加密传输（`RsaKeyManager`，密钥持久化于 `./data/rsa-key.pair`）
- 登录失败锁定：同一用户名 5 次失败锁定 15 分钟（内存态，重启后端可解除）
- 密码 BCrypt 加密（Hutool）
- 用户 CRUD 管理
- 角色权限控制（RBAC 模型）
- Token 管理（查看、吊销、踢人）

### 数据库表

- `sys_user` — 用户表
- `sys_role` — 角色表
- `sys_permission` — 权限表
- `sys_user_role` — 用户角色关联表
- `sys_role_permission` — 角色权限关联表

### Sa-Token 配置（application.yml）

```yaml
sa-token:
  token-name: satoken     # Token 请求头名称
  timeout: 259200          # 有效期 3 天
  active-timeout: -1      # 不启用临时过期
  is-concurrent: true     # 允许同一账号并发登录
  is-share: true
  token-style: uuid
  is-read-header: true
```

> Sa-Token 的会话存储默认为内存；`sa-token-redis-jackson` 依赖在 classpath 且配置了
> Redis 时可持久化到 Redis。登录锁定计数（`RsaKeyManager.loginFailures`）始终为内存态。

### 快速使用

1. 获取 RSA 公钥并加密密码，然后登录：

```bash
# 1. 获取公钥
curl http://localhost:27000/api/v1/all/auth/public-key
# 2. 用公钥 RSA 加密密码（前端 astral-front/src/lib/crypto.ts 已自动处理），登录
curl -X POST http://localhost:27000/api/v1/all/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<RSA-encrypted-base64>"}'
```

2. 使用 Token 访问 API：

```bash
curl http://localhost:27000/api/v1/admin/system/user/list \
  -H "satoken: <your-token>"
```

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/all/auth/public-key` | 获取 RSA 登录公钥 |
| POST | `/api/v1/all/auth/login` | 用户登录（密码需 RSA 加密） |
| POST | `/api/v1/all/auth/logout` | 用户登出 |
| GET | `/api/v1/all/auth/info` | 获取当前用户信息 |
| GET/POST/PUT/DELETE | `/api/v1/admin/system/user` | 用户管理 |
| GET/POST/PUT/DELETE | `/api/v1/admin/system/role` | 角色管理 |
| GET/POST/PUT/DELETE | `/api/v1/admin/system/permission` | 权限管理 |
| GET/PUT/DELETE | `/api/v1/admin/system/token` | Token 管理 |

### 默认管理员

- 用户名：`admin`
- 密码：`admin`（BCrypt 存储，种子 SQL 写入；生产环境登录后请立即修改）

---

## 3. 监控服务（astral-monitor）

### 功能

- 系统监控（CPU、内存、磁盘、网络）
- JVM 监控（堆内存、GC、线程数）
- 业务指标监控（QPS、响应时间、错误率）
- 集成 Micrometer + Prometheus

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/monitor/system` | 系统资源状态 |
| GET | `/api/v1/admin/monitor/jvm` | JVM 状态 |
| GET | `/api/v1/admin/monitor/business` | 业务指标 |

### Prometheus 集成

```bash
curl http://localhost:27000/actuator/prometheus
```

---

## 4. 序列生成（astral-sequence）

### 支持的算法

| 算法 | 说明 | 性能 | 连续性 | 分布式 |
|------|------|------|--------|--------|
| Segment（默认） | 号段模式，批量获取本地分配 | ⭐⭐⭐⭐ | ✅ | ✅ |
| Snowflake | 推特分布式 ID 算法 | ⭐⭐⭐⭐⭐ | ❌ | ✅ |
| Redis | Redis 原子递增 | ⭐⭐⭐⭐ | ✅ | ✅ |
| Database | 数据库逐次分配 | ⭐ | ✅ | ✅ |
| Simple | 内存计数器 | ⭐⭐⭐⭐⭐ | ✅ | ❌ |

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/all/sequence/next` | 获取序列号（有限流） |
| POST | `/api/v1/sequence/batch` | 批量获取 |
| GET | `/api/v1/sequence/types` | 支持的类型 |
| GET/POST/PUT/DELETE | `/api/v1/sequence/configs` | 序列配置管理 |
| GET | `/api/v1/admin/sequence/statistics` | 使用统计 |

---

## 5. 表结构管理（astral-schema）

### 功能

- 查看所有表结构
- **新建表结构**（表名、注释、模块、默认字段）
- 编辑表结构（添加/删除字段、修改属性）
- 生成代码（Entity/Mapper/Service/Controller）
- 生成建表 SQL / ALTER SQL
- 导出 JSON

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/system/table-schema` | 获取所有表结构 |
| POST | `/api/v1/admin/system/table-schema` | **新建表结构** |
| GET | `/api/v1/admin/system/table-schema/{tableName}` | 获取单个表结构 |
| PUT | `/api/v1/admin/system/table-schema/{tableName}` | 更新表结构 |
| DELETE | `/api/v1/admin/system/table-schema/{tableName}` | 删除表结构 |
| GET | `/api/v1/admin/system/table-schema/{tableName}/sql` | 生成建表 SQL |
| GET | `/api/v1/admin/system/table-schema/{tableName}/full-code` | 生成完整代码 |

---

## 6. 邮件服务（astral-system）

### 功能

- 多发件账户管理（SMTP 主机/端口/SSL 均存库）
- 邮件模板 + 变量渲染（`${var}` 占位符）
- 发送日志
- 插件发送授权（qt 等插件经授权后可发邮件，如 App 注册验证码）

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| CRUD | `/api/v1/admin/system/mail/account` | 发件账户管理 |
| CRUD | `/api/v1/admin/system/mail/template` | 模板管理 |
| GET | `/api/v1/admin/system/mail/log` | 发送日志 |
| CRUD | `/api/v1/admin/system/mail/plugin-auth` | 插件发送授权 |

> 发送实现：`MailServiceImpl` 按场景从 `sys_mail_account` 选择可用账户，自建
> `JavaMailSenderImpl` 发送（不使用 `spring.mail.*` 自动装配），失败自动尝试下一账户并记录日志。

---

## 🏗️ 模块依赖关系

```
astral-server (Web 层, 入口)
├── astral-common (公共工具)
├── astral-dao (数据访问)
├── astral-log (日志服务)
├── astral-auth (认证授权)
├── astral-monitor (监控服务)
├── astral-sequence (序列生成, 系统必需插件)
├── astral-schema (表结构元数据)
├── astral-system (系统管理域)
├── astral-plugin (插件注册中心, 依赖 astral-plugin-api)
├── astral-plugin-demo (示例插件)
└── astral-plugin-qt (轻听音乐插件)
```

---

## 📋 完整 API 列表

### 认证

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| GET | `/api/v1/all/auth/public-key` | 登录公钥 | ❌ |
| POST | `/api/v1/all/auth/login` | 登录 | ❌ |
| POST | `/api/v1/all/auth/logout` | 登出 | ✅ |
| GET | `/api/v1/all/auth/info` | 当前用户信息 | ✅ |

### 系统管理

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| CRUD | `/api/v1/admin/system/user` | 用户管理 | ✅ |
| CRUD | `/api/v1/admin/system/role` | 角色管理 | ✅ |
| CRUD | `/api/v1/admin/system/permission` | 权限管理 | ✅ |
| CRUD | `/api/v1/admin/system/menu` | 菜单管理 | ✅ |
| CRUD | `/api/v1/admin/system/dict` | 数据字典 | ✅ |
| CRUD | `/api/v1/admin/system/config` | 系统配置 | ✅ |
| CRUD | `/api/v1/admin/system/token` | Token 管理 | ✅ |
| CRUD | `/api/v1/admin/system/table-schema` | 表结构管理 | ✅ |
| CRUD | `/api/v1/admin/system/mail/*` | 邮件服务（账户/模板/日志/插件授权） | ✅ |

### 日志

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| GET | `/api/v1/admin/log/operate_log` | 操作日志 | ✅ |
| GET | `/api/v1/admin/log/login_log` | 登录日志 | ✅ |

### 监控与统计

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| GET | `/api/v1/admin/monitor/system` | 系统监控 | ✅ |
| GET | `/api/v1/admin/monitor/jvm` | JVM 监控 | ✅ |
| GET | `/api/v1/admin/monitor/business` | 业务监控 | ✅ |

### 序列

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| POST | `/api/v1/all/sequence/next` | 获取序列号 | ✅ |
| POST | `/api/v1/sequence/batch` | 批量获取 | ✅ |
| GET | `/api/v1/sequence/types` | 支持的类型 | ✅ |
| CRUD | `/api/v1/sequence/configs` | 序列配置 | ✅ |

### 插件与集群

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| GET | `/api/v1/admin/plugin` | 插件列表 | ✅ |
| GET | `/api/v1/admin/plugin/nav-extensions` | 前端导航扩展 | ✅ |
| GET | `/api/v1/admin/cluster/**` | 集群节点/状态（仅管理员） | ✅ |
| * | `/api/v1/user/**`、`/api/v1/app/**` | 轻听音乐 App 端（qt 插件 Bearer 拦截器接管） | Bearer Token |

---

## 🔧 开发指南

### 添加操作日志

```java
@OperateLog("删除用户")
@DeleteMapping("/{id}")
public Result deleteUser(@PathVariable Long id) {
    userService.delete(id);
    return Result.success();
}
```

### 认证拦截器白名单

Sa-Token 认证拦截器作用于 `/api/**`，排除项（见 `astral-server` 的 `WebMvcConfig.java`）：

- `/api/v1/all/auth/public-key`、`/api/v1/all/auth/login`、`/api/v1/all/auth/logout` — 登录相关
- `/api/v1/admin/system/table-schema/**` — 表结构查看
- `/api/v1/user/**`、`/api/v1/app/**` — 轻听音乐 App 端（qt 插件自行校验 APP 用户）
- `/swagger-ui/**`、`/v3/api-docs/**`、`/doc.html` — API 文档

如需添加新的白名单路径，编辑 `astral-server/src/main/java/com/astral/server/config/WebMvcConfig.java`。

---

## 📊 技术栈

| 功能 | 技术 |
|------|------|
| Java 版本 | 21 |
| 框架 | Spring Boot 3.2.3 |
| ORM | MyBatis-Plus 3.5.5 |
| 认证 | Sa-Token |
| 密码加密 | BCrypt (Hutool) |
| 本地缓存 | Caffeine（`spring.cache.type: caffeine`） |
| 指标收集 | Micrometer + Prometheus |
| 工具库 | Hutool 5.8.25 |

---

## 🔐 安全建议

1. **生产环境修改默认密码**（admin/admin）
2. **配置 HTTPS**
3. **使用环境变量存储敏感信息**
4. **定期清理过期 Token**
5. **配置 IP 白名单限制管理接口**

---

**Made with ❤️ by Astral Team**


