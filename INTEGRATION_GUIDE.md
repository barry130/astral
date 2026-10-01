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

> 前置要求：**JDK 25 + Maven 3.9+**。根 `pom.xml` 的 `maven.compiler.release=25`，
> 且依赖 Spring Boot 4.1 / Spring Framework 7，JDK 17 / 21 无法编译。

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

> **宿主项目必须与 Astral 的技术栈对齐**：Spring Boot **4.1.x**（Spring Framework 7）+ **JDK 25**。
> 用 `spring-boot-starter-parent:4.1.0` 可保证 `jakarta.*`、Spring 7 API 与托管依赖版本一致；
> 用 JDK 17/21 或 Spring Boot 3.x 的宿主项目无法编译这些模块（`release=25` + 已移除的旧 API）。
> 前端集成同理，见下文「前端集成」——管理台是 Next.js 16 + React 19 + Tailwind v4 + shadcn/ui。

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

> 技术栈：Next.js 16.3（App Router + Turbopack，`output: 'standalone'`）+ React 19.2 + TypeScript 5.6 +
> Tailwind CSS v4 + shadcn/ui（Radix UI）。要求 Node.js 24 LTS（最低 20.9+），包管理用 npm。
> Next 16 已移除 `next lint`，仓库因此没有 lint 脚本，类型检查用 `npx tsc --noEmit`。

### 方式二：嵌入现有前端

复制 `astral-front/src/app/dashboard/` 下的页面到你的项目中，并调整 API 调用路径。

> 注意：页面依赖 Tailwind v4 的主题变量（`src/app/globals.css`）与 shadcn/ui 组件
> （`src/components/ui/`，基于 Radix UI）。宿主项目若不用 Tailwind v4 + shadcn/ui，
> 需要一并迁移这些基础设施，而不是只拷页面；共享的请求/字典/权限逻辑在 `src/api/`、`src/lib/`。

### Token 管理

前端登录成功后，将 Token 存储在 localStorage，通过 Axios 拦截器自动携带：

```javascript
axios.interceptors.request.use(config => {
  // 管理台实际用的是 'token' 这个 key（见 astral-front/src/api/client.ts），
  // 取到后放进请求头 'satoken'（后端 sa-token.token-name 的取值）
  const token = localStorage.getItem('token');
  if (token) {
    config.headers['satoken'] = token;
  }
  return config;
});
```

---

## 集成检查清单

### 基础检查

- [ ] JDK 25 + Maven 3.9+ 已就绪（JDK 17/21 无法编译）
- [ ] Node.js 24 LTS 已就绪（前端）
- [ ] 后端编译无错误：`mvn clean compile`
- [ ] 后端服务正常启动
- [ ] 前端服务正常启动（`npm run dev`）
- [ ] 前端类型检查通过：`npx tsc --noEmit`
- [ ] API 文档可访问

### 数据库检查

- [ ] 数据库已创建（PostgreSQL，连接配置见 `application.yml`）
- [ ] 数据库迁移由 Flyway 启动时自动应用（`db/migration/`，存量库自动基线化，见该目录 README.md）
- [ ] 连接配置正确

### 功能检查

- [ ] 登录功能正常
- [ ] 用户管理可用
- [ ] 序列生成可用
- [ ] 操作日志正常记录

### 安全检查

- [ ] 修改初始管理员密码（初始密码与用户名相同，见 README「初始账号」）
- [ ] 配置 HTTPS
- [ ] 敏感信息使用环境变量

---

## 📞 获取帮助

1. 查看 [README.md](README.md) 了解完整功能
2. 访问 [API 文档](http://localhost:27000/swagger-ui.html)
3. 查看各模块详细指南

---

**Made with ❤️ by Astral Team**


