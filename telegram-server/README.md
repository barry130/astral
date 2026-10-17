# Telegram 文件网关：独立服务器部署

支持两种独立模式，二选一即可：

| 模式 | 文件链路 | 部署入口 |
| --- | --- | --- |
| 原 CF + Telegram | 客户端 → CF Worker → Telegram | [原 Worker 文档](../cloudflare/storage-worker/README.md) |
| 服务器 + Telegram（本目录） | 客户端 → HTTPS 反代 → 本网关 → Telegram | 本文 |

两种模式都通过 HTTP 调用 Astral：签发上传凭证、登记文件元数据、下载权限和签名、远端删除任务仍由 Astral 负责。**服务器版不依赖 CF，不修改 Astral 原 Docker/Compose，不连接数据库或 Redis。** 使用相同接口和 HMAC 规范串，后台 providerType 仍选 TELEGRAM。

## 独立性与要求

只需把本目录完整复制到国外 Linux 服务器（例如 `/opt/astral-telegram`），不需要复制整个 Astral 仓库。Docker 构建上下文为本目录，不打包 `.env`。安装 Docker Engine + Compose v2。**管理端上传必须经 HTTPS：生产管理端是 HTTPS，网关也必须是 HTTPS（需域名 + 证书），否则浏览器按混合内容拦截——IP + 纯 HTTP 只能用于 `curl` 和服务端调试，详见第 2 节。** 若域名已托管在 Cloudflare，直接用第 2 节的**方案 C（Cloudflare Tunnel）**，网关机无需开放任何入站端口、无需证书。服务器必须能访问 `https://api.telegram.org` 和 Astral 的公网地址。频道需要授予 Bot 上传/删除消息的权限。

`worker.mjs` 是原 CF 网关协议的独立快照，服务器专用修正了 multipart EOF 时的流关闭逻辑；原 CF 文件未修改。以后协议变更须同步两份并运行测试。

## 1. 配置并部署

```bash
cd /opt/astral-telegram
bash deploy.sh
# 首次创建 .env 后退出；编辑配置，替换全部占位符
nano .env
bash deploy.sh
curl --fail http://127.0.0.1:38787/healthz
```

环境变量：

- `TG_BOT_TOKEN`：与频道对应的 Bot Token，仅保存在网关环境中。
- `STORAGE_UPLOAD_TICKET_KEY` / `STORAGE_ORIGIN_SHARED_SECRET` / `STORAGE_DOWNLOAD_SIGNING_KEY_V1`：必须与 Astral 后端实际使用的三个密钥逐字一致，不能在这里随意新生成后不同步后端。
- `ASTRAL_ORIGIN_BASE_URL`：Astral 后端地址，如 `https://astral.example.com`，不是管理端前端地址。
- `MAX_CONCURRENT_REQUESTS=8`：同时处理的请求上限，超额返回 503。
- `DELETE_INTERVAL_SECONDS=60`：远端删除任务轮询间隔；不重叠执行。
- `UPSTREAM_TIMEOUT_SECONDS=120`：Telegram / Astral 外部请求超时。
- **不要在 `.env` 里设 `PORT` / `HOST`**：容器内部端口由 `compose.yaml` 的 `environment` 钉死为 `8787`，与端口映射 `"38787:8787"` 的右侧对应。若在 `.env` 里写 `PORT=38787`，容器会监听 38787 而映射仍指向 8787，宿主机端口无人应答，表现为 `curl: (52) Empty reply from server`。

Compose 默认对外发布宿主机 TCP **38787**（容器内部仍为 8787）。**该端口仅供 HTTPS 反代回源与 `curl` 调试**：管理端是 HTTPS，浏览器会拦截到纯 HTTP 网关的请求（第 2 节），因此正式使用前必须在前面加域名 + TLS。不会修改 Astral 服务端口，也不加入 Astral 网络。容器使用非 root 用户、只读文件系统、512 MiB 内存限制。不要把 `.env` 提交到仓库或把密钥写进启动命令/日志。

## 2. 浏览器上传必须走 HTTPS

**结论：只要 Astral 管理端是 HTTPS（生产即 `https://web.canace.cn`），网关就必须是 HTTPS。纯 HTTP + IP 只适用于 `curl` 和服务端调试，管理端页面里的上传一定失败。**

这不是配置错误，而是浏览器的 Mixed Content 策略：HTTPS 页面发起的 `fetch`/XHR 属于「主动混合内容」，Chrome 无条件拦截，网关侧补任何 CORS 头都无效。实测（无头 Chrome，页面 `https://web.canace.cn/`）：

