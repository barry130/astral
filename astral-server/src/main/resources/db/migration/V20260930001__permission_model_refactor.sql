-- ============================================================
-- 迁移版本：V20260930001
-- 说明：权限体系重构（数据层部分）
--
-- 1) sys_role 新增 is_super：超管从「持有一条 *:*:* 权限记录」改为「角色标记」。
--    语义等价（持 is_super=1 的启用角色即拥有全部权限），但不再需要把通配权限
--    绑到每个管理员角色上，也不会再出现在权限树/角色勾选列表里被误删。
-- 2) sys_permission 新增 domain：权限域（system/qt/storage/...），
--    支撑管理端按插件/模块分组展示，以及「结果级权限」按前缀解析可见集合。
-- 3) sys_permission.type 语义扩展：1-目录 2-菜单 3-按钮 4-接口 5-数据。
--    新增 4/5 两类不参与前端菜单渲染，只承载接口可达性与数据/结果范围。
-- 4) 旧权限码统一为「域:资源:操作[:范围]」：
--      qt_admin  -> qt:admin                          (type=4 接口)
--      qt_tester -> qt:update:channel:beta            (type=5 数据，版本更新 beta 渠道可见资格)
--                -> qt:source:channel:beta            (type=5 数据，音源包 beta 渠道可见资格)
--    角色绑定按旧码原样搬迁（qt_tester 的角色同时获得两个新码，
--    保持「能收测试版更新的人也能收测试版音源包」这一升级前语义）。
--
-- 幂等：所有 DDL 用 IF NOT EXISTS；DML 用 WHERE NOT EXISTS / NOT EXISTS 守卫；
--       新权限 id 取 MAX(id)+1（与 V20260926001 同策略，不与号段序列冲突）。
-- 影响：删除 sys_permission 中 permission_code='*:*:*' 一行及其角色绑定，
--       删除旧码 qt_admin / qt_tester 两行及其绑定（绑定已先搬迁）。
-- ============================================================

-- ---------- 1. 结构变更 ----------

ALTER TABLE sys_role ADD COLUMN IF NOT EXISTS is_super SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN sys_role.is_super IS '是否超级管理员角色(0-否 1-是，持该角色即拥有全部权限)';

ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS domain VARCHAR(32);
COMMENT ON COLUMN sys_permission.domain IS '权限域(按插件/模块分组，如 system/qt/storage)';

COMMENT ON COLUMN sys_permission.type IS '类型(1-目录 2-菜单 3-按钮 4-接口 5-数据)';

-- ---------- 2. 超管角色标记 ----------

-- 历史上凡绑定了 *:*:* 的角色一律升级为超管角色（含基线种子的 ADMIN 角色）
UPDATE sys_role
SET is_super = 1
WHERE id IN (
    SELECT rp.role_id
    FROM sys_role_permission rp
    JOIN sys_permission p ON p.id = rp.permission_id
    WHERE p.permission_code = '*:*:*'
);

-- 通配权限由角色标记承载，删除权限行与绑定（前端/后端仍以 *:*:* 作为超管通配符语义，
-- 由 StpInterfaceImpl 在超管角色的权限列表中合成，不再落库）
DELETE FROM sys_role_permission
WHERE permission_id IN (SELECT id FROM sys_permission WHERE permission_code = '*:*:*');

DELETE FROM sys_permission WHERE permission_code = '*:*:*';

-- ---------- 3. 权限域回填 ----------

-- 冒号分层权限码：首段即权限域（system / sequence / log / plugin / qt ...）
UPDATE sys_permission
SET domain = split_part(permission_code, ':', 1)
WHERE position(':' IN permission_code) > 0;

-- 未识别的扁平历史码统一归入 system，避免前端分组出现空域
UPDATE sys_permission SET domain = 'system' WHERE domain IS NULL OR domain = '';

-- ---------- 4. 旧权限码 -> 新权限码 ----------

-- 4.1 确保三个新权限码存在（全新库不含 qt_admin / qt_tester，需在此新建）
--     注意：这里必须用「base 聚合 CTE + 逐行 NOT EXISTS」的写法。
--     若写成 `SELECT COALESCE(MAX(id),0)+1, 'qt:admin' ... FROM t WHERE NOT EXISTS(...)`，
--     聚合查询在条件不成立时仍会返回一行（SELECT 列表里是常量），会插入重复权限码
--     并撞上唯一索引 uk_sys_permission_code 导致整个迁移失败。
WITH base AS (
    SELECT COALESCE(MAX(id), 0) AS max_id FROM sys_permission
),
seed(permission_code, permission_name, type, sort) AS (
    VALUES
        ('qt:admin',                  '轻听管理',                     4, 41),
        ('qt:update:channel:beta',    '轻听测试版接收资格(版本更新)', 5, 42),
        ('qt:source:channel:beta',    '轻听测试版接收资格(音源包)',   5, 43)
),
numbered AS (
    SELECT permission_code, permission_name, type, sort,
           ROW_NUMBER() OVER (ORDER BY sort) AS rn
    FROM seed
)
INSERT INTO sys_permission (id, parent_id, permission_code, permission_name, url, method, type, domain, sort, status)
SELECT b.max_id + n.rn, 0, n.permission_code, n.permission_name, NULL, NULL, n.type, 'qt', n.sort, 1
FROM numbered n
CROSS JOIN base b
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission p WHERE p.permission_code = n.permission_code
);

-- 4.2 新码属性对齐（历史库可能已手工建过 qt_admin，此处只补域/类型/文案，不动 id）
UPDATE sys_permission SET domain = 'qt', type = 4 WHERE permission_code = 'qt:admin';
UPDATE sys_permission SET domain = 'qt', type = 5 WHERE permission_code IN ('qt:update:channel:beta', 'qt:source:channel:beta');

-- 4.3 角色绑定搬迁：qt_admin -> qt:admin
WITH base AS (SELECT COALESCE(MAX(id), 0) AS m FROM sys_role_permission)
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT base.m + ROW_NUMBER() OVER (ORDER BY rp.role_id), rp.role_id, np.id
FROM sys_role_permission rp
JOIN sys_permission lp ON lp.id = rp.permission_id AND lp.permission_code = 'qt_admin'
CROSS JOIN sys_permission np
CROSS JOIN base
WHERE np.permission_code = 'qt:admin'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission x WHERE x.role_id = rp.role_id AND x.permission_id = np.id
  );

-- 4.4 角色绑定搬迁：qt_tester -> qt:update:channel:beta 与 qt:source:channel:beta
WITH base AS (SELECT COALESCE(MAX(id), 0) AS m FROM sys_role_permission)
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT base.m + ROW_NUMBER() OVER (ORDER BY rp.role_id, np.permission_code), rp.role_id, np.id
FROM sys_role_permission rp
JOIN sys_permission lp ON lp.id = rp.permission_id AND lp.permission_code = 'qt_tester'
CROSS JOIN sys_permission np
CROSS JOIN base
WHERE np.permission_code IN ('qt:update:channel:beta', 'qt:source:channel:beta')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission x WHERE x.role_id = rp.role_id AND x.permission_id = np.id
  );

-- 4.5 清理旧码及其残留绑定
DELETE FROM sys_role_permission
WHERE permission_id IN (SELECT id FROM sys_permission WHERE permission_code IN ('qt_admin', 'qt_tester'));

DELETE FROM sys_permission WHERE permission_code IN ('qt_admin', 'qt_tester');
