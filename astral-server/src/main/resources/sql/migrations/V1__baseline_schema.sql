-- ============================================================
-- 迁移版本：V1（基线脚本，仅用于全新数据库首次部署，执行一次）
-- 命名规范：V{序号}__{描述}.sql，序号递增、只增不改
-- 应用方式：见同目录 README.md
-- 注意：一旦应用过就禁止再修改本文件，后续变更请新增下一个版本号
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
