-- ============================================================================
-- V20260930005 权限配置收口（二）：角色权限基线
--
-- 背景：V20260930004 之后权限码齐备，但角色绑定仍是历史种子留下的状态，存在两个问题：
--   1) USER（普通用户，is_super=0）角色绑定了 13 条权限，其中包含
--      system:permission:view / system:role:view / system:token:view / system:schema:view
--      这类敏感视图权限——一旦把该角色发给 user_type=ADMIN 的账号，他就能浏览
--      权限定义、角色、在线会话与表结构，属于越权配置。
--   2) ADMIN 角色（is_super=1）绑定了 18 条权限，但超管在登录态由
--      StpInterfaceImpl 依据 is_super 合成 `*:*:*`，这 18 条绑定**不参与任何判定**，
--      只会让人误以为「授权靠这些行」。保留反而误导运维。
--
-- 本脚本：
--   A. 清空超管角色的 sys_role_permission（语义：超管=全量，不靠关联表表达）；
--   B. 重置 USER 角色为「通用只读」基线，移除敏感视图权限；
--   C. 确保 monitor:view 出现在基线里——仪表盘页面的接口强依赖它，
--      否则非超管角色登录后首页直接 403。
--
-- 幂等性：先 DELETE 再按权限码 INSERT，且 INSERT 带 NOT EXISTS 保护，可重复执行。
-- 注意：本脚本**不**给任何角色授予插件业务权限（feedback/message/storage/qt/plugin）
--       与各类 :edit 写权限，这些一律保持默认拒绝，由管理员在「角色权限」页按需勾选。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- A. 超管角色：清空冗余绑定（超管语义由 is_super=1 表达）
-- ---------------------------------------------------------------------------
DELETE FROM sys_role_permission
WHERE role_id IN (SELECT id FROM sys_role WHERE is_super = 1);

-- ---------------------------------------------------------------------------
-- B. USER 角色：先清空，再授「通用只读」基线
--    基线 = 仪表盘(monitor:view) + 数据统计(statistics:view) + 日志(log:view)
--    这三项是纯只读、无敏感信息（表结构/权限定义/会话/角色均不含）。
-- ---------------------------------------------------------------------------
DELETE FROM sys_role_permission
WHERE role_id IN (SELECT id FROM sys_role WHERE role_code = 'USER' AND is_super = 0);

WITH baseline(permission_code) AS (
    VALUES
        ('monitor:view'),
        ('statistics:view'),
        ('log:view')
),
-- 角色可能不存在（例如被改名/删除）：用 CROSS JOIN 保证无角色时不插入任何行
target_role AS (
    SELECT id FROM sys_role WHERE role_code = 'USER' AND is_super = 0
),
id_base AS (
    SELECT COALESCE(MAX(id), 0) AS max_id FROM sys_role_permission
),
to_grant AS (
    -- 注意别把 baseline 别名取成 b：同一查询里再 CROSS JOIN 一个叫 base 的 CTE 时，
    -- 别名 b 会与 base 混淆，b.max_id 会被解析到 baseline 上而报 "column b.max_id does not exist"
    SELECT p.id AS permission_id, ib.max_id,
           ROW_NUMBER() OVER (ORDER BY bl.permission_code) AS rn
    FROM baseline bl
    JOIN sys_permission p ON p.permission_code = bl.permission_code
    CROSS JOIN id_base ib
)
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT g.max_id + g.rn, r.id, g.permission_id
FROM to_grant g
CROSS JOIN target_role r
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role_permission x
    WHERE x.role_id = r.id AND x.permission_id = g.permission_id
);

-- ---------------------------------------------------------------------------
-- C. 结果核对
-- ---------------------------------------------------------------------------
SELECT r.role_code,
       r.is_super,
       count(rp.permission_id) AS 绑定权限数,
       COALESCE(string_agg(p.permission_code, ', ' ORDER BY p.permission_code), '(无)') AS 权限
FROM sys_role r
LEFT JOIN sys_role_permission rp ON rp.role_id = r.id
LEFT JOIN sys_permission p ON p.id = rp.permission_id
GROUP BY r.id, r.role_code, r.is_super
ORDER BY r.id;