```
Mixed Content: The page at 'https://web.canace.cn/' was loaded over HTTPS,
but requested an insecure resource 'http://<网关IP>:38787/healthz'.
This request has been blocked; the content must be served over HTTPS.
→ fetch 抛 TypeError: Failed to fetch
```

自测时可临时把网关源站加进 `chrome://flags/#unsafely-treat-insecure-origin-as-secure`，**仅限自测，不能给用户用**。

`curl http://IP:38787/healthz` 返回 200 只证明服务端链路正常，**不代表浏览器可用**；必须用 HTTPS 访问才算通过。

三种取得 HTTPS 入口的方式，按本网关的实际情况排序：**方案 C（Cloudflare Tunnel，不需要入站端口，推荐）** > 方案 B（复用 Astral 机器 Nginx） > 方案 A（网关机自己开 80/443 申请证书）。

### 方案 A：给网关服务器配域名 + 证书（需 80/443 入站）

文件直达海外网关，链路最短（浏览器 → 网关 → Telegram）。需要把一个域名（如 `tg.example.com`）A 记录指向网关公网 IP，并放行 80/443。

```bash
# 网关上安装 nginx 与 certbot，证书用 HTTP-01 申请（需 80 端口可访问）
sudo apt install -y nginx certbot python3-certbot-nginx
sudo certbot --nginx -d tg.example.com
```

然后 `workerBaseUrl` 填 `https://tg.example.com`（只填源站，不带路径、不带结尾斜杠）。

网关机上 Nginx 的站点配置（证书路径按 certbot 实际生成调整）：

```nginx
server {
    listen 443 ssl;
    server_name tg.example.com;
    ssl_certificate /etc/letsencrypt/live/tg.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/tg.example.com/privkey.pem;
    client_max_body_size 21m;
    # 上传凭证和签名在 URL 内，不记录带 query 的访问日志。
    access_log off;
    location / {
        proxy_pass http://127.0.0.1:38787;
        proxy_http_version 1.1;
        proxy_request_buffering off;
        proxy_buffering off;
        proxy_read_timeout 150s;
        proxy_send_timeout 150s;
    }
}
```

验证 Nginx 配置后 reload，再访问 `https://tg.example.com/healthz`。DNS 可直接指向网关服务器，不需要 CF Worker 或 CF 代理。如使用 HTTPS 反代，建议将 Compose 端口映射改为 `127.0.0.1:38787:8787`，防止绕过反代；HTTPS 入口按需配置限流及防火墙。如果 Nginx 本身在容器内，需另行配置能访问宿主回环服务的网络，本例针对宿主机 Nginx。

### 方案 B：复用 Astral 机器的 Nginx 加子域名反代

若无法在网关机申请证书，可在 Astral 那台机器上用现有 Nginx 加一个子域名（如 `gw.example.com`），反代到网关。注意此时**上传文件体会经国内机器中转一次**，链路变长；且 `workerBaseUrl` 必须是纯源站（后端校验 `^https?://[\w.-]+(:\d+)?$` 不接受任何路径）。

```nginx
server {
    listen 443 ssl;
    server_name gw.example.com;
    ssl_certificate     /etc/letsencrypt/live/gw.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/gw.example.com/privkey.pem;
    client_max_body_size 21m;
    location / {
        proxy_pass http://GATEWAY_PUBLIC_IP:38787;
        proxy_http_version 1.1;
        proxy_request_buffering off;
        proxy_buffering off;
        proxy_read_timeout 150s;
        proxy_send_timeout 150s;
    }
}
```

> 注意：`ASTRAL_ORIGIN_BASE_URL`（网关 → Astral）与 `workerBaseUrl`（Astral → 网关，浏览器用）是两个方向的地址，不要写反。前者填 Astral 后端源站（`https://astral.canace.cn`，**不是**管理端前端 `web.canace.cn`）；若网关与 Astral 同机，用 `http://127.0.0.1:27000` 更省一跳。

### 方案 C：Cloudflare 入口（免证书；三条路线，按你能进到哪一步选）

> **先说结论**：Dashboard 里 `Networking → Tunnels → Create a tunnel` 有时会先要求开通 **Zero Trust 组织**，表现为弹窗要求**绑定银行卡**。那是 Zero Trust **组织开通**的付款墙，**不是隧道本身的要求**——如果它没拦住你，用 C2 的 Dashboard 隧道最省事；如果拦住了，用 C3 的纯 CLI 本地管理隧道，不创建组织、不需要绑卡。C1 的 Origin Rules 则完全不用装任何东西。

