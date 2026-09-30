-- ============================================================================
-- V20261001009 APP_USER 收回结果级（DATA）权限
--
-- 背景：V20261001005 与 AppUserRoleInitializer.syncPermissions() 按
--       `permission_code LIKE 'user:%'` 前缀全量授权时未排除 type=5（DATA）。
--       权限码加端前缀（V20261001001）后，两条范围权限恰好变成 user: 开头：
--         user:qt:update:channel:beta   （版本更新测试渠道可见资格）
--         user:qt:source:channel:beta   （音源包测试渠道可见资格）
--       于是被批发给了 APP_USER → 所有登录 App 用户都能收到测试版，
--       违背结果级权限「测试资格由管理员按人授予」的投放意图。
--
-- 本迁移：一次性收回 APP_USER 名下全部 type=5 授权（不逐条罗列权限码——
--       以后新增任何 user: 范围权限同样不该出现在默认角色上）。
--       不变量由两侧共同保证：
--         * 本脚本收敛存量（Flyway 先于 ApplicationRunner 执行）；
--         * syncPermissions() 只授 type=API 且每次启动收回 DATA（增量自愈）。
--
-- 缓存：SQL 无法触达 Redis 的 astral:perm:version；本批配套 Java 改动中
--       AppUserRoleInitializer 已改为启动时无条件 bumpVersion()，
--       因此**本迁移必须与该代码同一次部署**，否则会话缓存最长 5 分钟后才自愈。
--
-- 幂等：DELETE 天然幂等，可重复执行。
-- ============================================================================

DELETE FROM sys_role_permission rp
USING sys_role r, sys_permission p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.role_code = 'APP_USER'
  AND p.type = 5;

-- ---------------------------------------------------------------------------
-- 结果核对
--   app_user_data_perm_cnt : APP_USER 名下 DATA 权限数（应为 0）
--   app_user_perm_cnt      : APP_USER 持有权限总数（应 = user: API 权限数）
--   user_api_perm_cnt      : 库里 user: 前缀且 type=4 的权限总数
-- ---------------------------------------------------------------------------
SELECT 'app_user_data_perm_cnt' AS check_item, count(*) AS cnt
FROM sys_role_permission rp
JOIN sys_role r ON r.id = rp.role_id
JOIN sys_permission p ON p.id = rp.permission_id
WHERE r.role_code = 'APP_USER' AND p.type = 5
UNION ALL
SELECT 'app_user_perm_cnt', count(*)
FROM sys_role_permission rp
JOIN sys_role r ON r.id = rp.role_id
WHERE r.role_code = 'APP_USER'
UNION ALL
SELECT 'user_api_perm_cnt', count(*)
FROM sys_permission
WHERE permission_code LIKE 'user:%' AND type = 4;
