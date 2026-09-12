# Docker 部署指南

本文档介绍如何将 Astral 管理后台（后端 + 前端）打包为 Docker 镜像并部署到服务器。

- 后端：Spring Boot 3.2.3 / Java 21 / 端口 `27000`
- 前端：Next.js 14（standalone）/ 端口 `3000`
- 数据库：PostgreSQL（复用服务器已有实例）
- 缓存：Redis（复用服务器已有实例）

## 部署架构

```
浏览器页面 ───────────────► frontend 容器 (:3000)
浏览器 API 请求 ──────────► backend 容器 (:27000) ──► PostgreSQL / Redis (服务器已有)
```

两个服务编排在 `deploy/docker-compose.yml`（不使用反向代理，前端 3000、后端 27000 直接对外）：

| 容器 | 镜像 | 宿主端口 | 说明 |
|------|------|----------|------|
| `astral-backend` | 本地构建 | `27000` | Spring Boot 后端 |
| `astral-frontend` | 本地构建 | `3000` | Next.js 前端 |

## 前置条件

**服务器：**

- Docker Engine 20.10+（含 `docker compose` 插件，v2）
- 已有可访问的 PostgreSQL 实例
- 已有可访问的 Redis 实例
- 已放行安全组/防火墙端口：`3000`、`27000`（均必需）

**本机（可选，仅本地调试）：**

- JDK 21、Maven 3.6+、Node.js 18+

## 部署相关文件

| 路径 | 说明 |
|------|------|
| `deploy/docker-compose.yml` | 服务编排（backend + frontend） |
| `astral-server/Dockerfile` | 后端多阶段构建（Java 21，含全部 14 个模块） |
| `astral-front/Dockerfile` | 前端多阶段构建（Next.js standalone） |
| `.dockerignore` | 后端构建上下文排除项 |
| `astral-front/.dockerignore` | 前端构建上下文排除项 |

## 环境变量配置

数据库与 Redis 的连接信息**不写死在版本库**里，统一通过 `deploy/.env` 注入（该文件已被 `.gitignore` 忽略）。首次部署先复制模板并填写真实值：

```bash
cd deploy
cp .env.example .env
vi .env      # 填入数据库、Redis 的真实地址与密码
```

`deploy/.env.example` 中的变量：

| 变量 | 示例 | 说明 |
|------|------|------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<主机>:5432/astral?currentSchema=astral` | PostgreSQL 连接串 |
| `SPRING_DATASOURCE_USERNAME` | `<数据库账号>` | 数据库账号 |
| `SPRING_DATASOURCE_PASSWORD` | `<数据库密码>` | 数据库密码 |
| `REDIS_HOST` | `<Redis主机>` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | `<Redis密码>` | Redis 密码 |
| `REDIS_DB` | `7` | Redis 库索引 |
| `NEXT_PUBLIC_API_URL` | 留空（推荐）或 `https://<你的API域名>` | 浏览器访问 API 的基地址（构建时内联） |

> `SPRING_DATASOURCE_*` 与 `REDIS_PASSWORD` 在 compose 中使用 `:?` 断言：未在 `.env` 填写时 `docker compose` 会**直接报错并提示缺哪个变量**，不会用占位值静默启动。`QT_PLUGIN_ENABLED`、`TZ` 已在 compose 中固定为 `true` / `Asia/Shanghai`。
>
> 若 PostgreSQL/Redis 与容器在**同一台宿主机**上，主机名可用 `host.docker.internal`（compose 已为 backend 配置 `host-gateway` 映射）；否则填其可达的内网或公网地址。

前端通过构建参数 `NEXT_PUBLIC_API_URL` 指定浏览器访问 API 的基地址，**默认留空** → 浏览器使用同源相对路径（如 `/api/v1/all/auth/login`），由 Next.js 服务端 rewrites 转发到 `BACKEND_URL`（默认 `http://backend:27000`）。该变量会在 `npm run build` 时内联，修改后必须重新构建 frontend 镜像（`docker compose build --no-cache frontend`）。

`BACKEND_URL` 是 Next.js 服务端 rewrites 的转发目标，容器内默认 `http://backend:27000`，浏览器永远不直接访问它。

