# 数据库迁移脚本（手动增量）

本目录用于**手动**管理数据库结构与种子数据的变更。

应用启动时**不再自动执行任何 SQL**（`spring.sql.init.mode: never`）。原因：旧的自动初始化会在每次启动时由 `dict-init.sql` 清空并重建字典表，导致后台新增的字典项重启后丢失，且脚本一旦报错会直接让应用启动失败。

## 命名规范

```
V{序号}__{描述}.sql        例：V4__add_user_avatar.sql
```

- 序号**严格递增**（V1、V2、V3 …），按序号升序应用；
- 每个版本的脚本一旦被应用过就**禁止再修改**，后续变更一律新增下一个版本号；
- 描述用下划线分隔的小写英文，简短说明这次改了什么。

## 版本清单

| 版本 | 文件 | 说明 | 幂等 |
|---|---|---|---|
| V1 | `V1__baseline_schema.sql` | 基线：全部建表 + 核心种子数据 | 是（`IF NOT EXISTS` / `ON CONFLICT`） |
| V2 | `V2__baseline_dict.sql` | 基线：数据字典类型与字典项 | **否**（先 `DELETE` 再重建） |
| V3 | `V3__baseline_dict_sequence_reset.sql` | 基线：字典表自增序列推进到当前最大值 | 是 |

> V1–V3 是历史初始化脚本的基线，只供**全新库**首次部署执行。

## 变更跟踪表

应用用一张 `schema_migrations` 表记录已应用的版本（需手动创建，属于环境初始化的一部分）：

```sql
CREATE TABLE IF NOT EXISTS schema_migrations (
  version     VARCHAR(32)  PRIMARY KEY,
  description VARCHAR(128) NOT NULL,
  applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

## 连接参数

应用数据源使用 `currentSchema=astral`，即所有表位于 **`astral`** schema。手动执行脚本时必须把 `search_path` 指过去，否则表会建到 `public`。

```bash
export PGHOST=<数据库主机> PGPORT=5432 PGUSER=<账号> PGPASSWORD=<密码> PGDATABASE=astral
export PGOPTIONS='-c search_path=astral'     # 关键，否则建到 public
```

## 一、全新数据库首次部署

```bash
cd astral-server/src/main/resources/sql/migrations

# 1) 建跟踪表
psql -v ON_ERROR_STOP=1 -c "CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(32) PRIMARY KEY, description VARCHAR(128) NOT NULL, applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);"

# 2) 按序应用全部基线脚本并登记
for f in V*.sql; do
  ver="${f%%__*}"
  echo ">> applying $f"
  psql -v ON_ERROR_STOP=1 -f "$f" || exit 1
  psql -v ON_ERROR_STOP=1 -c "INSERT INTO schema_migrations(version, description) VALUES ('$ver', '$f') ON CONFLICT DO NOTHING;"
done
```

服务器上没装 `psql` 时，可用 postgres 客户端容器（在 migrations 目录下执行）：

```bash
docker run --rm -i -v "$PWD":/mig -w /mig \
  -e PGPASSWORD='<密码>' postgres:16-alpine \
  psql -h <数据库主机> -U <账号> -d astral -v ON_ERROR_STOP=1 \
       -c "SET search_path=astral;" -f V1__baseline_schema.sql
```

（用 Navicat / DBeaver 等客户端直接执行对应 `.sql` 文件同样可以，注意把当前 schema 切到 `astral`。）

## 二、既有数据库：基线登记（不执行脚本）

已经用旧版自动初始化跑起来的库，表和数据都在，**不要**再执行 V1–V3（V2 会清空字典表）。只需把它们登记为已应用：

```bash
psql -v ON_ERROR_STOP=1 -c "CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(32) PRIMARY KEY, description VARCHAR(128) NOT NULL, applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);"
psql -v ON_ERROR_STOP=1 -c "INSERT INTO schema_migrations(version, description) VALUES ('V1','V1__baseline_schema.sql'),('V2','V2__baseline_dict.sql'),('V3','V3__baseline_dict_sequence_reset.sql') ON CONFLICT DO NOTHING;"
```

## 三、后续增量变更流程

1. 在数据库里改好结构（或先写好 SQL 并本地验证）；
2. 新建文件，序号取当前最大版本 +1：
   ```sql
   -- V4__add_user_avatar.sql
   ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS avatar VARCHAR(512);
   ```
3. 提交到仓库；
4. 部署时对该环境执行这一次脚本，并写入跟踪表：
   ```bash
   psql -v ON_ERROR_STOP=1 -f V4__add_user_avatar.sql
   psql -v ON_ERROR_STOP=1 -c "INSERT INTO schema_migrations(version, description) VALUES ('V4','V4__add_user_avatar.sql') ON CONFLICT DO NOTHING;"
   ```
5. 查看某环境已应用到哪一版：
   ```bash
   psql -c "SELECT version, description, applied_at FROM schema_migrations ORDER BY version;"
   ```

**写增量脚本的建议**

- 尽量写成幂等语句（`CREATE TABLE IF NOT EXISTS`、`ADD COLUMN IF NOT EXISTS`、`INSERT ... ON CONFLICT DO NOTHING`），避免重复执行时报错；
- 涉及删列/改类型/清数据等破坏性操作，先在测试库验证，并在脚本头部用注释写明影响；
- 不要把真实凭据写进脚本（参考 `V1` 里邮件账号已改为占位示例）。

## 四、插件自建表

轻听（qt）、反馈（feedback）插件的表由各自的 `@PostConstruct` 初始化器在启动时**幂等**创建/补列（`CREATE TABLE IF NOT EXISTS`、`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`），不会清空数据。这部分不随 `spring.sql.init` 开关变化；如需彻底关闭，可在插件配置中处理（当前未提供开关）。

## 五、遗留文件

同目录上级的 `h2-init.sql`、`auth-init.sql` 为历史遗留脚本，当前配置未引用，可忽略；确认无用后可删除。
