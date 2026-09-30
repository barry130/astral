-- ============================================================================
-- V20261001001 权限码规范升级：新增首段「端」（admin / user）
--
-- 旧规范：`域:资源:操作[:范围]`      如 system:user:view / qt:update:channel:beta
-- 新规范：`端:域:资源:操作[:范围]`  如 admin:system:user:view / user:qt:update:channel:beta
--
-- 为什么加「端」：管理端与 APP 端会出现同名资源（管理端 qt:admin 与 APP 端 qt:* ，
-- 未来还有 user:profile 之类），只靠「域」区分不开两端，也会让 `admin:*` 这种
-- 「整个管理端」的通配无法表达。
--
-- 端取值：admin=管理端 / user=APP 端 / all=两端共用
--
-- 同步改动（本脚本之外，属同一次变更）：
--   * PermissionChecker.domainOf/inferType 改为**先剥掉端段**再取域/判结果级
--     （否则 admin:system:user:view 会被误判成 4 段 → 数据权限）
--   * PermissionDeclaration.resolvedDomain 复用 PermissionChecker.domainOf
--   * 全部 @RequiresPermission 注解、插件 PermissionProvider 声明、前端常量
--
-- 幂等：已带端前缀的行不会重复加前缀（NOT LIKE 守卫）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- A. APP 端（结果级权限）：加 user: 前缀
--    必须先于 B 执行——否则会被 B 一并加上 admin: 前缀。
--    目前仅轻听渠道可见集合两码属 APP 端（语源包 / 版本更新）。
-- ---------------------------------------------------------------------------
UPDATE sys_permission
SET permission_code = 'user:' || permission_code
WHERE permission_code IN ('qt:update:channel:beta', 'qt:source:channel:beta');

-- ---------------------------------------------------------------------------
-- B. 其余全部视为管理端：加 admin: 前缀
-- ---------------------------------------------------------------------------
UPDATE sys_permission
SET permission_code = 'admin:' || permission_code
WHERE permission_code NOT LIKE 'admin:%'
  AND permission_code NOT LIKE 'user:%'
  AND permission_code NOT LIKE 'all:%';

-- ---------------------------------------------------------------------------
-- C. 菜单上绑定的权限码同步（sys_menu.permission 只有管理端码）
--    菜单的可见性判定走同一套权限码，不迁移会导致菜单整片消失。
-- ---------------------------------------------------------------------------
UPDATE sys_menu
SET permission = 'admin:' || permission
WHERE permission IS NOT NULL
  AND permission <> ''
  AND permission NOT LIKE 'admin:%'
  AND permission NOT LIKE 'user:%'
  AND permission NOT LIKE 'all:%';

-- ---------------------------------------------------------------------------
-- D. sys_permission.domain 无需改动
--    domain 是独立列，本来就存 system / qt / storage 这类值，
--    「端」是它前面新增的一段，不改变域本身。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- E. 结果核对：不应再有「无端前缀」的权限码与菜单权限
-- ---------------------------------------------------------------------------
SELECT 'perm_without_side' AS check_item, count(*) AS cnt
FROM sys_permission
WHERE permission_code NOT LIKE 'admin:%'
  AND permission_code NOT LIKE 'user:%'
  AND permission_code NOT LIKE 'all:%'
UNION ALL
SELECT 'menu_perm_without_side', count(*)
FROM sys_menu
WHERE permission IS NOT NULL
  AND permission <> ''
  AND permission NOT LIKE 'admin:%'
  AND permission NOT LIKE 'user:%'
  AND permission NOT LIKE 'all:%'
UNION ALL
SELECT 'perm_total', count(*) FROM sys_permission
UNION ALL
SELECT 'app_side_perm', count(*) FROM sys_permission WHERE permission_code LIKE 'user:%';