-- 邮件场景绑定收紧：scene 从「自由文本标签」升级为「场景 -> 模板」的一等绑定。
-- 发送侧按 scene 解析模板（MailServiceImpl.resolveTemplate：先 scene 后 template_code），
-- 模板/授权写路径校验场景必须已在 MailSceneRegistry 登记（changePasswordByEmail / systemAlert）。

-- 1) 存量重复 scene 防御：同场景保留 update_time 最新的一条，其余置 NULL（否则唯一索引建不起来）
UPDATE sys_mail_template t
SET scene = NULL
WHERE t.scene IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM sys_mail_template newer
      WHERE newer.scene = t.scene AND newer.id <> t.id
        AND (newer.update_time > t.update_time
             OR (newer.update_time = t.update_time AND newer.id > t.id))
  );

-- 2) 同场景唯一绑定（PostgreSQL 唯一索引默认 NULLS DISTINCT，未绑定场景的模板不受影响）
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_mail_template_scene ON sys_mail_template(scene);

-- 3) 告警场景种子模板：告警引擎 EMAIL 渠道从「内置样式直发」升级为可后台改文案；
--    删除该模板即回退内置样式直发。不写死主键 id（取 MAX(id)+1，见下方抬号说明）。
INSERT INTO sys_mail_template (id, template_code, template_name, subject, content, variables, scene, remark)
SELECT s.id, 'systemAlert', '系统告警通知', '轻听音乐 - ${title}',
'<div style="font-family:sans-serif;max-width:560px;margin:0 auto;background:#fff;border-radius:10px;border:1px solid #eee;overflow:hidden;"><div style="background:#fef2f2;border-bottom:1px solid #fecaca;padding:18px 24px;"><div style="font-size:17px;font-weight:600;color:#b91c1c;">${title}</div><div style="margin-top:4px;font-size:12px;color:#ef4444;">Astral 告警通知</div></div><div style="padding:20px 24px;"><div style="font-size:13px;color:#333;line-height:1.7;white-space:pre-wrap;">${content}</div></div><div style="padding:12px 24px;background:#fafafa;font-size:12px;color:#bbb;border-top:1px solid #f0f0f0;">本邮件由告警引擎自动发送，请勿直接回复</div></div>',
'["title","content"]', 'systemAlert', '告警引擎 EMAIL 渠道通知模板；删除后告警回退内置样式直发'
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM sys_mail_template) s
WHERE NOT EXISTS (SELECT 1 FROM sys_mail_template WHERE template_code = 'systemAlert');

-- 4) 上述 INSERT 写了显式主键（sys_mail_template_id 为序列供号表），按规范把号段上限
--    抬到 MAX(id)+step 以上，避免该 id 落进运行时发号区间被二次发出（V20261001005 撞主键事故的
--    系统性防御；全新库此时 sequence_segment 尚无该行，由 SequencePlugin 启动预置 [1,1000000] 段，
--    显式 id 天然落在预留区内，本语句影响 0 行属预期）
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT MAX(id) + 1000 FROM sys_mail_template)),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_mail_template_id';
