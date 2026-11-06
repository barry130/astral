-- ============================================================
-- 第三方 HTTP 图床渠道 + 存储组 + 两步上传操作（设计 v1.1 第 5、6 节）
--
-- 1) sys_storage_config 增加 config_version：CUSTOM_HTTP 渠道每次修改配置/凭据递增，
--    上传意图按首次快照固定版本，未发送的旧意图在版本不一致时拒绝执行。
-- 2) sys_storage_folder 增加 target_mode/storage_group_id/target_version：
--    CONFIG（指定渠道）/ GROUP（存储组）/ INHERIT（跟随平台默认）三态互斥，
--    storage_config_id 放开 NOT NULL 以支持 GROUP/INHERIT。
-- 3) 新建 sys_storage_group / sys_storage_group_member：组只承担路由，不改变已登记文件归属。
-- 4) 新建 sys_storage_upload_operation：两步上传的持久状态机与配额预留。
-- 5) sys_storage_file 增加 upload_operation_id（唯一）/ storage_group_id。
--
-- 幂等：所有 DDL 均带 IF NOT EXISTS / DROP CONSTRAINT IF EXISTS；
--       字典与号段沿用 V20261102004 的 MAX(id)+ROW_NUMBER() 与 NOT EXISTS 守卫写法。
-- ============================================================

-- ============================================================
-- 1. 存储渠道：配置版本
-- ============================================================
ALTER TABLE sys_storage_config
  ADD COLUMN IF NOT EXISTS config_version BIGINT NOT NULL DEFAULT 1;

