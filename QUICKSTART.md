# 快速开始指南

## 前置条件

- JDK 21+
- Maven 3.6+
- Node.js 18+
- MySQL 8.0+（可选，默认使用 H2）
- Redis 6.0+（可选，仅 Redis 序列模式需要）

## 第一步：编译项目

```bash
cd F:\JavaFile\xulie
mvn clean install -DskipTests
```

## 第二步：启动后端

### 方式一：Maven 直接运行（推荐开发环境）

```bash
mvn -pl astral-server spring-boot:run
```

### 方式二：打包后运行

```bash
mvn clean package -DskipTests
java -jar astral-server/target/astral-server-1.0.0.jar
```

后端默认使用 H2 文件数据库（存储在 `./data/astral`），无需安装 MySQL 即可运行。

## 第三步：启动前端

```bash
cd astral-front
npm install
npm run dev
```

## 第四步：访问系统

| 服务 | 地址 | 说明 |
|------|------|------|
| 前端界面 | http://localhost:3000 | 管理后台 |
| API 文档 | http://localhost:8080/swagger-ui.html | Swagger |
| H2 控制台 | http://localhost:8080/h2-console | 数据库管理 |
| 健康检查 | http://localhost:8080/actuator/health | 服务状态 |

### 默认账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | 管理员 |

## 切换到 MySQL（可选）

如果需要持久化数据，可以切换到 MySQL：

1. 创建数据库：
```sql
CREATE DATABASE astral CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 执行初始化脚本：
```bash
mysql -u root -p astral < sql/init.sql
```

3. 修改 `astral-server/src/main/resources/application.yml`：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/astral?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
```

## 常见问题

### Q1: 启动时端口 8080 被占用？

修改 `application.yml`：
```yaml
server:
  port: 8081
```

### Q2: 前端无法连接后端？

确认前端 `astral-front/next.config.js` 中的代理地址正确指向后端。

### Q3: H2 数据库文件在哪里？

默认位于项目根目录下的 `./data/astral` 目录。

### Q4: 如何重置管理员密码？

在 H2 控制台或 MySQL 中执行：
```sql
UPDATE sys_user SET password = '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH' WHERE username = 'admin';
```

## 生产部署建议

1. **数据库**：使用 MySQL/PostgreSQL，关闭 H2
2. **缓存**：启用 Redis，提升 Token 和序列性能
3. **安全**：修改默认管理员密码，配置 HTTPS
4. **监控**：接入 Prometheus + Grafana
5. **日志**：配置日志轮转，接入 ELK

## 下一步

- 查看 [README.md](README.md) 了解完整功能
- 查看 [组件指南](COMPONENTS_GUIDE.md) 了解各模块用法
- 访问 [API 文档](http://localhost:8080/swagger-ui.html) 查看所有接口
