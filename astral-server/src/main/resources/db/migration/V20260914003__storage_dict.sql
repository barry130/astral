-- ============================================================
-- 迁移版本：V3（原手工 V5__storage_dict.sql，随 Flyway 引入重新编号）
-- 说明：文件存储插件（storage）的枚举值登记进数据字典（AGENTS.md §3）。
--       覆盖 Provider 类型、配置/文件夹状态、文件状态、任务状态、可见性、
--       文件夹权限集合、审计结果共 7 个字典类型；前端 storage 页面改为按
--       dict_code 动态拉取选项，不再硬编码枚举值。
-- 应用方式：见同目录 README.md「三、后续增量变更流程」。
-- 幂等：类型行按 dict_code 唯一（ON CONFLICT DO NOTHING）；
--       数据行无业务唯一约束，用 WHERE NOT EXISTS 守卫，可重复执行。
-- ============================================================

-- [!] id 段约定：本脚本显式 id 使用 900~906（类型）与 9001~9028（数据项）。
--     必须落在全局序列的预留段 [1, 1,000,000] 内（AGENTS.md「实体ID全局序列」）：
--     运行时发号从 1,000,001 起，种子显式 id 只要不超过 1,000,000 就永不与
--     运行时发号冲突。段位避开 V2 基线已用的 1~88（类型）/1~142（数据项）。
--     后续字典迁移脚本沿用此约定递增（如 V6 类型从 910、数据从 9100 起）。

-- ============================================
-- 字典类型（7 个）
-- ============================================
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (900, 'storage_provider_type', '存储类型', 'String', 'VARCHAR', 32, '文件存储 Provider 类型', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (901, 'storage_status', '存储状态', 'String', 'VARCHAR', 16, '存储配置/文件夹状态', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (902, 'storage_file_status', '文件状态', 'String', 'VARCHAR', 24, '存储文件生命周期状态', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (903, 'storage_task_status', '任务状态', 'String', 'VARCHAR', 16, '存储任务状态', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (904, 'storage_visibility', '可见性', 'String', 'VARCHAR', 16, '存储文件夹/文件可见性', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (905, 'storage_permission', '存储权限', 'String', 'VARCHAR', 16, '文件夹授权权限集合成员', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (906, 'storage_audit_result', '审计结果', 'String', 'VARCHAR', 16, '存储审计结果', 1)
ON CONFLICT DO NOTHING;

-- ============================================
-- 字典数据项
-- ============================================

-- storage_provider_type（与 StorageConfigEntity.PROVIDER_* 常量一一对应）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9001, id, 'Telegram', 'TELEGRAM', 1, 1, 'Telegram 频道经 Cloudflare Worker 中转'
FROM sys_dict_type WHERE dict_code = 'storage_provider_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'TELEGRAM');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9002, id, 'Cloudflare R2', 'R2', 2, 1, 'R2 对象存储，预签名 PUT 直传'
FROM sys_dict_type WHERE dict_code = 'storage_provider_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'R2');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9003, id, 'S3 兼容存储', 'S3_COMPATIBLE', 3, 1, 'AWS/MinIO 等通用 S3，预签名 PUT 直传'
FROM sys_dict_type WHERE dict_code = 'storage_provider_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'S3_COMPATIBLE');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9004, id, '七牛云', 'QINIU', 4, 1, '七牛 S3 网关，预签名 PUT 直传'
FROM sys_dict_type WHERE dict_code = 'storage_provider_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'QINIU');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9005, id, '腾讯云 COS', 'COS', 5, 1, '腾讯云 COS，预签名 PUT 直传'
FROM sys_dict_type WHERE dict_code = 'storage_provider_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'COS');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9006, id, '阿里云 OSS', 'OSS', 6, 1, '阿里云 OSS，预签名 PUT 直传'
FROM sys_dict_type WHERE dict_code = 'storage_provider_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'OSS');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9007, id, '又拍云', 'UPYUN', 7, 1, '又拍云表单 API 直传'
FROM sys_dict_type WHERE dict_code = 'storage_provider_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'UPYUN');

-- storage_status（配置/文件夹共用）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9008, id, '启用', 'ENABLED', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'ENABLED');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9009, id, '停用', 'DISABLED', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'DISABLED');

-- storage_file_status（与 StorageFileEntity.STATUS_* 常量一一对应）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9010, id, '可用', 'AVAILABLE', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_file_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'AVAILABLE');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9011, id, '删除中', 'DELETING', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_file_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'DELETING');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9012, id, '删除失败', 'DELETE_FAILED', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_file_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'DELETE_FAILED');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9013, id, '已删除', 'DELETED', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_file_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'DELETED');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9014, id, '疑似孤儿对象', 'ORPHAN_POSSIBLE', 5, 1, '远端对象可能未被清理'
FROM sys_dict_type WHERE dict_code = 'storage_file_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'ORPHAN_POSSIBLE');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9015, id, '失败', 'FAILED', 6, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_file_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'FAILED');

-- storage_task_status（与 StorageTaskEntity.STATUS_* 常量一一对应）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9016, id, '待执行', 'PENDING', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_task_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'PENDING');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9017, id, '执行中', 'RUNNING', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_task_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'RUNNING');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9018, id, '已完成', 'DONE', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_task_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'DONE');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9019, id, '失败', 'FAILED', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_task_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'FAILED');

-- storage_visibility（与 StorageFolderEntity/StorageFileEntity.VISIBILITY_* 常量一一对应）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9020, id, '公开', 'PUBLIC', 1, 1, '获授权者与持有链接者可见'
FROM sys_dict_type WHERE dict_code = 'storage_visibility'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'PUBLIC');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9021, id, '私有', 'PRIVATE', 2, 1, '仅属主与获授权者可见'
FROM sys_dict_type WHERE dict_code = 'storage_visibility'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'PRIVATE');

-- storage_permission（与 StorageFolderPermissionEntity 权限集合成员一一对应）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9022, id, '读取', 'READ', 1, 1, '浏览与下载'
FROM sys_dict_type WHERE dict_code = 'storage_permission'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'READ');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9023, id, '上传', 'UPLOAD', 2, 1, '向文件夹上传文件'
FROM sys_dict_type WHERE dict_code = 'storage_permission'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'UPLOAD');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9024, id, '更新', 'UPDATE', 3, 1, '修改文件夹与文件元数据'
FROM sys_dict_type WHERE dict_code = 'storage_permission'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'UPDATE');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9025, id, '删除', 'DELETE', 4, 1, '删除文件与文件夹'
FROM sys_dict_type WHERE dict_code = 'storage_permission'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'DELETE');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9026, id, '管理', 'MANAGE', 5, 1, '管理文件夹授权'
FROM sys_dict_type WHERE dict_code = 'storage_permission'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'MANAGE');

-- storage_audit_result
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9027, id, '成功', 'OK', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_audit_result'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'OK');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9028, id, '失败', 'FAIL', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'storage_audit_result'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'FAIL');
