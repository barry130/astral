# Astral 组件指南

本文档介绍 Astral 后台管理系统的各模块功能与使用方法。

---

## 📦 模块概览

| 模块 | 说明 |
|------|------|
| astral-server | Web/API 层（控制器、入口） |
| astral-common | 公共工具（统一返回、异常、常量） |
| astral-dao | 数据访问（MyBatis-Plus 实体 + Mapper） |
| astral-schema | 表结构元数据（实体同步、代码生成引擎） |
| astral-auth | 认证授权（Sa-Token、RSA 登录加密、登录限流） |
| astral-log | 日志服务（操作日志、登录日志） |
| astral-monitor | 监控服务（系统/JVM/业务指标） |
| astral-system | 系统管理（用户/角色/权限/菜单/字典/配置/Token/表结构/邮件） |
| astral-sequence | 序列生成（5 种算法，系统必需插件） |
| astral-plugin-api | 插件 SPI（稳定接口） |
| astral-plugin | 插件注册中心及内置 qt、feedback 业务插件 |

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
- 初始密码与用户名相同（BCrypt 存储，种子 SQL 写入；生产环境登录后请立即修改）

---

## 3. 监控服务（astral-monitor）

### 功能

- 系统监控（CPU、内存、磁盘、网络）
- JVM 监控（堆内存、GC、线程数）
- 业务指标监控（序列配置数、当前并发请求数）
- 站点统计（PV/访客/活跃设备/错误次数、接口调用成功率）
- 外部依赖监控（数据库连接池水位与容量、Redis 内存与命中率）
- 集成 Micrometer + Prometheus

### API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/monitor/dashboard` | 仪表盘总览（首页聚合，一次返回全部） |
| GET | `/api/v1/admin/monitor/system` | 系统资源状态 |
| GET | `/api/v1/admin/monitor/jvm` | JVM 状态 |
| GET | `/api/v1/admin/monitor/business` | 业务指标 |

### 站点统计接口

