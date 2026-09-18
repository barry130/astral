-- ============================================================
-- Flyway V1 基线初始化脚本（仅全新空库执行；存量库由 baseline-on-migrate 跳过）
--
-- 本文件由原手工迁移脚本 V1__baseline_schema / V2__baseline_dict /
-- V3__baseline_dict_sequence_reset / V4__storage_plugin 按序合并而成，
-- SQL 内容逐字保留（历史应用过的库与全新库产物一致）。
-- 注意：Flyway 对已应用脚本做校验和比对，本文件一经发布禁止再修改，
--       后续变更一律新增 V{N}__xxx.sql。
-- ============================================================


-- ============================================================
-- 基线 1/4：全部宿主建表 + 核心种子数据
-- 宿主全部表结构与种子数据（幂等：IF NOT EXISTS / ON CONFLICT）
-- ============================================================

-- ============================================
-- PostgreSQL 初始化脚本（完整建表 + 核心种子数据）
-- 说明：业务表主键由全局序列生成（应用层取号），故用 BIGINT PRIMARY KEY；
--       序列系统自身的 4 张表（sequence_*）仍由数据库自增（BIGSERIAL）维护。
-- ============================================

-- 序列号段表
CREATE TABLE IF NOT EXISTS sequence_segment (
    id BIGSERIAL PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL UNIQUE,
    min_value BIGINT NOT NULL DEFAULT 1,
    max_value BIGINT NOT NULL,
    current_max_value BIGINT NOT NULL,
    step INT NOT NULL DEFAULT 1000,
    version INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_segment_biz_key ON sequence_segment(biz_key);
CREATE INDEX IF NOT EXISTS idx_segment_update_time ON sequence_segment(update_time);

-- 序列配置表
CREATE TABLE IF NOT EXISTS sequence_config (
    id BIGSERIAL PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL UNIQUE,
    sequence_type VARCHAR(32) NOT NULL,
    step INT NOT NULL DEFAULT 1000,
    date_format VARCHAR(32),
    prefix VARCHAR(32),
    suffix VARCHAR(32),
    min_value BIGINT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_config_enabled ON sequence_config(enabled);

-- 序列使用统计表
CREATE TABLE IF NOT EXISTS sequence_statistics (
    id BIGSERIAL PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL,
    current_value BIGINT NOT NULL DEFAULT 0,
    total_generate BIGINT NOT NULL DEFAULT 0,
    last_generate_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_statistics_biz_key ON sequence_statistics(biz_key);

-- 序列历史记录表
CREATE TABLE IF NOT EXISTS sequence_history (
    id BIGSERIAL PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL,
    sequence_type VARCHAR(32) NOT NULL,
    sequence_value BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_history_biz_key ON sequence_history(biz_key);
CREATE INDEX IF NOT EXISTS idx_history_create_time ON sequence_history(create_time);

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    nickname VARCHAR(64),
    email VARCHAR(128),
    phone VARCHAR(32),
    avatar VARCHAR(256),
    status SMALLINT NOT NULL DEFAULT 1,
    login_ip VARCHAR(64),
    login_time TIMESTAMP,
    pwd_update_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE INDEX IF NOT EXISTS idx_sys_user_status ON sys_user(status);
CREATE INDEX IF NOT EXISTS idx_sys_user_email ON sys_user(email);
CREATE INDEX IF NOT EXISTS idx_sys_user_phone ON sys_user(phone);

-- 用户体系统一：区分管理端(ADMIN)与插件 App 端(APP)用户，device_id 为 App 端设备标识
-- 幂等：重复启动仅 no-op
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS user_type VARCHAR(20) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS device_id VARCHAR(128);

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    status SMALLINT NOT NULL DEFAULT 1,
    sort INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_role_code UNIQUE (role_code)
);

-- 权限表
CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT PRIMARY KEY,
    permission_code VARCHAR(128) NOT NULL,
    permission_name VARCHAR(128) NOT NULL,
    url VARCHAR(256),
    method VARCHAR(16),
    parent_id BIGINT NOT NULL DEFAULT 0,
    type SMALLINT NOT NULL DEFAULT 1,
    icon VARCHAR(64),
    sort INT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_permission_code UNIQUE (permission_code)
);

CREATE INDEX IF NOT EXISTS idx_sys_permission_parent_id ON sys_permission(parent_id);
CREATE INDEX IF NOT EXISTS idx_sys_permission_type ON sys_permission(type);

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_role UNIQUE (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_sys_user_role_role_id ON sys_user_role(role_id);

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGINT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_role_permission UNIQUE (role_id, permission_id)
);

CREATE INDEX IF NOT EXISTS idx_sys_role_permission_permission_id ON sys_role_permission(permission_id);

-- 字典类型表
CREATE TABLE IF NOT EXISTS sys_dict_type (
    id BIGINT PRIMARY KEY,
    dict_code VARCHAR(64) NOT NULL,
    dict_name VARCHAR(128) NOT NULL,
    data_type VARCHAR(32),
    jdbc_type VARCHAR(32),
    data_length INT,
    description VARCHAR(1024),
    status SMALLINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_dict_type_code UNIQUE (dict_code)
);

-- 字典数据表
CREATE TABLE IF NOT EXISTS sys_dict_data (
    id BIGINT PRIMARY KEY,
    dict_type_id BIGINT NOT NULL,
    dict_label VARCHAR(128) NOT NULL,
    dict_value VARCHAR(128) NOT NULL,
    dict_sort INT NOT NULL DEFAULT 0,
    css_class VARCHAR(128),
    list_class VARCHAR(128),
    is_default SMALLINT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 1,
    description VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dict_data_type_id ON sys_dict_data(dict_type_id);
CREATE INDEX IF NOT EXISTS idx_dict_data_status ON sys_dict_data(status);

-- 系统配置表
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT PRIMARY KEY,
    config_name VARCHAR(128) NOT NULL,
    config_key VARCHAR(128) NOT NULL,
    config_value TEXT,
    config_type SMALLINT NOT NULL DEFAULT 1,
    description VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_config_key UNIQUE (config_key)
);

CREATE INDEX IF NOT EXISTS idx_sys_config_type ON sys_config(config_type);

-- ============ 邮件子系统 ============
CREATE TABLE IF NOT EXISTS sys_mail_account (
    id BIGINT PRIMARY KEY,
    account_name VARCHAR(64) NOT NULL,
    smtp_host VARCHAR(128) NOT NULL,
    smtp_port INT NOT NULL,
    username VARCHAR(128) NOT NULL,
    password VARCHAR(256) NOT NULL,
    from_addr VARCHAR(128) NOT NULL,
    from_name VARCHAR(64),
    enabled SMALLINT NOT NULL DEFAULT 1,
    ssl_enable SMALLINT NOT NULL DEFAULT 1,
    starttls_enable SMALLINT NOT NULL DEFAULT 0,
    weight INT NOT NULL DEFAULT 1,
    remark VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sys_mail_account_enabled ON sys_mail_account(enabled);

CREATE TABLE IF NOT EXISTS sys_mail_template (
    id BIGINT PRIMARY KEY,
    template_code VARCHAR(64) NOT NULL,
    template_name VARCHAR(64) NOT NULL,
    subject VARCHAR(256),
    content TEXT,
    variables VARCHAR(512),
    scene VARCHAR(64),
    remark VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_mail_template_code UNIQUE (template_code)
);

CREATE TABLE IF NOT EXISTS sys_mail_log (
    id BIGINT PRIMARY KEY,
    account_id BIGINT,
    plugin_id VARCHAR(64),
    scene VARCHAR(64),
    to_email VARCHAR(128),
    subject VARCHAR(256),
    content TEXT,
    status SMALLINT NOT NULL DEFAULT 0,
    error_msg VARCHAR(512),
    send_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sys_mail_log_to ON sys_mail_log(to_email, send_time);

CREATE TABLE IF NOT EXISTS sys_mail_plugin_auth (
    id BIGINT PRIMARY KEY,
    plugin_id VARCHAR(64) NOT NULL,
    plugin_name VARCHAR(64),
    enabled SMALLINT NOT NULL DEFAULT 0,
    daily_limit INT NOT NULL DEFAULT 2,
    allowed_scenes VARCHAR(512),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_mail_plugin_auth_pid UNIQUE (plugin_id)
);

-- 邮件账号占位示例（默认停用 enabled=0）：
-- 真实 SMTP 授权码/邮箱请勿写入版本库，部署后在管理后台「系统 -> 邮件账号」中配置。
INSERT INTO sys_mail_account (id, account_name, smtp_host, smtp_port, username, password, from_addr, from_name, enabled, ssl_enable, starttls_enable, weight)
VALUES (1, '示例邮箱', 'smtp.example.com', 465, 'noreply@example.com', 'CHANGE_ME_SMTP_PASSWORD', 'noreply@example.com', '示例发件人', 0, 1, 0, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_mail_template (id, template_code, template_name, subject, content, variables, scene, remark)
VALUES (1, 'changePasswordByEmail', '密码重置验证码', '轻听音乐 - 获取验证码',
'<div style="font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC",sans-serif;max-width:480px;margin:0 auto;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,0.06);"><div style="background:linear-gradient(135deg,#6a5cff,#9b7bff);padding:26px 28px;color:#fff;"><div style="font-size:20px;font-weight:600;">轻听音乐</div><div style="margin-top:6px;font-size:13px;opacity:.85;">账号安全验证</div></div><div style="padding:28px;"><div style="font-size:15px;color:#333;line-height:1.6;">您好，</div><div style="margin-top:12px;font-size:15px;color:#333;line-height:1.6;">您正在获取轻听音乐账号验证码，<span style="color:#e0533d;font-weight:600;">10 分钟内</span>有效：</div><div style="margin:22px 0;text-align:center;"><span style="display:inline-block;font-size:30px;font-weight:700;letter-spacing:6px;color:#6a5cff;background:#f3f1ff;border:1px solid #e2dcff;border-radius:10px;padding:14px 26px;font-family:"Courier New",monospace;">${code}</span></div><div style="font-size:13px;color:#999;line-height:1.6;">如非本人操作，请忽略此邮件并注意账号安全。</div></div><div style="padding:16px 28px;background:#fafafa;font-size:12px;color:#bbb;border-top:1px solid #f0f0f0;">本邮件由系统自动发送，请勿直接回复 © 轻听音乐</div></div>',
'["code"]', 'changePasswordByEmail', '密码重置/获取验证码')
ON CONFLICT (template_code) DO NOTHING;

INSERT INTO sys_mail_plugin_auth (id, plugin_id, plugin_name, enabled, daily_limit, allowed_scenes)
VALUES (1, 'qt', '轻听App', 1, 2, NULL)
ON CONFLICT (plugin_id) DO NOTHING;

-- 插件状态表
CREATE TABLE IF NOT EXISTS sys_plugin (
    id BIGINT PRIMARY KEY,
    plugin_id VARCHAR(64) NOT NULL,
    enabled SMALLINT NOT NULL DEFAULT 1,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_plugin_id UNIQUE (plugin_id)
);

-- 操作日志表
CREATE TABLE IF NOT EXISTS sys_operate_log (
    id BIGINT PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(64),
    module VARCHAR(64) NOT NULL,
    operate_type VARCHAR(32) NOT NULL,
    request_method VARCHAR(16) NOT NULL,
    request_url VARCHAR(256) NOT NULL,
    request_params TEXT,
    response_result TEXT,
    ip VARCHAR(64),
    location VARCHAR(128),
    status SMALLINT NOT NULL DEFAULT 1,
    error_msg TEXT,
    execute_time BIGINT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sys_operate_log_user_id ON sys_operate_log(user_id);
CREATE INDEX IF NOT EXISTS idx_sys_operate_log_operate_type ON sys_operate_log(operate_type);
CREATE INDEX IF NOT EXISTS idx_sys_operate_log_create_time ON sys_operate_log(create_time);
CREATE INDEX IF NOT EXISTS idx_sys_operate_log_status ON sys_operate_log(status);

-- 登录日志表
CREATE TABLE IF NOT EXISTS sys_login_log (
    id BIGINT PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(64) NOT NULL,
    login_type VARCHAR(32) NOT NULL DEFAULT 'PASSWORD',
    ip VARCHAR(64) NOT NULL,
    location VARCHAR(128),
    browser VARCHAR(64),
    os VARCHAR(64),
    status SMALLINT NOT NULL DEFAULT 1,
    msg VARCHAR(256),
    login_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sys_login_log_user_id ON sys_login_log(user_id);
CREATE INDEX IF NOT EXISTS idx_sys_login_log_username ON sys_login_log(username);
CREATE INDEX IF NOT EXISTS idx_sys_login_log_status ON sys_login_log(status);
CREATE INDEX IF NOT EXISTS idx_sys_login_log_login_time ON sys_login_log(login_time);

-- 菜单管理表
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT PRIMARY KEY,
    parent_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(64) NOT NULL,
    icon VARCHAR(64),
    path VARCHAR(256),
    permission VARCHAR(128),
    sort INT NOT NULL DEFAULT 0,
    visible SMALLINT NOT NULL DEFAULT 1,
    type SMALLINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sys_menu_parent ON sys_menu(parent_id);
CREATE INDEX IF NOT EXISTS idx_sys_menu_sort ON sys_menu(sort);

-- 集群节点配置表
CREATE TABLE IF NOT EXISTS cluster_node_config (
    id BIGINT PRIMARY KEY,
    node_id VARCHAR(64) NOT NULL,
    worker_id INT NOT NULL,
    ip_address VARCHAR(64) NOT NULL,
    port INT NOT NULL DEFAULT 8080,
    node_name VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'ONLINE',
    max_worker_id INT NOT NULL DEFAULT 1023,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_cluster_node_id UNIQUE (node_id),
    CONSTRAINT uk_cluster_worker_id UNIQUE (worker_id)
);

CREATE INDEX IF NOT EXISTS idx_cluster_node_status ON cluster_node_config(status);
CREATE INDEX IF NOT EXISTS idx_cluster_node_enabled ON cluster_node_config(enabled);

-- ============================================
-- 核心种子数据
-- ============================================

-- 演示业务键号段
INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
VALUES
    ('order_id', 1, 1000, 1000, 1000),
    ('user_id', 1, 1000, 1000, 1000),
    ('payment_id', 1, 1000, 1000, 1000)
ON CONFLICT (biz_key) DO NOTHING;

-- 管理员用户（密码: admin）
INSERT INTO sys_user (id, username, password, nickname, status)
VALUES (1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuPiSQ5e6YnpjfPFWezevy2Mqt7R1D1RC', '系统管理员', 1)
ON CONFLICT DO NOTHING;

-- 角色
INSERT INTO sys_role (id, role_code, role_name, description, sort)
VALUES (1, 'ADMIN', '系统管理员', '系统管理员，拥有所有权限', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_role (id, role_code, role_name, description, sort)
VALUES (2, 'USER', '普通用户', '普通用户，拥有基本权限', 2)
ON CONFLICT DO NOTHING;

-- 权限
INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (1, 'sequence:view', '查看序列', '/api/v1/sequence/**', NULL, 1, 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (2, 'sequence:generate', '生成序列', '/api/v1/sequence/next', 'POST', 2, 2)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (3, 'sequence:batch', '批量生成', '/api/v1/sequence/batch', 'POST', 2, 3)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (4, 'log:view', '查看日志', '/api/v1/log/**', NULL, 1, 4)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (6, 'cluster:view', '集群管理', '/api/v1/cluster/**', NULL, 1, 6)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (7, 'system:user:view', '用户管理', '/api/v1/system/user/**', NULL, 1, 10)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (8, 'system:role:view', '角色管理', '/api/v1/system/role/**', NULL, 1, 11)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (9, 'system:permission:view', '权限管理', '/api/v1/system/permission/**', NULL, 1, 12)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (10, 'system:dict:view', '数据字典', '/api/v1/system/dict/**', NULL, 1, 13)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (11, 'system:config:view', '系统配置', '/api/v1/system/config/**', NULL, 1, 14)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (12, 'system:token:view', 'Token管理', '/api/v1/system/token/**', NULL, 1, 15)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (13, 'system:schema:view', '表结构管理', '/api/v1/system/table-schema/**', NULL, 1, 16)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (14, 'plugin:view', '插件管理', '/api/v1/plugin/**', NULL, 1, 17)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (15, '*:*:*', '超级管理员', '*', NULL, 0, 0)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort, status)
VALUES (16, 'system:menu:view', '菜单管理', '/api/v1/system/menu/**', NULL, 0, 16, 1)
ON CONFLICT DO NOTHING;

-- 邮箱管理 / 邮箱统计（邮件模块权限，AGENTS.md：管理端接口须纳入权限管理）
INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort, status)
VALUES (17, 'system:mail:view', '邮箱管理', '/api/v1/admin/system/mail/**', NULL, 1, 18, 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort, status)
VALUES (18, 'system:mail:statistics:view', '邮箱统计', '/api/v1/admin/system/mail/log/statistics', NULL, 1, 19, 1)
ON CONFLICT DO NOTHING;

-- 邮箱管理写权限（按钮级 type=2，挂 system:mail:view 之下；USER 角色只自动获得 *:view，写操作仅管理员）
INSERT INTO sys_permission (id, parent_id, permission_code, permission_name, url, method, type, sort, status)
VALUES (19, 17, 'system:mail:account:edit', '邮箱账户维护', '/api/v1/admin/system/mail/account/**', NULL, 2, 20, 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, parent_id, permission_code, permission_name, url, method, type, sort, status)
VALUES (20, 17, 'system:mail:template:edit', '邮箱模板维护', '/api/v1/admin/system/mail/template/**', NULL, 2, 21, 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_permission (id, parent_id, permission_code, permission_name, url, method, type, sort, status)
VALUES (21, 17, 'system:mail:plugin-auth:edit', '发信授权维护', '/api/v1/admin/system/mail/plugin-auth/**', NULL, 2, 22, 1)
ON CONFLICT DO NOTHING;

-- 用户-角色关联
INSERT INTO sys_user_role (id, user_id, role_id)
SELECT 1, u.id, r.id
FROM sys_user u, sys_role r
WHERE u.username = 'admin'
  AND r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

-- 角色-权限关联（管理员拥有全部权限）
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT p.id, r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 角色-权限关联（普通用户拥有查看权限）
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT 1000 + p.id, r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'USER'
  AND p.permission_code LIKE '%:view'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 系统配置初始数据
INSERT INTO sys_config (id, config_name, config_key, config_value, config_type, description)
VALUES (1, '系统名称', 'sys.site.name', 'Astral 序列管理系统', 1, '系统显示名称')
ON CONFLICT DO NOTHING;

INSERT INTO sys_config (id, config_name, config_key, config_value, config_type, description)
VALUES (2, '默认序列类型', 'sequence.default.type', 'SEGMENT', 1, '新建业务键时默认使用的序列类型')
ON CONFLICT DO NOTHING;

INSERT INTO sys_config (id, config_name, config_key, config_value, config_type, description)
VALUES (3, '默认号段步长', 'sequence.default.step', '1000', 1, '号段模式默认步长大小')
ON CONFLICT DO NOTHING;

INSERT INTO sys_config (id, config_name, config_key, config_value, config_type, description)
VALUES (900001, '邮箱每日发送上限', 'qt.mail.daily.limit', '2', 2, '每个账号每天最多发送邮件封数(参数管理可改)')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO sys_config (id, config_name, config_key, config_value, config_type, description)
VALUES (900002, '反馈/需求通知保留天数', 'notice.feedback.retention_days', '7', 2,
        'feedback/request 类通知仅展示最近 N 天；announce 公告不受限。0=永久')
ON CONFLICT (config_key) DO NOTHING;

-- 菜单初始化
INSERT INTO sys_menu (id, parent_id, name, icon, path, permission, sort, visible, type)
VALUES
    (1, 0, '仪表盘', 'DashboardOutlined', '/dashboard', NULL, 1, 1, 0),
    (4, 0, '系统管理', 'SettingOutlined', NULL, NULL, 4, 1, 0),
    (5, 4, '集群管理', 'ClusterOutlined', '/dashboard/cluster', 'cluster:view', 1, 1, 0),
    (6, 4, '用户管理', 'TeamOutlined', '/dashboard/system/user', 'system:user:view', 2, 1, 0),
    (7, 4, '角色权限', 'SafetyOutlined', '/dashboard/system/role', 'system:role:view', 3, 1, 0),
    (8, 4, '权限管理', 'SafetyOutlined', '/dashboard/system/permission', 'system:permission:view', 4, 1, 0),
    (9, 4, '数据字典', 'BookOutlined', '/dashboard/system/dict', 'system:dict:view', 5, 1, 0),
    (10, 4, '系统配置', 'SettingOutlined', '/dashboard/system/config', 'system:config:view', 6, 1, 0),
    (11, 4, 'Token管理', 'KeyOutlined', '/dashboard/system/token', 'system:token:view', 7, 1, 0),
    (12, 4, '表结构管理', 'TableOutlined', '/dashboard/system/table-schema', 'system:schema:view', 8, 1, 0),
    (13, 4, '菜单管理', 'MenuOutlined', '/dashboard/system/menu', 'system:menu:view', 9, 1, 0),
    (14, 0, '日志管理', 'FileTextOutlined', '/dashboard/log', 'log:view', 5, 1, 0),
    (15, 0, '插件管理', 'BlockOutlined', '/dashboard/plugin', 'plugin:view', 6, 1, 0)
ON CONFLICT (id) DO NOTHING;

-- 邮箱管理菜单（单个页面，内含三个 Tab：邮箱帐户 / 邮箱模板 / 发信授权）
DELETE FROM sys_menu WHERE id IN (20, 21, 22, 23);
INSERT INTO sys_menu (id, parent_id, name, icon, path, permission, sort, visible, type)
VALUES
    (20, 4, '邮箱管理', 'MailOutlined', '/dashboard/system/mail', 'system:mail:view', 10, 1, 1),
    (24, 4, '邮箱统计', 'BarChartOutlined', '/dashboard/system/mail/log', 'system:mail:statistics:view', 11, 1, 1)
ON CONFLICT (id) DO NOTHING;
-- 存量菜单权限为空时回填（INSERT 冲突即跳过，老库不会自动带上新权限标识）
UPDATE sys_menu SET permission = 'system:mail:view'
WHERE id = 20 AND (permission IS NULL OR permission = '');
UPDATE sys_menu SET permission = 'system:mail:statistics:view'
WHERE id = 24 AND (permission IS NULL OR permission = '');

-- 序列管理菜单改由 SequencePlugin 前端导航扩展动态注入（仿 qt 插件），
-- 此处清理历史静态种子，避免与插件注入项重复。幂等：无记录时为 no-op。
DELETE FROM sys_menu WHERE id = 2 AND path = '/dashboard/sequence';

-- 字段注释（幂等，启动可重复执行）
COMMENT ON COLUMN sequence_segment.id IS '主键ID';
COMMENT ON COLUMN sequence_segment.biz_key IS '业务键';
COMMENT ON COLUMN sequence_segment.min_value IS '号段起始值';
COMMENT ON COLUMN sequence_segment.max_value IS '号段结束值';
COMMENT ON COLUMN sequence_segment.current_max_value IS '当前已分配最大值';
COMMENT ON COLUMN sequence_segment.step IS '步长';
COMMENT ON COLUMN sequence_segment.version IS '乐观锁版本号';
COMMENT ON COLUMN sequence_segment.create_time IS '创建时间';
COMMENT ON COLUMN sequence_segment.update_time IS '更新时间';
COMMENT ON COLUMN sequence_config.id IS '主键ID';
COMMENT ON COLUMN sequence_config.biz_key IS '业务键';
COMMENT ON COLUMN sequence_config.sequence_type IS '序列类型(SEGMENT-号段 SNOWFLAKE-雪花 REDIS-Redis DATABASE-数据库 SIMPLE-内存)';
COMMENT ON COLUMN sequence_config.step IS '步长';
COMMENT ON COLUMN sequence_config.date_format IS '日期格式(none-无 yyyyMMdd-按日 yyyyMM-按月 yyyy-按年)';
COMMENT ON COLUMN sequence_config.prefix IS '前缀';
COMMENT ON COLUMN sequence_config.suffix IS '后缀';
COMMENT ON COLUMN sequence_config.min_value IS '最小值';
COMMENT ON COLUMN sequence_config.enabled IS '是否启用(0-禁用 1-启用)';
COMMENT ON COLUMN sequence_config.description IS '描述';
COMMENT ON COLUMN sequence_config.create_time IS '创建时间';
COMMENT ON COLUMN sequence_config.update_time IS '更新时间';
COMMENT ON COLUMN sequence_statistics.id IS '主键ID';
COMMENT ON COLUMN sequence_statistics.biz_key IS '业务键';
COMMENT ON COLUMN sequence_statistics.current_value IS '当前值';
COMMENT ON COLUMN sequence_statistics.total_generate IS '总生成数';
COMMENT ON COLUMN sequence_statistics.last_generate_time IS '最后生成时间';
COMMENT ON COLUMN sequence_statistics.create_time IS '创建时间';
COMMENT ON COLUMN sequence_statistics.update_time IS '更新时间';
COMMENT ON COLUMN sequence_statistics.deleted IS '逻辑删除(0-未删除 1-已删除)';
COMMENT ON COLUMN sequence_history.id IS '主键ID';
COMMENT ON COLUMN sequence_history.biz_key IS '业务键';
COMMENT ON COLUMN sequence_history.sequence_type IS '序列类型(SEGMENT-号段 SNOWFLAKE-雪花 REDIS-Redis DATABASE-数据库 SIMPLE-内存)';
COMMENT ON COLUMN sequence_history.sequence_value IS '序列值';
COMMENT ON COLUMN sequence_history.create_time IS '创建时间';
COMMENT ON COLUMN sys_user.id IS '主键ID';
COMMENT ON COLUMN sys_user.username IS '用户名';
COMMENT ON COLUMN sys_user.password IS '密码';
COMMENT ON COLUMN sys_user.nickname IS '昵称';
COMMENT ON COLUMN sys_user.email IS '邮箱';
COMMENT ON COLUMN sys_user.phone IS '手机号';
COMMENT ON COLUMN sys_user.avatar IS '头像URL';
COMMENT ON COLUMN sys_user.status IS '状态(0-禁用 1-启用)';
COMMENT ON COLUMN sys_user.login_ip IS '最后登录IP';
COMMENT ON COLUMN sys_user.login_time IS '最后登录时间';
COMMENT ON COLUMN sys_user.pwd_update_time IS '密码更新时间';
COMMENT ON COLUMN sys_user.create_time IS '创建时间';
COMMENT ON COLUMN sys_user.update_time IS '更新时间';
COMMENT ON COLUMN sys_user.deleted IS '逻辑删除(0-未删除 1-已删除)';
COMMENT ON COLUMN sys_role.id IS '主键ID';
COMMENT ON COLUMN sys_role.role_code IS '角色编码';
COMMENT ON COLUMN sys_role.role_name IS '角色名称';
COMMENT ON COLUMN sys_role.description IS '描述';
COMMENT ON COLUMN sys_role.status IS '状态(0-禁用 1-启用)';
COMMENT ON COLUMN sys_role.sort IS '排序';
COMMENT ON COLUMN sys_role.create_time IS '创建时间';
COMMENT ON COLUMN sys_role.update_time IS '更新时间';
COMMENT ON COLUMN sys_permission.id IS '主键ID';
COMMENT ON COLUMN sys_permission.permission_code IS '权限编码';
COMMENT ON COLUMN sys_permission.permission_name IS '权限名称';
COMMENT ON COLUMN sys_permission.url IS '请求URL';
COMMENT ON COLUMN sys_permission.method IS '请求方法';
COMMENT ON COLUMN sys_permission.parent_id IS '父级ID';
COMMENT ON COLUMN sys_permission.type IS '类型(1-目录 2-菜单 3-按钮)';
COMMENT ON COLUMN sys_permission.icon IS '图标';
COMMENT ON COLUMN sys_permission.sort IS '排序';
COMMENT ON COLUMN sys_permission.status IS '状态(0-禁用 1-启用)';
COMMENT ON COLUMN sys_permission.create_time IS '创建时间';
COMMENT ON COLUMN sys_permission.update_time IS '更新时间';
COMMENT ON COLUMN sys_user_role.id IS '主键ID';
COMMENT ON COLUMN sys_user_role.user_id IS '用户ID';
COMMENT ON COLUMN sys_user_role.role_id IS '角色ID';
COMMENT ON COLUMN sys_user_role.create_time IS '创建时间';
COMMENT ON COLUMN sys_role_permission.id IS '主键ID';
COMMENT ON COLUMN sys_role_permission.role_id IS '角色ID';
COMMENT ON COLUMN sys_role_permission.permission_id IS '权限ID';
COMMENT ON COLUMN sys_role_permission.create_time IS '创建时间';
COMMENT ON COLUMN sys_dict_type.id IS '主键ID';
COMMENT ON COLUMN sys_dict_type.dict_code IS '字典编码';
COMMENT ON COLUMN sys_dict_type.dict_name IS '字典名称';
COMMENT ON COLUMN sys_dict_type.data_type IS '数据类型(1-字符串 2-数字 3-布尔 4-日期 5-JSON)';
COMMENT ON COLUMN sys_dict_type.jdbc_type IS 'JDBC类型(VARCHAR-字符串 INT-整数 BIGINT-长整数 DECIMAL-小数 TIMESTAMP-时间 DATE-日期 BIT-布尔)';
COMMENT ON COLUMN sys_dict_type.data_length IS '数据长度';
COMMENT ON COLUMN sys_dict_type.description IS '描述';
COMMENT ON COLUMN sys_dict_type.status IS '状态(0-禁用 1-启用)';
COMMENT ON COLUMN sys_dict_type.create_time IS '创建时间';
COMMENT ON COLUMN sys_dict_type.update_time IS '更新时间';
COMMENT ON COLUMN sys_dict_data.id IS '主键ID';
COMMENT ON COLUMN sys_dict_data.dict_type_id IS '字典类型ID';
COMMENT ON COLUMN sys_dict_data.dict_label IS '字典标签';
COMMENT ON COLUMN sys_dict_data.dict_value IS '字典值';
COMMENT ON COLUMN sys_dict_data.dict_sort IS '排序';
COMMENT ON COLUMN sys_dict_data.css_class IS 'CSS样式';
COMMENT ON COLUMN sys_dict_data.list_class IS '表格回显样式';
COMMENT ON COLUMN sys_dict_data.is_default IS '是否默认(0-否 1-是)';
COMMENT ON COLUMN sys_dict_data.status IS '状态(0-禁用 1-启用)';
COMMENT ON COLUMN sys_dict_data.description IS '描述';
COMMENT ON COLUMN sys_dict_data.create_time IS '创建时间';
COMMENT ON COLUMN sys_dict_data.update_time IS '更新时间';
COMMENT ON COLUMN sys_config.id IS '主键ID';
COMMENT ON COLUMN sys_config.config_name IS '配置名称';
COMMENT ON COLUMN sys_config.config_key IS '配置键';
COMMENT ON COLUMN sys_config.config_value IS '配置值';
COMMENT ON COLUMN sys_config.config_type IS '配置类型(1-系统内置 2-自定义)';
COMMENT ON COLUMN sys_config.description IS '描述';
COMMENT ON COLUMN sys_config.create_time IS '创建时间';
COMMENT ON COLUMN sys_config.update_time IS '更新时间';
COMMENT ON COLUMN sys_plugin.id IS '主键ID';
COMMENT ON COLUMN sys_plugin.plugin_id IS '插件ID';
COMMENT ON COLUMN sys_plugin.enabled IS '是否启用(1启用 0禁用)';
COMMENT ON COLUMN sys_plugin.update_time IS '更新时间';
COMMENT ON COLUMN sys_operate_log.id IS '主键ID';
COMMENT ON COLUMN sys_operate_log.user_id IS '用户ID';
COMMENT ON COLUMN sys_operate_log.username IS '用户名';
COMMENT ON COLUMN sys_operate_log.module IS '操作模块';
COMMENT ON COLUMN sys_operate_log.operate_type IS '操作类型(1-查询 2-新增 3-修改 4-删除 5-登录 6-导出 7-导入 8-其他)';
COMMENT ON COLUMN sys_operate_log.request_method IS '请求方法';
COMMENT ON COLUMN sys_operate_log.request_url IS '请求URL';
COMMENT ON COLUMN sys_operate_log.request_params IS '请求参数';
COMMENT ON COLUMN sys_operate_log.response_result IS '响应结果';
COMMENT ON COLUMN sys_operate_log.ip IS 'IP地址';
COMMENT ON COLUMN sys_operate_log.location IS '操作地点';
COMMENT ON COLUMN sys_operate_log.status IS '状态(0-失败 1-成功)';
COMMENT ON COLUMN sys_operate_log.error_msg IS '错误信息';
COMMENT ON COLUMN sys_operate_log.execute_time IS '执行时间(ms)';
COMMENT ON COLUMN sys_operate_log.create_time IS '创建时间';
COMMENT ON COLUMN sys_login_log.id IS '主键ID';
COMMENT ON COLUMN sys_login_log.user_id IS '用户ID';
COMMENT ON COLUMN sys_login_log.username IS '用户名';
COMMENT ON COLUMN sys_login_log.login_type IS '登录类型(1-密码 2-短信验证码 3-邮箱 4-第三方 5-扫码)';
COMMENT ON COLUMN sys_login_log.ip IS 'IP地址';
COMMENT ON COLUMN sys_login_log.location IS '登录地点';
COMMENT ON COLUMN sys_login_log.browser IS '浏览器';
COMMENT ON COLUMN sys_login_log.os IS '操作系统';
COMMENT ON COLUMN sys_login_log.status IS '状态(0-失败 1-成功)';
COMMENT ON COLUMN sys_login_log.msg IS '消息';
COMMENT ON COLUMN sys_login_log.login_time IS '登录时间';
COMMENT ON COLUMN sys_menu.id IS '主键ID';
COMMENT ON COLUMN sys_menu.parent_id IS '父菜单ID';
COMMENT ON COLUMN sys_menu.name IS '菜单名称';
COMMENT ON COLUMN sys_menu.icon IS '图标名称';
COMMENT ON COLUMN sys_menu.path IS '路由路径';
COMMENT ON COLUMN sys_menu.permission IS '所需权限标识';
COMMENT ON COLUMN sys_menu.sort IS '排序号';
COMMENT ON COLUMN sys_menu.visible IS '是否可见(1可见 0隐藏)';
COMMENT ON COLUMN sys_menu.type IS '菜单类型(0目录 1菜单 2按钮)';
COMMENT ON COLUMN sys_menu.create_time IS '创建时间';
COMMENT ON COLUMN sys_menu.update_time IS '更新时间';
COMMENT ON COLUMN cluster_node_config.id IS '主键ID';
COMMENT ON COLUMN cluster_node_config.node_id IS '节点ID';
COMMENT ON COLUMN cluster_node_config.worker_id IS 'Worker ID';
COMMENT ON COLUMN cluster_node_config.ip_address IS 'IP地址';
COMMENT ON COLUMN cluster_node_config.port IS '端口';
COMMENT ON COLUMN cluster_node_config.node_name IS '节点名称';
COMMENT ON COLUMN cluster_node_config.status IS '节点状态(0-离线 1-在线)';
COMMENT ON COLUMN cluster_node_config.max_worker_id IS '最大Worker ID';
COMMENT ON COLUMN cluster_node_config.enabled IS '是否启用(0-禁用 1-启用)';
COMMENT ON COLUMN cluster_node_config.create_time IS '创建时间';
COMMENT ON COLUMN cluster_node_config.update_time IS '更新时间';
COMMENT ON COLUMN sys_user.user_type IS '用户类型(ADMIN-管理端 APP-插件App端，统一用户体系)';
COMMENT ON COLUMN sys_user.device_id IS 'App端设备标识';

-- ============================================
-- 全端统计系统（STATS_DESIGN.md §3）
-- ============================================

-- 设备登记表（一台设备一行）
CREATE TABLE IF NOT EXISTS stat_device (
    id BIGINT PRIMARY KEY,
    device_id VARCHAR(64) NOT NULL,
    ut VARCHAR(16) NOT NULL,
    app_version VARCHAR(32),
    model VARCHAR(128),
    os VARCHAR(64),
    last_ip VARCHAR(64),
    first_date DATE NOT NULL,
    last_date DATE NOT NULL,
    last_active_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stat_device_device_id UNIQUE (device_id)
);

CREATE INDEX IF NOT EXISTS idx_stat_device_first_date ON stat_device(first_date);
CREATE INDEX IF NOT EXISTS idx_stat_device_last_date ON stat_device(last_date);

-- 兼容历史库：已有表补列（幂等）
ALTER TABLE stat_device ADD COLUMN IF NOT EXISTS last_ip VARCHAR(64);

-- 小时指标桶
CREATE TABLE IF NOT EXISTS stat_metric_hourly (
    id BIGINT PRIMARY KEY,
    bucket_hour TIMESTAMP NOT NULL,
    ut VARCHAR(16) NOT NULL,
    app_version VARCHAR(32) NOT NULL DEFAULT '',
    pv BIGINT DEFAULT 0,
    visits BIGINT DEFAULT 0,
    launches BIGINT DEFAULT 0,
    total_duration_ms BIGINT DEFAULT 0,
    error_count BIGINT DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stat_metric_hourly UNIQUE (bucket_hour, ut, app_version)
);

-- 页面小时桶（页面排行用）
CREATE TABLE IF NOT EXISTS stat_page_hourly (
    id BIGINT PRIMARY KEY,
    bucket_hour TIMESTAMP NOT NULL,
    ut VARCHAR(16) NOT NULL,
    page VARCHAR(256) NOT NULL,
    pv BIGINT DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stat_page_hourly UNIQUE (bucket_hour, ut, page)
);

-- 错误明细
CREATE TABLE IF NOT EXISTS stat_error_log (
    id BIGINT PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    error_type VARCHAR(16) NOT NULL,
    message VARCHAR(1024),
    stack TEXT,
    page VARCHAR(256),
    ut VARCHAR(16),
    app_version VARCHAR(32),
    os VARCHAR(64),
    model VARCHAR(128),
    device_id VARCHAR(64),
    release VARCHAR(64),
    ip VARCHAR(64),
    occur_time TIMESTAMP NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_stat_error_fingerprint ON stat_error_log(fingerprint);
CREATE INDEX IF NOT EXISTS idx_stat_error_occur_time ON stat_error_log(occur_time);
CREATE INDEX IF NOT EXISTS idx_stat_error_type ON stat_error_log(error_type);

-- 兼容历史库：已有表补列（幂等）
ALTER TABLE stat_error_log ADD COLUMN IF NOT EXISTS ip VARCHAR(64);

-- 接口小时桶（服务端自动测量）
CREATE TABLE IF NOT EXISTS stat_api_hourly (
    id BIGINT PRIMARY KEY,
    bucket_hour TIMESTAMP NOT NULL,
    uri VARCHAR(256) NOT NULL,
    method VARCHAR(8) NOT NULL,
    status SMALLINT NOT NULL,
    call_count BIGINT DEFAULT 0,
    sum_ms BIGINT DEFAULT 0,
    max_ms INT DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stat_api_hourly UNIQUE (bucket_hour, uri, method, status)
);

-- 数据统计菜单（/dashboard/statistics，D3：DB 菜单驱动）
INSERT INTO sys_menu (id, parent_id, name, icon, path, permission, sort, visible, type)
VALUES (30, 0, '数据统计', 'BarChartOutlined', '/dashboard/statistics', 'statistics:view', 2, 1, 0)
ON CONFLICT (id) DO NOTHING;

-- 数据统计权限（对齐 sys_permission 现有风格）
INSERT INTO sys_permission (id, permission_code, permission_name, url, method, type, sort)
VALUES (30, 'statistics:view', '查看数据统计', '/api/v1/stat/**', NULL, 1, 30)
ON CONFLICT DO NOTHING;


-- ============================================================
-- 基线 2/4：数据字典类型与字典项
-- 字典基线（对全新库为空表，DELETE 为无操作）
-- ============================================================

-- [!] 本脚本非幂等：会先清空 sys_dict_type / sys_dict_data 再重建。
--     已初始化过的数据库请勿重复执行，否则后台新增的字典项会被删除。
--     它只应作为全新库的基线执行一次；既有库请按 README 的「基线登记」方式跳过。

-- ============================================
-- 数据字典初始化脚本（字段维度 - 枚举值）
-- ============================================

-- 清空现有数据
DELETE FROM sys_dict_data;
DELETE FROM sys_dict_type;

-- 字典类型: id
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (1, 'id', '主键ID', 'Long', 'BIGINT', 0, '主键ID', 1);

-- 字典类型: bizKey
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (2, 'bizKey', '业务键', 'String', 'VARCHAR', 64, '业务键', 1);

-- 字典类型: sequenceType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (3, 'sequenceType', '序列类型', 'String', 'VARCHAR', 32, '序列类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 1, id, '雪花算法', 'SNOWFLAKE', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 2, id, '号段模式', 'SEGMENT', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 3, id, 'Redis模式', 'REDIS', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 4, id, '数据库模式', 'DATABASE', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 5, id, '简单模式', 'SIMPLE', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';

-- 字典类型: step
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (4, 'step', '步长', 'Integer', 'INT', 0, '步长', 1);

-- 字典类型: dateFormat
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (5, 'dateFormat', '日期格式', 'String', 'VARCHAR', 32, '日期格式', 1);

-- 字典类型: prefix
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (6, 'prefix', '前缀', 'String', 'VARCHAR', 32, '前缀', 1);

-- 字典类型: suffix
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (7, 'suffix', '后缀', 'String', 'VARCHAR', 32, '后缀', 1);

-- 字典类型: minValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (8, 'minValue', '最小值', 'Long', 'BIGINT', 0, '最小值', 1);

-- 字典类型: enabled
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (9, 'enabled', '是否启用', 'Boolean', 'BOOLEAN', 0, '是否启用', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 6, id, '禁用', 'false', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'enabled';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 7, id, '启用', 'true', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'enabled';

-- 字典类型: description
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (10, 'description', '描述', 'String', 'VARCHAR', 256, '描述', 1);

-- 字典类型: createTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (11, 'createTime', '创建时间', 'LocalDateTime', 'TIMESTAMP', 0, '创建时间', 1);

-- 字典类型: updateTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (12, 'updateTime', '更新时间', 'LocalDateTime', 'TIMESTAMP', 0, '更新时间', 1);

-- 字典类型: currentValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (13, 'currentValue', '当前值', 'Long', 'BIGINT', 0, '当前值', 1);

-- 字典类型: totalGenerate
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (14, 'totalGenerate', '总生成数', 'Long', 'BIGINT', 0, '总生成数', 1);

-- 字典类型: sequenceValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (15, 'sequenceValue', '序列值', 'Long', 'BIGINT', 0, '序列值', 1);

-- 字典类型: maxValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (16, 'maxValue', '号段结束值', 'Long', 'BIGINT', 0, '号段结束值', 1);

-- 字典类型: currentMaxValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (17, 'currentMaxValue', '当前已分配最大值', 'Long', 'BIGINT', 0, '当前已分配最大值', 1);

-- 字典类型: version
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (18, 'version', '乐观锁版本号', 'Integer', 'INT', 0, '乐观锁版本号', 1);

-- 字典类型: lastGenerateTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (19, 'lastGenerateTime', '最后生成时间', 'LocalDateTime', 'TIMESTAMP', 0, '最后生成时间', 1);

-- 字典类型: deleted
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (20, 'deleted', '逻辑删除', 'Integer', 'TINYINT', 0, '逻辑删除', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 8, id, '未删除', '0', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'deleted';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9, id, '已删除', '1', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'deleted';

-- 字典类型: configName
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (21, 'configName', '配置名称', 'String', 'VARCHAR', 128, '配置名称', 1);

-- 字典类型: configKey
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (22, 'configKey', '配置键', 'String', 'VARCHAR', 128, '配置键', 1);

-- 字典类型: configValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (23, 'configValue', '配置值', 'String', 'VARCHAR', 512, '配置值', 1);

-- 字典类型: configType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (24, 'configType', '类型', 'Integer', 'TINYINT', 0, '类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 10, id, '内置', '1', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'configType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 11, id, '自定义', '2', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'configType';

-- 字典类型: dictTypeId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (25, 'dictTypeId', '字典类型ID', 'Long', 'BIGINT', 0, '字典类型ID', 1);

-- 字典类型: dictLabel
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (26, 'dictLabel', '字典标签', 'String', 'VARCHAR', 128, '字典标签', 1);

-- 字典类型: dictValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (27, 'dictValue', '字典值', 'String', 'VARCHAR', 128, '字典值', 1);

-- 字典类型: dictSort
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (28, 'dictSort', '排序', 'Integer', 'INT', 0, '排序', 1);

-- 字典类型: cssClass
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (29, 'cssClass', 'CSS样式', 'String', 'VARCHAR', 128, 'CSS样式', 1);

-- 字典类型: listClass
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (30, 'listClass', '表格回显样式', 'String', 'VARCHAR', 128, '表格回显样式', 1);

-- 字典类型: isDefault
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (31, 'isDefault', '是否默认', 'Integer', 'TINYINT', 0, '是否默认', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 12, id, '否', '0', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'isDefault';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 13, id, '是', '1', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'isDefault';

-- 字典类型: status
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (32, 'status', '状态', 'Integer', 'TINYINT', 0, '状态', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 14, id, '禁用', '0', 1, 1, '停用/失败/已吊销'
FROM sys_dict_type WHERE dict_code = 'status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 15, id, '启用', '1', 2, 1, '正常/成功/有效'
FROM sys_dict_type WHERE dict_code = 'status';

-- 字典类型: dictCode
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (33, 'dictCode', '字典编码', 'String', 'VARCHAR', 64, '字典编码', 1);

-- 字典类型: dictName
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (34, 'dictName', '字典名称', 'String', 'VARCHAR', 128, '字典名称', 1);

-- 字典类型: dataType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (35, 'dataType', '数据类型', 'String', 'VARCHAR', 32, '数据类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 16, id, 'String', 'String', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 17, id, 'Character', 'Character', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 18, id, 'Clob', 'Clob', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 19, id, 'Byte', 'Byte', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 20, id, 'Short', 'Short', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 21, id, 'Integer', 'Integer', 6, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 22, id, 'Long', 'Long', 7, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 23, id, 'Float', 'Float', 8, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 24, id, 'Double', 'Double', 9, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 25, id, 'BigDecimal', 'BigDecimal', 10, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 26, id, 'LocalDate', 'LocalDate', 11, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 27, id, 'LocalTime', 'LocalTime', 12, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 28, id, 'LocalDateTime', 'LocalDateTime', 13, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 29, id, 'Date', 'Date', 14, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 30, id, 'Timestamp', 'Timestamp', 15, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 31, id, 'Boolean', 'Boolean', 16, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 32, id, 'byte[]', 'byte[]', 17, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 33, id, 'Blob', 'Blob', 18, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';

-- 字典类型: jdbcType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (36, 'jdbcType', 'JDBC类型', 'String', 'VARCHAR', 32, 'JDBC类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 34, id, 'VARCHAR', 'VARCHAR', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 35, id, 'CHAR', 'CHAR', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 36, id, 'LONGVARCHAR', 'LONGVARCHAR', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 37, id, 'TEXT', 'TEXT', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 38, id, 'CLOB', 'CLOB', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 39, id, 'TINYINT', 'TINYINT', 6, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 40, id, 'SMALLINT', 'SMALLINT', 7, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 41, id, 'INT', 'INT', 8, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 42, id, 'INTEGER', 'INTEGER', 9, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 43, id, 'BIGINT', 'BIGINT', 10, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 44, id, 'FLOAT', 'FLOAT', 11, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 45, id, 'DOUBLE', 'DOUBLE', 12, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 46, id, 'DECIMAL', 'DECIMAL', 13, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 47, id, 'NUMERIC', 'NUMERIC', 14, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 48, id, 'DATE', 'DATE', 15, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 49, id, 'TIME', 'TIME', 16, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 50, id, 'TIMESTAMP', 'TIMESTAMP', 17, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 51, id, 'DATETIME', 'DATETIME', 18, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 52, id, 'BOOLEAN', 'BOOLEAN', 19, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 53, id, 'BINARY', 'BINARY', 20, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 54, id, 'VARBINARY', 'VARBINARY', 21, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 55, id, 'LONGVARBINARY', 'LONGVARBINARY', 22, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 56, id, 'BLOB', 'BLOB', 23, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';

-- 字典类型: dataLength
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (37, 'dataLength', '数据长度', 'Integer', 'INT', 0, '数据长度', 1);

-- 字典类型: userId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (38, 'userId', '用户ID', 'Long', 'BIGINT', 0, '用户ID', 1);

-- 字典类型: username
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (39, 'username', '用户名', 'String', 'VARCHAR', 64, '用户名', 1);

-- 字典类型: loginType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (40, 'loginType', '登录类型', 'String', 'VARCHAR', 32, '登录类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 57, id, '密码登录', 'PASSWORD', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'loginType';

-- 字典类型: ip
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (41, 'ip', 'IP地址', 'String', 'VARCHAR', 64, 'IP地址', 1);

-- 字典类型: location
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (42, 'location', '登录地点', 'String', 'VARCHAR', 128, '登录地点', 1);

-- 字典类型: msg
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (43, 'msg', '消息', 'String', 'VARCHAR', 256, '消息', 1);

-- 字典类型: loginTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (44, 'loginTime', '登录时间', 'LocalDateTime', 'TIMESTAMP', 0, '登录时间', 1);

-- 字典类型: module
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (45, 'module', '操作模块', 'String', 'VARCHAR', 64, '操作模块', 1);

-- 字典类型: operateType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (46, 'operateType', '操作类型', 'String', 'VARCHAR', 32, '操作类型', 1);

-- 字典类型: requestMethod
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (47, 'requestMethod', '请求方法', 'String', 'VARCHAR', 10, '请求方法', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 58, id, 'GET', 'GET', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 59, id, 'POST', 'POST', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 60, id, 'PUT', 'PUT', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 61, id, 'DELETE', 'DELETE', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 62, id, 'PATCH', 'PATCH', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';

-- 字典类型: requestUrl
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (48, 'requestUrl', '请求URL', 'String', 'VARCHAR', 256, '请求URL', 1);

-- 字典类型: requestParams
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (49, 'requestParams', '请求参数', 'String', 'TEXT', 0, '请求参数', 1);

-- 字典类型: responseResult
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (50, 'responseResult', '响应结果', 'String', 'TEXT', 0, '响应结果', 1);

-- 字典类型: errorMsg
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (51, 'errorMsg', '错误信息', 'String', 'TEXT', 0, '错误信息', 1);

-- 字典类型: executeTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (52, 'executeTime', '执行时间', 'Long', 'BIGINT', 0, '执行时间', 1);

-- 字典类型: permissionCode
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (53, 'permissionCode', '权限编码', 'String', 'VARCHAR', 64, '权限编码', 1);

-- 字典类型: permissionName
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (54, 'permissionName', '权限名称', 'String', 'VARCHAR', 128, '权限名称', 1);

-- 字典类型: url
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (55, 'url', '请求URL', 'String', 'VARCHAR', 256, '请求URL', 1);

-- 字典类型: method
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (56, 'method', '请求方法', 'String', 'VARCHAR', 10, '请求方法', 1);

-- 字典类型: parentId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (57, 'parentId', '父级ID', 'Long', 'BIGINT', 0, '父级ID', 1);

-- 字典类型: type
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (58, 'type', '类型', 'Integer', 'TINYINT', 0, '类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 63, id, '目录', '1', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 64, id, '菜单', '2', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 65, id, '按钮', '3', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'type';

-- 字典类型: icon
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (59, 'icon', '图标', 'String', 'VARCHAR', 64, '图标', 1);

-- 字典类型: sort
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (60, 'sort', '排序', 'Integer', 'INT', 0, '排序', 1);

-- 字典类型: roleCode
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (61, 'roleCode', '角色编码', 'String', 'VARCHAR', 64, '角色编码', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 66, id, '系统管理员', 'ADMIN', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'roleCode';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 67, id, '普通用户', 'USER', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'roleCode';

-- 字典类型: roleName
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (62, 'roleName', '角色名称', 'String', 'VARCHAR', 128, '角色名称', 1);

-- 字典类型: roleId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (63, 'roleId', '角色ID', 'Long', 'BIGINT', 0, '角色ID', 1);

-- 字典类型: permissionId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (64, 'permissionId', '权限ID', 'Long', 'BIGINT', 0, '权限ID', 1);

-- 字典类型: token
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (65, 'token', 'Token值', 'String', 'VARCHAR', 256, 'Token值', 1);

-- 字典类型: refreshToken
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (66, 'refreshToken', '刷新Token', 'String', 'VARCHAR', 256, '刷新Token', 1);

-- 字典类型: expireTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (67, 'expireTime', '过期时间', 'LocalDateTime', 'TIMESTAMP', 0, '过期时间', 1);

-- 字典类型: loginIp
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (68, 'loginIp', '登录IP', 'String', 'VARCHAR', 64, '登录IP', 1);

-- 字典类型: password
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (69, 'password', '密码', 'String', 'VARCHAR', 128, '密码', 1);

-- 字典类型: nickname
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (70, 'nickname', '昵称', 'String', 'VARCHAR', 64, '昵称', 1);

-- 字典类型: email
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (71, 'email', '邮箱', 'String', 'VARCHAR', 128, '邮箱', 1);

-- 字典类型: phone
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (72, 'phone', '手机号', 'String', 'VARCHAR', 20, '手机号', 1);

-- 字典类型: avatar
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (73, 'avatar', '头像URL', 'String', 'VARCHAR', 256, '头像URL', 1);

-- 字典类型: pwdUpdateTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (74, 'pwdUpdateTime', '密码更新时间', 'LocalDateTime', 'TIMESTAMP', 0, '密码更新时间', 1);

-- ==================== 轻听(qt)插件：公告 / 版本更新 枚举（数据字典化，避免前端/后端写死） ====================

-- 字典类型: qt_notice_channel（公告展示渠道位掩码，值=2的幂，可叠加）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (75, 'qt_notice_channel', '公告展示渠道', 'Integer', 'VARCHAR', 8, '公告展示渠道位掩码，值按2的幂，type字段为各值之和', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 100, id, '开屏弹窗', '1', 1, 1, 'Splash 开屏弹窗' FROM sys_dict_type WHERE dict_code = 'qt_notice_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 101, id, '首页通告栏', '2', 2, 1, 'NoticeBar 首页顶部通告栏' FROM sys_dict_type WHERE dict_code = 'qt_notice_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 102, id, '消息中心', '4', 3, 1, 'MessageCenter 消息中心存档' FROM sys_dict_type WHERE dict_code = 'qt_notice_channel';

-- 字典类型: qt_notice_audience（可见人群，按是否登录过滤）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (76, 'qt_notice_audience', '公告可见人群', 'String', 'VARCHAR', 16, '公告可见人群：按是否登录过滤', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 106, id, '全部用户', 'ALL', 1, 1, '登录/未登录均展示' FROM sys_dict_type WHERE dict_code = 'qt_notice_audience';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 107, id, '仅登录用户', 'LOGGED_IN', 2, 1, '仅已登录用户可见' FROM sys_dict_type WHERE dict_code = 'qt_notice_audience';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 108, id, '仅游客(未登录)', 'NOT_LOGGED_IN', 3, 1, '仅未登录游客可见' FROM sys_dict_type WHERE dict_code = 'qt_notice_audience';

-- 字典类型: qt_yes_no（通用是否标志 0/1）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (77, 'qt_yes_no', '是否', 'Integer', 'VARCHAR', 2, '通用是否标志：1=是 0=否', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 109, id, '是', '1', 1, 1, '是' FROM sys_dict_type WHERE dict_code = 'qt_yes_no';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 110, id, '否', '0', 2, 1, '否' FROM sys_dict_type WHERE dict_code = 'qt_yes_no';

-- 字典类型: qt_update_platform（版本更新平台）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (78, 'qt_update_platform', '版本更新平台', 'Integer', 'VARCHAR', 8, '版本更新适用平台', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 111, id, '安卓', '1101', 1, 1, 'Android' FROM sys_dict_type WHERE dict_code = 'qt_update_platform';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 112, id, 'iOS', '1102', 2, 1, 'iOS' FROM sys_dict_type WHERE dict_code = 'qt_update_platform';

-- 字典类型: qt_update_type（提示方式）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (79, 'qt_update_type', '版本更新提示方式', 'String', 'VARCHAR', 8, '版本更新提示方式', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 113, id, '弹窗', '1', 1, 1, '弹窗提示' FROM sys_dict_type WHERE dict_code = 'qt_update_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 114, id, '红点', '2', 2, 1, '小红点提示' FROM sys_dict_type WHERE dict_code = 'qt_update_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 115, id, '无提示', '3', 3, 1, '无提示' FROM sys_dict_type WHERE dict_code = 'qt_update_type';

-- 字典类型: qt_update_channel（发布渠道）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (80, 'qt_update_channel', '版本发布渠道', 'String', 'VARCHAR', 16, '版本发布渠道', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 116, id, '正式版', 'stable', 1, 1, '正式版渠道' FROM sys_dict_type WHERE dict_code = 'qt_update_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 117, id, '测试版', 'beta', 2, 1, '测试版渠道' FROM sys_dict_type WHERE dict_code = 'qt_update_channel';

-- 字典类型: qt_update_download_mode（下载方式）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (81, 'qt_update_download_mode', '版本下载方式', 'String', 'VARCHAR', 16, '版本更新下载方式', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 118, id, '应用内下载', 'app', 1, 1, '应用内下载' FROM sys_dict_type WHERE dict_code = 'qt_update_download_mode';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 119, id, '浏览器跳转', 'browser', 2, 1, '浏览器跳转下载' FROM sys_dict_type WHERE dict_code = 'qt_update_download_mode';

-- 字典类型: qt_update_publish（版本发布状态：1 已发布 / 0 未发布；未发布仅本地测试，不推送更新通知、不校验非官方）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (86, 'qt_update_publish', '版本发布状态', 'Integer', 'VARCHAR', 2, '版本是否发布：1=已发布(用户收到更新通知) 0=未发布(仅本地测试)', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 133, id, '已发布', '1', 1, 1, '已发布：App 端收到更新通知' FROM sys_dict_type WHERE dict_code = 'qt_update_publish';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 134, id, '未发布', '0', 2, 1, '未发布：仅本地版本测试，不推送、不校验非官方' FROM sys_dict_type WHERE dict_code = 'qt_update_publish';

-- ==================== 反馈插件（astral-plugin 的 feedback 分类）：状态 / 类型 / 通知 枚举 ====================

-- 字典类型: feedback_status（反馈状态机；TRANSITIONS 允许 pending→received→resolved→published，任意→deprecated，deprecated 可任意回退）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (82, 'feedback_status', '反馈状态', 'String', 'VARCHAR', 16, '反馈状态机5态', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 120, id, '提出', 'pending', 1, 1, '用户提交初始状态' FROM sys_dict_type WHERE dict_code = 'feedback_status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 121, id, '已接收', 'received', 2, 1, '管理员已接收' FROM sys_dict_type WHERE dict_code = 'feedback_status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 122, id, '已解决', 'resolved', 3, 1, '管理员已解决' FROM sys_dict_type WHERE dict_code = 'feedback_status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 123, id, '已发布', 'published', 4, 1, '公开，App 可见' FROM sys_dict_type WHERE dict_code = 'feedback_status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 124, id, '已废弃', 'deprecated', 5, 1, '终止处理，可回退' FROM sys_dict_type WHERE dict_code = 'feedback_status';

-- 字典类型: feedback_type（反馈类型）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (83, 'feedback_type', '反馈类型', 'String', 'VARCHAR', 16, '反馈分类', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 125, id, '问题', 'issue', 1, 1, '问题反馈' FROM sys_dict_type WHERE dict_code = 'feedback_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 126, id, '需求', 'request', 2, 1, '需求建议' FROM sys_dict_type WHERE dict_code = 'feedback_type';

-- 字典类型: notice_channel（通知展示渠道）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (84, 'notice_channel', '通知渠道', 'String', 'VARCHAR', 16, '通知展示渠道', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 127, id, 'App', 'app', 1, 1, 'App 端展示' FROM sys_dict_type WHERE dict_code = 'notice_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 128, id, 'Web', 'web', 2, 1, 'Web 端展示' FROM sys_dict_type WHERE dict_code = 'notice_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 129, id, '全部', 'all', 3, 1, '全部端展示' FROM sys_dict_type WHERE dict_code = 'notice_channel';
-- PC 端（桌面端）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 137, id, 'PC', 'pc', 4, 1, 'PC/桌面端展示' FROM sys_dict_type WHERE dict_code = 'notice_channel';

-- 字典类型: notice_type（通知业务类型）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (85, 'notice_type', '通知类型', 'String', 'VARCHAR', 16, '通知业务分类', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 130, id, '公告', 'announce', 1, 1, '运营公告' FROM sys_dict_type WHERE dict_code = 'notice_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 131, id, '反馈', 'feedback', 2, 1, '反馈/回复通知' FROM sys_dict_type WHERE dict_code = 'notice_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 132, id, '需求', 'request', 3, 1, '需求通知' FROM sys_dict_type WHERE dict_code = 'notice_type';

-- 平台枚举：android/ios 分开的（qt_update_platform）新增 Windows(1103)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 138, id, 'Windows', '1103', 3, 1, 'Windows 桌面端' FROM sys_dict_type WHERE dict_code = 'qt_update_platform';

-- ==================== 统计上报：平台（ut） ====================

-- 字典类型: stat_platform（统计上报平台 ut：app-android / app-ios / app-windows / web）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (88, 'stat_platform', '统计平台', 'String', 'VARCHAR', 16, '统计上报平台标识(ut)', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 139, id, 'Android', 'app-android', 1, 1, 'Android App' FROM sys_dict_type WHERE dict_code = 'stat_platform';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 140, id, 'iOS', 'app-ios', 2, 1, 'iOS App' FROM sys_dict_type WHERE dict_code = 'stat_platform';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 141, id, 'Windows', 'app-windows', 3, 1, 'Windows 桌面端' FROM sys_dict_type WHERE dict_code = 'stat_platform';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 142, id, 'Web', 'web', 4, 1, 'Web/H5' FROM sys_dict_type WHERE dict_code = 'stat_platform';

-- ==================== 表结构管理：SQL方言（生成建表/变更SQL时可选） ====================

-- 字典类型: sql_dialect（表结构管理生成SQL的目标数据库方言）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (87, 'sql_dialect', 'SQL方言', 'String', 'VARCHAR', 16, '表结构管理生成SQL的目标数据库方言', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 135, id, 'PostgreSQL', 'postgresql', 1, 1, 'COMMENT ON 注释、BIGSERIAL 自增（运行时默认）' FROM sys_dict_type WHERE dict_code = 'sql_dialect';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 136, id, 'MySQL', 'mysql', 2, 1, '内联 COMMENT、AUTO_INCREMENT 自增、ENGINE/CHARSET 表选项' FROM sys_dict_type WHERE dict_code = 'sql_dialect';


-- ============================================================
-- 基线 3/4：字典表自增序列推进到当前最大值
-- 显式种子 id 后推进 PG 序列，避免运行时发号冲突
-- ============================================================

-- ============================================
-- PostgreSQL 序列重置脚本
-- dict-init.sql 使用显式 id 插入字典数据，需将 BIGSERIAL 序列推进到当前最大值，
-- 避免后续运行时自动生成 id 时与种子数据冲突。
-- ============================================

SELECT setval(pg_get_serial_sequence('sys_dict_type', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM sys_dict_type), 1),
              (SELECT COUNT(*) > 0 FROM sys_dict_type));

SELECT setval(pg_get_serial_sequence('sys_dict_data', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM sys_dict_data), 1),
              (SELECT COUNT(*) > 0 FROM sys_dict_data));


-- ============================================================
-- 基线 4/4：storage 插件 6 张表
-- 文件存储插件表结构（幂等：IF NOT EXISTS）
-- ============================================================

-- ============================================================
-- 1. 存储配置表 sys_storage_config
--    Telegram Worker 直连架构下 Bot Token 保存在 Cloudflare Worker Secret，
--    数据库只保存非敏感连接信息；credential 列为后续 R2/S3 Provider 预留。
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_config (
  id                  BIGINT       PRIMARY KEY,
  name                VARCHAR(128) NOT NULL,
  provider_type       VARCHAR(32)  NOT NULL DEFAULT 'TELEGRAM',
  chat_id             VARCHAR(64),
  worker_base_url     VARCHAR(256),
  provider_options    TEXT,
  max_file_size       BIGINT,
  status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
  is_default          SMALLINT     NOT NULL DEFAULT 0,
  health_status       VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
  last_test_time      TIMESTAMP,
  last_test_message   VARCHAR(512),
  remark              VARCHAR(256),
  create_by           VARCHAR(64),
  create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by           VARCHAR(64),
  update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_storage_config_name UNIQUE (name)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_storage_config_default
  ON sys_storage_config (is_default) WHERE is_default = 1;

-- ============================================================
-- 2. 存储文件夹表 sys_storage_folder（授权与组织边界）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_folder (
  id                  BIGINT       PRIMARY KEY,
  parent_id           BIGINT,
  folder_name         VARCHAR(128) NOT NULL,
  folder_path         VARCHAR(512) NOT NULL DEFAULT '',
  storage_config_id   BIGINT       NOT NULL,
  owner_type          VARCHAR(16)  NOT NULL DEFAULT 'ADMIN',
  owner_id            VARCHAR(64),
  visibility          VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE',
  status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
  create_by           VARCHAR(64),
  create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_by           VARCHAR(64),
  update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_storage_folder_parent ON sys_storage_folder (parent_id);
CREATE INDEX IF NOT EXISTS idx_storage_folder_config ON sys_storage_folder (storage_config_id);

-- ============================================================
-- 3. 文件夹授权表 sys_storage_folder_permission
--    permissions 为扁平权限集合（逗号分隔：READ,UPLOAD,UPDATE,DELETE,MANAGE）
--    Scope 别名映射：storage:file:upload->UPLOAD 等，见设计文档 §9.1
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_folder_permission (
  id                  BIGINT       PRIMARY KEY,
  folder_id           BIGINT       NOT NULL,
  subject_type        VARCHAR(16)  NOT NULL,
  subject_id          VARCHAR(64)  NOT NULL,
  permissions         VARCHAR(256) NOT NULL,
  expires_time        TIMESTAMP,
  create_by           VARCHAR(64),
  create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_time        TIMESTAMP,
  CONSTRAINT uk_storage_folder_perm UNIQUE (folder_id, subject_type, subject_id)
);

-- ============================================================
-- 4. 存储文件表 sys_storage_file
--    public_id 为对外稳定标识（不可预测）；provider_locator_json 仅服务端使用
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_file (
  id                    BIGINT       PRIMARY KEY,
  public_id             VARCHAR(48)  NOT NULL,
  upload_id             VARCHAR(48),
  folder_id             BIGINT       NOT NULL,
  storage_config_id     BIGINT       NOT NULL,
  provider_type         VARCHAR(32)  NOT NULL DEFAULT 'TELEGRAM',
  provider_locator_json TEXT,
  original_name         VARCHAR(255) NOT NULL DEFAULT '',
  content_type          VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
  size_bytes            BIGINT       NOT NULL DEFAULT 0,
  checksum              VARCHAR(128),
  content_version       BIGINT       NOT NULL DEFAULT 1,
  visibility            VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE',
  status                VARCHAR(24)  NOT NULL DEFAULT 'AVAILABLE',
  uploader_type         VARCHAR(16)  NOT NULL DEFAULT 'USER',
  uploader_id           VARCHAR(64),
  create_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_time          TIMESTAMP,
  CONSTRAINT uk_storage_file_public_id UNIQUE (public_id)
);
CREATE INDEX IF NOT EXISTS idx_storage_file_folder ON sys_storage_file (folder_id);
CREATE INDEX IF NOT EXISTS idx_storage_file_upload ON sys_storage_file (upload_id);
CREATE INDEX IF NOT EXISTS idx_storage_file_status ON sys_storage_file (status);

-- ============================================================
-- 5. 存储任务表 sys_storage_task（远端删除补偿等）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_task (
  id                  BIGINT       PRIMARY KEY,
  task_type           VARCHAR(48)  NOT NULL,
  file_id             BIGINT,
  payload_json        TEXT,
  retry_count         INT          NOT NULL DEFAULT 0,
  next_retry_time     TIMESTAMP,
  status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  error_message       VARCHAR(512),
  create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_storage_task_pickup ON sys_storage_task (status, next_retry_time);

-- ============================================================
-- 6. 存储审计表 sys_storage_audit
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_storage_audit (
  id                  BIGINT       PRIMARY KEY,
  action              VARCHAR(64)  NOT NULL,
  subject_type        VARCHAR(16),
  subject_id          VARCHAR(64),
  target_type         VARCHAR(32),
  target_id           VARCHAR(64),
  detail              VARCHAR(512),
  result              VARCHAR(16)  NOT NULL DEFAULT 'OK',
  create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_storage_audit_time ON sys_storage_audit (create_time);
CREATE INDEX IF NOT EXISTS idx_storage_audit_action ON sys_storage_audit (action);
