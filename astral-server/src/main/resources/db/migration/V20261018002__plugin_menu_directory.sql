-- 「插件管理」一级节点只作目录，现有插件列表页迁到同名二级菜单。
-- 保留原目录 ID，使已有子菜单继续挂在原节点下。
UPDATE sys_menu
SET path = '/dashboard/group/plugin', parent_id = 0, type = 0,
    update_time = CURRENT_TIMESTAMP
WHERE path = '/dashboard/plugin'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/dashboard/group/plugin');

INSERT INTO sys_menu (id, parent_id, name, icon, path, permission, sort, visible, type)
SELECT ids.id, parent.id, '插件管理', 'ApiOutlined', '/dashboard/plugin',
       'admin:plugin:view', 0, 1, 1
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_menu) ids
JOIN sys_menu parent ON parent.path = '/dashboard/group/plugin'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/dashboard/plugin');

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT MAX(id) + 1000 FROM sys_menu)),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_menu_id';
