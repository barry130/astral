-- 菜单数据修正：「邮箱统计」升级为「消息统计」，路径从被删除的 /dashboard/system/mail/log
-- 迁到 /dashboard/system/message/statistics（页签由前端 menuConfig 兜底，但侧边栏真正渲染的是
-- sys_menu 这张表；只改前端代码不会改变已存在的菜单行）。
UPDATE sys_menu
SET name = '消息统计',
    path = '/dashboard/system/message/statistics',
    update_time = CURRENT_TIMESTAMP
WHERE path = '/dashboard/system/mail/log'
   OR (name = '邮箱统计' AND type = 1);
