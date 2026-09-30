-- ============================================================================
-- V20261001005 App 端默认角色 APP_USER（权限归属 + 存量 App 用户回填）
--
-- 目标：新增一个**普通角色**（不是超管、可移除）`APP_USER`，持有全部 `user:` 前缀权限，
--       并让所有 `user_type='APP'` 的存量用户默认纳入该角色。
--
-- 权限码规范：`端:域:资源:操作[:范围]`（端 = admin / user / all）。
--   App 端（qt-uniappx / qt-pc 等客户端）的接口权限一律 `user:` 前缀，
--   管理端一律 `admin:` 前缀，两端共用可写 `all:`。
--   因此「App 用户默认有权限」这件事可以精确表达为「持有全部 user: 权限」，
--   管理端权限（admin:）与超管（*:*:*）完全不受影响。
--
-- 与 Java 侧的分工（重要，别删任何一边）：
--   * 本迁移负责**存量数据**的一次性收敛：建角色、授权、回填老 App 用户；
--   * `AppUserRoleInitializer` 每次启动做同样的幂等收敛，负责**增量**：
--     注解上新增一个 user: 权限后，PermissionRegistry 自动登记它，
--     初始化器随即把它补给 APP_USER，无需再写一条迁移。
--   两者都幂等，重复执行不会产生重复授权。
--
-- 权限缓存：本迁移改动了「角色→权限」映射，但 SQL 无法访问 Redis 里的
--   `astral:perm:version`（PermissionCache.VERSION_KEY）。启动时
--   AppUserRoleInitializer 会调用 bumpVersion() 使会话内缓存的权限列表立即失效，
--   所以部署本迁移后**必须同时部署配套 Java 代码**（否则最长 5 分钟才能自愈）。
--
-- 幂等：全部带 NOT EXISTS 守卫，可重复执行。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) APP_USER 角色
--    id 用 COALESCE(MAX(id),0)+1 取号（不硬编码：见 AGENTS.md 约束 2，
--    V20260930002 就是写死 id 被静默跳过导致整份字典丢失）。
--    落在种子段 [1,1000000]：运行时实体取号从 1000001 起，不会撞号。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role (id, role_code, role_name, description, status, sort, is_super, create_time, update_time)
SELECT COALESCE((SELECT MAX(id) FROM sys_role), 0) + 1,
       'APP_USER',
       'App 用户',
       'App 端默认角色：持有全部 user: 前缀权限（App 客户端接口）。新注册 App 用户自动分配，可按需移除。',
       1,
       100,
       0,
       NOW(),
       NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'APP_USER');

-- ---------------------------------------------------------------------------
-- 2) 授权：APP_USER ← 全部 user: 前缀权限
--    用权限码前缀做集合定义（而不是罗列具体码）：唯一约束
--    uk_sys_role_permission(role_id, permission_id) 保证不会重复授权。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_time)
SELECT COALESCE((SELECT MAX(id) FROM sys_role_permission), 0)
         + ROW_NUMBER() OVER (ORDER BY p.id),
       r.id,
       p.id,
       NOW()
FROM sys_role r
JOIN sys_permission p ON p.permission_code LIKE 'user:%'
WHERE r.role_code = 'APP_USER'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- ---------------------------------------------------------------------------
-- 3) 回填：所有 user_type='APP' 的存量用户纳入 APP_USER
--    注意是「追加」而非替换：测试人员（TESTER）等已有角色保持不变，
--    App 用户因此同时拥有自己的业务角色与默认的 APP_USER。
-- ---------------------------------------------------------------------------
INSERT INTO sys_user_role (id, user_id, role_id, create_time)
SELECT COALESCE((SELECT MAX(id) FROM sys_user_role), 0)
         + ROW_NUMBER() OVER (ORDER BY u.id),
       u.id,
       r.id,
       NOW()
FROM sys_user u
JOIN sys_role r ON r.role_code = 'APP_USER'
WHERE u.user_type = 'APP'
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_role ur
      WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

-- ---------------------------------------------------------------------------
-- 4) 结果核对
--    app_user_role      : APP_USER 角色数（应为 1）
--    app_user_perm_cnt  : APP_USER 持有的权限数（应 = user: 权限总数）
--    user_perm_cnt      : 库里 user: 前缀权限总数
--    app_user_linked    : 纳入 APP_USER 的 App 用户数（应 = user_type='APP' 用户数）
--    app_user_total     : user_type='APP' 用户总数
-- ---------------------------------------------------------------------------
SELECT 'app_user_role' AS check_item, count(*) AS cnt
FROM sys_role WHERE role_code = 'APP_USER'
UNION ALL
SELECT 'app_user_perm_cnt', count(*)
FROM sys_role_permission rp
JOIN sys_role r ON r.id = rp.role_id
WHERE r.role_code = 'APP_USER'
UNION ALL
SELECT 'user_perm_cnt', count(*)
FROM sys_permission WHERE permission_code LIKE 'user:%'
UNION ALL
SELECT 'app_user_linked', count(*)
FROM sys_user_role ur
JOIN sys_role r ON r.id = ur.role_id
WHERE r.role_code = 'APP_USER'
UNION ALL
SELECT 'app_user_total', count(*)
FROM sys_user WHERE user_type = 'APP';