# astral 备份与恢复手册

> 适用部署方式：`deploy/docker-compose.registry.yml`（腾讯云 PostgreSQL + Redis + 管理端对象存储）。
> 原则：**数据库是唯一需要备份的状态**。镜像可从 CNB 制品库重新拉取，上传的媒体文件走对象存储的版本化/跨区冗余，Redis（db 7）里只有会话与频控缓存，丢了只影响在线会话，不影响数据正确性。

## 一、备份什么

| 内容 | 位置 | 方式 | 频率 |
| --- | --- | --- | --- |
| 全量业务数据（核心） | 腾讯云 PG 实例 | 控制台自动备份 + 手动 pg_dump | 自动每日；变更前手动 |
| 媒体文件 | 对象存储（图片/音频封面等） | 桶开启版本化即可，无需自建脚本 | 开启一次 |
| 部署配置 | `deploy/.env`（REGISTRY/数据源/密钥等） | 离线抄送一份到密码管理器 | 每次修改后 |
| RSA 密钥对 | `RSA_PRIVATE_KEY`（deploy/.env） | **必须**随 .env 抄送，丢失后前端加密密码全部无法解密 | 生成时一次 |

Redis 不备份。astral 只把 Redis 用于：Sa-Token 会话、登录限流计数（`astral:login:fail:*` / `astral:login:lock:*`）、统计缓存。全部可重建。

## 二、腾讯云 PG 自动备份（兜底）

控制台 → 数据库 → PostgreSQL → 实例 → 备份恢复：

- 自动备份默认每日一次、保留 7 天，建议改为保留 **30 天**；
- 开启**日志备份（WAL 归档）**，可支持时间点恢复（PITR）；
- 跨地域备份按需开启（低成本高保障，建议开）。

自动备份覆盖「误删库/实例故障」这类灾难场景，但恢复粒度是整实例。日常误操作（误删一张表、误改配置）用下面的 pg_dump 更精准。

## 三、手动全量备份（pg_dump）

在任意装有 psql 16+ 客户端的机器上执行（腾讯云 PG 版本 15/16，注意客户端版本 ≥ 服务端）：

```bash
# 从 deploy/.env 里取连接信息（注意连接串形如 jdbc:postgresql://host:5432/astral）
export PGHOST='<host>' PGPORT='5432' PGUSER='<用户名>' PGPASSWORD='<密码>' PGDATABASE='astral'

# 推荐：custom 格式 + 压缩，恢复灵活（可单表恢复）
pg_dump -Fc -f astral_$(date +%Y%m%d_%H%M).dump

# 校验非空且可读
pg_restore -l astral_*.dump | tail -5
```

本地留存至少两份（本机 + 网盘/另一台机器），建议同时保留最近 4 个周末点。

### 为什么 custom 格式 + 恢复时才建库

`-Fc` 恢复时可以选择只恢复某些表（`pg_restore -t sys_user`），且对象定义与数据分离；纯 SQL 格式（`-Fp`）只适合整库重放。

## 四、恢复步骤

### 4.1 整库恢复（新实例 / 灾难恢复）

```bash
# 1. 目标库先建好空库（腾讯云 PG 建实例后默认 postgres 库，需自建 astral）
psql -h <host> -U <用户名> -d postgres -c 'CREATE DATABASE astral ENCODING ''UTF8'';'

# 2. 恢复（先建 schema 再放数据；-j 4 并行加速）
pg_restore -h <host> -U <用户名> -d astral -j 4 --no-owner --no-privileges astral_YYYYMMDD_HHMM.dump
```

恢复完成后：

```bash
# 3. 重启后端容器，让 Flyway 跳过已应用的迁移
docker compose -f deploy/docker-compose.registry.yml up -d backend
```

Flyway 看到 schema 与 `flyway_schema_history` 一致会直接正常运行；**不需要**手工改 flyway 表。

### 4.2 恢复到某张表（误删/误改单表）

```bash
# 只恢复 sys_user 一张表（数据会与现有表冲突时，先清空目标表）
pg_restore -h <host> -U <用户名> -d astral -t sys_user --data-only --no-owner astral_YYYYMMDD_HHMM.dump
```

> 误操作发生在今天、实例开了 WAL 归档时，也可以直接用腾讯云 PITR 拉起一个临时实例，从临时实例里把表导回来，避免覆盖线上。

### 4.3 序列表一致性（astral 特有，重要）

astral 的主键不是自增，而是 `sequence_segment` 表按业务键分段发号（内存取段，落库水位 `max_value`）。备份与恢复遵循三条规则：

1. **备份天然一致**：`pg_dump` 是事务快照，`sequence_segment.max_value` 与各表 `max(id)` 在同一快照里，不会出现「号段已盖章但业务行没进备份」。
2. **恢复后由应用启动自动对齐水位**：`SequencePlugin` 每次启动都会把各业务键的 `max_value` 抬到「表 MAX(id) + step」（`alignHighWatermark`，幂等、只升不降）。所以整库恢复的标准动作就是**恢复完直接启动后端**，启动阶段自动完成对齐，不需要手工修数。
3. **手工对齐（仅应急）**：只在你需要在应用启动前就直接写库（维护窗口用 psql 补数）时才需要：

```sql
-- 以 sys_user 为例：把水位抬到当前最大 id + 1000（与迁移规范 V20261001005 同一套安全阈）
UPDATE sequence_segment
SET max_value = (SELECT COALESCE(MAX(id), 0) + 1000 FROM sys_user)
WHERE biz_key = 'sys_user_id'
  AND max_value < (SELECT COALESCE(MAX(id), 0) + 1000 FROM sys_user);
```

先查 `SELECT biz_key FROM sequence_segment;` 拿到全部业务键（命名规则 `表名_id`），逐表执行。只升不降，重复执行无害。

## 五、恢复演练（每季度一次）

1. 在本地或临时 PG 实例（端口 55432）`CREATE DATABASE astral_restore;`
2. `pg_restore -h 127.0.0.1 -p 55432 -d astral_restore -j 4 astral_最新.dump`
3. 核对：`SELECT COUNT(*) FROM sys_user;`、`SELECT MAX(id) FROM sys_user;` 与线上对齐；`SELECT * FROM sequence_segment;` 水位 ≥ 各表 max(id)；
4. 用本地 `python localenv.py`（F:\qtMusic\.localtest）把后端指向 astral_restore 起一次，登录后台走一遍核心页面；
5. 记录演练耗时，评估 RTO 是否满足预期。

演练通过的标准：**能在 30 分钟内从任意一份备份恢复出可登录的完整后台**。

## 六、常见问题

- **pg_dump 报 SSL/TLS 错误**：腾讯云 PG 默认只收 TLS 时需加 `sslmode=require`（`PGSSLMODE=require`）。
- **恢复时报角色不存在**：用 `--no-owner --no-privileges`，对象归还原连接用户。
- **恢复后登录报「解密失败」**：`RSA_PRIVATE_KEY` 与备份时不是同一对，用旧 .env 里的密钥重启后端。
- **dump 文件多大算正常**：与 `stat_*` 三张表体量强相关（占大头），首次备份后以此为基线，突增时先查统计表。
