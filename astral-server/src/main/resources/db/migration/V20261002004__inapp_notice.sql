-- 通知子系统阶段③：站内信（INAPP 渠道）+ 系统公告。
-- 站内信按用户落库（投递即一行），模板复用 sys_mail_template（channel=INAPP，
-- 与邮件共用 (scene,channel) 唯一绑定，sys_mail_template.channel 合法值同步扩为 EMAIL/INAPP）。

-- ============ 系统公告 ============
CREATE TABLE IF NOT EXISTS sys_announcement (
    id BIGINT PRIMARY KEY,
    title VARCHAR(128) NOT NULL,
    content TEXT,
    level SMALLINT NOT NULL DEFAULT 1,
    status SMALLINT NOT NULL DEFAULT 0,
    publisher_id BIGINT,
    publisher_name VARCHAR(64),
    publish_time TIMESTAMP,
    offline_time TIMESTAMP,
    remark VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sys_announcement_status ON sys_announcement(status, publish_time);

-- ============ 站内信 ============
CREATE TABLE IF NOT EXISTS sys_notify_inapp (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(128),
    content TEXT,
    scene VARCHAR(64),
    read_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sys_notify_inapp_user ON sys_notify_inapp(user_id, read_time);

-- ============ 公告枚举字典（前端展示枚举走数据字典，AGENTS §3） ============
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (921, 'notify_notice_level', '公告级别', 'Integer', 'SMALLINT', 2, '系统公告重要级别', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (922, 'notify_notice_status', '公告状态', 'Integer', 'SMALLINT', 2, '系统公告生命周期状态', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9211, id, '普通', '1', 1, 1, '常规信息'
FROM sys_dict_type WHERE dict_code = 'notify_notice_level'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '1');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9212, id, '重要', '2', 2, 1, '需要用户关注'
FROM sys_dict_type WHERE dict_code = 'notify_notice_level'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '2');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9213, id, '紧急', '3', 3, 1, '需要用户立即处理'
FROM sys_dict_type WHERE dict_code = 'notify_notice_level'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '3');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9221, id, '草稿', '0', 1, 1, '仅管理端可见'
FROM sys_dict_type WHERE dict_code = 'notify_notice_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '0');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9222, id, '已发布', '1', 2, 1, '门户端可见；发布后正文锁定，需先下线才能改'
FROM sys_dict_type WHERE dict_code = 'notify_notice_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '1');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9223, id, '已下线', '2', 3, 1, '历史公告，门户端不可见'
FROM sys_dict_type WHERE dict_code = 'notify_notice_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = '2');

-- 字典种子写了显式主键，抬号段上限（全新库影响 0 行属预期）
UPDATE sequence_segment
SET max_value = GREATEST(max_value, 9223 + 1000), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
