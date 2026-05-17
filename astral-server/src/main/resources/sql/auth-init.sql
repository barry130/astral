-- 用户认证与权限系统初始化脚本

USE astral_sequence;

-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `password` VARCHAR(128) NOT NULL COMMENT '密码(加密)',
    `nickname` VARCHAR(64) DEFAULT NULL COMMENT '昵称',
    `email` VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    `phone` VARCHAR(32) DEFAULT NULL COMMENT '手机号',
    `avatar` VARCHAR(256) DEFAULT NULL COMMENT '头像URL',
    `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    `login_ip` VARCHAR(64) DEFAULT NULL COMMENT '最后登录IP',
    `login_time` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `pwd_update_time` DATETIME DEFAULT NULL COMMENT '密码更新时间',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_status` (`status`),
    KEY `idx_email` (`email`),
    KEY `idx_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 角色表
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `role_code` VARCHAR(64) NOT NULL COMMENT '角色编码',
    `role_name` VARCHAR(128) NOT NULL COMMENT '角色名称',
    `description` VARCHAR(256) DEFAULT NULL COMMENT '描述',
    `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    `sort` INT(11) NOT NULL DEFAULT 0 COMMENT '排序',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

-- 权限表
CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `permission_code` VARCHAR(128) NOT NULL COMMENT '权限编码',
    `permission_name` VARCHAR(128) NOT NULL COMMENT '权限名称',
    `url` VARCHAR(256) DEFAULT NULL COMMENT 'URL路径',
    `method` VARCHAR(16) DEFAULT NULL COMMENT '请求方式',
    `parent_id` BIGINT(20) NOT NULL DEFAULT 0 COMMENT '父权限ID',
    `type` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '类型(1-菜单 2-按钮 3-接口)',
    `icon` VARCHAR(64) DEFAULT NULL COMMENT '图标',
    `sort` INT(11) NOT NULL DEFAULT 0 COMMENT '排序',
    `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`permission_code`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限表';

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT(20) NOT NULL COMMENT '用户ID',
    `role_id` BIGINT(20) NOT NULL COMMENT '角色ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `role_id` BIGINT(20) NOT NULL COMMENT '角色ID',
    `permission_id` BIGINT(20) NOT NULL COMMENT '权限ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
    KEY `idx_permission_id` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- 插入初始数据

-- 默认管理员用户 (密码: admin, BCrypt加密)
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `status`)
VALUES 
    ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuPiSQ5e6YnpjfPFWezevy2Mqt7R1D1RC', '系统管理员', 1)
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;

-- 默认角色
INSERT INTO `sys_role` (`role_code`, `role_name`, `description`, `sort`)
VALUES 
    ('ADMIN', '系统管理员', '系统管理员，拥有所有权限', 1),
    ('USER', '普通用户', '普通用户，拥有基本权限', 2)
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;

-- 默认权限
INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `url`, `method`, `type`, `sort`)
VALUES 
    ('sequence:view', '查看序列', '/api/v1/sequence/**', NULL, 1, 1),
    ('sequence:generate', '生成序列', '/api/v1/sequence/next', 'POST', 2, 2),
    ('sequence:batch', '批量生成', '/api/v1/sequence/batch', 'POST', 2, 3),
    ('log:view', '查看日志', '/api/v1/log/**', NULL, 1, 4),
    ('user:manage', '用户管理', '/api/v1/users/**', NULL, 1, 5),
    ('monitor:view', '查看监控', '/api/v1/monitor/**', NULL, 1, 6)
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;

-- 关联管理员角色和权限
INSERT INTO `sys_user_role` (`user_id`, `role_id`)
VALUES (1, 1)
ON DUPLICATE KEY UPDATE `create_time` = CURRENT_TIMESTAMP;

-- 关联角色权限（管理员拥有所有权限）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT 1, id FROM `sys_permission`
ON DUPLICATE KEY UPDATE `create_time` = CURRENT_TIMESTAMP;

-- 普通用户只有查看权限
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT 2, id FROM `sys_permission` WHERE `permission_code` LIKE '%:view'
ON DUPLICATE KEY UPDATE `create_time` = CURRENT_TIMESTAMP;

-- 字典类型表
CREATE TABLE IF NOT EXISTS `sys_dict_type` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `dict_code` VARCHAR(64) NOT NULL COMMENT '字典编码',
    `dict_name` VARCHAR(128) NOT NULL COMMENT '字典名称',
    `data_type` VARCHAR(32) COMMENT '数据类型',
    `jdbc_type` VARCHAR(32) COMMENT 'JDBC类型',
    `data_length` INT COMMENT '数据长度',
    `description` VARCHAR(1024) DEFAULT NULL COMMENT '描述',
    `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dict_code` (`dict_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典类型表';