**前提**：域名的 zone 已在 Cloudflare（本项目用的 `canace.icu` 即是，NS 为 `jihoon.ns.cloudflare.com` / `sneh.ns.cloudflare.com`；`canace.cn` 在 DNSPod，不满足，除非整区迁移）。Cloudflare 的「子域单独建 zone」（subdomain setup）**仅 Enterprise 可用**，所以不能只把 `tg.canace.cn` 拆出来。

选方案 C 的理由（对比方案 A/B）：本网关机器的 **80/443 均未开放**，8080/8443/2053/2096 已被其它服务占用，而 `38787` 不在 Cloudflare 可代理端口白名单内（可代理的只有 80/8080/8880/2052/2082/2086/2095 与 443/2053/2083/2087/2096/8443）。

#### C1. Origin Rules 改回源端口（推荐：零新增软件、全程只在 zone 页面点几下）

1. **DNS**：加一条 `A` 记录，名称 `tg`，内容 `124.81.137.137`，代理状态选**已代理**（橙色云）。记录必须被代理，否则 Origin Rules 不生效。
2. **SSL/TLS → 概述 → 加密模式**选 **Flexible**。网关是纯 HTTP，选 Full / Full(strict) 会因为源站没有证书而 **525**。
3. **Rules → Overview → Origin Rules → Create rule**：
   - 表达式：`Hostname` `equals` `tg.canace.icu`
   - 设置：`Destination Port` → **Override** → `38787`

   > 端口白名单只约束「访客 → Cloudflare」这一段（访客走 443，本来就合规），**不约束 Cloudflare → 源站**那一段。改回源端口正是为绕开这个限制设计的，1–65535 任意端口，**全计划可用**（Free 也有 10 条规则）。
4. 验证：

   ```bash
   curl --fail https://tg.canace.icu/healthz
   # 期望：{"ok":true,"bot":"..."}
   ```

然后到 Astral 后台把 `workerBaseUrl` 填 `https://tg.canace.icu`（只填源站，不带路径、不带尾斜杠）。

注意：Flexible 表示 **Cloudflare → 源站这一段不加密**，并且要放行入站 **38787**（现已放行）。若要求加密回源，就得在网关机上配证书，那就回到方案 A 了。

#### C2. Dashboard 远程管理隧道（最省事：回源与主机名都在网页上点）

在 `Networking → Tunnels → Create a tunnel` 建出来的隧道（例如 `astral-telegram`）属于**远程管理隧道**：路由配置存在 Cloudflare 侧，本机只跑一个连接器容器，不需要本机配置文件。若建隧道时被要求开通 Zero Trust 组织（弹窗要求绑卡），见下面 C3 的纯 CLI 做法。

1. **取 token**：`Networking → Tunnels → astral-telegram → Overview`，安装方式选 **Docker**，复制命令里的 `eyJ...` 串。页面会警告 *This token grants access to your account*——它等同隧道运行凭证，别外传、别提交。写进项目目录的 `.env`：

   ```bash
   TUNNEL_TOKEN=eyJ...
   ```

2. **配公开主机名**：同一页 `Public Hostname → Add a public hostname`：
   - Subdomain `tg`，Domain `canace.icu`（DNS 记录由 Cloudflare 自动创建，已代理）
   - Service：`HTTP` → `telegram-storage:8787`

   > 这里**必须填 Compose 服务名 + 容器端口 8787**。连接器容器与 `telegram-storage` 同属本 Compose 网络，服务名可直接解析；容器内监听恒为 8787，宿主端口 38787 只用于直连 IP，容器之间不走它。填 `localhost:38787` 会 **502**。

3. **起连接器**：

   ```bash
   docker compose --profile tunnel up -d --build
   docker compose --profile tunnel logs --tail=50 cloudflared
   ```

   日志出现 `Registered tunnel connection` 即成功，Dashboard 的 Connection Status 也会转绿。`deploy.sh` 检测到 `.env` 里 `TUNNEL_TOKEN` 非空时会自动带上 `--profile tunnel`。

4. 验证并填后台：

   ```bash
   curl --fail https://tg.canace.icu/healthz
   # 期望：{"ok":true,"bot":"..."}
   ```

   然后到 Astral 后台把 `workerBaseUrl` 填 `https://tg.canace.icu`（只填源站，不带路径、不带尾斜杠）。

不要给这个 hostname 加 Cloudflare Access 策略，否则 Astral 后端和浏览器都会拿到登录页而不是网关响应。

