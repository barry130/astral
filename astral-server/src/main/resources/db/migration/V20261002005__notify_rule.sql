-- 通知子系统阶段④：事件订阅规则表。事件 × 渠道 → 模板 + 收件人（FIXED / PAYLOAD_FIELD），
-- 发布事件按启用规则逐条投递（NotifyPublisher，逐规则隔离、结果逐条回报）。

CREATE TABLE IF NOT EXISTS sys_notify_rule (
    id BIGINT PRIMARY KEY,
    rule_name VARCHAR(64) NOT NULL,
    event_code VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    template_id BIGINT NOT NULL,
    recipient_type VARCHAR(16) NOT NULL DEFAULT 'FIXED',
    recipient_value VARCHAR(128),
    enabled SMALLINT NOT NULL DEFAULT 1,
    remark VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_notify_rule_name UNIQUE (rule_name)
);
CREATE INDEX IF NOT EXISTS idx_sys_notify_rule_event ON sys_notify_rule(event_code, enabled);
