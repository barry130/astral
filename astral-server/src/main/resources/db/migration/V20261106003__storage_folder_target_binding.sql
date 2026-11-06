-- ============================================================
-- 存储组下线：文件夹与授权直接绑定存储配置（多选 + 优先级 + 调度策略）
--
-- 1) 新建 sys_storage_folder_config：文件夹 ↔ 存储配置多对多绑定，
--    priority 决定 FAILOVER 的故障转移顺序；同一文件夹内唯一。
-- 2) 新建 sys_storage_permission_config：授权行 ↔ 存储配置绑定，
--    必须是文件夹绑定集合的子集（服务写入时校验）；为空 = 跟随文件夹绑定。
-- 3) sys_storage_folder 增加 storage_strategy（FAILOVER/ROUND_ROBIN），
--    删除 target_mode/storage_config_id/storage_group_id 三态列：
--    - CONFIG 文件夹 → 迁移为单条绑定（priority 100）
--    - GROUP 文件夹 → 按组展开启用成员为绑定（保留成员优先级与组策略）
--    - INHERIT 文件夹 → 不迁移（新语义：无绑定即禁止上传）
-- 4) sys_storage_upload_operation 删除 target_mode/group_id/group_version
--    （folder_id + folder_version + route_snapshot_json 足够溯源）。
-- 5) sys_storage_file 删除 storage_group_id（storage_config_id 才是真实归属）。
-- 6) 删除 sys_storage_group / sys_storage_group_member 与其号段。
-- 7) 字典：storage_target_mode/storage_group_strategy 下线，新增 storage_target_strategy。
--
-- 幂等：DDL 带 IF NOT EXISTS / IF EXISTS；数据迁移用 ON CONFLICT DO NOTHING。
-- ============================================================

-- ============================================================
-- 1. 文件夹-配置绑定表
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_folder_config (
  id                 BIGINT      PRIMARY KEY,
  folder_id          BIGINT      NOT NULL,
  storage_config_id  BIGINT      NOT NULL,
  priority           INTEGER     NOT NULL DEFAULT 100,
  create_time        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_storage_folder_config UNIQUE (folder_id, storage_config_id),
  CONSTRAINT ck_storage_folder_config_priority CHECK (priority BETWEEN 0 AND 10000)
);
CREATE INDEX IF NOT EXISTS idx_storage_folder_config_order
  ON sys_storage_folder_config (folder_id, priority, id);
CREATE INDEX IF NOT EXISTS idx_storage_folder_config_config
  ON sys_storage_folder_config (storage_config_id);

-- ============================================================
-- 2. 授权-配置绑定表（授权行选择的配置，须为文件夹绑定的子集）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_permission_config (
  id                 BIGINT      PRIMARY KEY,
  permission_id      BIGINT      NOT NULL,
  storage_config_id  BIGINT      NOT NULL,
  priority           INTEGER     NOT NULL DEFAULT 100,
  create_time        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_storage_permission_config UNIQUE (permission_id, storage_config_id),
  CONSTRAINT ck_storage_permission_config_priority CHECK (priority BETWEEN 0 AND 10000)
);
CREATE INDEX IF NOT EXISTS idx_storage_permission_config_order
  ON sys_storage_permission_config (permission_id, priority, id);
CREATE INDEX IF NOT EXISTS idx_storage_permission_config_config
  ON sys_storage_permission_config (storage_config_id);

-- ============================================================
-- 3. 文件夹：调度策略列（FAILOVER 按优先级故障转移 / ROUND_ROBIN 轮询）
-- ============================================================
ALTER TABLE sys_storage_folder
  ADD COLUMN IF NOT EXISTS storage_strategy VARCHAR(20) NOT NULL DEFAULT 'FAILOVER';
ALTER TABLE sys_storage_folder DROP CONSTRAINT IF EXISTS ck_storage_folder_strategy;
ALTER TABLE sys_storage_folder ADD CONSTRAINT ck_storage_folder_strategy CHECK (
  storage_strategy IN ('FAILOVER', 'ROUND_ROBIN'));

-- ============================================================
-- 4. 数据迁移：三态列 → 绑定表
--    （先迁 CONFIG，再迁 GROUP；两段顺序执行保证 COALESCE(MAX(id)) 不重叠）
-- ============================================================
-- 4a. CONFIG 文件夹 → 单条绑定
INSERT INTO sys_storage_folder_config (id, folder_id, storage_config_id, priority)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_storage_folder_config)
       + ROW_NUMBER() OVER (ORDER BY id),
       id, storage_config_id, 100
