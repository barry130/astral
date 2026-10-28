-- 消息模板/通知规则的枚举全部由数据字典提供，前端不再写死。
-- 只补字典，不动 sys_mail_template / sys_sms_template / sys_notify_rule 的数据。
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('notify_delivery_channel', '消息投递渠道', '邮件 / 站内信 / 短信，供消息模板与通知规则复用'),
           ('notify_recipient_type', '通知收件人类型', '通知规则里收件人的取值方式')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

WITH seed(dict_code, dict_value, dict_label, dict_sort, list_class) AS (
    VALUES ('notify_delivery_channel', 'EMAIL', '邮箱', 1, 'blue'),
           ('notify_delivery_channel', 'INAPP', '站内信', 2, 'purple'),
           ('notify_delivery_channel', 'SMS', '短信', 3, 'green'),
           ('notify_recipient_type', 'FIXED', '固定值', 1, NULL),
           ('notify_recipient_type', 'PAYLOAD_FIELD', '事件字段', 2, NULL),
           ('notify_recipient_type', 'ROLE', '按角色', 3, NULL)
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = s.dict_code
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, list_class, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_code, dict_sort),
       type_id, dict_label, dict_value, dict_sort, list_class, 1 FROM missing;

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';

-- 短信审核状态字典补回显色（只补空值，不动已有配置）
UPDATE sys_dict_data d SET list_class = v.color
FROM (VALUES ('0', 'default'), ('1', 'gold'), ('2', 'green'), ('3', 'red')) AS v(value, color)
JOIN sys_dict_type t ON t.dict_code = 'notify_sms_audit_status'
WHERE d.dict_type_id = t.id AND d.dict_value = v.value AND d.list_class IS NULL;

-- 通用启用状态（多个后台列表页复用的布尔型枚举）
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('enable_status', '启用状态', '1 启用 / 0 停用，通用列表状态枚举')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description FROM missing;

WITH seed(dict_value, dict_label, dict_sort, list_class) AS (
    VALUES ('1', '启用', 1, 'green'), ('0', '停用', 2, 'default')
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = 'enable_status'
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, list_class, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_sort),
       type_id, dict_label, dict_value, dict_sort, list_class, 1 FROM missing;

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
