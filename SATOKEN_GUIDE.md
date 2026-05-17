# Sa-Token 集成指南

本文档说明如何在 Astral 项目中使用 Sa-Token 进行认证和权限管理。

---

## 📦 什么是 Sa-Token

Sa-Token 是一个轻量级的 Java 权限认证框架，主要解决：
- ✅ 登录认证
- ✅ 权限认证
- ✅ 单点登录
- ✅ OAuth2.0 认证
- ✅ 微服务网关鉴权

**官网文档：** https://sa-token.cc/

---

## 🔧 快速开始

### 1. 登录

```java
@PostMapping("/login")
public Result login(@RequestBody LoginRequest request) {
    User user = userService.findByUsername(request.getUsername());
    StpUtil.login(user.getId());
    return Result.success(StpUtil.getTokenValue());
}
```

### 2. 前端使用 Token

登录成功后，前端需要在每次请求时携带 Token：

```javascript
// Axios 拦截器自动携带
axios.interceptors.request.use(config => {
  const token = localStorage.getItem('satoken');
  if (token) {
    config.headers['satoken'] = token;
  }
  return config;
});
```

### 3. 获取当前登录用户信息

```java
Long userId = StpUtil.getLoginIdAsLong();
String username = StpUtil.getLoginIdAsString();
boolean isLogin = StpUtil.isLogin();
```

---

## 🔐 权限控制

### 方式一：拦截器配置（已集成）

```java
// 登录认证
SaRouter.match("/api/v1/**")
    .notMatch("/api/v1/auth/login")
    .check(r -> StpUtil.checkLogin());

// 权限认证
SaRouter.match("/api/v1/system/user/**")
    .check(r -> StpUtil.checkPermission("user:manage"));

// 角色认证（仅管理员）
SaRouter.match("/api/v1/cluster/**")
    .check(r -> StpUtil.checkRole("ADMIN"));
```

### 方式二：注解式权限控制

```java
@SaCheckLogin
@GetMapping("/info")
public Result getUserInfo() { ... }

@SaCheckPermission("user:add")
@PostMapping("/add")
public Result addUser(@RequestBody User user) { ... }

@SaCheckRole("ADMIN")
@DeleteMapping("/{id}")
public Result deleteUser(@PathVariable Long id) { ... }
```

---

## 📋 API 使用示例

```bash
# 1. 登录
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 2. 使用 Token 访问
curl http://localhost:8080/api/v1/system/user/list \
  -H "satoken: <your-token>"

# 3. 获取当前用户信息
curl http://localhost:8080/api/v1/auth/info \
  -H "satoken: <your-token>"

# 4. 登出
curl -X POST http://localhost:8080/api/v1/auth/logout \
  -H "satoken: <your-token>"
```

---

## ⚙️ 配置说明

```yaml
sa-token:
  token-name: satoken
  timeout: 604800          # Token 有效期 7 天
  active-timeout: -1       # 临时有效期（不限制）
  is-concurrent: true      # 允许多端登录
  is-share: false          # 不共享 Token
  token-style: uuid        # Token 风格
  is-log: false            # 不输出操作日志
```

---

## 🔑 核心 API

### 登录相关
```java
StpUtil.login(userId);                    // 登录
StpUtil.logout();                         // 登出
StpUtil.logout(userId);                   // 踢指定用户下线
StpUtil.isLogin();                        // 是否登录
StpUtil.getLoginIdAsLong();              // 获取登录 ID
StpUtil.getTokenValue();                 // 获取 Token 值
```

### 权限验证
```java
StpUtil.checkLogin();                    // 检查是否登录
StpUtil.checkRole("ADMIN");              // 检查角色
StpUtil.checkPermission("user:add");     // 检查权限
```

### Token 管理
```java
StpUtil.getTokenValue();                 // 获取当前 Token
StpUtil.renewToken(3600);                // 续期 Token
StpUtil.disableToken(100);               // 临时冻结 Token
```

---

## 🆚 与 JWT 的对比

| 特性 | JWT | Sa-Token |
|------|-----|----------|
| 存储位置 | 客户端 | Redis（服务端） |
| 安全性 | 中等 | 高 |
| 踢人下线 | 困难 | 简单 |
| 权限变更 | 需重新登录 | 立即生效 |
| Token 续签 | 手动 | 自动 |

---

## ⚠️ 注意事项

1. **Redis 依赖**：Sa-Token 使用 Redis 存储 Session，确保 Redis 已启动
2. **权限实时生效**：修改权限后下次请求立即生效，无需重新登录
3. **前端 Token 传递**：推荐使用请求头 `satoken` 传递

---

**Made with ❤️ by Astral Team**
