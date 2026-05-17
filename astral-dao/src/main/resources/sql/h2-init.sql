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
    current_value BIGINT,
    total_generate BIGINT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_config_enabled ON sequence_config(enabled);

-- 序列使用统计表
CREATE TABLE IF NOT EXISTS sequence_statistics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL UNIQUE,
    current_value BIGINT NOT NULL DEFAULT 0,
    total_generate BIGINT NOT NULL DEFAULT 0,
    last_generate_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_statistics_biz_key ON sequence_statistics(biz_key);

-- 序列生成历史记录表
CREATE TABLE IF NOT EXISTS sequence_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL,
    sequence_type VARCHAR(32) NOT NULL,
    sequence_value BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_history_biz_key ON sequence_history(biz_key);
CREATE INDEX IF NOT EXISTS idx_history_create_time ON sequence_history(create_time);

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
    deleted TINYINT NOT NULL DEFAULT 0,
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
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0
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
