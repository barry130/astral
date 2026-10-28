-- ============================================================================
-- 消息模板合并：邮箱模板 / 站内信模板 / 短信模板 → 一套「消息模板」
--
-- 【背景】
--   管理端原来两个页面各管一半：邮箱模板（sys_mail_template，channel=EMAIL）
--   与短信模板（sys_sms_template）。同一个业务事件（如「反馈状态变更」）要在两处
--   各建一条，内容还对不上。合并后：一条模板 = 编码 + 绑定事件 + 渠道多选，
--   勾了 EMAIL/INAPP 就落 sys_mail_template 两行（同编码、同 scene、不同 channel），
--   勾了 SMS 就落 sys_sms_template 一行。
--
-- 【为什么要改唯一键】
--   sys_mail_template 原唯一约束 uk_sys_mail_template_code 只允许一个编码出现一次，
--   合并后同一编码必然要在 EMAIL 和 INAPP 各存一行，故放宽为 (template_code, channel)；
--   (scene, channel) 的唯一约束保持不变——「一个事件的一个渠道只有一条模板」仍然是硬规则，
--   否则发送侧按 (scene, channel) 取模板就取不准。
--
-- 【兼容性】
--   纯放宽约束，现有数据（每个编码只有一行）不受影响；发送侧查询全部按 (scene, channel)，
--   不依赖 template_code 全局唯一，行为不变。
-- ============================================================================

DO $$
BEGIN
    IF to_regclass('sys_mail_template') IS NOT NULL THEN
        ALTER TABLE sys_mail_template DROP CONSTRAINT IF EXISTS uk_sys_mail_template_code;
        DROP INDEX IF EXISTS uk_sys_mail_template_code;
        CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_mail_template_code_channel
            ON sys_mail_template (template_code, channel);
    END IF;
END $$;
