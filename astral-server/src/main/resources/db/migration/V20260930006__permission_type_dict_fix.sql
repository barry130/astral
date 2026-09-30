-- ============================================================================
-- V20260930006 修复：permission_type 字典实际未落库
--
-- 背景（这是一个「静默失败」，很隐蔽，必须记下来）：
--   V20260930002 意图登记权限类型字典，但**硬编码了 id=911**，而 911 早已被
--   V20260914006 的 `qt_source_report_result` 占用（数据行 9110/9111 同理被
--   `ok` / `smoke_failed` 占用）。于是：
--     1) `INSERT INTO sys_dict_type (...) VALUES (911, 'permission_type', ...)
--        ON CONFLICT DO NOTHING` 撞主键 → **静默什么都不做**，且 Flyway 仍记为
--        success（ON CONFLICT 不报错，所以历史表看起来一切正常）；
--     2) 随后的 5 条 sys_dict_data 用
--        `SELECT ... FROM sys_dict_type WHERE dict_code='permission_type'`
--        取 dict_type_id —— 类型行根本没进库，子查询零行 → 5 条也全部落空。
--   结果：前端 `permission_type` 字典查不到数据，权限管理页「类型」列回退成
--   裸码值 1/2/3/4/5，码值映射失效。
--
-- 教训：**字典/权限种子不要硬编码主键 id**。存量库与全新库都会撞号，
--       而且撞了不报错、只是没有数据。本脚本改为按 MAX(id)+1 取号。
--
-- 幂等：类型行按 dict_code 守卫；数据行按 (dict_type_id, dict_value) 守卫，
--       可重复执行。
-- 注意：V20260930002 已应用（checksum 已登记），**不得修改**，否则
--       Flyway 校验和不一致会导致启动失败；因此修复必须走本脚本。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- A. 字典类型：permission_type（动态取号，避免再撞 911/912 这类既有号）
-- ---------------------------------------------------------------------------
WITH id_base AS (
    SELECT COALESCE(MAX(id), 0) + 1 AS new_id FROM sys_dict_type
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
SELECT ib.new_id,
       'permission_type',
       '权限类型',
       'Integer',
       'TINYINT',
       2,
       'sys_permission.type 取值：1-目录 2-菜单 3-按钮 4-接口 5-数据',
       1
FROM id_base ib
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = 'permission_type');

-- ---------------------------------------------------------------------------
-- B. 字典数据：5 个码值 → 文案（与 PermissionType 常量一一对应）
--    1-3 属前端菜单树；4/5 属后端权限（4=接口可达性，5=结果级权限可见集合）
-- ---------------------------------------------------------------------------
WITH target_type AS (
    SELECT id FROM sys_dict_type WHERE dict_code = 'permission_type'
),
src(dict_label, dict_value, dict_sort, description) AS (
    VALUES
        ('目录', '1', 1, '前端菜单树节点（含子菜单）'),
        ('菜单', '2', 2, '前端路由页面，对应 sys_menu'),
        ('按钮', '3', 3, '页面内操作按钮/子功能'),
        ('接口', '4', 4, '后端 API 可达性（@RequiresPermission 自动登记）'),
        ('数据', '5', 5, '结果级权限：解析为可见集合（如 beta 渠道资格），同一接口按人群返回不同结果')
),
id_base AS (
    SELECT COALESCE(MAX(id), 0) AS max_id FROM sys_dict_data
),
to_insert AS (
    SELECT s.dict_label,
           s.dict_value,
           s.dict_sort,
           s.description,
           ib.max_id + ROW_NUMBER() OVER (ORDER BY s.dict_sort) AS new_id
    FROM src s
    CROSS JOIN id_base ib
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT ti.new_id, tt.id, ti.dict_label, ti.dict_value, ti.dict_sort, 1, ti.description
FROM to_insert ti
CROSS JOIN target_type tt
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type_id = tt.id AND d.dict_value = ti.dict_value
);

-- ---------------------------------------------------------------------------
-- C. 结果核对（应输出 5 行：1..5 全部有中文文案）
-- ---------------------------------------------------------------------------
SELECT t.dict_code, t.dict_name, d.dict_value, d.dict_label, d.dict_sort, d.status
FROM sys_dict_type t
JOIN sys_dict_data d ON d.dict_type_id = t.id
WHERE t.dict_code = 'permission_type'
ORDER BY d.dict_sort;
