-- ============================================================
-- 迁移版本：V20260930003
-- 说明：权限模型重构（20260930001/002）后，管理端控制器改为声明式权限注解
--       （@RequiresPermission/@RequiresSuper），其中一部分权限码是本次**新增**的：
--       这些接口在重构前完全没有校验，因此库里不存在对应权限行。
--
--       本脚本把这些新码按规范（域:资源:操作）登记进 sys_permission，
--       并给出**中文名/域/类型**，避免启动时由 PermissionRegistry 自动登记成
--       「名称 = 权限编码」而难以在角色授权界面辨认。
--
--       PermissionRegistry 是「只增不改」的：已存在的编码不会被覆盖，
--       所以本脚本先插入，自动登记器随后会跳过它们。
--
-- 重要（升级须知）：
--       本脚本**不向任何角色授权**。重构前这些接口无校验、任何登录管理员都能操作，
--       重构后按「默认拒绝」处理：
--         * 超管角色（sys_role.is_super = 1，由 20260930001 设置）不受影响，永远拥有全部权限；
--         * 其他角色请到「系统管理 → 角色管理 → 权限」里按需勾选（权限树已按权限域分组）。
--       这是刻意的收紧，属于本次权限重构的预期行为。
--
-- 幂等：按 permission_code 唯一键守卫（uk_sys_permission_code），可重复执行。
-- id 段约定：取当前 sys_permission 最大 id 之后连续分配（不与运行时发号 < 1,000,000 冲突）。
-- ============================================================

WITH base AS (
    SELECT COALESCE(MAX(id), 0) AS max_id FROM sys_permission
),
seed(permission_code, permission_name, domain, type, sort) AS (
    VALUES
        -- 系统管理：数据字典 / 系统配置的写权限（原为无校验）
        ('system:dict:edit',            '数据字典维护',   'system',   4, 101),
        ('system:config:edit',          '系统配置维护',   'system',   4, 102),
        -- 序列管理：配置/号段/统计的写权限（原为无校验）
        ('sequence:edit',               '序列配置维护',   'sequence', 4, 111),
        -- 插件管理：启停写权限（原为无校验）
        ('plugin:edit',                 '插件启停',       'plugin',   4, 121),
        -- 系统监控（原为无校验）
        ('monitor:view',                '系统监控查看',   'monitor',  4, 131),
        -- 反馈管理（原为无校验）
        ('feedback:view',               '反馈查看',       'feedback', 4, 141),
        ('feedback:edit',               '反馈编辑',       'feedback', 4, 142),
        -- 统一通知（原为无校验）
        ('message:view',                '通知查看',       'message',  4, 151),
        ('message:edit',                '通知编辑',       'message',  4, 152),
        -- 对象存储管理（原为无校验）
        ('storage:view',                '存储查看',       'storage',  4, 161),
        ('storage:edit',                '存储编辑',       'storage',  4, 162)
),
numbered AS (
    SELECT permission_code, permission_name, domain, type, sort,
           ROW_NUMBER() OVER (ORDER BY sort) AS rn
    FROM seed
)
INSERT INTO sys_permission (id, parent_id, permission_code, permission_name, domain, type, sort, status)
SELECT b.max_id + n.rn, 0, n.permission_code, n.permission_name, n.domain, n.type, n.sort, 1
FROM numbered n
CROSS JOIN base b
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission p WHERE p.permission_code = n.permission_code
)
ON CONFLICT DO NOTHING;

-- 回填：本脚本新增行的 domain 已直接写入；此处兼容「已被自动登记器抢先插入」的场景
-- （自动登记器写的是注解上的 domain，语法上不会为空，这里仅作兜底）。
UPDATE sys_permission
SET domain = split_part(permission_code, ':', 1)
WHERE (domain IS NULL OR domain = '')
  AND permission_code LIKE '%:%';
