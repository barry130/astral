-- ============================================================================
-- V20261001003 登记菜单类型字典 menu_type
--
-- 背景：`sys_menu.type` 的取值一直是前端硬编码的
--   astral-front/src/app/dashboard/system/menu/page.tsx 里写死
--     options=[{value:0,label:'目录'},{value:1,label:'菜单'},{value:2,label:'按钮'}]
-- 违反 AGENTS.md 约束 3（进入前端展示的枚举必须走数据字典）。
--
-- ⚠️ 极易混淆：`sys_menu.type` 与 `sys_permission.type` 是**两套不同的枚举，且整体错开一位**：
--     sys_menu.type       ：0-目录  1-菜单  2-按钮          （3 个值，从 0 开始）
--     sys_permission.type ：1-目录  2-菜单  3-按钮  4-接口  5-数据（5 个值，从 1 开始）
--   同一棵菜单树的两个表，同一个语义「目录」一个是 0 一个是 1。
--   复用 permission_type 字典会把「目录」渲染错（0 会查不到 → 回退裸码值 0）。
--   因此这里必须是独立的字典编码 menu_type，不要合并。
--
-- 幂等：类型行按 dict_code 守卫；数据行按 (dict_type_id, dict_value) 守卫。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- A. 字典类型：menu_type（动态取号，禁止硬编码 id）
-- ---------------------------------------------------------------------------
WITH id_base AS (
    SELECT COALESCE(MAX(id), 0) + 1 AS new_id FROM sys_dict_type
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
SELECT ib.new_id,
       'menu_type',
       '菜单类型',
       'Integer',
       'TINYINT',
       2,
       'sys_menu.type 取值：0-目录 1-菜单 2-按钮（注意与 permission_type 错开一位）',
       1
FROM id_base ib
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = 'menu_type');

-- ---------------------------------------------------------------------------
-- B. 字典数据：3 个码值 → 文案
--    dict_value 用字符串（与 permission_type 一致，前端 Select 的 value 是 number，
--    比对时统一 String() 归一即可）
-- ---------------------------------------------------------------------------
WITH target_type AS (
    SELECT id FROM sys_dict_type WHERE dict_code = 'menu_type'
),
src(dict_label, dict_value, dict_sort, description) AS (
    VALUES
        ('目录', '0', 1, '一级分组节点，通常只作为父级，自身不承载路由'),
        ('菜单', '1', 2, '可点击跳转的页面，对应一条前端路由'),
        ('按钮', '2', 3, '页面内操作点，用于挂更细的按钮级权限')
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
-- C. 结果核对（应输出 3 行：目录/菜单/按钮）
-- ---------------------------------------------------------------------------
SELECT t.dict_code, t.dict_name, d.dict_value, d.dict_label, d.dict_sort, d.status
FROM sys_dict_type t
JOIN sys_dict_data d ON d.dict_type_id = t.id
WHERE t.dict_code = 'menu_type'
ORDER BY d.dict_sort;