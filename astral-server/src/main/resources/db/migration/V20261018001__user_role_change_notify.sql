-- 用户角色增减：站内信模板 + 默认启用规则。只通知角色发生变更的用户，全部平台可见。
INSERT INTO sys_mail_template (id, template_code, template_name, subject, content, variables, scene, channel, remark)
SELECT s.id, 'userRolesChangedInapp', '用户角色变更站内信',
       '您的角色权限已变更', '${changeSummary}',
       '["userId","addedRoles","removedRoles","changeSummary"]', 'userRolesChanged', 'INAPP',
       '管理员增加或移除用户角色后通知本人；仅站内信'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_mail_template) s
WHERE NOT EXISTS (SELECT 1 FROM sys_mail_template WHERE scene = 'userRolesChanged' AND channel = 'INAPP');

INSERT INTO sys_notify_rule (id, rule_name, event_code, channel, template_id, recipient_type, recipient_value, platform, enabled, remark)
SELECT s.id, '角色变更-通知本人', 'userRolesChanged', 'INAPP',
       (SELECT id FROM sys_mail_template WHERE scene = 'userRolesChanged' AND channel = 'INAPP'),
       'PAYLOAD_FIELD', 'userId', 'all', 1, '用户角色实际增减后通知本人；提交成功后异步发送'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_notify_rule) s
WHERE NOT EXISTS (SELECT 1 FROM sys_notify_rule WHERE event_code = 'userRolesChanged' AND channel = 'INAPP');

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT MAX(id) + 1000 FROM sys_mail_template)),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_mail_template_id';

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT MAX(id) + 1000 FROM sys_notify_rule)),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_notify_rule_id';

-- 管理员回复、用户回复反馈的规则，以及已投递的回复站内信，统一为全部平台。
-- 不改变收件人：all 只影响平台可见范围，点对点目标用户保持原样。
UPDATE sys_notify_rule
SET platform = 'all', update_time = CURRENT_TIMESTAMP
WHERE event_code IN ('feedbackAdminReplied', 'feedbackUserReplied') AND channel = 'INAPP';

UPDATE sys_notice
SET channel = 'all', update_time = CURRENT_TIMESTAMP
WHERE scene IN ('feedbackAdminReplied', 'feedbackUserReplied');
