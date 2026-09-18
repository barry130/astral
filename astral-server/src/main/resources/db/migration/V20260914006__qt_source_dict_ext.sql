-- ============================================================
-- 迁移版本：V20260914006
-- 说明：音源包热更新（SOURCE_UPDATE_DESIGN）第二批枚举登记数据字典：
--       ① qt_source_report_result —— 装载结果（装机分布统计弹窗展示）；
--       ② qt_source_release_state  —— 发布状态（列表标签展示，由 is_published/is_bad 派生）。
--       平台（1101/1102/1103）与渠道（stable/beta）复用已有字典
--       qt_update_platform / qt_update_channel（值集完全相同，避免重复维护）。
-- 幂等：类型行按 dict_code 唯一（ON CONFLICT DO NOTHING）；
--       数据行无业务唯一约束，用 WHERE NOT EXISTS 守卫，可重复执行。
-- id 段约定：沿用递增约定——类型 911~912、数据项 9110~9122
--       （预留段 [1, 1,000,000] 内，与运行时发号 1,000,001 起不冲突）。
-- ============================================================

-- 字典类型: qt_source_report_result（音源包装载结果）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (911, 'qt_source_report_result', '音源包装载结果', 'String', 'VARCHAR', 32, '客户端装载远程音源包后的上报结果', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9110, id, '装载成功', 'ok', 1, 1, '音源包加载并通过冒烟自检'
FROM sys_dict_type WHERE dict_code = 'qt_source_report_result'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'ok');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9111, id, '冒烟失败', 'smoke_failed', 2, 1, '冒烟自检未通过，客户端自动回退并拉黑该版本'
FROM sys_dict_type WHERE dict_code = 'qt_source_report_result'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'smoke_failed');

-- 字典类型: qt_source_release_state（音源包发布状态，由 is_published/is_bad 派生）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (912, 'qt_source_release_state', '音源包发布状态', 'String', 'VARCHAR', 16, '音源包列表状态标签(派生值:bad > published > unpublished)', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9120, id, '未发布', 'unpublished', 1, 1, '仅本地草稿，客户端拉不到'
FROM sys_dict_type WHERE dict_code = 'qt_source_release_state'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'unpublished');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9121, id, '已发布', 'published', 2, 1, '客户端可拉到该版本'
FROM sys_dict_type WHERE dict_code = 'qt_source_release_state'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'published');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9122, id, '坏包', 'bad', 3, 1, '已标记坏包，客户端回退并拉黑该版本'
FROM sys_dict_type WHERE dict_code = 'qt_source_release_state'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'bad');