#### C3. 本地管理（locally-managed）隧道：纯 CLI，不创建 Zero Trust 组织

有些账号在 Dashboard 建隧道前会被要求开通 **Zero Trust 组织**（表现为弹窗要求**绑定银行卡**）。那是**组织开通**的付款墙，不是隧道本身的要求：`cloudflared` CLI 建的是**本地管理隧道**，凭证和配置都放在本机文件里，**不创建组织**，因此绕开付款墙。官方文档给本地隧道列的前提只有两条——「把域名加到 Cloudflare」和「NS 指向 Cloudflare」，`canace.icu` 均已满足。

```bash
# 1) 登录：浏览器里选择 canace.icu 那个 zone 授权，凭证写到 ~/.cloudflared/cert.pem
#    无图形界面的服务器：把终端打印的 URL 复制到本地浏览器打开即可。
cloudflared tunnel login

# 2) 创建隧道，同时生成凭证文件 ~/.cloudflared/<UUID>.json（记下这个 UUID）
cloudflared tunnel create astral-tg

# 3) 在 canace.icu 上自动写一条指向该隧道的 CNAME
cloudflared tunnel route dns astral-tg tg.canace.icu

# 4) 写配置 ~/.cloudflared/config.yml
```

```yaml
tunnel: <UUID>
credentials-file: /home/<USER>/.cloudflared/<UUID>.json
ingress:
  # 宿主机直接跑 cloudflared 时用宿主端口 38787（8787 在本机已被 ARTEX 占用）
  - hostname: tg.canace.icu
    service: http://localhost:38787
  - service: http_status:404
```

```bash
# 5) 先前台试跑，确认链路
cloudflared tunnel run astral-tg
# 另开一个终端：
curl --fail https://tg.canace.icu/healthz

# 6) 装成开机自启服务。本地管理隧道必须显式给 --config，
#    否则会去找 /etc/cloudflared/config.yml，找不到凭证直接启动失败。
sudo cloudflared --config /home/<USER>/.cloudflared/config.yml service install
sudo systemctl enable --now cloudflared
systemctl status cloudflared --no-pager
```

若要把 `cloudflared` 跑在 Compose 里（而不是宿主机 systemd），用本目录的 `tunnel-local` profile：把 `config.yml` 和 `<UUID>.json` 放进 `./cloudflared/`，并把 ingress 的 service 改成 Compose 服务名 `http://telegram-storage:8787`（同网络内可直接解析，此时宿主机 38787 可完全关掉）。

```bash
docker compose --profile tunnel-local up -d --build
docker compose --profile tunnel-local logs --tail=50 cloudflared-local
```

两个连接器服务在 `compose.yaml` 里用 profile 互斥隔离，**不加对应 profile 时都不会启动**，方案 A/B 的 Nginx 反代不受影响：

| 服务名 | profile | 适用 | 本机需要什么 |
| --- | --- | --- | --- |
| `cloudflared` | `tunnel` | 远程管理隧道（C2，Dashboard 建的） | `.env` 里的 `TUNNEL_TOKEN` |
| `cloudflared-local` | `tunnel-local` | 本地管理隧道（C3，CLI 建的） | `./cloudflared/config.yml` + `<UUID>.json` |

`deploy.sh` 按 `TUNNEL_TOKEN` 优先、其次 `cloudflared/config.yml` 的顺序自动选 profile。

#### C4. 排障

| 现象 | 原因与处理 |
| --- | --- |
| **Error 1033（Cloudflare Tunnel error）**、HTTP 530 | 连接器没在跑。`systemctl status cloudflared`，或 `docker compose --profile tunnel logs --tail=50 cloudflared`。快速隧道（`trycloudflare.com`）是一次性的，重启换域名，**不要用它做正式入口**。 |
| **Error 1016（Origin DNS error）** | DNS 记录还在但隧道已不存在（隧道被删）。删掉旧 CNAME，重新 `cloudflared tunnel route dns`。 |
| **Error 525 / 526** | 走 C1 时加密模式被设成了 Full / Full(strict)，而源站是纯 HTTP。改回 **Flexible**。 |
| **Error 522** | Cloudflare 连不上源站 38787：确认网关容器在跑（`docker compose ps`）且防火墙放行 38787。 |
| 502/504 | 隧道通了但回源失败。Service URL 写错端口最常见——宿主机是 `localhost:38787`，Compose 内是 `telegram-storage:8787`。 |
| `service install` 后服务起不来、日志说找不到凭证 | 安装时漏了 `--config`，或配置里 `credentials-file` 路径写错。 |
| 上传大文件偶发 **524** | Cloudflare 代理读超时 125 秒（Free 不可调）。20 MiB 文件在慢速国际链路上可能超时；重试即可，或降低并发。 |
| 隧道状态 `Degraded` / 日志有 ICMP 代理告警 | Pingora 的 ICMP 代理是可选的，告警可忽略，不影响 HTTP 回源。 |

