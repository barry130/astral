-- ============================================
-- H2 Database 初始化脚本
-- ============================================

-- 序列号段表
CREATE TABLE IF NOT EXISTS sequence_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL,
    sequence_type VARCHAR(32) NOT NULL,
    current_value BIGINT NOT NULL DEFAULT 0,
    total_count BIGINT NOT NULL DEFAULT 0,
    date DATE NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_statistics_biz_date ON sequence_statistics(biz_key, date);
CREATE INDEX IF NOT EXISTS idx_statistics_date ON sequence_statistics(date);

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    nickname VARCHAR(64),
    email VARCHAR(128),
    phone VARCHAR(32),
    avatar VARCHAR(256),
    status TINYINT NOT NULL DEFAULT 1,
    login_ip VARCHAR(64),
    login_time TIMESTAMP,
    pwd_update_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE INDEX IF NOT EXISTS idx_sys_user_status ON sys_user(status);
CREATE INDEX IF NOT EXISTS idx_sys_user_email ON sys_user(email);
CREATE INDEX IF NOT EXISTS idx_sys_user_phone ON sys_user(phone);

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    status TINYINT NOT NULL DEFAULT 1,
    sort INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_role_code UNIQUE (role_code)
);

-- 权限表
CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    permission_code VARCHAR(128) NOT NULL,
    permission_name VARCHAR(128) NOT NULL,
    url VARCHAR(256),
    method VARCHAR(16),
    parent_id BIGINT NOT NULL DEFAULT 0,
    type TINYINT NOT NULL DEFAULT 1,
    icon VARCHAR(64),
    sort INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_permission_code UNIQUE (permission_code)
);

CREATE INDEX IF NOT EXISTS idx_sys_permission_parent_id ON sys_permission(parent_id);
CREATE INDEX IF NOT EXISTS idx_sys_permission_type ON sys_permission(type);

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_role UNIQUE (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS idx_sys_user_role_role_id ON sys_user_role(role_id);

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_role_permission UNIQUE (role_id, permission_id)
);

CREATE INDEX IF NOT EXISTS idx_sys_role_permission_permission_id ON sys_role_permission(permission_id);

-- 字典类型表
CREATE TABLE IF NOT EXISTS sys_dict_type (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dict_code VARCHAR(64) NOT NULL,
    dict_name VARCHAR(128) NOT NULL,
    data_type VARCHAR(32),
    jdbc_type VARCHAR(32),
    data_length INT,
    description VARCHAR(1024),
    status TINYINT NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_dict_type_code UNIQUE (dict_code)
);

-- 字典数据表
CREATE TABLE IF NOT EXISTS sys_dict_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dict_type_id BIGINT NOT NULL,
    dict_label VARCHAR(128) NOT NULL,
    dict_value VARCHAR(128) NOT NULL,
    dict_sort INT NOT NULL DEFAULT 0,
    css_class VARCHAR(128),
    list_class VARCHAR(128),
    is_default TINYINT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dict_data_type_id ON sys_dict_data(dict_type_id);
CREATE INDEX IF NOT EXISTS idx_dict_data_status ON sys_dict_data(status);

-- 系统配置表
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_name VARCHAR(128) NOT NULL,
    config_key VARCHAR(128) NOT NULL,
    config_value CLOB,
    config_type TINYINT NOT NULL DEFAULT 1,
    description VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_config_key UNIQUE (config_key)
);

CREATE INDEX IF NOT EXISTS idx_sys_config_type ON sys_config(config_type);

-- 操作日志表
CREATE TABLE IF NOT EXISTS sys_operate_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(64),
    module VARCHAR(64) NOT NULL,
    operate_type VARCHAR(32) NOT NULL,
    request_method VARCHAR(16) NOT NULL,
    request_url VARCHAR(256) NOT NULL,
    request_params CLOB,
    response_result CLOB,
    ip VARCHAR(64),
    location VARCHAR(128),
    status TINYINT NOT NULL DEFAULT 1,
    error_msg CLOB,
    execute_time BIGINT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sys_operate_log_user_id ON sys_operate_log(user_id);
CREATE INDEX IF NOT EXISTS idx_sys_operate_log_operate_type ON sys_operate_log(operate_type);
CREATE INDEX IF NOT EXISTS idx_sys_operate_log_create_time ON sys_operate_log(create_time);
CREATE INDEX IF NOT EXISTS idx_sys_operate_log_status ON sys_operate_log(status);

-- 登录日志表
CREATE TABLE IF NOT EXISTS sys_login_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(64) NOT NULL,
    login_type VARCHAR(32) NOT NULL DEFAULT 'PASSWORD',
    ip VARCHAR(64) NOT NULL,
    location VARCHAR(128),
    browser VARCHAR(64),
    os VARCHAR(64),
    status TINYINT NOT NULL DEFAULT 1,
    msg VARCHAR(256),
    login_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sys_login_log_user_id ON sys_login_log(user_id);
CREATE INDEX IF NOT EXISTS idx_sys_login_log_username ON sys_login_log(username);
CREATE INDEX IF NOT EXISTS idx_sys_login_log_status ON sys_login_log(status);
CREATE INDEX IF NOT EXISTS idx_sys_login_log_login_time ON sys_login_log(login_time);

