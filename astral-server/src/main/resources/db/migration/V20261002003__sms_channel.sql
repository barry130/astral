-- 通知子系统阶段②：SMS 渠道。供应商池 + 供应商侧模板映射（正文在供应商备案审核，
-- 本地只存映射与审核态）+ 发送日志 + 审核状态字典。告警引擎新增 SMS 渠道
-- （复用 systemAlert 事件的短信模板，AlertConfigParser 校验 {"phone": ...}）。

-- ============ 短信供应商 ============
CREATE TABLE IF NOT EXISTS sys_sms_provider (
    id BIGINT PRIMARY KEY,
    provider_name VARCHAR(64) NOT NULL,
    provider_type VARCHAR(16) NOT NULL,
    access_key VARCHAR(128),
    access_secret VARCHAR(256),
    sign_name VARCHAR(64),
    region VARCHAR(32),
    endpoint VARCHAR(128),
    enabled SMALLINT NOT NULL DEFAULT 1,
    weight INT NOT NULL DEFAULT 1,
    remark VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sys_sms_provider_enabled ON sys_sms_provider(enabled);

-- ============ 短信模板（供应商侧映射） ============
CREATE TABLE IF NOT EXISTS sys_sms_template (
    id BIGINT PRIMARY KEY,
    template_code VARCHAR(64) NOT NULL,
    template_name VARCHAR(64) NOT NULL,
    event_code VARCHAR(64) NOT NULL,
    provider_template_code VARCHAR(64) NOT NULL,
    sign_name VARCHAR(64),
    content_sample TEXT,
    audit_status SMALLINT NOT NULL DEFAULT 0,
    audit_remark VARCHAR(256),
    remark VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_sms_template_code UNIQUE (template_code)
);
-- 同事件只允许绑定一个短信模板（SMS008 语义的数据库兜底）
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_sms_template_event ON sys_sms_template(event_code);

-- ============ 短信发送日志 ============
CREATE TABLE IF NOT EXISTS sys_sms_log (
    id BIGINT PRIMARY KEY,
    provider_id BIGINT,
    plugin_id VARCHAR(64),
    scene VARCHAR(64),
    phone VARCHAR(32),
    content TEXT,
    status SMALLINT NOT NULL DEFAULT 0,
    error_msg VARCHAR(512),
    send_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sys_sms_log_phone ON sys_sms_log(phone, send_time);

-- ============ 审核状态字典（前端展示枚举走数据字典，AGENTS §3） ============
-- id 段：类型 920、数据 9201~9204，落在全局序列预留段 [1, 1000000] 内
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (920, 'notify_sms_audit_status', '短信审核状态', 'Integer', 'SMALLINT', 2, '短信模板供应商侧报备审核状态', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9201, id, '草稿', '0', 1, 1, '本地登记，尚未提交供应商审核'
FROM sys_dict_type WHERE dict_code = 'notify_sms_audit_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '0');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9202, id, '审核中', '1', 2, 1, '已提交供应商，等待审核结果'
FROM sys_dict_type WHERE dict_code = 'notify_sms_audit_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '1');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9203, id, '已通过', '2', 3, 1, '供应商审核通过，可正常发送'
FROM sys_dict_type WHERE dict_code = 'notify_sms_audit_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '2');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9204, id, '已拒绝', '3', 4, 1, '供应商审核拒绝，见 audit_remark'
FROM sys_dict_type WHERE dict_code = 'notify_sms_audit_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '3');

-- 字典种子写了显式主键，按规范抬号段上限（全新库影响 0 行属预期）
UPDATE sequence_segment
SET max_value = GREATEST(max_value, 9201 + 1000), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
