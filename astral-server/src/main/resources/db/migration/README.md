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
| 20260926001 | `V20260926001__qt_permission_codes.sql` | 登记轻听测试权限编码 qt_tester（**已废弃**：20260930001 已将其迁移为结果级权限 qt:update:channel:beta / qt:source:channel:beta 并删除） | 执行 |
| 20260928001 | `V20260928001__sequence_segment_unique_key.sql` | 序列号段表唯一键（按业务键 + 号段区间去重，修复并发预加载产生重复号段） | 执行 |
| 20260929001 | `V20260929001__stat_api_hourly_client_dimension.sql` | 接口小时统计增加客户端维度（平台/应用版本/设备/OS，来自统一客户端系统头） | 执行 |
| 20260930001 | `V20260930001__permission_model_refactor.sql` | **权限模型重构**：`sys_role.is_super`（超管角色标志，替代 `*:*:*` 权限行）、`sys_permission.domain`（权限域，按域分组展示）；回填 domain 与 is_super；旧码迁移（qt_admin→qt:admin、qt_tester→qt:update:channel:beta + qt:source:channel:beta）后删除旧行 | 执行 |
| 20260930002 | `V20260930002__permission_type_dict.sql` | 权限类型登记数据字典（permission_type：1目录 2菜单 3按钮 4接口 5数据），修正前端把 1/2/3 误标为「菜单/按钮/接口」的历史不一致 | 执行 |
| 20260930003 | `V20260930003__permission_seed_new_codes.sql` | 登记本次新增的 11 个接口权限码（`system:dict:edit`、`system:config:edit`、`sequence:edit`、`plugin:edit`、`monitor:view`、`feedback:view/edit`、`message:view/edit`、`storage:view/edit`），带中文名/域/类型。**不向任何角色授权**（默认拒绝）：这些接口重构前无校验，需到角色管理页按需勾选；超管角色不受影响 | 执行 |
| 20260930004 | `V20260930004__permission_granular_write_and_tree.sql` | **权限配置收口（一）**：① 修正 `system:menu:view` 非法 `type=0` → 2；② 补 4 个可委派写权限码（`system:user:edit`、`system:role:edit`、`system:menu:edit`、`system:token:edit`），配套代码侧把 UserController/RoleController/SysMenuController/TokenController 的对应写接口从 `@RequiresSuper` 降级为 `@RequiresPermission`（提权类操作——分配角色、重置密码、改用户类型、分配权限、`is_super` 变更、权限定义 CRUD、表结构写——**仍保持超管专属**）；③ 建立 `sys_permission.parent_id` 层级（动作码挂到同资源 `:view` 下，共 19 条子权限）；④ `sys_menu` 仪表盘行接线 `permission='monitor:view'`（其接口本就要求该权限，此前菜单可见但非超管打不开） | 执行 |
| 20260930005 | `V20260930005__role_permission_baseline.sql` | **权限配置收口（二）角色基线**：① 清空超管角色的 `sys_role_permission`（超管语义由 `is_super=1` 表达，绑定行不参与判定，仅误导运维）；② 重置 `USER` 角色为通用只读基线 `monitor:view` + `statistics:view` + `log:view`，移除历史种子留下的 `system:{permission,role,token,schema,menu}:view`、`plugin:view`、`sequence:view`、`system:{config,dict}:view` 等敏感视图越权；③ 不向任何角色授予插件业务权限与 `:edit` 写权限（保持默认拒绝，由管理员在角色权限页勾选） | 执行 |
| 20260930006 | `V20260930006__permission_type_dict_fix.sql` | **修复：`permission_type` 字典实际未落库**。20260930002 硬编码了 `id=911`，而该 id 已被 20260914006 的 `qt_source_report_result` 占用（数据行 9110/9111 同理被占），于是 `ON CONFLICT DO NOTHING` **静默跳过**（Flyway 仍记 success），后续 5 条数据行又因取不到 `dict_type_id` 而全部落空——前端权限管理页「类型」列回退成裸码值。本脚本改按 `MAX(id)+1` 动态取号补齐 5 个码值。**新增字典/权限种子脚本不要硬编码主键 id** | 执行 |
| 20261001001 | `V20261001001__permission_code_side_prefix.sql` | **权限码加「端」段**：旧 `域:资源:操作[:范围]` → 新 `端:域:资源:操作[:范围]`（端 ∈ `admin`/`user`/`all`，与接口路径三层前缀一一对应）。**原地改写** permission_code（id 不变，故 `sys_role_permission` 关联自动继续有效），同时给 `sys_menu.permission` 加同样的前缀（否则菜单整片消失）。⚠️ **破坏性重命名**：必须与代码字面量同一次部署生效，否则注册器会把旧码当缺失重新插回（见 20261001004） | 执行 |
| 20261001003 | `V20261001003__menu_type_dict.sql` | 菜单类型登记数据字典（`menu_type`：0目录 1菜单 2按钮），供 `sys_menu.typ` 走字典渲染。**注意**：与 `permission_type`（1..5）是两套独立编码，差一位，勿混用 | 执行 |
| 20261001004 | `V20261001004__permission_cleanup_legacy_codes.sql` | 清理「端前缀迁移」期间被注册器重新插回的旧码（无前缀 + 无角色引用）。记录了一个真实时序陷阱：迁移先于代码生效时，旧代码启动会补插一批无引用孤儿行（id ≥ 1000001），使权限管理页条目翻倍 | 执行 |
| 20261001005 | `V20261001005__app_user_role_and_permissions.sql` | 新增 App 端默认角色 `APP_USER`（`is_super=0`，持有全部 `user:` 前缀权限），并回填所有 `user_type='APP'` 存量用户。后续新增 `user:` 权限由 `AppUserRoleInitializer` 启动时自动补授，无需再写迁移；管理员可移除（移除后该用户 App 端接口 403，属预期） | 执行 |
| 20261001006 | `V20261001006__cleanup_orphan_unprefixed_permissions.sql` | 再次清理无端前缀孤儿权限。与 20261001004 删除条件**完全一致**（无前缀 + 无角色引用），作为幂等收尾：任何未来的混合态部署都能在下次启动时自动收敛干净 | 执行 |
| 20261001007 | `V20261001007__notice_channel_platform_multi.sql` | 通知渠道 `sys_notice.channel` 值域对齐统计平台并支持多选（逗号分隔，如 `app-android,app-ios`；`all`=不限）。旧值 `app`→`app-android,app-ios`、`pc`→`app-windows` 存量迁移，老客户端继续识别旧值。iOS 字典行用 `COALESCE(MAX(id),0)+1` + `NOT EXISTS` 取号（初版写死 9123 会撞 20261001003 的 menu_type 行、必然失败；修正发生在**部署前**，该版本从未应用到任何库，本地开发库跑的已是修正版） | 执行 |
| 20261001008 | `V20261001008__daka_unique_and_stat_error_composite_index.sql` | ① `qt_user_daka` 建 `(uid,data)` 唯一索引（先清存量重复行，堵住并发双签到/积分翻倍）；② 统计错误表补 `(fingerprint,occur_time)` 复合索引并退役被其左前缀覆盖的旧单列 `idx_stat_error_fingerprint`。①整段带 `to_regclass` 守卫：全新空库（init.sql 流程 / `astral.plugins.qt.enabled=false`）下 Flyway 先于 `QtSchemaInitializer` 执行、`qt_user_daka` 尚不存在，此时跳过，唯一索引由 `qt-schema.sql` 的 `IF NOT EXISTS` 兜底。⚠️ 本地开发库若曾应用**初版** V08（2026-09-30 18:33 前后启动过、当时文件尚无守卫段），需 `flyway repair` 或删除 history 中 20261001008 行让其幂等重跑 | 执行 |
| 20261001009 | `V20261001009__app_user_revoke_data_permissions.sql` | **修复：APP_USER 误持范围权限**。20261001005 按 `user:` 前缀全量授权未排除 `type=5`（DATA），端前缀重命名后 `user:qt:update:channel:beta` / `user:qt:source:channel:beta` 被批发给所有 App 用户（测试版全员可见）。本脚本收回 APP_USER 名下全部 DATA 授权；配套 `syncPermissions()` 改为只授 `type=API` 并每次启动收回 DATA。⚠️ 与该代码**同一次部署** | 执行 |

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
