-- 通知规则（事件 × 渠道）拆分所需的三本新字典：投递渠道 / 收件人类型 / 限额口径。
-- 只补字典，不动业务表；重复执行安全（NOT EXISTS 幂等 + 取号用 MAX(id)+ROW_NUMBER）。
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('notify_delivery_channel', '投递渠道', 'EMAIL 邮件 / SMS 短信 / INAPP 站内信（通知规则渠道多选）'),
           ('notify_recipient_type', '通知收件人类型', 'FIXED 固定值 / PAYLOAD_FIELD 取事件载荷字段 / ROLE 按角色展开'),
           ('notify_limit_scope', '通知限额口径', 'RECIPIENT 按收件人限额 / GLOBAL 按渠道全局限额')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

WITH seed(dict_code, dict_value, dict_label, dict_sort, list_class) AS (
    VALUES ('notify_delivery_channel', 'EMAIL', '邮件', 1, 'blue'),
           ('notify_delivery_channel', 'SMS', '短信', 2, 'gold'),
           ('notify_delivery_channel', 'INAPP', '站内信', 3, 'green'),
           ('notify_recipient_type', 'FIXED', '固定值', 1, 'default'),
           ('notify_recipient_type', 'PAYLOAD_FIELD', '取事件载荷字段', 2, 'blue'),
           ('notify_recipient_type', 'ROLE', '按角色展开', 3, 'purple'),
           ('notify_limit_scope', 'RECIPIENT', '按收件人', 1, 'blue'),
           ('notify_limit_scope', 'GLOBAL', '全局', 2, 'gold')
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
