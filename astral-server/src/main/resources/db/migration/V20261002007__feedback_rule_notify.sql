-- 通知子系统阶段⑥：反馈/需求通知规则化。feedback 插件 5 个硬编码触发点改走
-- NotifyPublisher.publishAsync，文案/收件人/平台全部由订阅规则 + INAPP 模板配置。
-- 1) 规则表加平台定向列（INAPP 投递时写 sys_notice.channel，EMAIL/SMS 忽略）
-- 2) 种子：5 个 INAPP 模板（文案与原硬编码一致，后台可改）+ 5 条启用规则（行为与原代码等价）

-- 1) 规则表加 platform
ALTER TABLE sys_notify_rule ADD COLUMN IF NOT EXISTS platform VARCHAR(64) NOT NULL DEFAULT 'all';

-- 2) INAPP 模板种子（id 取 MAX+1；不写死主键，防冲突由 NOT EXISTS 守卫；
--    变量列表必须覆盖事件 payload 全部字段——与模板写路径校验 MAIL010 同口径）
INSERT INTO sys_mail_template (id, template_code, template_name, subject, content, variables, scene, channel, remark)
SELECT s.id, 'feedbackStatusChangedInapp', '反馈状态变更站内信',
'您的反馈「${feedbackTitle}」${statusName}',
'您的反馈「${feedbackTitle}」状态已更新为「${statusName}」。',
'["feedbackId","userId","feedbackTitle","statusName"]', 'feedbackStatusChanged', 'INAPP',
'feedback 插件反馈状态变更通知提交人；INAPP 渠道'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_mail_template) s
WHERE NOT EXISTS (SELECT 1 FROM sys_mail_template WHERE template_code = 'feedbackStatusChangedInapp');

INSERT INTO sys_mail_template (id, template_code, template_name, subject, content, variables, scene, channel, remark)
SELECT s.id, 'feedbackPublishedInapp', '反馈公开发布站内信',
'您的反馈「${feedbackTitle}」已公开发布',
'您的反馈已通过审核并公开发布，其他用户可以在「公开」列表中看到。',
'["feedbackId","userId","feedbackTitle"]', 'feedbackPublished', 'INAPP',
'feedback 插件公开发布通知提交人；INAPP 渠道'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_mail_template) s
WHERE NOT EXISTS (SELECT 1 FROM sys_mail_template WHERE template_code = 'feedbackPublishedInapp');

INSERT INTO sys_mail_template (id, template_code, template_name, subject, content, variables, scene, channel, remark)
SELECT s.id, 'feedbackAdminRepliedInapp', '管理员回复站内信',
'您的反馈「${feedbackTitle}」有新回复',
'管理员回复了您的反馈，点击查看详情。',
'["feedbackId","userId","feedbackTitle"]', 'feedbackAdminReplied', 'INAPP',
'feedback 插件管理员回复通知提交人；INAPP 渠道'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_mail_template) s
WHERE NOT EXISTS (SELECT 1 FROM sys_mail_template WHERE template_code = 'feedbackAdminRepliedInapp');

INSERT INTO sys_mail_template (id, template_code, template_name, subject, content, variables, scene, channel, remark)
SELECT s.id, 'feedbackNewSubmissionInapp', '新反馈/需求提交站内信',
'新反馈/需求「${feedbackTitle}」已提交',
'用户提交了反馈/需求「${feedbackTitle}」，请及时处理。',
'["feedbackId","feedbackTitle"]', 'feedbackNewSubmission', 'INAPP',
'feedback 插件新反馈通知管理员（ROLE=ADMIN 展开）；INAPP 渠道'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_mail_template) s
WHERE NOT EXISTS (SELECT 1 FROM sys_mail_template WHERE template_code = 'feedbackNewSubmissionInapp');

INSERT INTO sys_mail_template (id, template_code, template_name, subject, content, variables, scene, channel, remark)
SELECT s.id, 'feedbackUserRepliedInapp', '用户回复反馈站内信',
'用户在反馈「${feedbackTitle}」中回复',
'用户回复了反馈「${feedbackTitle}」，请及时处理。',
'["feedbackId","feedbackTitle"]', 'feedbackUserReplied', 'INAPP',
'feedback 插件用户回复通知管理员（ROLE=ADMIN 展开）；INAPP 渠道'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_mail_template) s
WHERE NOT EXISTS (SELECT 1 FROM sys_mail_template WHERE template_code = 'feedbackUserRepliedInapp');

