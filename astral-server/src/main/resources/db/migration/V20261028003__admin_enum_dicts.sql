-- 管理端剩余写死枚举全部改由数据字典提供，前端不再硬编码「值→文案」映射。
-- 只补字典，不动业务表数据；重复执行安全（NOT EXISTS 幂等 + 取号用 MAX(id)+ROW_NUMBER）。
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('user_type', '用户类型', 'ADMIN 管理端 / APP 用户端（sys_user.user_type）'),
           ('token_status', 'Token 状态', '1 有效 / 0 已吊销（Token 管理页）'),
           ('config_type', '配置类型', '1 内置（不可编辑删除） / 2 自定义（系统配置页）'),
           ('notify_send_status', '消息发送状态', '1 成功 / 0 失败（邮件、短信发送日志）'),
           ('stat_error_source', '异常来源', 'server 服务端异常 / client 客户端上报（接口统计异常列表）'),
           ('storage_subject_type', '存储权限主体', 'USER 用户 / PLUGIN 插件（存储授权主体）')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

WITH seed(dict_code, dict_value, dict_label, dict_sort, list_class) AS (
    VALUES ('user_type', 'ADMIN', '管理端', 1, 'blue'),
           ('user_type', 'APP', 'App用户', 2, 'green'),
           ('token_status', '1', '有效', 1, 'green'),
           ('token_status', '0', '已吊销', 2, 'red'),
           ('config_type', '1', '内置', 1, 'blue'),
           ('config_type', '2', '自定义', 2, 'gold'),
           ('notify_send_status', '1', '成功', 1, 'green'),
           ('notify_send_status', '0', '失败', 2, 'red'),
           ('stat_error_source', 'server', '服务端异常', 1, 'red'),
           ('stat_error_source', 'client', '客户端上报', 2, 'blue'),
           ('storage_subject_type', 'USER', '用户', 1, 'blue'),
           ('storage_subject_type', 'PLUGIN', '插件', 2, 'purple')
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = s.dict_code
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, list_class, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_code, dict_sort),
       type_id, dict_label, dict_value, dict_sort, list_class, 1
FROM missing;

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
