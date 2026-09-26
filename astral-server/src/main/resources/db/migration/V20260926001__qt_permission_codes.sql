-- ============================================================
-- 迁移版本：V20260926001
-- 说明：音源包 / 版本更新的「测试版」人群投放权限——
--       测试版（channel=beta）仅对拥有 qt_admin 或 qt_tester 权限（含超管 *:*:*）的用户投放。
--       qt_admin 权限此前已存在，无需修改；本脚本仅新增 qt_tester 权限编码。
--       角色绑定由管理端「角色管理」按需配置；存量数据行为与升级前一致。
-- 幂等：WHERE NOT EXISTS 按 permission_code 守卫，可重复执行；
--       id 取当前 sys_permission 最大 id + 1（不与线上已有 qt_admin 的 id 冲突）。
-- ============================================================

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort, status)
SELECT COALESCE(MAX(id), 0) + 1, 'qt_tester', '轻听测试', NULL, NULL, 0, 41, 1
FROM sys_permission
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'qt_tester');