> 方案 C 不解除 Telegram 公共 Bot API 的 **20 MiB** 单文件上限，也不改变 Astral 侧的签名与删除任务契约——它只替换「浏览器 → 网关」这一段链路。

## 3. Astral 后台选择模式

在「文件存储 → 存储配置」：

1. providerType 选 TELEGRAM，填写频道 chatId。
2. **Worker 地址 / workerBaseUrl** 字段：CF 模式填 CF Worker 域名；服务器模式填网关的 HTTPS 源站（如 `https://tg.canace.icu`）。**管理端是 HTTPS，这里必须也是 HTTPS**，填 `http://IP:38787` 会在浏览器被混合内容拦截（第 2 节）。字段名称虽叫 Worker，也兼容本服务器网关。
3. 地址仅填写 origin，不带 `/upload`、尾斜杠或路径。
4. 测试连接，上传文件，验证签名下载和永久公开链接。

可保留原 CF 配置并另建服务器配置。若修改已有配置，已发出的永久链接不会自动改域名；需保留旧域名服务或规划迁移。Bot 和频道也必须与原文件一致，换 Bot 不保证旧 file_id 可用。不要对同一任务配置同时启动多个删除轮询消费者，当前接口并未在此实现分布式消费者协调。

## 行为与限制

- 文件本体在 Telegram，Astral 只保存元数据；上传入口仍为 `/upload?ticket=...`，下载仍为 `/f/...` 和 `/p/...`。
- 与 Astral 的 HMAC 规范串是 `METHOD\nPATH\nTIMESTAMP\nNONCE`，**PATH 不含 query string**：Astral 侧用 `request.getRequestURI()` 拼接，它永远不带 query。带 query 的路径（如删除任务拉取 `/worker/tasks/delete?limit=10`）必须先剥离再签名，否则恒定 STORAGE017 / HTTP 400。CF 版与本服务器版遵循同一约定。
- 服务器模式不使用 CF `caches.default`，**不提供共享边缘缓存**。公开响应仍保留浏览器 Cache-Control（签名链接 300 秒、永久链接 86400 秒）；私有文件 `private, no-store`。转私有或删除后，浏览器/外部缓存里的旧公开内容可能在原 TTL 内仍可访问。
- 目前只支持公共 Telegram Bot API 和 **20 MiB 文件**，并对整个 multipart 请求设 21 MiB 上限。不是 Telegram Local Bot API 大文件方案。
- 虽然到 Telegram 的 multipart 构造和下载使用流，上传入口的 `request.formData()` 会缓冲文件；不能宣称全链路零内存。高并发大文件应降低并发数或后续改为流式 multipart 解析。
- `/healthz` 检查 Bot 是否能连接 Telegram，不代表频道写权限或 Astral 回调完整正常；上线还需真实上传下载验证。
- Telegram 已上传但 Astral 回调失败时不自动重传，避免重复文件；需要核对频道与元数据。

## 运维与测试

```bash
docker compose logs --tail=100
docker compose restart
# 更新本目录代码后重建本服务，不影响 Astral
docker compose up -d --build
# 启用 Cloudflare Tunnel 时（方案 C）带对应 profile；
# 远程管理隧道用 tunnel profile（.env 里 TUNNEL_TOKEN 非空），本地管理隧道用 tunnel-local（cloudflared/config.yml）
docker compose --profile tunnel up -d --build
docker compose --profile tunnel logs --tail=50 cloudflared
# 本地管理隧道则是：
# docker compose --profile tunnel-local up -d --build
# docker compose --profile tunnel-local logs --tail=50 cloudflared-local
# 仅停止本目录 Compose 项目
docker compose down
# 无 npm 依赖；可在 Node.js 24+ 环境运行模拟契约测试
node --test server.test.mjs
```

模拟测试不需要真实 Bot、Astral 或密钥；覆盖上传回调 HMAC、签名下载、公开/私有权限、Telegram 删除任务和请求体大小限制。测试内的 Astral 桩按 Java 的 `request.getRequestURI()`（**不含 query**）校验签名，并断言删除任务拉取成功与失败原因透出。Docker 镜像启动和真实海外链路需在目标服务器验证。