> 默认（`NEXT_PUBLIC_API_URL` 留空）时浏览器只与前端域名同源通信，后端 `27000` 无需对公网开放，建议安全组收紧。仅当显式设置 `NEXT_PUBLIC_API_URL` 让浏览器直连后端时才需要放行，且该地址必须为 `https://`（HTTPS 页面下 `http://` 请求会被浏览器 Mixed Content 拦截）。

## 部署步骤

### 第一步：准备源码

**方式 A：服务器直接拉取仓库（推荐）**

```bash
cd /opt
git clone <你的仓库地址> astral
```

**方式 B：本机打包上传（无仓库时）**

仓库自带打包脚本，产物仅含源码（自动排除 `node_modules` / `.next` / `target` / `data` / `logs`）：

```bash
# Linux / macOS：在仓库根执行
./scripts/pack.sh -o /tmp -n astral
scp /tmp/astral.tar.gz root@<服务器IP>:/opt/

# Windows：在仓库根执行
scripts\pack.bat D:\release
scp D:\release\astral-<时间戳>.tar.gz root@<服务器IP>:/opt/
```

服务器解压：

```bash
ssh root@<服务器IP>
cd /opt && tar -xzf astral.tgz
```

> 上传前务必确认 `deploy/`、各 `Dockerfile`、`.dockerignore`、`src/` 均在；`node_modules`/`target` 已排除（否则构建上下文巨大且编译混乱）。

### 第二步：构建并启动

确认 `deploy/.env` 已按上节填写完毕，然后：

```bash
cd /opt/astral/deploy

# 小内存服务器（如 1 核 2G）建议串行构建，避免 Maven 与 Next 同时编译触发 OOM
docker compose build backend
docker compose build frontend
docker compose up -d
```

首次构建需下载 Maven 依赖 + npm 依赖，后端镜像可能耗时 5~15 分钟；若仍被 OOM 杀掉，先加 2G swap 再重试。

查看进度与状态：

```bash
docker compose ps            # 容器状态
docker compose logs -f backend   # 后端日志
```

成功标志：后端日志出现 `Started AstralApplication`，且 `docker compose ps` 中 backend 为 `healthy`。

### 第三步：初始化/迁移数据库

应用启动时**不再自动执行 SQL**。数据库变更统一由 `sql/migrations/` 下的增量脚本手动应用。

**全新数据库**（首次部署）：按序执行 V1–V3 基线脚本并登记版本。

```bash
cd /opt/astral/astral-server/src/main/resources/sql/migrations
export PGHOST=<数据库主机> PGUSER=<账号> PGPASSWORD=<密码> PGDATABASE=astral
export PGOPTIONS='-c search_path=astral'      # 必须，表建在 astral schema

psql -v ON_ERROR_STOP=1 -c "CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(32) PRIMARY KEY, description VARCHAR(128) NOT NULL, applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);"
for f in V*.sql; do
  ver="${f%%__*}"; echo ">> $f"
  psql -v ON_ERROR_STOP=1 -f "$f" || exit 1
  psql -v ON_ERROR_STOP=1 -c "INSERT INTO schema_migrations(version, description) VALUES ('$ver','$f') ON CONFLICT DO NOTHING;"
done
```

**既有数据库**（已用旧版自动初始化跑起来）：**不要**执行 V1–V3（V2 会清空字典表），只登记基线版本：

```bash
psql -v ON_ERROR_STOP=1 -c "INSERT INTO schema_migrations(version, description) VALUES ('V1','V1__baseline_schema.sql'),('V2','V2__baseline_dict.sql'),('V3','V3__baseline_dict_sequence_reset.sql') ON CONFLICT DO NOTHING;"
```

> 服务器未安装 `psql` 时，可用 `postgres:16-alpine` 容器执行；后续每次结构变更新增 `V4__xxx.sql` 并按上面方式应用一次。详见 [sql/migrations/README.md](../astral-server/src/main/resources/sql/migrations/README.md)。

### 第四步：访问验证

| 入口 | 地址 |
|------|------|
| 管理后台 | `http://<服务器IP>:3000/` |
| 直接访问前端 | `http://<服务器IP>:3000` |
| 直接访问后端 API | `http://<服务器IP>:27000/api/v1/...` |
| Swagger 文档 | `http://<服务器IP>:27000/swagger-ui.html` |

用 `admin` 账号登录管理后台，观察 `docker compose logs -f backend` 确认无异常。

## 日常运维

