# Astral 组件指南

本文档介绍 Astral 后台管理系统的各模块功能与使用方法。

---

## 📦 模块概览

| 模块 | 说明 |
|------|------|
| astral-log | 日志服务（操作日志、登录日志） |
| astral-auth | 认证授权（Sa-Token、RBAC） |
| astral-monitor | 监控服务（系统/JVM/业务指标） |
| astral-sequence | 序列生成（5 种算法） |
| astral-schema | 表结构管理（代码生成引擎） |

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
| GET | `/api/v1/log/operate` | 查询操作日志 |
| GET | `/api/v1/log/login` | 查询登录日志 |

---

## 2. 认证授权（astral-auth）

### 功能

- Sa-Token 认证
- 密码 BCrypt 加密
- 用户 CRUD 管理
- 角色权限控制（RBAC 模型）
- Token 管理（查看、吊销、踢人）

### 数据库表

- `sys_user` — 用户表
- `sys_role` — 角色表
- `sys_permission` — 权限表
- `sys_user_role` — 用户角色关联表
- `sys_role_permission` — 角色权限关联表

### 快速使用

1. 登录获取 Token：
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

2. 使用 Token 访问 API：
```bash
curl http://localhost:8080/api/v1/system/user/list \
  -H "satoken: <your-token>"
```

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/login` | 用户登录 |
| POST | `/api/v1/auth/logout` | 用户登出 |
| GET | `/api/v1/auth/info` | 获取当前用户信息 |
| GET/POST/PUT/DELETE | `/api/v1/system/user` | 用户管理 |
| GET/POST/PUT/DELETE | `/api/v1/system/role` | 角色管理 |
| GET/POST/PUT/DELETE | `/api/v1/system/permission` | 权限管理 |
| GET/PUT/DELETE | `/api/v1/system/token` | Token 管理 |

### 默认管理员

- 用户名：`admin`
- 密码：`admin123`

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
| GET | `/api/v1/monitor/system` | 系统资源状态 |
| GET | `/api/v1/monitor/jvm` | JVM 状态 |
| GET | `/api/v1/monitor/business` | 业务指标 |

### Prometheus 集成

```bash
curl http://localhost:8080/actuator/prometheus
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
| POST | `/api/v1/sequence/next` | 获取序列号 |
| POST | `/api/v1/sequence/batch` | 批量获取 |
| GET | `/api/v1/sequence/types` | 支持的类型 |
| GET/POST/PUT/DELETE | `/api/v1/sequence/configs` | 序列配置管理 |
| GET | `/api/v1/sequence/statistics` | 使用统计 |

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
| GET | `/api/v1/system/table-schema` | 获取所有表结构 |
| POST | `/api/v1/system/table-schema` | **新建表结构** |
| GET | `/api/v1/system/table-schema/{tableName}` | 获取单个表结构 |
| PUT | `/api/v1/system/table-schema/{tableName}` | 更新表结构 |
| DELETE | `/api/v1/system/table-schema/{tableName}` | 删除表结构 |
| GET | `/api/v1/system/table-schema/{tableName}/sql` | 生成建表 SQL |
| GET | `/api/v1/system/table-schema/{tableName}/full-code` | 生成完整代码 |

---

## 🏗️ 模块依赖关系

```
astral-server (Web 层)
├── astral-common (公共工具)
├── astral-dao (数据访问)
├── astral-log (日志服务)
├── astral-auth (认证授权)
├── astral-monitor (监控服务)
├── astral-sequence (序列生成)
└── astral-schema (表结构元数据)
```

---

## 📋 完整 API 列表

### 认证

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| POST | `/api/v1/auth/login` | 登录 | ❌ |
| POST | `/api/v1/auth/logout` | 登出 | ✅ |
| GET | `/api/v1/auth/info` | 当前用户信息 | ✅ |

### 系统管理

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| CRUD | `/api/v1/system/user` | 用户管理 | ✅ |
| CRUD | `/api/v1/system/role` | 角色管理 | ✅ |
| CRUD | `/api/v1/system/permission` | 权限管理 | ✅ |
| CRUD | `/api/v1/system/dict` | 数据字典 | ✅ |
| CRUD | `/api/v1/system/config` | 系统配置 | ✅ |
| CRUD | `/api/v1/system/token` | Token 管理 | ✅ |
| CRUD | `/api/v1/system/table-schema` | 表结构管理 | ✅ |

### 日志

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| GET | `/api/v1/log/operate` | 操作日志 | ✅ |
| GET | `/api/v1/log/login` | 登录日志 | ✅ |

### 监控

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| GET | `/api/v1/monitor/system` | 系统监控 | ✅ |
| GET | `/api/v1/monitor/jvm` | JVM 监控 | ✅ |
| GET | `/api/v1/monitor/business` | 业务监控 | ✅ |

### 序列

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| POST | `/api/v1/sequence/next` | 获取序列号 | ✅ |
| POST | `/api/v1/sequence/batch` | 批量获取 | ✅ |
| GET | `/api/v1/sequence/types` | 支持的类型 | ✅ |
| CRUD | `/api/v1/sequence/configs` | 序列配置 | ✅ |

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

### 添加权限控制

Sa-Token 拦截器作用于 `/api/v1/**`，排除项：
- `/api/v1/auth/login` — 登录
- `/api/v1/auth/refresh` — 刷新 Token
- `/swagger-ui/**` — API 文档
- `/actuator/**` — 健康检查

如需添加新的白名单路径，编辑 `astral-auth` 模块的 `WebConfig.java`。

---

## 📊 技术栈

| 功能 | 技术 |
|------|------|
| Java 版本 | 21 |
| 框架 | Spring Boot 3.2.3 |
| ORM | MyBatis-Plus 3.5.5 |
| 认证 | Sa-Token |
| 密码加密 | BCrypt (Hutool) |
| 缓存 | Redisson + Caffeine |
| 系统监控 | OSHI 6.4.7 |
| 指标收集 | Micrometer + Prometheus |
| 工具库 | Hutool 5.8.25 |

---

## 🔐 安全建议

1. **生产环境修改默认密码**
2. **配置 HTTPS**
3. **使用环境变量存储敏感信息**
4. **定期清理过期 Token**
5. **配置 IP 白名单限制管理接口**

---

**Made with ❤️ by Astral Team**
