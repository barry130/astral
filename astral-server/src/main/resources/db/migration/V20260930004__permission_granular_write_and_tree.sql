-- ============================================================================
-- V20260930004 权限配置收口（一）：细粒度写权限码 + 权限树层级 + 类型修正 + 菜单接线
--
-- 背景：V20260930001~003 完成了权限模型改造（domain / is_super / 结果级权限 / 新码播种），
--       但仍有以下收口项未完成，本脚本一并处理：
--         1) system:menu:view 的 type=0 是非法值（合法 1~5），前端按字典渲染会显示异常；
--         2) 用户/角色/权限/菜单/Token 的写接口此前一律 @RequiresSuper，
--            缺少 system:user:edit / system:role:edit / system:permission:edit /
--            system:menu:edit / system:token:edit / system:schema:edit 这些细粒度码，
--            导致无法委派「只能管用户、不能碰角色」这类角色。本脚本补齐其中的可委派码
--            （system:user:edit / system:menu:edit / system:token:edit），
--            角色/权限定义类写操作仍由 @RequiresSuper 承担，故意不建码。
--         3) sys_permission 几乎全是 parent_id=0 的平铺结构（33 条里 30 条），
--            权限管理页的默认树（不传 groupBy）无法阅读。本脚本把
--            「动作/编辑类权限」挂到同资源的 :view 权限下，形成两三层语义树。
--         4) sys_menu 的「仪表盘」行 permission 为空，但其接口
--            /api/v1/admin/monitor/dashboard 要求 monitor:view，
--            导致非超管角色能看到菜单却打不开页面（500/403）。本脚本接线为 monitor:view。
--
-- 幂等性：全部使用 NOT EXISTS / WHERE 条件保护，重复执行不产生重复行、不报唯一键冲突。
-- ID 分配：沿用 V20260930003 的 base+numbered CTE 模式，取当前 max(id)+row_number。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 修正非法类型值：菜单管理是「菜单」类权限，应为 type=2
--    （type=1 目录 / 2 菜单 / 3 按钮 / 4 接口 / 5 数据）
-- ---------------------------------------------------------------------------
UPDATE sys_permission
SET type = 2
WHERE permission_code = 'system:menu:view'
  AND (type IS NULL OR type NOT IN (1, 2, 3, 4, 5));

-- ---------------------------------------------------------------------------
-- 2. 补齐可委派的细粒度写权限码
--    只给「不构成提权」的写操作建码；提权类写操作仍由 @RequiresSuper 承担，
--    因此这里不建 system:permission:edit / system:schema:edit：
--      - 权限定义增删改 = 可自造 `*:*:*` 权限行，属提权面；
--      - 表结构写操作会落盘 schema JSON 并生成 Java 源码，属代码执行面。
--    对应地，RoleController.update 已显式丢弃客户端传入的 is_super，
--    RoleController.create 强制 is_super=0，否则 system:role:edit 可自造超管。
-- ---------------------------------------------------------------------------
WITH base AS (
    SELECT COALESCE(MAX(id), 0) AS max_id FROM sys_permission
),
seed(permission_code, permission_name, type, domain, sort) AS (
    VALUES
        ('system:user:edit', '用户资料维护', 4, 'system', 103),
        ('system:menu:edit', '菜单维护',     4, 'system', 104),
        ('system:token:edit', 'Token吊销',   4, 'system', 105),
        ('system:role:edit', '角色维护',     4, 'system', 106)
),
numbered AS (
    SELECT permission_code, permission_name, type, domain, sort,
           ROW_NUMBER() OVER (ORDER BY sort) AS rn
    FROM seed
)
INSERT INTO sys_permission (id, parent_id, permission_code, permission_name, url, method, type, domain, sort, status)
SELECT b.max_id + n.rn, 0, n.permission_code, n.permission_name, NULL, NULL, n.type, n.domain, n.sort, 1
FROM numbered n
CROSS JOIN base b
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission p WHERE p.permission_code = n.permission_code
);

-- ---------------------------------------------------------------------------
-- 3. 权限树层级：把动作/编辑类权限挂到同资源的 :view 权限下
--    规则：子权限 parent_id := 同资源 :view 权限的 id。
--    只处理「父必须存在」的组合；父不存在时保持 parent_id=0（顶级），不做悬空挂载。
--    说明：角色授权界面走 groupBy=domain（按权限域分组），不依赖本层级；
--          本层级服务「权限管理」页的默认树与语义阅读。
-- ---------------------------------------------------------------------------
WITH mapping(child_code, parent_code) AS (
    VALUES
        -- 系统域
        ('system:dict:edit',              'system:dict:view'),
        ('system:config:edit',            'system:config:view'),
        ('system:user:edit',              'system:user:view'),
        ('system:menu:edit',              'system:menu:view'),
        ('system:token:edit',             'system:token:view'),
        ('system:role:edit',              'system:role:view'),
        ('system:mail:statistics:view',   'system:mail:view'),
        ('system:mail:account:edit',      'system:mail:view'),
        ('system:mail:template:edit',     'system:mail:view'),
        ('system:mail:plugin-auth:edit',  'system:mail:view'),
        -- 序列域
        ('sequence:generate',             'sequence:view'),
        ('sequence:batch',                'sequence:view'),
        ('sequence:edit',                 'sequence:view'),
        -- 插件域
        ('plugin:edit',                   'plugin:view'),
        -- 轻听域：结果级权限挂到轻听管理下
        ('qt:update:channel:beta',        'qt:admin'),
        ('qt:source:channel:beta',        'qt:admin'),
        -- 插件业务域
        ('feedback:edit',                 'feedback:view'),
        ('message:edit',                  'message:view'),
        ('storage:edit',                  'storage:view')
)
UPDATE sys_permission child
SET parent_id = parent.id
FROM mapping m
JOIN sys_permission parent ON parent.permission_code = m.parent_code
WHERE child.permission_code = m.child_code
  AND child.id <> parent.id
  AND child.parent_id IS DISTINCT FROM parent.id;

-- ---------------------------------------------------------------------------
-- 4. sys_menu 接线：「仪表盘」行绑定 monitor:view
--    其数据来自 /api/v1/admin/monitor/dashboard（@RequiresPermission("monitor:view")）。
--    此前 permission 为空 => 人人可见菜单，但非超管调用即 403，页面空白。
--    绑定后菜单可见性与接口可达性一致；基础角色由 V20260930005 授予 monitor:view。
-- ---------------------------------------------------------------------------
UPDATE sys_menu
SET permission = 'monitor:view'
WHERE path = '/dashboard'
  AND (permission IS NULL OR permission = '');

-- ---------------------------------------------------------------------------
-- 5. 结果核对
-- ---------------------------------------------------------------------------
SELECT '非法 type 行数(应为0)' AS 检查项, count(*)::text AS 值
FROM sys_permission WHERE type IS NULL OR type NOT IN (1, 2, 3, 4, 5)
UNION ALL
SELECT '新增细粒度写码数(应为4)', count(*)::text
FROM sys_permission WHERE permission_code IN ('system:user:edit', 'system:menu:edit', 'system:token:edit', 'system:role:edit')
UNION ALL
SELECT '已有层级子权限数', count(*)::text
FROM sys_permission WHERE parent_id <> 0
UNION ALL
SELECT '仪表盘菜单权限', COALESCE(permission, '(空)')
FROM sys_menu WHERE path = '/dashboard';
