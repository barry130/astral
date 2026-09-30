-- ============================================================================
-- V20261001006 再次清理「无端前缀」的孤儿权限
--
-- 为什么 V20261001004 清过一次还要再清一次：
--   那次清理与「权限注册器补登记」在**同一次启动**里前后脚发生，而当时处于
--   **代码混合态**——部分模块（astral-plugin，含 App 端注解）已是新代码，
--   部分模块（astral-monitor / astral-sequence）还是旧代码。于是：
--     1. Flyway 先跑 V20261001004，把当时存在的孤儿行删干净（0 残留，符合预期）；
--     2. 紧接着 PermissionRegistry 扫描注解，发现旧代码里声明的
--        `monitor:view` / `statistics:view` / `sequence:edit` / `sequence:view`
--        「库里没有」（库里已是 `admin:` 前缀版），又用全局序列补插了一批孤儿
--        （id 1001001~1001004），且**没有任何角色/菜单引用它们**。
--   即「删了之后又被写回来」，属于删除时机与注册时机的竞争，不是删除逻辑有问题。
--
-- 因此本脚本与 V20261001004 的删除条件**完全一致**（无前缀 + 无角色引用），
-- 作为幂等收尾：任何一次未来的混合态部署都能在下次启动时自动收敛干净。
-- 前提是代码里的字面量已经统一（本次全量构建后，启动日志已显示
-- 「权限声明 45 条，均已登记，无需补齐」，即不再产生新的孤儿）。
--
-- 幂等：条件式 DELETE，可重复执行。
-- ============================================================================

DELETE FROM sys_permission p
WHERE p.permission_code NOT LIKE 'admin:%'
  AND p.permission_code NOT LIKE 'user:%'
  AND p.permission_code NOT LIKE 'all:%'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp WHERE rp.permission_id = p.id
  );

-- ---------------------------------------------------------------------------
-- 结果核对：
--   no_side_perm      无端前缀权限数（应为 0）
--   perm_total        权限总数
--   role_link_total   角色-权限链接总数（用于确认没误删被引用的行）
-- ---------------------------------------------------------------------------
SELECT 'no_side_perm' AS check_item, count(*) AS cnt
FROM sys_permission
WHERE permission_code NOT LIKE 'admin:%'
  AND permission_code NOT LIKE 'user:%'
  AND permission_code NOT LIKE 'all:%'
UNION ALL
SELECT 'perm_total', count(*) FROM sys_permission
UNION ALL
SELECT 'role_link_total', count(*) FROM sys_role_permission;