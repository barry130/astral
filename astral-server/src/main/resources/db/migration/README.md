# 数据库迁移（Flyway）

数据库结构与种子数据的增量变更由 **Flyway** 管理：应用启动时自动按版本号顺序应用本目录下的
`V*.sql`，版本历史记录在数据库的 **`flyway_schema_history`** 表（astral schema）。

## 机制要点

- **全新空库**：从 `V20260914001__init.sql` 全量执行（建表 + 种子 + storage 表），随后执行后续版本…
- **存量库**（表已存在、无 flyway_schema_history）：`baseline-on-migrate` 自动打基线，
  `baseline-version: 20260914001` 意味着 **初始化脚本被跳过不执行**（其内容历史上已手工应用），
  仅执行编号更大的脚本。整个基线化过程零手工操作。
- **正常增量**：每次发布新增一个 `V{yyyyMMddNNN}__描述.sql`，应用启动时自动应用。
- 前置条件只有一个：数据库 `astral` 本身要存在（`CREATE DATABASE astral`），
  schema/表/种子/字典全部由 Flyway 完成。
- 应急关闭：环境变量 `FLYWAY_ENABLED=false`（仅排查问题用，正常部署必须开启）。

## 脚本清单

| 版本 | 文件 | 说明 | 存量库 |
|---|---|---|---|
| 20260914001 | `V20260914001__init.sql` | 基线初始化（原手工 V1-V4 按序合并：宿主建表+种子 / 字典基线 / 序列推进 / storage 表） | **跳过**（基线） |
| 20260914002 | `V20260914002__drop_legacy_schema_migrations.sql` | 删除旧的手工版本跟踪表 schema_migrations | 执行 |
| 20260914003 | `V20260914003__storage_dict.sql` | storage 插件枚举登记数据字典（7 个字典类型，原手工 V5） | 执行 |
| 20260914004 | `V20260914004__remove_cluster.sql` | 整体移除集群模式：删菜单/权限/表/序列登记（代码侧同步删除） | 执行 |
| 20260914005 | `V20260914005__qt_source_dict.sql` | 音源包产物路径登记数据字典（qt_source_artifact_path） | 执行 |
| 20260914006 | `V20260914006__qt_source_dict_ext.sql` | 音源包装载结果/发布状态登记数据字典（qt_source_report_result / qt_source_release_state） | 执行 |
| 20260925001 | `V20260925001__storage_folder_upload_policy.sql` | 文件夹级上传策略：storage 表加列 + storage_verify_content 字典 | 执行 |
| 20260926001 | `V20260926001__qt_permission_codes.sql` | 登记轻听测试权限编码 qt_tester（音源包/版本更新测试版 beta 渠道按 qt_admin/qt_tester 人群投放） | 执行 |

## 命名规范

```
V{yyyyMMddNNN}__{下划线小写描述}.sql    例：V20260915001__add_user_avatar.sql
```

- 版本号 = **当天日期（yyyyMMdd）+ 当日三位序号（001 起）**；按 Flyway 数值比较**严格递增**，
  新脚本取「今天日期 + 当日已有最大序号 +1」；
- **只增不改**：Flyway 对已应用脚本做校验和（checksum）比对，已发布文件一经修改，
  下次启动会因 checksum 不一致直接失败——改历史脚本等于拒绝启动，这是刻意的防漂移机制；
- 尽量写成幂等语句（`IF NOT EXISTS` / `ON CONFLICT DO NOTHING`），降低排障成本；
- 破坏性操作（删列/改类型/清数据）先在本地验证，并在脚本头部注释写明影响；
- 真实凭据不进脚本。

## 新增一个变更的流程

1. 本地写好 `V{yyyyMMddNNN}__xxx.sql` 并验证（本地库跑一次启动即应用）；
2. 提交仓库；
3. 部署发布时**无需任何手工数据库操作**——backend 启动时 Flyway 自动应用。

## 查看已应用版本

```sql
SELECT installed_rank, version, description, success, installed_on
FROM astral.flyway_schema_history ORDER BY installed_rank;
```

## 与旧机制的关系（历史说明）

- 2026-09 起迁移机制为 Flyway（本目录）；
- 更早的手工机制（`sql/migrations/V*.sql` + 人工登记 `schema_migrations`）已移除：
  原 V1-V4 合并为本目录 `V20260914001__init.sql`（存量库基线化、不再执行），原 V5 重新编号为 20260914003，
  旧跟踪表由 V2 脚本在存量库上删除；
- 插件自建表（qt/feedback 的 `*SchemaInitializer` 启动幂等建表）不受 Flyway 影响，维持原样；
- `spring.sql.init.mode: never` 维持不变，避免任何双轨执行。
