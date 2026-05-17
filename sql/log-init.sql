-- 日志系统初始化脚本

USE astral_sequence;

-- 操作日志表
CREATE TABLE IF NOT EXISTS `sys_operate_log` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT(20) DEFAULT NULL COMMENT '用户ID',
    `username` VARCHAR(64) DEFAULT NULL COMMENT '用户名',
    `module` VARCHAR(64) NOT NULL COMMENT '模块名称',
    `operate_type` VARCHAR(32) NOT NULL COMMENT '操作类型(ADD/UPDATE/DELETE/QUERY/LOGIN/LOGOUT)',
    `request_method` VARCHAR(16) NOT NULL COMMENT '请求方式(GET/POST/PUT/DELETE)',
    `request_url` VARCHAR(256) NOT NULL COMMENT '请求URL',
    `request_params` TEXT DEFAULT NULL COMMENT '请求参数(JSON)',
    `response_result` TEXT DEFAULT NULL COMMENT '响应结果(JSON)',
    `ip` VARCHAR(64) DEFAULT NULL COMMENT '操作IP',
    `location` VARCHAR(128) DEFAULT NULL COMMENT '操作地点',
    `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '操作状态(0-失败 1-成功)',
    `error_msg` TEXT DEFAULT NULL COMMENT '错误信息',
    `execute_time` BIGINT(20) DEFAULT NULL COMMENT '执行时间(毫秒)',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_operate_type` (`operate_type`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表';

-- 登录日志表
CREATE TABLE IF NOT EXISTS `sys_login_log` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT(20) DEFAULT NULL COMMENT '用户ID',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `login_type` VARCHAR(32) NOT NULL DEFAULT 'PASSWORD' COMMENT '登录方式(PASSWORD/TOKEN/REFRESH)',
    `ip` VARCHAR(64) NOT NULL COMMENT '登录IP',
    `location` VARCHAR(128) DEFAULT NULL COMMENT '登录地点',
    `browser` VARCHAR(64) DEFAULT NULL COMMENT '浏览器',
    `os` VARCHAR(64) DEFAULT NULL COMMENT '操作系统',
    `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '登录状态(0-失败 1-成功)',
    `msg` VARCHAR(256) DEFAULT NULL COMMENT '提示消息',
    `login_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_username` (`username`),
    KEY `idx_status` (`status`),
    KEY `idx_login_time` (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录日志表';

-- 插入测试数据（可选）
INSERT INTO `sys_operate_log` (`user_id`, `username`, `module`, `operate_type`, `request_method`, `request_url`, `ip`, `status`, `execute_time`)
VALUES 
    (1, 'admin', '序列管理', 'QUERY', 'POST', '/api/v1/sequence/next', '127.0.0.1', 1, 15),
    (1, 'admin', '用户管理', 'LOGIN', 'POST', '/api/v1/auth/login', '127.0.0.1', 1, 50)
ON DUPLICATE KEY UPDATE `create_time` = CURRENT_TIMESTAMP;
