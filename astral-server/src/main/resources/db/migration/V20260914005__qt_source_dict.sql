-- ============================================================
-- 迁移版本：V20260914005
-- 说明：音源包热更新（SOURCE_UPDATE_DESIGN）的产物路径登记进数据字典，
--       前端「音源包」管理页 artifacts 编辑行的 path 下拉按此动态拉取。
--       当前发布物只有 chain.json / source-bundle.js 两个文件（§二），
--       后续新增产物类型在字典管理里加数据项即可，无需改前端。
-- 幂等：类型行按 dict_code 唯一（ON CONFLICT DO NOTHING）；
--       数据行无业务唯一约束，用 WHERE NOT EXISTS 守卫，可重复执行。
-- id 段约定：沿用 V20260914003 的递增约定——类型 910、数据项 9100~9101
--       （预留段 [1, 1,000,000] 内，与运行时发号 1,000,001 起不冲突）。
-- ============================================================

INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (910, 'qt_source_artifact_path', '音源包产物路径', 'String', 'VARCHAR', 128, '音源包 artifacts 的 path 取值', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9100, id, 'chain.json', 'chain.json', 1, 1, '编排文件：线路表/顺序/档位/预算（KB 级，高频变更）'
FROM sys_dict_type WHERE dict_code = 'qt_source_artifact_path'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'chain.json');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9101, id, 'source-bundle.js', 'source-bundle.js', 2, 1, '实现文件：脚本原文/官方接口/LX 宿主（~2 MB，低频变更）'
FROM sys_dict_type WHERE dict_code = 'qt_source_artifact_path'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'source-bundle.js');