-- 集群节点配置表
CREATE TABLE IF NOT EXISTS cluster_node_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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

-- 初始数据
MERGE INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step) KEY(biz_key) VALUES
('order_id', 1, 1000, 1000, 1000),
('user_id', 1, 1000, 1000, 1000),
('payment_id', 1, 1000, 1000, 1000);

INSERT INTO sys_user (username, password, nickname, status)
SELECT 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuPiSQ5e6YnpjfPFWezevy2Mqt7R1D1RC', '系统管理员', 1
WHERE NOT EXISTS (
    SELECT 1 FROM sys_user WHERE username = 'admin'
);

INSERT INTO sys_role (role_code, role_name, description, sort)
SELECT 'ADMIN', '系统管理员', '系统管理员，拥有所有权限', 1
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role WHERE role_code = 'ADMIN'
);

INSERT INTO sys_role (role_code, role_name, description, sort)
SELECT 'USER', '普通用户', '普通用户，拥有基本权限', 2
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role WHERE role_code = 'USER'
);

INSERT INTO sys_permission (permission_code, permission_name, url, method, type, sort)
SELECT 'sequence:view', '查看序列', '/api/v1/sequence/**', NULL, 1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'sequence:view'
);

INSERT INTO sys_permission (permission_code, permission_name, url, method, type, sort)
SELECT 'sequence:generate', '生成序列', '/api/v1/sequence/next', 'POST', 2, 2
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'sequence:generate'
);

INSERT INTO sys_permission (permission_code, permission_name, url, method, type, sort)
SELECT 'sequence:batch', '批量生成', '/api/v1/sequence/batch', 'POST', 2, 3
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'sequence:batch'
);

INSERT INTO sys_permission (permission_code, permission_name, url, method, type, sort)
SELECT 'log:view', '查看日志', '/api/v1/log/**', NULL, 1, 4
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'log:view'
);

INSERT INTO sys_permission (permission_code, permission_name, url, method, type, sort)
SELECT 'user:manage', '用户管理', '/api/v1/users/**', NULL, 1, 5
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'user:manage'
);

INSERT INTO sys_permission (permission_code, permission_name, url, method, type, sort)
SELECT 'monitor:view', '查看监控', '/api/v1/monitor/**', NULL, 1, 6
WHERE NOT EXISTS (
    SELECT 1 FROM sys_permission WHERE permission_code = 'monitor:view'
);

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u, sys_role r
WHERE u.username = 'admin'
  AND r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'USER'
  AND p.permission_code LIKE '%:view'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 字典初始数据
INSERT INTO sys_dict_type (dict_code, dict_name, description)
SELECT 'sys_user_status', '用户状态', '用户状态列表'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_code = 'sys_user_status');

INSERT INTO sys_dict_type (dict_code, dict_name, description)
SELECT 'sys_enabled_status', '启用状态', '通用启用禁用状态'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_code = 'sys_enabled_status');

INSERT INTO sys_dict_type (dict_code, dict_name, description)
SELECT 'sequence_type', '序列类型', '序列生成器类型'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_code = 'sequence_type');

-- 用户状态字典数据
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, is_default, status)
SELECT id, '正常', '1', 1, 1, 1 FROM sys_dict_type WHERE dict_code = 'sys_user_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_label = '正常' AND dict_value = '1');

INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, is_default, status)
SELECT id, '停用', '0', 2, 0, 1 FROM sys_dict_type WHERE dict_code = 'sys_user_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_label = '停用' AND dict_value = '0');

-- 启用状态字典数据
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, is_default, status)
SELECT id, '启用', '1', 1, 1, 1 FROM sys_dict_type WHERE dict_code = 'sys_enabled_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_label = '启用' AND dict_value = '1');

INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, is_default, status)
SELECT id, '禁用', '0', 2, 0, 1 FROM sys_dict_type WHERE dict_code = 'sys_enabled_status'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_label = '禁用' AND dict_value = '0');

-- 序列类型字典数据
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, is_default, status)
SELECT id, '雪花算法', 'SNOWFLAKE', 1, 0, 1 FROM sys_dict_type WHERE dict_code = 'sequence_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_label = '雪花算法');

INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, is_default, status)
SELECT id, '号段模式', 'SEGMENT', 2, 1, 1 FROM sys_dict_type WHERE dict_code = 'sequence_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_label = '号段模式');

INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, is_default, status)
SELECT id, 'Redis模式', 'REDIS', 3, 0, 1 FROM sys_dict_type WHERE dict_code = 'sequence_type'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_label = 'Redis模式');

-- 系统配置初始数据
INSERT INTO sys_config (config_name, config_key, config_value, config_type, description)
SELECT '系统名称', 'sys.site.name', 'Astral 序列管理系统', 1, '系统显示名称'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.site.name');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, description)
SELECT '默认序列类型', 'sequence.default.type', 'SEGMENT', 1, '新建业务键时默认使用的序列类型'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sequence.default.type');

INSERT INTO sys_config (config_name, config_key, config_value, config_type, description)
SELECT '默认号段步长', 'sequence.default.step', '1000', 1, '号段模式默认步长大小'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sequence.default.step');
