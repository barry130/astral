-- 站内信补「来源标识」列（sys_notice.sender）。
-- 背景：站内信已是通用投递渠道（反馈插件 / 告警引擎 / 账号安全 / AI 公告 都会发），
-- 但表上没有来源列，消息中心无法按来源归类，消息统计只能靠事件码前缀猜发送方——
-- 猜前缀会把 AI 或系统发的站内信错记成反馈插件。来源改为由发送方在事件定义里显式声明，
-- 投递时原样落库，读侧空值显示 unknown（不猜）。
-- 历史数据回填：反馈插件的 5 个事件码都以 feedback 开头，回填 feedback 是安全的；
-- 其余历史行来源未知，保持 NULL（统计归到 unknown），不做猜测性回填。
ALTER TABLE sys_notice ADD COLUMN IF NOT EXISTS sender VARCHAR(48);

COMMENT ON COLUMN sys_notice.sender IS '来源标识(system/qt/ai/feedback，由事件定义声明，历史数据为空)';

UPDATE sys_notice
SET sender = 'feedback'
WHERE sender IS NULL
  AND scene LIKE 'feedback%';
