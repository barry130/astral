# Docker 部署指南

本文档介绍如何将 Astral 管理后台（后端 + 前端）打包为 Docker 镜像并部署到服务器。

- 后端：Spring Boot 4.1.0 / Java 25 / 端口 `27000`
- 前端：Next.js 16.3（standalone）/ 端口 `3000`
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

- JDK 25、Maven 3.9+、Node.js 24 LTS（与前端镜像 `node:24-alpine` 保持一致）

## 部署相关文件

| 路径 | 说明 |
|------|------|
| `deploy/docker-compose.yml` | 服务编排（backend + frontend） |
| `astral-server/Dockerfile` | 后端多阶段构建（构建阶段 `maven:3.9-eclipse-temurin-25`；BuildKit 缓存 Maven 仓库） |
| `astral-front/Dockerfile` | 前端多阶段构建（Next.js 16 standalone + `node:24-alpine` × 3 阶段；BuildKit 缓存 npm） |
| `.mvn/settings.xml` | Docker 构建用 Maven 镜像（阿里云 public） |
| `.dockerignore` | 后端构建上下文排除项 |
| `astral-front/.dockerignore` | 前端构建上下文排除项 |
| `.cnb.yml` | （可选）CI：CNB 云原生构建，push 到 `master` 按改动路径自动构建并推送镜像到 CNB Docker 制品库（`docker.cnb.cool`） |
| `.cnb/web_trigger.yml` | （可选）CI：分支详情页「构建并推送镜像」手动按钮（全量重建） |
| `deploy/docker-compose.registry.yml` | （可选）overlay：服务镜像改为从私有仓库 pull，服务器不本地构建 |
| `deploy/update.sh` | （可选）服务器一键更新：pull + 重建 + 清理旧镜像 |

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
| `STORAGE_UPLOAD_TICKET_KEY` | `openssl rand -base64 48` 生成 | 文件存储：上传凭证 HMAC 密钥，需与 Worker Secret 一致 |
| `STORAGE_ORIGIN_SHARED_SECRET` | 同上（另生成） | 文件存储：Worker 回调鉴权密钥，需与 Worker Secret 一致 |
| `STORAGE_DOWNLOAD_SIGNING_KEY_V1` | 同上（另生成） | 文件存储：下载地址签名密钥，需与 Worker Secret 一致 |
| `NEXT_PUBLIC_API_URL` | 留空（推荐）或 `https://<你的API域名>` | 浏览器访问 API 的基地址（构建时内联）。**改用 CI 构建镜像后本项在 `.env` 中不再生效**，需写进 `.cnb.yml` 前端流水线的构建参数（`--build-arg`），见下文「CI 构建 + 服务器 pull 更新」 |
| `REGISTRY` | `docker.cnb.cool/canace/astral` | （可选）镜像仓库前缀，只到命名空间；配合 `docker-compose.registry.yml` 使用 |
| `TAG` | `latest` 或 `20260926-a1b2c3d4` | （可选）镜像 tag，兼作回滚开关 |

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

**不要加 `--no-cache`。** Dockerfile 用了 BuildKit cache mount 缓存 Maven/npm 依赖；`--no-cache` 会迫使每次重下全部 jar，把构建重新拖到十几分钟。

首次构建需下载 Maven 依赖 + npm 依赖，后端大约 2~5 分钟（走阿里云镜像）；同一台机器上的二次构建（只改源码、pom 没变）通常 1 分钟内。若仍被 OOM 杀掉，先加 2G swap 再重试。

构建要求 Docker 20.10+ 且 BuildKit 开启（Docker 23+ 默认开启；若 `RUN --mount=type=cache` 报错，执行 `export DOCKER_BUILDKIT=1` 后再 build）。

> Dockerfile 里的 `/root/.m2/repository` 是**构建容器内部**路径，不是宿主机目录。服务器上没有 `/root/.m2` 完全正常，也不需要手工创建。缓存由 BuildKit 自动落在 Docker 数据目录（Linux 一般是 `/var/lib/docker/buildkit`）。确认缓存在工作：`docker buildx du`，二次构建日志里应大量 `Downloading skipped` / 几乎不再打 `Downloading from aliyun`。

查看进度与状态：

```bash
docker compose ps            # 容器状态
docker compose logs -f backend   # 后端日志
```

