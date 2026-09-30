-- ============================================================================
-- V20261001004 清理「端前缀迁移」期间被权限注册器重新插回的旧码
--
-- ⚠️ 记录一个真实的迁移时序陷阱（本脚本就是被它逼出来的）：
--
--   V20261001001 把库里 37 条旧码**原地改写**成带端前缀的新码（id 不变，
--   所以 sys_role_permission / sys_menu 的引用自动继续有效）。
--
--   但如果「数据库迁移」先于「配套代码」生效（本次就是如此：迁移文件写进仓库时，
--   运行中的后端仍是旧代码），后端一启动：
--       PermissionRegistry.registerMissing() 拿旧注解里的旧码去库里比对，
--       发现 `system:user:view` 之类「不存在」（因为库里已经是 `admin:system:user:view`），
--       于是把它们当作缺失权限，用**全局序列**（id ≥ 1000001）重新插了一批回来。
--
--   结果库里新旧并存：
--     * 旧码行（id 1..49，改写后带 admin:/user: 前缀）—— 被角色/菜单引用，是「正主」
--     * 新插的旧码行（id ≥ 1000001，无前缀）—— 无任何角色/菜单引用，是「孤儿」
--
--   危害：权限管理页条目凭空翻倍，且与真正生效的码语义重复，
--         运维在错误的那条上改名称/排序不会生效。
--
-- 教训：**改权限码这种 breaking rename，数据库迁移与代码字面量必须同一次部署生效**；
--       若无法保证，就先发代码（新码写进注解）再发迁移，或给旧码保留别名映射。
--
-- 处理：删除「无端前缀 且 没有任何角色引用」的权限行。
--       带角色引用的行一律保留（保守，宁可漏删不可误删）。
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
-- 结果核对：三项都应为 0 / 或符合预期
--   no_side_perm      : 无端前缀的权限码（应为 0）
--   no_side_menu_perm : 无端前缀的菜单权限（应为 0）
--   orphan_no_side    : 无前缀孤儿残留（应为 0）
-- ---------------------------------------------------------------------------
SELECT 'no_side_perm' AS check_item, count(*) AS cnt
FROM sys_permission
WHERE permission_code NOT LIKE 'admin:%'
  AND permission_code NOT LIKE 'user:%'
  AND permission_code NOT LIKE 'all:%'
UNION ALL
SELECT 'no_side_menu_perm', count(*)
FROM sys_menu
WHERE permission IS NOT NULL
  AND permission <> ''
  AND permission NOT LIKE 'admin:%'
  AND permission NOT LIKE 'user:%'
  AND permission NOT LIKE 'all:%'
UNION ALL
SELECT 'perm_total', count(*) FROM sys_permission
UNION ALL
SELECT 'admin_side_perm', count(*) FROM sys_permission WHERE permission_code LIKE 'admin:%'
UNION ALL
SELECT 'user_side_perm', count(*) FROM sys_permission WHERE permission_code LIKE 'user:%';