-- 字典数据表
CREATE TABLE IF NOT EXISTS `sys_dict_data` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `dict_type_id` BIGINT(20) NOT NULL COMMENT '字典类型ID',
    `dict_label` VARCHAR(128) NOT NULL COMMENT '字典标签',
    `dict_value` VARCHAR(128) NOT NULL COMMENT '字典值',
    `dict_sort` INT(11) NOT NULL DEFAULT 0 COMMENT '排序',
    `css_class` VARCHAR(128) DEFAULT NULL COMMENT 'CSS样式',
    `list_class` VARCHAR(128) DEFAULT NULL COMMENT '表格回显样式',
    `is_default` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否默认(0-否 1-是)',
    `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '状态(0-禁用 1-启用)',
    `description` VARCHAR(1024) DEFAULT NULL COMMENT '描述',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_dict_type_id` (`dict_type_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典数据表';

-- 系统配置表
CREATE TABLE IF NOT EXISTS `sys_config` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `config_name` VARCHAR(128) NOT NULL COMMENT '配置名称',
    `config_key` VARCHAR(128) NOT NULL COMMENT '配置键',
    `config_value` TEXT DEFAULT NULL COMMENT '配置值',
    `config_type` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '类型(1-内置 2-自定义)',
    `description` VARCHAR(256) DEFAULT NULL COMMENT '描述',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`),
    KEY `idx_config_type` (`config_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';

-- 字典初始数据
INSERT INTO `sys_dict_type` (`dict_code`, `dict_name`, `description`) VALUES
('sys_user_status', '用户状态', '用户状态列表'),
('sys_enabled_status', '启用状态', '通用启用禁用状态'),
('sequence_type', '序列类型', '序列生成器类型')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;

INSERT INTO `sys_dict_data` (`dict_type_id`, `dict_label`, `dict_value`, `dict_sort`, `is_default`, `status`)
SELECT id, '正常', '1', 1, 1, 1 FROM `sys_dict_type` WHERE `dict_code` = 'sys_user_status';

INSERT INTO `sys_dict_data` (`dict_type_id`, `dict_label`, `dict_value`, `dict_sort`, `is_default`, `status`)
SELECT id, '停用', '0', 2, 0, 1 FROM `sys_dict_type` WHERE `dict_code` = 'sys_user_status';

INSERT INTO `sys_dict_data` (`dict_type_id`, `dict_label`, `dict_value`, `dict_sort`, `is_default`, `status`)
SELECT id, '启用', '1', 1, 1, 1 FROM `sys_dict_type` WHERE `dict_code` = 'sys_enabled_status';

INSERT INTO `sys_dict_data` (`dict_type_id`, `dict_label`, `dict_value`, `dict_sort`, `is_default`, `status`)
SELECT id, '禁用', '0', 2, 0, 1 FROM `sys_dict_type` WHERE `dict_code` = 'sys_enabled_status';

INSERT INTO `sys_dict_data` (`dict_type_id`, `dict_label`, `dict_value`, `dict_sort`, `is_default`, `status`)
SELECT id, '雪花算法', 'SNOWFLAKE', 1, 0, 1 FROM `sys_dict_type` WHERE `dict_code` = 'sequence_type';

INSERT INTO `sys_dict_data` (`dict_type_id`, `dict_label`, `dict_value`, `dict_sort`, `is_default`, `status`)
SELECT id, '号段模式', 'SEGMENT', 2, 1, 1 FROM `sys_dict_type` WHERE `dict_code` = 'sequence_type';

INSERT INTO `sys_dict_data` (`dict_type_id`, `dict_label`, `dict_value`, `dict_sort`, `is_default`, `status`)
SELECT id, 'Redis模式', 'REDIS', 3, 0, 1 FROM `sys_dict_type` WHERE `dict_code` = 'sequence_type';

-- 系统配置初始数据
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `description`) VALUES
('系统名称', 'sys.site.name', 'Astral 序列管理系统', 1, '系统显示名称'),
('默认序列类型', 'sequence.default.type', 'SEGMENT', 1, '新建业务键时默认使用的序列类型'),
('默认号段步长', 'sequence.default.step', '1000', 1, '号段模式默认步长大小')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;