成功标志：后端日志出现 `Started AstralApplication`，且 `docker compose ps` 中 backend 为 `healthy`。

### 第三步：初始化/迁移数据库（Flyway 自动）

数据库迁移由 **Flyway** 接管：backend 容器启动时自动按版本号顺序应用
`astral-server/src/main/resources/db/migration/V*.sql`，版本历史记录在 `flyway_schema_history` 表。

- **全新服务器**：唯一手工步骤是建库（`CREATE DATABASE astral;`），schema、表结构、种子数据、数据字典全部由 Flyway 自动完成
- **存量库**（已手工应用过旧版 V1–V4 脚本）：自动基线化（`baseline-on-migrate`），跳过初始化脚本只应用增量——**无需任何人工登记**
- 后续每次发布新增 `V{yyyyMMddNNN}__xxx.sql`（如 `V20260915001__add_xxx.sql`），部署时**零手工数据库操作**

查看迁移执行情况：`docker compose logs backend | grep -i flyway`，或在库里查 `SELECT * FROM astral.flyway_schema_history;`。

> 机制细节（基线行为、命名规范、checksum 防漂移、应急关闭 `FLYWAY_ENABLED=false`）见
> [db/migration/README.md](../astral-server/src/main/resources/db/migration/README.md)。
> 迁移失败会导致 backend 拒绝启动——这是刻意的防带伤运行设计，排查后重新 up 即可。

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

# 更新发布（方式一：服务器本地构建；不要加 --no-cache，否则 Maven 依赖缓存作废）
git pull
docker compose up -d --build

