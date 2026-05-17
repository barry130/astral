# 多数据库支持指南

Astral 后台管理系统支持多种数据库，并提供灵活的配置方案。

---

## 📋 支持的数据库

| 数据库 | 版本要求 | 推荐场景 | 状态 |
|--------|----------|----------|------|
| **MySQL** | 5.7+ / 8.0+ | 生产环境（推荐） | ✅ |
| **PostgreSQL** | 12+ | 生产环境（推荐） | ✅ |
| **Oracle** | 12c+ | 企业级应用 | ✅ |
| **SQL Server** | 2017+ | 微软技术栈 | ✅ |
| **MariaDB** | 10.3+ | MySQL 替代方案 | ✅ |
| **H2** | 2.x | 开发测试 | ✅ |
| **SQLite** | 3.x | 轻量级/嵌入式 | ✅ |

---

## 快速开始

### 方式一：使用 H2（默认，零配置）

无需任何配置，启动即可使用。数据存储在 `./data/astral`。

### 方式二：使用 MySQL（推荐生产）

1. 创建数据库：
```sql
CREATE DATABASE astral CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 执行初始化脚本：
```bash
mysql -u root -p astral < sql/init.sql
```

3. 修改 `application.yml`：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/astral?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
```

### 方式三：使用 PostgreSQL

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/astral
    username: postgres
    password: your_password
```

初始化：
```bash
psql -U postgres -d astral -f sql/postgresql-init.sql
```

---

## 连接池配置

### HikariCP（默认推荐）

```yaml
spring:
  datasource:
    hikari:
      minimum-idle: 5
      maximum-pool-size: 20
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

| 场景 | minimum-idle | maximum-pool-size |
|------|--------------|-------------------|
| 开发测试 | 2 | 10 |
| 小型应用 | 5 | 20 |
| 中型应用 | 10 | 50 |

---

## 环境配置建议

### 开发环境

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/astral;AUTO_SERVER=TRUE;MODE=MySQL
    username: sa
    password:
  h2:
    console:
      enabled: true
      path: /h2-console
```

### 生产环境

```yaml
spring:
  datasource:
    url: jdbc:mysql://prod-db:3306/astral?useSSL=true&serverTimezone=Asia/Shanghai
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      minimum-idle: 10
      maximum-pool-size: 50
  h2:
    console:
      enabled: false
```

---

## 常见问题

### Q1: 如何切换数据库？

只需修改 `application.yml` 中的 `url`、`username`、`password`，并执行对应数据库的初始化脚本。

### Q2: H2 数据会丢失吗？

取决于配置：
- **文件模式**（`jdbc:h2:file:./data/astral`）：数据持久化，不会丢失
- **内存模式**（`jdbc:h2:mem:astral`）：重启后数据丢失

### Q3: 连接池报 "Connection is not available"？

连接池耗尽，增大最大连接数：
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
```

---

**Made with ❤️ by Astral Team**