三个统计维度（设备 / 接口 / 错误）共用「日期 + 平台 + 版本」筛选：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/stat/overview` | 设备统计概览（今日 vs 昨日） |
| GET | `/api/v1/admin/stat/trend` | 指标 24 小时趋势 |
| GET | `/api/v1/admin/stat/api/top` | 接口调用 Top 榜 + 当天全量汇总 |
| GET | `/api/v1/admin/stat/api/trend` | 单接口 24 小时趋势 |
| GET | `/api/v1/admin/stat/error/summary` | 错误分组汇总（按 fingerprint） |
| GET | `/api/v1/admin/stat/error/page` | 错误明细分页 |
| GET | `/api/v1/admin/stat/versions` | 某平台下出现过的版本列表（版本下拉数据源） |

筛选参数约定：

- `ut`：平台（`app-android` / `app-ios` / `app-windows` / `app-linux` / `app-macos` / `app-harmony` / `web`）；**不传、空、`all` 均表示不加平台过滤**
- `version`：客户端版本；同样遵循「不传 / 空 / `all` ⇒ 不限制」
- 版本下拉只在选定具体平台后才有数据：全部平台时 `/versions` 返回空数组，前端把版本置空并禁用。

### 统一客户端系统头（凡请求 astral 后端都必须携带）

四个端（qt-uniappx / qt-pc / astral-front / 将来的小程序）请求 astral 后端时，
一律携带下面这 4 个请求头。**反馈与统计共用同一套**，服务端两条独立链路的取值
都收口在 `com.astral.common.util.ClientHeaders`：

| 请求头 | 含义 | 取值 | 上限 | 接口统计落库 | 反馈落库 |
|--------|------|------|------|--------------|----------|
| `X-App-Ut` | 客户端平台 | `app-android` / `app-ios` / `app-windows` / `app-linux` / `app-macos` / `app-harmony` / `web` | 16 | `stat_api_hourly.ut` | `sys_feedback.platform` |
| `X-App-Version` | 客户端版本 | 语义化版本，如 `1.1.0` / `3.0.1` | 32 | `stat_api_hourly.app_version` | `sys_feedback.app_version` |
| `X-Device` | 设备型号 / 主机名 | `Pixel 6` / `iPhone 15 Pro` / `DESKTOP-ABC` / `Chrome 131` | 128 | — | `sys_feedback.device` |
| `X-OS` | 操作系统及版本 | `Android 14` / `iOS 18.2` / `Windows 11 Pro 23H2 (22631)` / `Ubuntu 24.04.1 LTS` / `macOS 15.1` | 64 | — | `sys_feedback.os` |

各端的实际取值：

| 端 | `X-App-Ut` | `X-App-Version` | `X-Device` | `X-OS` |
|----|-----------|-----------------|-----------|--------|
| qt-uniappx（Android / iOS / HarmonyOS） | `app-android` / `app-ios` / `app-harmony` | `manifest.json` 的 `versionName` | `getDeviceInfo().deviceModel` | `osName + " " + osVersion` |
| qt-pc（Windows / Linux / macOS） | `app-windows` / `app-linux` / `app-macos`（按 `target_os` 三选一） | `CARGO_PKG_VERSION`（= `app.config.json` 的 `version.name`） | `%COMPUTERNAME%` / `hostname` | Windows：注册表 `CurrentVersion` 的 ProductName + DisplayVersion + BuildNumber；Linux：`/etc/os-release` 的 PRETTY_NAME（如 `Ubuntu 24.04.1 LTS`）；macOS：`sw_vers` 的 `-productName` + `-productVersion`（如 `macOS 15.1`）；都取不到时退化为 `linux` / `macos` |
| astral-front（管理台） | `web` | `package.json` 的 `version`（构建期注入） | 浏览器及大版本 | UA 推断的操作系统 |

实现位置（改契约时必须四处同步）：

- 后端（权威定义）：`astral-common` 的 `com.astral.common.util.ClientHeaders`
- qt-uniappx：`services/client-info.ts`（`http.ts` / `source-update.uts` / `qt-stat` 上报均复用）
- qt-pc：`src-tauri/src/astral.rs` 的 `client_headers()`，在 `AstralClient::request()` 收口处注入
- astral-front：`src/lib/client-info.ts`，在 axios 请求拦截器统一注入

约定与注意事项：

- **平台值走 `stat_platform` 字典**（`app-android` / `app-ios` / `app-windows` / `app-linux` / `app-macos` / `app-harmony` / `web`），
  反馈页与统计页的下拉/展示共用这一份字典，不要另造取值。
- **`ut` 做白名单校验**：这四个头都是客户端可伪造的，而 `ut` / `app_version` 直接参与
  `stat_api_hourly` 的分组与唯一键。未知 `ut` 一律归空串（等价于「未携带」），
  防止伪造值把分组数撑爆。新增平台时在 `ClientHeaders.UT_VALUES` 与字典各加一行。
- **长度超限截断**（不导致落库失败）；缺失 / 空白一律归空串，`「全部平台 / 全部版本」`口径不受影响。
- 头名可配：`astral.stat.client-ut-header` / `client-version-header` / `client-legacy-platform-header`。
- **遗留 `X-Platform`**（旧版 App 反馈实现，值 `android` / `ios`）服务端仍会读取并映射为
  统一值做过渡兼容，客户端已不再发送。
- **只给 astral 请求加**：音源直链、GitHub 加速探测、对象存储预签名上传（PUT / multipart）
  **绝对不能**带——既会把本机信息泄露给第三方，自定义头还会直接破坏签名/表单校验。
- 接口统计是**服务端测量**，与设备统计/错误统计的来源不同：后两者由 App 主动上报，
  事件体里天然带 `ut` / `appVersion`，不依赖请求头。

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
├── astral-plugin-api (插件 SPI)
└── astral-plugin (插件注册中心及 qt、feedback 内置插件)
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
| GET | `/api/v1/admin/monitor/dashboard` | 仪表盘总览（首页聚合） | ✅ |
| GET | `/api/v1/admin/monitor/system` | 系统监控 | ✅ |
| GET | `/api/v1/admin/monitor/jvm` | JVM 监控 | ✅ |
| GET | `/api/v1/admin/monitor/business` | 业务监控 | ✅ |
| GET | `/api/v1/admin/stat/overview` | 设备统计概览 | ✅ |
| GET | `/api/v1/admin/stat/trend` | 指标 24 小时趋势 | ✅ |
| GET | `/api/v1/admin/stat/api/top` | 接口调用 Top 榜 | ✅ |
| GET | `/api/v1/admin/stat/api/trend` | 单接口 24 小时趋势 | ✅ |
| GET | `/api/v1/admin/stat/error/summary` | 错误分组汇总 | ✅ |
| GET | `/api/v1/admin/stat/error/page` | 错误明细分页 | ✅ |
| GET | `/api/v1/admin/stat/versions` | 平台版本列表（版本下拉数据源） | ✅ |

### 序列

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| POST | `/api/v1/all/sequence/next` | 获取序列号 | ✅ |
| POST | `/api/v1/sequence/batch` | 批量获取 | ✅ |
| GET | `/api/v1/sequence/types` | 支持的类型 | ✅ |
| CRUD | `/api/v1/sequence/configs` | 序列配置 | ✅ |

### 插件

| 方法 | 路径 | 说明 | 需要认证 |
|------|------|------|----------|
| GET | `/api/v1/admin/plugin` | 插件列表 | ✅ |
| GET | `/api/v1/admin/plugin/nav-extensions` | 前端导航扩展 | ✅ |
| * | `/api/v1/app/**` | 轻听音乐 App 端（qt 插件 Bearer 拦截器接管） | Bearer Token |

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
- `/api/v1/app/**` — 轻听音乐 App 端（qt 插件自行校验 APP 用户）
- `/swagger-ui/**`、`/v3/api-docs/**`、`/doc.html` — API 文档

如需添加新的白名单路径，编辑 `astral-server/src/main/java/com/astral/server/config/WebMvcConfig.java`。

---

## 📊 技术栈

### 后端

| 功能 | 技术 | 版本 |
|------|------|------|
| Java 版本 | JDK（`maven.compiler.release`） | 25 |
| 框架 | Spring Boot（Spring Framework 7.0） | 4.1.0 |
| Web 容器 | Tomcat（随 Spring Boot 托管），已开启虚拟线程 | 11.0.x |
| 认证 | Sa-Token（`sa-token-spring-boot4-starter` + `sa-token-redis-jackson`） | 1.46.0 |
| ORM | MyBatis-Plus（`mybatis-plus-spring-boot4-starter` + `mybatis-plus-jsqlparser`） | 3.5.17 |
| 数据库 | PostgreSQL（运行库，驱动 `org.postgresql:postgresql` 42.7.11）；MySQL 9.7 / H2 可切换 | — |
| 数据迁移 | Flyway（`spring-boot-flyway` + `flyway-database-postgresql`），启动自动应用 `db/migration/` | 12.4.0 |
| 连接池 | HikariCP | 7.0.2 |
| 分布式缓存 | Redis（Spring Data Redis / Lettuce）；Redisson 仅供可选 Redis 序列生成器 | 3.52.0 |
| 本地缓存 | Caffeine（`spring.cache.type: caffeine`） | 3.2.4 |
| API 文档 | SpringDoc OpenAPI（Swagger UI：`/swagger-ui.html`） | 3.1.1 |
| 指标收集 | Micrometer + Prometheus（`micrometer-registry-prometheus`） | — |
| 密码加密 | BCrypt（Hutool `cn.hutool.crypto.digest.BCrypt`） | — |
| 工具库 | Hutool（`hutool-core` + `hutool-crypto`） | 5.8.47 |
| 注解处理 | Lombok（JDK 23+ 起必须在 `maven-compiler-plugin` 显式声明） | 1.18.46 |

> 版本号来源：`pom.xml` 的 `<properties>` 与各模块 `pom.xml`（`postgresql` / `micrometer` 等由 Spring Boot BOM 托管）。
> Spring Boot 4 相关的 starter 名称与配置模块已改名，升级时勿直接套用 Spring Boot 3 的写法，见 `AGENTS.md`
> 「升级到 Spring Boot 4.1 / JDK 25 后必须知道的坑」。

### 前端（astral-front）

| 功能 | 技术 | 版本 |
|------|------|------|
| 框架 | Next.js（App Router，默认 Turbopack 构建，`output: 'standalone'`） | 16.3.8 |
| UI 运行时 | React / React DOM | 19.2 |
| 语言 | TypeScript | 5.6 |
| 样式 | Tailwind CSS（`@tailwindcss/postcss`）+ `tw-animate-css` | 4.3 |
| 组件 | shadcn/ui（Radix UI 原始组件，`@radix-ui/react-*`）；**已移除 Ant Design** | — |
| 图标 | lucide-react | 1.48 |
| 图表 | ECharts + echarts-for-react | 6.0 |
| 请求 | axios（统一收口在 `src/api/client.ts`） | 1.7 |
| 其它 | dayjs / sonner（toast）/ jsencrypt（登录 RSA）/ react-syntax-highlighter | — |
| 运行时 | Node.js（镜像 `node:24-alpine`，三阶段版本必须一致） | 24 LTS |
| 包管理 | npm（`package-lock.json` 已提交，镜像走 `npm ci`） | — |

注：Next 16 起 `next lint` 已移除，`package.json` 因此没有 lint 脚本；类型检查用 `npx tsc --noEmit`。

---

## 🔐 安全建议

1. **生产环境立即修改初始密码**（初始密码与初始用户名相同，见 README「初始账号」）
2. **配置 HTTPS**
3. **使用环境变量存储敏感信息**
4. **定期清理过期 Token**
5. **配置 IP 白名单限制管理接口**

---

**Made with ❤️ by Astral Team**


