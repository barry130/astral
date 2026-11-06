-- ============================================================
-- V20261106004 补种 CUSTOM_HTTP 渠道认证方式 BASIC（HTTP Basic）
--
-- 后端 HttpProfileValidator 已实现 BASIC 认证，前端表单分支亦已就绪，
-- 但 V20261105001 的 storage_http_auth_mode 字典未含 BASIC，导致该方式
-- 无法在下拉中选取（AGENTS.md §3：前端枚举必须走数据字典）。
-- ============================================================

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + 1, id, 'HTTP Basic', 'BASIC', 5, 1,
       'RFC 7617 Basic 认证（用户名/密码经 secret 引用注入）'
FROM sys_dict_type t
WHERE t.dict_code = 'storage_http_auth_mode'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = 'BASIC');

-- 字典类型描述同步补 BASIC 说明
UPDATE sys_dict_type
SET description = 'NONE 无认证 / HEADER 请求头 / QUERY 查询参数 / FORM 表单字段 / BASIC HTTP Basic'
WHERE dict_code = 'storage_http_auth_mode'
  AND description = 'NONE 无认证 / HEADER 请求头 / QUERY 查询参数 / FORM 表单字段';

-- 号段水位对齐（AGENTS.md §2：种子取号后抬 sequence_segment.max_value）
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