# 更新发布（方式二：镜像已由 CI 构建好，服务器只拉取、不编译，见下节）
./update.sh
```

## CI 构建 + 服务器 pull 更新（可选，推荐）

适用场景：镜像由 **CNB 云原生构建**（[cnb.cool](https://cnb.cool)，腾讯出品的云原生构建平台）自动构建并推送到 **CNB 自带 Docker 制品库**（`docker.cnb.cool`，随代码仓库 `canace/astral` 托管），服务器只负责 `pull` 和起容器。

构建与仓库方案选型（迭代记录）：

- ~~GitHub Actions~~：构建机在海外，推送镜像到腾讯云 TCR 走跨境链路（约 100KB/s，300MB 要 50 分钟）——已弃用，workflow 已删除
- ~~ghcr.io~~：国内服务器拉取慢且易断（实测首次 `pull` 15 分钟未完成）
- ~~TCR 企业版云构建 / CODING CI / 自建 runner~~：收费、改造量大或依赖服务器常开，均不合适
- ~~CNB 构建 + TCR 个人版~~：能用，但 TCR 凭证要经密钥仓库 imports 注入，多一套凭证管道；个人版共享实例也没有额外收益——已改为直推 CNB 制品库
- **CNB 构建 + CNB 制品库**：CI 内置触发者凭证，`services: [docker]` 后直接 push，**零凭证配置**；对象存储免费 100GiB（重复基础镜像去重计容量），个人项目用不满

> **代码仓是私有仓，镜像随之私有**：服务器需要**先 `docker login` 一次**（凭证持久化在 `/root/.docker/config.json`，之后 `update.sh` 自动复用），或者把仓库改成公开（镜像即可匿名拉取）。镜像名形如 `docker.cnb.cool/canace/astral/astral-backend:<tag>`。
>
> 仍想换回 TCR / ghcr.io 也可以：改 `.cnb.yml` 里的 `IMAGE_PREFIX` 与登录方式（TCR 需恢复密钥仓库 imports），`deploy/.env` 的 `REGISTRY` 同步改。

相比「本机 `docker save` 全量 `tar.gz` → `scp` → `docker load`」，这种方式**每次只传输发生变化的镜像层**（改后端≈jar 层，改前端≈`.next` 层），基础镜像层不再重复搬运；服务器也不再需要完整源码，只保留 `deploy/` 目录即可。

### 一次性配置

1. 提交 `.cnb.yml`、`.cnb/web_trigger.yml`、`deploy/docker-compose.registry.yml`、`deploy/update.sh`
2. **注册 CNB 并创建仓库**：在 [cnb.cool](https://cnb.cool) 注册后创建空仓库 `astral`（不要初始化 README），把本仓库推上去（推送方式见第 3 步）
3. **配置双推 remote**（本机一次性；之后 `git push` 同时推 GitHub 与 CNB，两边流水线各跑各的）：

   ```bash
   git remote set-url --add --push origin https://github.com/barry130/astral.git
   git remote set-url --add --push origin https://cnb.cool/canace/astral.git
   ```

   GitHub 那条照旧需要代理；CNB 走国内直连，不开代理也能推。代理没开时 GitHub 那条会失败，但 CNB 可能已经推上去了，以 git 输出为准。
4. **开启自动触发**：CNB 仓库「设置」里勾选**允许自动触发（云原生构建）**，否则 push 不会触发构建
5. **首次推送验证**：`git push`。master 在 CNB 属于新分支，必定触发两条流水线全量构建（后端、前端并行）。成功后在 CNB 仓库「制品」页能看到两个镜像，各有两个 tag：`latest` 与「日期-短SHA」如 `20261001-1a2b3c4d`（后者用于精确发布/回滚）。制品路径与仓库路径一致（`docker.cnb.cool/canace/astral/astral-backend` 等），首次 push 自动创建，无需手动建仓
   - 之后每次 push 按**改动路径**过滤：动 `astral-front/**` 只重建前端，动后端模块只重建后端，互不连坐；手动全量构建用 master 分支详情页的「构建并推送镜像」按钮
6. 服务器登录 CNB 制品库（私有仓的镜像需要鉴权），凭证会持久化，只需一次。
   CNB 访问令牌在「头像 → 设置 → 访问令牌」创建，**需勾选 `registry-package` 读权限**（拉镜像最低要求），用户名固定填 `cnb`：

   ```bash
   echo "<CNB访问令牌>" | docker login docker.cnb.cool -u cnb --password-stdin
   ```

7. 服务器 `deploy/.env` 补两行：`REGISTRY=docker.cnb.cool/canace/astral`、`TAG=latest`

### 每次更新

```bash
cd /opt/astral
git pull                    # ① 必须先更新仓库本身：compose 文件 / update.sh 都在里面
cd deploy
chmod +x update.sh          # 首次执行一次
./update.sh                 # ② 拉取镜像 + 重建容器 + 清理 7 天前的旧镜像
./update.sh 20260926-a1b2c3d4   # 指定 CI 产出的日期 tag 精确发布
```

> ⚠️ `./update.sh` 只负责「拉镜像 + 重建容器」，它读取的是服务器上**已有的** compose 文件。
> 所以跳过 `git pull`、只跑 `./update.sh`，会出现「镜像换了、容器配置还是旧的」——
> 典型症状是新增/修改过的 `extra_hosts`、`environment`、`ports`、`volumes` 不生效。
> 若服务器是用 tar.gz 部署而非 git，同样要先把新的 `deploy/` 覆盖上去（**别覆盖 `deploy/.env`**）。
> 只想更新 compose 配置而不换镜像时，在 `deploy/` 下执行
> `docker compose -f docker-compose.yml -f docker-compose.registry.yml up -d --no-build --force-recreate`。
>
> 另外：**宝塔面板里对容器点「升级」根本不读 compose 文件**（它只按原容器参数重建），
> 见下节「别用宝塔面板『容器 → 升级』来更新这套栈」。

等价的原始命令（不想用脚本时）：

```bash
docker compose -f docker-compose.yml -f docker-compose.registry.yml pull
docker compose -f docker-compose.yml -f docker-compose.registry.yml up -d --no-build
```

### ⛔ 别用宝塔面板「容器 → 升级」来更新这套栈

宝塔 Docker 面板「容器」列表里对 `astral-backend` 点**升级**，走的是「pull 新镜像 + 按**原容器的参数**重建一个容器」——**它不读 `deploy/docker-compose.yml`**。

compose 文件里有、但原容器参数里没有的东西，重建后一律丢失：

| 丢失项 | 症状 |
|---|---|
| `extra_hosts`（`host.docker.internal`） | 启动即挂：`java.net.UnknownHostException: host.docker.internal` |
| `env_file: prod.env` | JVM 参数 / `SPRING_PROFILES_ACTIVE=prod` 丢失，按默认配置跑 |
| `healthcheck` + `depends_on: service_healthy` | 前端不等后端就起，首次访问 502 |
| `networks: astral-net` | 前端 `http://backend:27000` 解析不到，rewrites 转发失败 |
| 命名卷 | 换成新卷 → `backend-data` 为空 → RSA 密钥对重新生成 |

而且面板重建出来的容器**不带 `com.docker.compose.project` 标签**，之后 `./update.sh` 会直接报
`Conflict. The container name "/astral-backend" is already in use`。

**这套栈的统一入口是 `deploy/update.sh`（compose）。面板只用来看日志/状态，不要对容器点升级、重建、停止。**

确实想留在面板里操作，只能用「Docker → Compose 项目 / 编排模板」那一栏；并且注意**面板项目里那份 yaml 是你创建项目时粘贴的副本**，在服务器上 `git pull` 不会自动更新它——要手动把 `deploy/docker-compose.yml` 的内容重新粘进去，再点「重新部署」。

先确认当前容器归不归 compose 管：

```bash
docker inspect astral-backend --format '{{ index .Config.Labels "com.docker.compose.project" }}'
# 有输出（如 deploy）→ 归 compose 管，直接 ./update.sh 即可
# 输出为空        → 是面板 / docker run 起的，走下面「回退到 compose」
```

回退到 compose 管理（一次性，**先核对卷名再删容器**）：

```bash
cd /opt/astral/deploy

# 1) 核对卷：/app/data 必须是命名卷 deploy_backend-data，/app/logs 必须是 deploy_backend-logs
docker inspect astral-backend --format '{{ range .Mounts }}{{ .Type }} {{ .Name }} -> {{ .Destination }}{{ "\n" }}{{ end }}'
#    若名字不对（或 Source 是随机路径），说明面板用了匿名卷，删容器前先备份：
#      docker run --rm -v <那个卷名>:/data -v "$PWD":/backup alpine tar czf /backup/backend-data.tgz -C /data .

# 2) 删掉面板起的容器（卷独立于容器，不会被删）；前端若也是面板起的，一并删
docker rm -f astral-backend astral-frontend

# 3) 由 compose 按 docker-compose.yml 重建
./update.sh

# 4) 验收
docker inspect astral-backend --format '{{json .HostConfig.ExtraHosts}}'
#    期望：["host.docker.internal:host-gateway"]
```

### 回滚

`./update.sh <上一个日期 tag>`。注意 backend 启动会自动应用 Flyway 迁移，**镜像回滚不会回滚数据库**，涉及破坏性迁移时需单独评估。

### 关键注意点

- **构建期参数搬到 CI**：改成 pull 之后 `docker-compose.yml` 里的 `build.args` 不再生效，`NEXT_PUBLIC_API_URL` 需写进 `.cnb.yml` 前端流水线的 `docker build`（加 `--build-arg NEXT_PUBLIC_API_URL=...`；留空即走同源 `/api` + Next rewrites，当前默认留空）
- **CPU 架构必须匹配**：CNB 构建节点产出 `linux/amd64`。服务器若是 ARM（aarch64），需在 `.cnb.yml` 的 `docker build` 命令加 `--platform linux/arm64`，否则容器报 `exec format error` 起不来
- **CNB 额度与并发**：免费额度以官方文档为准（构建约 160 核时/月，计费 = 核数 × 耗时；镜像存储走对象存储 100GiB 免费额度）；`.cnb.yml` 流水线已显式降配 4 核 8G（默认 8 核 16G，核时消耗减半），并已用 `lock.cancel-in-progress` 取消同镜像的在途构建（后端/前端互不取消）、用 `ifModify` 按改动路径过滤。构建机与制品库都在国内，推送/拉取不走跨境链路；首次构建要拉基础镜像和依赖会稍慢，Docker 层缓存仅在当前构建节点有效、命中率不稳定，属正常现象
- **磁盘回收**：每次 pull 都会留下旧镜像，`update.sh` 已带 7 天回收；手动回收用 `docker image prune -af --filter "until=168h"`
- **自动更新（仅建议测试环境）**：cron 每 10 分钟执行 `update.sh`；或用 watchtower：

  ```bash
  docker run -d --name watchtower --restart=always \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v /root/.docker/config.json:/config.json:ro -e DOCKER_CONFIG=/ \
    containrrr/watchtower --interval 300 --cleanup astral-backend astral-frontend
  ```

  watchtower 不遵守 compose 的 `depends_on` 健康检查顺序；且自动更新会把 Flyway 迁移静默推上线，生产环境建议固定日期 tag + 人工执行 `update.sh`
- **换镜像仓库**：拉起地址由 `deploy/.env` 的 `REGISTRY` 决定；要整体换回 ghcr.io、腾讯云 TCR 或阿里云 ACR，需同时改 `.cnb.yml` 的 `IMAGE_PREFIX` 与推送登录方式（CNB 制品库免登录，外部仓库需要把凭证放进密钥仓库经 imports 注入，**绝不能明文写进 `.cnb.yml`**）

### 从 tar.gz 镜像包迁移到本方案

现状：服务器上跑的是本机 `docker save` 打包、`docker load` 装载的镜像，再 `docker compose up -d` 起容器。

迁移**不需要改 `docker-compose.yml`**，只是给服务补上 `image:`，让容器改用从镜像仓库拉取的镜像。数据卷、端口、`deploy/.env`、数据库里的 Flyway 历史全部沿用。

> **最关键的一条**：必须在**原来的 compose 工作目录、原来的项目名下**操作。
> `deploy/docker-compose.yml` 的卷是 `backend-data` / `backend-logs`，compose 会给它们加项目名前缀（默认 = 首个 compose 文件所在目录名，通常是 `deploy`）。换个目录跑会新建一套空卷 → RSA 密钥对重新生成、日志丢失。

#### 迁移前核对（服务器上执行）

```bash
cd /opt/astral/deploy

# 1) 当前容器的 compose 项目名与工作目录 —— 迁移必须在这个目录下做
docker inspect astral-backend --format '{{ index .Config.Labels "com.docker.compose.project" }} | {{ index .Config.Labels "com.docker.compose.project.working_dir" }}'

# 2) 容器实际挂的卷名 —— 迁移后必须还是这几个
docker inspect astral-backend --format '{{ range .Mounts }}{{ .Type }} {{ .Name }} -> {{ .Destination }}{{ "\n" }}{{ end }}'

# 3) 当前镜像名与 ID —— 回滚锚点，先别删
docker images | grep -Ei 'astral|deploy'
docker inspect astral-backend astral-frontend --format '{{ .Name }} <- {{ .Config.Image }} ({{ .Image }})'

# 4) 前端构建期内联地址：NEXT_PUBLIC_API_URL 若在 .env 里非空，
#    必须先把它写进 .cnb.yml 前端构建参数并重跑 CI，否则新镜像会退回同源 /api 行为
grep -n 'NEXT_PUBLIC_API_URL' .env
```

若第 1 步查不到 `com.docker.compose.project` 标签，说明当前容器是 `docker run` 直接起的（不归 compose 管）。此时先 `docker rm -f astral-backend astral-frontend`（**卷独立于容器，不会丢**），再执行下面的 `./update.sh`，由 compose 重新创建。

#### 迁移步骤

```bash
cd /opt/astral/deploy            # 第 1 步查到的工作目录

git pull                         # 服务器有仓库时；否则 scp 三个文件过来：
#   deploy/docker-compose.registry.yml、deploy/update.sh、deploy/.env.example

# 给 .env 补两行（不要覆盖原有内容）
printf 'REGISTRY=docker.cnb.cool/canace/astral\nTAG=latest\n' >> .env
chmod +x update.sh

# 登录一次 CNB 制品库（私有仓镜像需要鉴权；凭证持久化在 /root/.docker/config.json，之后 update.sh 自动复用）
# 访问令牌在 cnb.cool「设置 → 访问令牌」创建，需勾选 registry-package 读权限
echo '<CNB访问令牌>' | docker login docker.cnb.cool -u cnb --password-stdin

# 首次迁移先手动两条命令，把「清理旧镜像」留到验证之后
docker compose -f docker-compose.yml -f docker-compose.registry.yml pull
docker compose -f docker-compose.yml -f docker-compose.registry.yml up -d --no-build
```

首次建议把 `TAG` 钉死成具体日期 tag（如 `20260927-358e7ba1`）而不是 `latest`，排查最省事。compose 只会 **recreate 容器**，不会动卷——日志里应出现 `Recreated`，而不应出现 `Creating volume`。

#### 验证

```bash
docker compose -f docker-compose.yml -f docker-compose.registry.yml ps
docker compose -f docker-compose.yml -f docker-compose.registry.yml logs --tail=200 backend | grep -Ei 'Started AstralApplication|flyway|ERROR'
curl -fsS http://localhost:27000/actuator/health
docker inspect astral-backend --format '{{ .Config.Image }}'   # 应变为 docker.cnb.cool/canace/astral/astral-backend:...
```

确认无误后再回收旧镜像与归档：

```bash
docker image prune -af --filter "until=24h"      # 回收 tar 装载的旧镜像
rm -f /opt/astral-docker-*.tar.gz                # 归档建议另存一份做离线备用
```

#### 回滚

- **本次切换前**没有 registry 上的历史镜像可回滚：摘掉 overlay 再 `up -d`（`docker compose up -d`，compose 会用回本地那对旧镜像，前提是还没被 prune 掉），或重新 `docker load` 旧 tar 包按原方式启动
- **切换之后**的每次发布：`./update.sh <上一个日期 tag>` 即可回滚镜像

> 任何时候都**不要** `docker compose down -v`：`-v` 会删掉 `backend-data` 卷（RSA 密钥对、文件数据随之丢失），`down` 不带 `-v` 是安全的。

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

**后端启动报 `java.net.UnknownHostException: host.docker.internal`？**

`host.docker.internal` 是 **Docker Desktop（macOS / Windows）自带**的主机名，Linux 上**必须靠 compose 的 `extra_hosts` 映射**才会出现在容器的 `/etc/hosts` 里。看到这个异常，说明那条映射没生效。按顺序查：

```bash
# 1) 容器是否真的拿到了这条映射（最常见原因：compose 文件是旧的，或容器压根不是 compose 起的）
docker inspect astral-backend --format '{{json .HostConfig.ExtraHosts}}'
#    期望输出：["host.docker.internal:host-gateway"]
#    输出 null 或 [] → 容器不是由当前 docker-compose.yml 创建的：
#      a) 若「宝塔面板 → 容器 → 升级」重建过：面板不读 compose 文件，走
#         本页「别用宝塔面板『容器 → 升级』来更新这套栈」一节回退到 compose 管理；
#      b) 否则是 compose 文件旧了：
#         cd /opt/astral && git pull          # 先更新 compose 文件
#         cd deploy && ./update.sh            # 再重建容器
#         （只跑 update.sh 不 git pull，用的就是服务器上的旧 compose 文件）

# 2) 容器内能否解析
docker exec astral-backend getent hosts host.docker.internal

# 3) host-gateway 这个特殊值需要 Docker Engine 20.10+
docker version --format '{{.Server.Version}}'
```

如果第 1 步有映射、第 2 步也能解析，但连接报的是 **`Connection refused`**（而不是 `UnknownHost`），那是另一回事：PostgreSQL 只监听了 `127.0.0.1`，容器经网关 IP 进不来。改 `postgresql.conf` 的 `listen_addresses = '*'` 并放行 `5432`，或把 `deploy/.env` 里的地址换成 PG 可达的内网 / 公网 IP。

**不想依赖 `host.docker.internal`**：直接在 `deploy/.env` 把 `SPRING_DATASOURCE_URL` 与 `REDIS_HOST` 写成数据库的内网或公网地址即可（此时 `extra_hosts` 多余但无害）。注意容器内访问宿主机不能用 `127.0.0.1`——那是容器自己。

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

**后端镜像构建卡在 Maven 很久（十几分钟 / 半小时）？**

- 确认已拉取包含 `astral-server/Dockerfile` 里 `RUN --mount=type=cache` 的版本；旧 Dockerfile 的 `mvn dependency:go-offline` 会把尚未编译的内部模块拿到 Maven Central 解析，默认超时 30 分钟。
- **不要** `docker compose build --no-cache backend`，这会丢掉 BuildKit 的 Maven 仓库缓存。
- 确认 BuildKit 已开启：`docker buildx version` 能跑即可。若报 `RUN --mount` 未知，先 `export DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1`。
- 构建日志里应出现从 `maven.aliyun.com` 拉 jar；若仍走 `repo.maven.apache.org`，检查 `.mvn/settings.xml` 是否在构建上下文里（不要被 `.dockerignore` 排除）。
- 服务器上没有 `/root/.m2/repository` 是正常的：那是容器内路径。看缓存用 `docker buildx du`，不要去宿主机 `/root` 下找。

## 相关文档

- [组件指南](COMPONENTS_GUIDE.md)
- [插件开发指南](PLUGIN_GUIDE.md)

