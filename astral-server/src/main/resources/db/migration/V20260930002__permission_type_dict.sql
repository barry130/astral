-- ============================================================
-- 迁移版本：V20260930002
-- 说明：权限类型登记进数据字典，供「权限管理」页类型列/下拉动态拉取
--       （项目规范：进入前端展示的枚举型字段必须走数据字典，禁止前后端硬编码文案）。
--
--       类型语义（sys_permission.type，见 PermissionType）：
--         1-目录 / 2-菜单 / 3-按钮   —— 前端菜单树类型，参与导航渲染
--         4-接口                     —— 后端 API 可达性
--         5-数据                     —— 结果级权限：同一接口对不同人群返回不同结果
--       （此前前端把 1/2/3 渲染成「菜单/按钮/接口」，与后端语义不一致，本次一并对齐）
--
-- 幂等：类型行 ON CONFLICT DO NOTHING；数据行按 (dict_type_id, dict_value) 守卫。
-- id 段约定：沿用 V20260914003/005 的递增约定——类型 911、数据项 9110~9114。
-- ============================================================

INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (911, 'permission_type', '权限类型', 'Integer', 'TINYINT', 2, 'sys_permission.type 取值：1-目录 2-菜单 3-按钮 4-接口 5-数据', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9110, id, '目录', '1', 1, 1, '前端菜单树节点（含子菜单）'
FROM sys_dict_type WHERE dict_code = 'permission_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '1');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9111, id, '菜单', '2', 2, 1, '前端路由页面，对应 sys_menu'
FROM sys_dict_type WHERE dict_code = 'permission_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '2');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9112, id, '按钮', '3', 3, 1, '页面内操作按钮/子功能'
FROM sys_dict_type WHERE dict_code = 'permission_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '3');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9113, id, '接口', '4', 4, 1, '后端 API 可达性（@RequiresPermission 自动登记）'
FROM sys_dict_type WHERE dict_code = 'permission_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '4');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9114, id, '数据', '5', 5, 1, '结果级权限：解析为可见集合（如 beta 渠道资格），同一接口按人群返回不同结果'
FROM sys_dict_type WHERE dict_code = 'permission_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '5');