FROM sys_storage_folder
WHERE target_mode = 'CONFIG' AND storage_config_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- 4b. GROUP 文件夹 → 展开组内启用成员（保留成员优先级）
INSERT INTO sys_storage_folder_config (id, folder_id, storage_config_id, priority)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_storage_folder_config)
       + ROW_NUMBER() OVER (ORDER BY f.id, m.priority, m.id),
       f.id, m.storage_config_id, m.priority
FROM sys_storage_folder f
JOIN sys_storage_group_member m ON m.group_id = f.storage_group_id
WHERE f.target_mode = 'GROUP' AND m.enabled = 1
ON CONFLICT DO NOTHING;

-- 4c. GROUP 文件夹的策略沿用所属组
UPDATE sys_storage_folder f
SET storage_strategy = g.strategy
FROM sys_storage_group g
WHERE f.target_mode = 'GROUP' AND f.storage_group_id = g.id
  AND g.strategy IN ('FAILOVER', 'ROUND_ROBIN');

-- 4d. 授权绑定：存储组从未接入授权，无历史数据需要迁移。

-- ============================================================
-- 5. 文件夹：删除三态列与相关约束/索引
-- ============================================================
ALTER TABLE sys_storage_folder DROP CONSTRAINT IF EXISTS ck_storage_folder_target;
DROP INDEX IF EXISTS idx_storage_folder_config;
DROP INDEX IF EXISTS idx_storage_folder_group;
ALTER TABLE sys_storage_folder
  DROP COLUMN IF EXISTS target_mode,
  DROP COLUMN IF EXISTS storage_config_id,
  DROP COLUMN IF EXISTS storage_group_id;

-- ============================================================
-- 6. 上传操作/文件：删除组上下文列
-- ============================================================
ALTER TABLE sys_storage_upload_operation DROP CONSTRAINT IF EXISTS ck_storage_upload_operation_target;
ALTER TABLE sys_storage_upload_operation
  DROP COLUMN IF EXISTS target_mode,
  DROP COLUMN IF EXISTS group_id,
  DROP COLUMN IF EXISTS group_version;

ALTER TABLE sys_storage_file DROP COLUMN IF EXISTS storage_group_id;

-- ============================================================
-- 7. 删除存储组表与号段
-- ============================================================
DROP TABLE IF EXISTS sys_storage_group_member;
DROP TABLE IF EXISTS sys_storage_group;
DELETE FROM sequence_segment WHERE biz_key IN ('sys_storage_group_id', 'sys_storage_group_member_id');
DELETE FROM sequence_config WHERE biz_key IN ('sys_storage_group_id', 'sys_storage_group_member_id');

-- ============================================================
-- 8. 新表主键号段（依据 AGENTS.md：业务键 = 表名_id）
-- ============================================================
INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
VALUES ('sys_storage_folder_config_id', 1, 1000, 1000, 1000)
ON CONFLICT (biz_key) DO NOTHING;
INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
VALUES ('sys_storage_permission_config_id', 1, 1000, 1000, 1000)
ON CONFLICT (biz_key) DO NOTHING;

-- ============================================================
-- 9. 数据字典：三态/组策略下线，新增绑定调度策略
-- ============================================================
-- 9a. 旧字典数据与类型
DELETE FROM sys_dict_data WHERE dict_type_id IN (
  SELECT id FROM sys_dict_type WHERE dict_code IN ('storage_target_mode', 'storage_group_strategy'));
DELETE FROM sys_dict_type WHERE dict_code IN ('storage_target_mode', 'storage_group_strategy');

-- 9b. 新字典类型 storage_target_strategy
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('storage_target_strategy', '存储绑定调度策略', 'FAILOVER 按优先级故障转移 / ROUND_ROBIN 轮询')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

-- 9c. 新字典数据
WITH seed(dict_code, dict_value, dict_label, dict_sort) AS (
    VALUES ('storage_target_strategy', 'FAILOVER', '按优先级', 1),
           ('storage_target_strategy', 'ROUND_ROBIN', '轮询', 2)
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = s.dict_code
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_code, dict_sort),
       type_id, dict_label, dict_value, dict_sort, 1
FROM missing;

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
