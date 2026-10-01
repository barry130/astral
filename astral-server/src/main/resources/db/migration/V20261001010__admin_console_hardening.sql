-- ============================================================================
-- V20261001010 管理端硬化批次：
--   ① stat_error_log 增加来源列（服务端自身 500 也登记，此前只收客户端上报）
--   ② sys_user 增加安全列（TOTP 二次验证 / 首登强制改密 / 封禁理由）
--   ③ 告警三表（渠道 / 规则 / 触发记录）
--   ④ 告警管理页菜单种子
-- 语义见 AGENTS.md「后台管理模块」与 docs/BACKUP_GUIDE.md（若涉及）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ① stat_error_log.source：client = 客户端上报（现状，默认值兜底存量），
--    server = 服务端 GlobalExceptionHandler 兜底 500 登记
-- ---------------------------------------------------------------------------
ALTER TABLE stat_error_log ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'client';
COMMENT ON COLUMN stat_error_log.source IS '错误来源：client=客户端上报 / server=服务端自身异常';

-- ---------------------------------------------------------------------------
-- ② sys_user 安全列
--    totp_secret: Base32 编码的 TOTP 密钥（RFC 6238），NULL=未绑定
--    totp_enabled: 1=登录需动态验证码；0/默认=未启用
--    must_change_password: 1=下次管理端登录强制改密（种子 admin 与管理员重置密码后置位，
--                          个人中心改密成功后清除；仅对管理端语义生效）
--    status_reason: 最近一次封禁/注销的理由（自助注销=「用户自助注销」），供用户管理页展示
-- ---------------------------------------------------------------------------
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(128);
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS totp_enabled SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS must_change_password SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS status_reason VARCHAR(255);
COMMENT ON COLUMN sys_user.totp_secret IS 'TOTP 密钥（Base32），NULL=未绑定';
COMMENT ON COLUMN sys_user.totp_enabled IS '是否启用 TOTP 二次验证：1=是';
COMMENT ON COLUMN sys_user.must_change_password IS '是否强制改密：1=下次管理端登录必须修改';
COMMENT ON COLUMN sys_user.status_reason IS '最近一次封禁/注销理由';

-- 种子 admin 若从未改过密码（pwd_update_time 为空），标记为下次登录强制改密：
-- 覆盖全新空库（admin/admin）与存量库仍用初始密码的情况；已改过密的管理员不受影响
UPDATE sys_user SET must_change_password = 1
WHERE username = 'admin' AND pwd_update_time IS NULL;

-- ---------------------------------------------------------------------------
-- ③ 告警三表
--    渠道 config 存 JSON：
--      EMAIL   -> {"accountId": <sys_mail_account.id>, "to": "收件邮箱"}
--      WEBHOOK -> {"url": "...", "secret": "可选请求头密钥值", "header": "可选请求头名，默认 X-Astral-Alert"}
--    规则 metric 取值（大小写不敏感，服务端规范化）：
--      SERVER_ERROR_COUNT  窗口内服务端 500 登记条数（stat_error_log.source=server）
--      CLIENT_ERROR_COUNT  窗口内客户端上报错误条数（stat_error_log.source=client）
--      HTTP_5XX_COUNT      窗口内 5xx 请求数（stat_api_hourly status>=500）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_alert_channel (
    id BIGINT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(20) NOT NULL,
    config TEXT NOT NULL,
    enabled SMALLINT NOT NULL DEFAULT 1,
    remark VARCHAR(255),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alert_channel_name UNIQUE (name)
);
COMMENT ON TABLE sys_alert_channel IS '告警通知渠道（EMAIL/WEBHOOK）';

CREATE TABLE IF NOT EXISTS sys_alert_rule (
    id BIGINT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    metric VARCHAR(32) NOT NULL,
    threshold BIGINT NOT NULL,
    window_minutes INT NOT NULL DEFAULT 5,
    channel_id BIGINT NOT NULL,
    cooldown_minutes INT NOT NULL DEFAULT 30,
    enabled SMALLINT NOT NULL DEFAULT 1,
    last_fired_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alert_rule_name UNIQUE (name)
);
COMMENT ON TABLE sys_alert_rule IS '告警规则（指标阈值 + 冷却时间）';

CREATE TABLE IF NOT EXISTS sys_alert_record (
    id BIGINT PRIMARY KEY,
    rule_id BIGINT NOT NULL,
    rule_name VARCHAR(64),
    channel_id BIGINT,
    channel_name VARCHAR(64),
    title VARCHAR(256),
    content TEXT,
    metric_value BIGINT,
    status VARCHAR(16) NOT NULL,
    error_msg VARCHAR(512),
    fired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE sys_alert_record IS '告警触发记录（status=SUCCESS/FAIL）';
CREATE INDEX IF NOT EXISTS idx_alert_record_fired_at ON sys_alert_record(fired_at);

-- ---------------------------------------------------------------------------
-- ④ 告警管理页菜单（单个页面，内含三个 Tab：渠道 / 规则 / 触发记录）
--    独立一级菜单，sort 取当前一级菜单最大 sort+1。
--    sys_menu 是序列供号表（IdType=INPUT）：按约束「显式 id + 同迁移内抬 max_value」，
--    取号 = 全局 MAX(id)+1，守卫条件用业务键 path，幂等。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, name, icon, path, permission, sort, visible, type)
SELECT
    (SELECT COALESCE(MAX(id), 0) + 1 FROM sys_menu m2),
    0, '告警管理', 'BellOutlined', '/dashboard/alert', 'alert:channel:view',
    (SELECT COALESCE(MAX(sort), 0) + 1 FROM sys_menu m3 WHERE m3.parent_id = 0),
    1, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m1 WHERE m1.path = '/dashboard/alert');

-- 同一迁移内把 sys_menu_id 的号段上限抬过 MAX(id)+step（默认步长 1000），
-- 防止运行时发号爬进本迁移写入的 id 区间（V20261001005 撞主键事故的防御约束）
UPDATE sequence_segment
SET max_value = (SELECT MAX(id) + 1000 FROM sys_menu),
    update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_menu_id'
  AND max_value < (SELECT MAX(id) + 1000 FROM sys_menu);
