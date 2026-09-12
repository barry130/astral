# Astral 系统集成指南

本文档介绍如何将 Astral 后台管理系统集成到你的项目中，包含多种集成方案。

---

## 📋 目录

- [集成方案选择](#集成方案选择)
- [方案一：独立部署（推荐）](#方案一独立部署推荐)
- [方案二：Maven 依赖引入](#方案二maven-依赖引入)
- [方案三：源码集成](#方案三源码集成)
- [前端集成](#前端集成)
- [集成检查清单](#集成检查清单)

---

## 集成方案选择

| 方案 | 适用场景 | 复杂度 | 维护成本 |
|------|----------|--------|----------|
| **独立部署** | 作为独立后台管理系统 | ⭐ | 低 |
| **Maven 依赖** | 在现有 Spring Boot 项目中集成模块 | ⭐⭐ | 中 |
| **源码集成** | 需要深度定制 | ⭐⭐⭐ | 高 |

### 如何选择？

- **需要完整的后台管理系统** → 选择方案一（独立部署）
- **只需要序列生成功能** → 选择方案二（Maven 依赖）
- **需要修改源码适配特殊需求** → 选择方案三（源码集成）

---

## 方案一：独立部署（推荐）

### 步骤 1：编译打包

```bash
mvn clean package -DskipTests
```

生成的 jar 位于 `astral-server/target/astral-server-1.0.0.jar`

### 步骤 2：启动服务

```bash
java -jar astral-server/target/astral-server-1.0.0.jar
```

### 步骤 3：访问系统

- **前端**：http://localhost:3000（需单独启动前端）
- **API 文档**：http://localhost:27000/swagger-ui.html

### 步骤 4：通过 HTTP 调用 API

> 登录接口的 `password` 字段为 **RSA 加密后的 Base64 串**（公钥通过 `GET /api/v1/all/auth/public-key` 获取），
> 直接传明文会校验失败。前端（astral-front 的 `src/lib/crypto.ts`）已自动处理加密。

```bash
# 登录（password 需 RSA 加密）
curl -X POST http://localhost:27000/api/v1/all/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<RSA-encrypted-base64>"}'

# 获取用户列表
curl http://localhost:27000/api/v1/admin/system/user/list \
  -H "satoken: <your-token>"

# 获取序列号
curl -X POST http://localhost:27000/api/v1/all/sequence/next \
  -H "Content-Type: application/json" \
  -H "satoken: <your-token>" \
  -d '{"bizKey":"order_id"}'
```

---

## 方案二：Maven 依赖引入

如果你只需要在现有项目中集成部分功能（如序列生成）：

### 步骤 1：发布到本地仓库

```bash
mvn clean install -DskipTests
```

### 步骤 2：添加依赖

```xml
<dependency>
    <groupId>com.astral</groupId>
    <artifactId>astral-sequence</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 步骤 3：配置组件扫描

```java
@ComponentScan(basePackages = {
    "com.yourpackage",
    "com.astral.sequence"
})
```

### 步骤 4：使用序列生成

```java
@Autowired
private GeneratorFactory generatorFactory;

public String generateOrderNo() {
    long sequence = generatorFactory.getGenerator("order_id").next("order_id");
    return "ORD" + sequence;
}
```

---

## 方案三：源码集成

### 步骤 1：复制源码

将需要的模块复制到你的项目中：

```
你的项目/src/main/java/com/astral/
├── sequence/     # 序列生成
├── log/          # 日志模块
├── auth/         # 认证模块
└── monitor/      # 监控模块
```

### 步骤 2：修改包名（可选）

```java
// 原包名
package com.astral.sequence.generator;

// 修改为你的包名
package com.yourcompany.sequence.generator;
```

### 步骤 3：添加依赖

参考 `pom.xml` 中的依赖配置。

---

## 前端集成

### 方式一：独立运行（推荐）

```bash
cd astral-front
npm install
npm run dev
```

前端通过 Next.js 的 rewrite 功能将 `/api/*` 代理到后端 `http://localhost:27000`。

### 方式二：嵌入现有前端

复制 `astral-front/src/app/dashboard/` 下的页面到你的项目中，并调整 API 调用路径。

### Token 管理

前端登录成功后，将 Token 存储在 localStorage，通过 Axios 拦截器自动携带：

```javascript
axios.interceptors.request.use(config => {
  const token = localStorage.getItem('satoken');
  if (token) {
    config.headers['satoken'] = token;
  }
  return config;
});
```

---

## 集成检查清单

### 基础检查

- [ ] 后端编译无错误：`mvn clean compile`
- [ ] 后端服务正常启动
- [ ] 前端服务正常启动
- [ ] API 文档可访问

### 数据库检查

- [ ] 数据库已创建（PostgreSQL，连接配置见 `application.yml`）
- [ ] 启动时自动执行 `postgresql-init.sql` / `dict-init.sql` 初始化（`spring.sql.init.mode: always`）
- [ ] 连接配置正确

### 功能检查

- [ ] 登录功能正常
- [ ] 用户管理可用
- [ ] 序列生成可用
- [ ] 操作日志正常记录

### 安全检查

- [ ] 修改默认管理员密码（admin/admin）
- [ ] 配置 HTTPS
- [ ] 敏感信息使用环境变量

---

## 📞 获取帮助

1. 查看 [README.md](README.md) 了解完整功能
2. 访问 [API 文档](http://localhost:27000/swagger-ui.html)
3. 查看各模块详细指南

---

**Made with ❤️ by Astral Team**