-- ============================================================
-- 2. 存储组
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_group (
  id                  BIGINT       PRIMARY KEY,
  group_code          VARCHAR(64)  NOT NULL,
  group_name          VARCHAR(128) NOT NULL,
  strategy            VARCHAR(32)  NOT NULL DEFAULT 'FAILOVER',
  status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
  is_default          SMALLINT     NOT NULL DEFAULT 0,
  max_attempts        SMALLINT     NOT NULL DEFAULT 2,
  version             BIGINT       NOT NULL DEFAULT 1,
  remark              VARCHAR(512),
  create_by           VARCHAR(64),
  create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by           VARCHAR(64),
  update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_storage_group_code UNIQUE (group_code),
  CONSTRAINT ck_storage_group_strategy CHECK (strategy IN ('FAILOVER', 'ROUND_ROBIN')),
  CONSTRAINT ck_storage_group_status CHECK (status IN ('ENABLED', 'DISABLED')),
  CONSTRAINT ck_storage_group_attempts CHECK (max_attempts BETWEEN 1 AND 2),
  CONSTRAINT ck_storage_group_default CHECK (is_default IN (0, 1))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_storage_group_default
  ON sys_storage_group (is_default) WHERE is_default = 1;

-- ============================================================
-- 3. 存储组成员
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_group_member (
  id                  BIGINT      PRIMARY KEY,
  group_id            BIGINT      NOT NULL,
  storage_config_id   BIGINT      NOT NULL,
  priority            INTEGER     NOT NULL DEFAULT 100,
  enabled             SMALLINT    NOT NULL DEFAULT 1,
  create_time         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_storage_group_member UNIQUE (group_id, storage_config_id),
  CONSTRAINT ck_storage_group_member_priority CHECK (priority BETWEEN 0 AND 10000),
  CONSTRAINT ck_storage_group_member_enabled CHECK (enabled IN (0, 1))
);
CREATE INDEX IF NOT EXISTS idx_storage_group_member_group
  ON sys_storage_group_member (group_id, priority, id);
CREATE INDEX IF NOT EXISTS idx_storage_group_member_config
  ON sys_storage_group_member (storage_config_id);

-- ============================================================
-- 4. 上传操作（两步协议状态机 + 配额预留）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_upload_operation (
  id                     BIGINT       PRIMARY KEY,
  upload_id              VARCHAR(64)  NOT NULL,
  public_id              VARCHAR(64)  NOT NULL,
  actor_type             VARCHAR(16)  NOT NULL,
  actor_id               VARCHAR(64)  NOT NULL,
  idempotency_key_hash   CHAR(64)     NOT NULL,
  intent_fingerprint     CHAR(64)     NOT NULL,
  original_name          VARCHAR(255) NOT NULL,
  expected_mime          VARCHAR(64)  NOT NULL,
  expected_size          BIGINT       NOT NULL,
  expected_sha256        CHAR(64)     NOT NULL,
  folder_id              BIGINT       NOT NULL,
  folder_version         BIGINT       NOT NULL,
  target_mode            VARCHAR(16)  NOT NULL,
  group_id               BIGINT,
  group_version          BIGINT,
  route_snapshot_json    TEXT,
  chosen_config_id       BIGINT,
  chosen_config_version  BIGINT,
  chosen_preset_version  INTEGER,
  state                  VARCHAR(32)  NOT NULL DEFAULT 'CREATED',
  version                BIGINT       NOT NULL DEFAULT 1,
  lease_token            VARCHAR(64),
  lease_until            TIMESTAMP,
  send_deadline          TIMESTAMP,
  intent_expires_at      TIMESTAMP    NOT NULL,
  quota_day              DATE,
  quota_charge           VARCHAR(16)  NOT NULL DEFAULT 'NONE',
  quota_exempt           SMALLINT     NOT NULL DEFAULT 0,
  actual_size            BIGINT,
  actual_sha256          CHAR(64),
  attempts_json          TEXT,
  safe_result_json       TEXT,
  failure_code           VARCHAR(64),
  file_id                BIGINT,
  purpose                VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD',
  audit_pending          SMALLINT     NOT NULL DEFAULT 0,
  audit_event_key        VARCHAR(128),
  create_time            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at           TIMESTAMP,
  CONSTRAINT uk_storage_upload_operation_upload UNIQUE (upload_id),
  CONSTRAINT uk_storage_upload_operation_public UNIQUE (public_id),
  CONSTRAINT uk_storage_upload_operation_idempotent
    UNIQUE (actor_type, actor_id, idempotency_key_hash),
  CONSTRAINT ck_storage_upload_operation_state CHECK (state IN (
    'CREATED', 'RECEIVING', 'SENDING', 'REMOTE_SUCCEEDED', 'COMMITTED',
    'FAILED', 'EXPIRED', 'UNKNOWN_OUTCOME')),
  CONSTRAINT ck_storage_upload_operation_charge CHECK (quota_charge IN (
    'NONE', 'HELD', 'CONSUMED', 'REFUNDED')),
  CONSTRAINT ck_storage_upload_operation_exempt CHECK (quota_exempt IN (0, 1)),
  CONSTRAINT ck_storage_upload_operation_target CHECK (target_mode IN ('CONFIG', 'GROUP', 'INHERIT')),
  CONSTRAINT ck_storage_upload_operation_purpose CHECK (purpose IN ('UPLOAD', 'PROBE'))
);
CREATE INDEX IF NOT EXISTS idx_storage_upload_operation_recover
  ON sys_storage_upload_operation (state, lease_until);
CREATE INDEX IF NOT EXISTS idx_storage_upload_operation_quota
  ON sys_storage_upload_operation (folder_id, actor_id, quota_day, quota_charge);
CREATE INDEX IF NOT EXISTS idx_storage_upload_operation_file
  ON sys_storage_upload_operation (file_id);

-- ============================================================
-- 5. 存储文件夹：上传目标三态
-- ============================================================
ALTER TABLE sys_storage_folder
  ADD COLUMN IF NOT EXISTS target_mode VARCHAR(16) NOT NULL DEFAULT 'CONFIG',
  ADD COLUMN IF NOT EXISTS storage_group_id BIGINT,
  ADD COLUMN IF NOT EXISTS target_version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE sys_storage_folder ALTER COLUMN storage_config_id DROP NOT NULL;

UPDATE sys_storage_folder SET target_mode = 'CONFIG' WHERE target_mode IS NULL;

ALTER TABLE sys_storage_folder DROP CONSTRAINT IF EXISTS ck_storage_folder_target;
ALTER TABLE sys_storage_folder ADD CONSTRAINT ck_storage_folder_target CHECK (
  (target_mode = 'CONFIG' AND storage_config_id IS NOT NULL AND storage_group_id IS NULL)
  OR (target_mode = 'GROUP' AND storage_config_id IS NULL AND storage_group_id IS NOT NULL)
  OR (target_mode = 'INHERIT' AND storage_config_id IS NULL AND storage_group_id IS NULL)
);
CREATE INDEX IF NOT EXISTS idx_storage_folder_group ON sys_storage_folder (storage_group_id);

-- ============================================================
-- 6. 存储文件：操作关联与组上下文
-- ============================================================
ALTER TABLE sys_storage_file
  ADD COLUMN IF NOT EXISTS upload_operation_id BIGINT,
  ADD COLUMN IF NOT EXISTS storage_group_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_storage_file_operation
  ON sys_storage_file (upload_operation_id);

-- ============================================================
-- 7. 主键号段（依据 AGENTS.md：业务键 = 表名_id）
-- ============================================================
INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
VALUES ('sys_storage_group_id', 1, 1000, 1000, 1000)
ON CONFLICT (biz_key) DO NOTHING;
INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
VALUES ('sys_storage_group_member_id', 1, 1000, 1000, 1000)
ON CONFLICT (biz_key) DO NOTHING;
INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
VALUES ('sys_storage_upload_operation_id', 1, 1000, 1000, 1000)
ON CONFLICT (biz_key) DO NOTHING;

-- ============================================================
-- 8. 数据字典（AGENTS.md §3：前端可见枚举必须走字典）
-- ============================================================
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('storage_target_mode', '存储目标模式', 'CONFIG 指定渠道 / GROUP 使用存储组 / INHERIT 跟随平台默认'),
           ('storage_group_strategy', '存储组策略', 'FAILOVER 按优先级故障转移 / ROUND_ROBIN 组内轮询'),
           ('storage_upload_state', '上传操作状态', '两步上传操作状态机'),
           ('storage_http_auth_mode', 'HTTP 渠道认证方式', 'NONE 无认证 / HEADER 请求头 / QUERY 查询参数 / FORM 表单字段')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

WITH seed(dict_code, dict_value, dict_label, dict_sort) AS (
    VALUES ('storage_target_mode', 'CONFIG', '指定渠道', 1),
           ('storage_target_mode', 'GROUP', '存储组', 2),
           ('storage_target_mode', 'INHERIT', '跟随平台默认', 3),

           ('storage_group_strategy', 'FAILOVER', '故障转移', 1),
           ('storage_group_strategy', 'ROUND_ROBIN', '轮询', 2),

           ('storage_upload_state', 'CREATED', '已创建', 1),
           ('storage_upload_state', 'RECEIVING', '接收中', 2),
           ('storage_upload_state', 'SENDING', '发送中', 3),
           ('storage_upload_state', 'REMOTE_SUCCEEDED', '远端已成功', 4),
           ('storage_upload_state', 'COMMITTED', '已登记', 5),
           ('storage_upload_state', 'FAILED', '失败', 6),
           ('storage_upload_state', 'EXPIRED', '已过期', 7),
           ('storage_upload_state', 'UNKNOWN_OUTCOME', '结果未知', 8),

           ('storage_http_auth_mode', 'NONE', '无认证', 1),
           ('storage_http_auth_mode', 'HEADER', '请求头', 2),
           ('storage_http_auth_mode', 'QUERY', '查询参数', 3),
           ('storage_http_auth_mode', 'FORM', '表单字段', 4)
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = s.dict_code
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_code, dict_sort),
       type_id, dict_label, dict_value, dict_sort, 1 FROM missing;

-- CUSTOM_HTTP 登记为可选存储类型（前端下拉/标签统一走 storage_provider_type）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + 1, id, '第三方 HTTP 接口', 'CUSTOM_HTTP', 90, 1,
       '服务器直连第三方图床上传接口（仅公网可读）'
FROM sys_dict_type t
WHERE t.dict_code = 'storage_provider_type'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = 'CUSTOM_HTTP');

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