-- 上述 INSERT 写了显式主键（sys_mail_template_id 为序列供号表），按规范抬号段上限
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT MAX(id) + 1000 FROM sys_mail_template)),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_mail_template_id';

-- 3) 订阅规则种子（行为与原硬编码等价：提交人通知走 PAYLOAD_FIELD=userId + 移动端双平台；
--    管理端通知走 ROLE=ADMIN + 仅 Windows 管理端）。规则 id 取 MAX+1。
INSERT INTO sys_notify_rule (id, rule_name, event_code, channel, template_id, recipient_type, recipient_value, platform, enabled, remark)
SELECT s.id, '反馈状态变更-通知提交人', 'feedbackStatusChanged', 'INAPP',
       (SELECT id FROM sys_mail_template WHERE template_code = 'feedbackStatusChangedInapp'),
       'PAYLOAD_FIELD', 'userId', 'app-android,app-ios', 1, '阶段⑥种子：行为等价于原硬编码'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_notify_rule) s
WHERE NOT EXISTS (SELECT 1 FROM sys_notify_rule WHERE rule_name = '反馈状态变更-通知提交人');

INSERT INTO sys_notify_rule (id, rule_name, event_code, channel, template_id, recipient_type, recipient_value, platform, enabled, remark)
SELECT s.id, '反馈公开发布-通知提交人', 'feedbackPublished', 'INAPP',
       (SELECT id FROM sys_mail_template WHERE template_code = 'feedbackPublishedInapp'),
       'PAYLOAD_FIELD', 'userId', 'app-android,app-ios', 1, '阶段⑥种子：行为等价于原硬编码'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_notify_rule) s
WHERE NOT EXISTS (SELECT 1 FROM sys_notify_rule WHERE rule_name = '反馈公开发布-通知提交人');

INSERT INTO sys_notify_rule (id, rule_name, event_code, channel, template_id, recipient_type, recipient_value, platform, enabled, remark)
SELECT s.id, '管理员回复-通知提交人', 'feedbackAdminReplied', 'INAPP',
       (SELECT id FROM sys_mail_template WHERE template_code = 'feedbackAdminRepliedInapp'),
       'PAYLOAD_FIELD', 'userId', 'app-android,app-ios', 1, '阶段⑥种子：行为等价于原硬编码'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_notify_rule) s
WHERE NOT EXISTS (SELECT 1 FROM sys_notify_rule WHERE rule_name = '管理员回复-通知提交人');

INSERT INTO sys_notify_rule (id, rule_name, event_code, channel, template_id, recipient_type, recipient_value, platform, enabled, remark)
SELECT s.id, '新反馈提交-通知管理员', 'feedbackNewSubmission', 'INAPP',
       (SELECT id FROM sys_mail_template WHERE template_code = 'feedbackNewSubmissionInapp'),
       'ROLE', 'ADMIN', 'app-windows', 1, '阶段⑥种子：按 ADMIN 角色展开群发'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_notify_rule) s
WHERE NOT EXISTS (SELECT 1 FROM sys_notify_rule WHERE rule_name = '新反馈提交-通知管理员');

INSERT INTO sys_notify_rule (id, rule_name, event_code, channel, template_id, recipient_type, recipient_value, platform, enabled, remark)
SELECT s.id, '用户回复反馈-通知管理员', 'feedbackUserReplied', 'INAPP',
       (SELECT id FROM sys_mail_template WHERE template_code = 'feedbackUserRepliedInapp'),
       'ROLE', 'ADMIN', 'app-windows', 1, '阶段⑥种子：按 ADMIN 角色展开群发'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_notify_rule) s
WHERE NOT EXISTS (SELECT 1 FROM sys_notify_rule WHERE rule_name = '用户回复反馈-通知管理员');

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT MAX(id) + 1000 FROM sys_notify_rule)),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_notify_rule_id';
