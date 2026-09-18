-- ============================================================
-- 迁移版本：V20260914004（增量脚本，幂等）
-- 说明：整体移除集群模式功能模块（代码已删，本脚本清理数据库侧残留）。
--       清理项：集群管理菜单、cluster:view 权限及其角色绑定、
--       cluster_node_config 表、该表的全局序列登记行。
-- 应用方式：后端启动时 Flyway 自动应用。
-- ============================================================

-- 1. 解除角色与 cluster:view 权限的绑定
DELETE FROM sys_role_permission
WHERE permission_id IN (SELECT id FROM sys_permission WHERE permission_code = 'cluster:view');

-- 2. 删除 cluster:view 权限
DELETE FROM sys_permission WHERE permission_code = 'cluster:view';

-- 3. 删除「集群管理」菜单（sys_menu 的权限列名为 permission，按权限标识删除不依赖种子 id）
DELETE FROM sys_menu WHERE permission = 'cluster:view';

-- 4. 删除集群节点配置表
DROP TABLE IF EXISTS cluster_node_config;

-- 5. 清理该表的全局序列登记（SequencePlugin 启动预置产生的行；幂等，无行时为空操作）
DELETE FROM sequence_segment    WHERE biz_key = 'cluster_node_config_id';
DELETE FROM sequence_config     WHERE biz_key = 'cluster_node_config_id';
DELETE FROM sequence_statistics WHERE biz_key = 'cluster_node_config_id';
DELETE FROM sequence_history    WHERE biz_key = 'cluster_node_config_id';