```bash
cd /opt/astral/deploy

docker compose ps                 # 查看状态
docker compose logs -f backend    # 跟踪后端日志
docker compose logs frontend      # 前端日志
docker compose restart frontend   # 重启单个服务
docker compose down               # 停止（保留数据卷）
docker compose up -d              # 再次启动

# 更新发布（拉新代码后重建）
git pull
docker compose up -d --build
```

## 数据持久化

| 数据卷 | 挂载点 | 内容 |
|--------|--------|------|
| `backend-data` | `/app/data` | RSA 密钥对 `rsa-key.pair`（重启后公钥不变） |
| `backend-logs` | `/app/logs` | 应用日志 / JVM 堆转储 |

使用 Docker 命名卷，自动继承镜像内目录属主（非 root 用户可写）。

## 接入域名与 HTTPS

在服务器部署反向代理（Nginx / 宝塔 / Caddy 等）并签发 SSL 证书后，推荐把整站（含 API）统一到同一个 HTTPS 域名：

- 反向代理只需把 `/` 整体转发到前端 `3000`。`/api/` 无需单独转发：浏览器请求 `https://<域名>/api/...` 到达 Next.js 后，由服务端 rewrites 转发到 backend 容器（`http://backend:27000`）
- `NEXT_PUBLIC_API_URL` 保持**留空**（默认），浏览器使用同源相对路径请求 API，同源请求没有 Mixed Content、也没有 CORS 问题
- **不要**把 `NEXT_PUBLIC_API_URL` 设为 `http://` 开头的地址：HTTPS 页面下浏览器会以 Mixed Content 拦截全部明文请求，前端表现为"无法连接到服务器，请检查后端服务是否启动"（实际是浏览器拦截，后端本身是正常的）
- 若坚持浏览器直连 API（独立 API 域名），该地址必须也是 `https://`，且后端 `CORS_ALLOWED_ORIGINS` 需包含前端域名
- 收紧安全组：`27000` 无需对公网开放；compose 中可将端口映射改为 `127.0.0.1:27000:27000` 仅供本机反向代理转发

修改 `NEXT_PUBLIC_API_URL` 后必须重新构建并重启 frontend：

```bash
docker compose build --no-cache frontend && docker compose up -d frontend
```

若服务器 `deploy/.env` 中设置过 `NEXT_PUBLIC_API_URL`，需同步删除或改为 `https://` 地址。

## 故障排查

**后端无法连接数据库？**

- 确认 compose 中 `SPRING_DATASOURCE_URL` 指向的 PostgreSQL 实例可从服务器访问。
- 检查数据库账号密码与 `currentSchema` 是否正确。
- 查看 `docker compose logs backend` 中的连接异常。

**前端无法调用后端 API？**

- 确认 backend 容器已 `healthy`（frontend 的 `depends_on` 依赖其健康检查）。
- 确认 compose 中 `NEXT_PUBLIC_API_URL` 位于 frontend 的 `build.args` 中，而不是只配置在运行时 `environment` 中；修改后执行 `docker compose build --no-cache frontend`。
- 浏览器控制台出现 `Mixed Content ... blocked`：页面是 HTTPS，而构建时内联的 `NEXT_PUBLIC_API_URL` 是 `http://` 地址。改为留空（走同源 rewrites）或改成 `https://` 地址后重新构建 frontend 镜像。
- 页面报"无法连接到服务器"但浏览器控制台无 Mixed Content/NET::ERR 报错？先打开控制台 Network 面板确认请求最终落点，再对照下方两条排查。
- 仅 `NEXT_PUBLIC_API_URL` 直连模式需要服务器安全组放行 `27000`。
- 出现 CORS 错误时，检查后端 `CORS_ALLOWED_ORIGINS` 配置包含前端域名（默认 `*`）。

**RSA 公钥登录异常？**

- `backend-data` 卷未挂载或为空时会在启动时重新生成密钥对。
- 确认卷已持久化，避免频繁重建导致前端缓存了旧公钥。

**端口冲突？**

- 若服务器 `3000/27000` 被占用，修改 compose 中 `ports` 的宿主侧端口。

## 相关文档

- [组件指南](COMPONENTS_GUIDE.md)
- [插件开发指南](PLUGIN_GUIDE.md)
- [集群模式](CLUSTER_MODE.md)

