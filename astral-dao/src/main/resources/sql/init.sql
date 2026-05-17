-- 创建数据库
CREATE DATABASE IF NOT EXISTS astral_sequence DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE astral_sequence;

-- 序列号段表
CREATE TABLE IF NOT EXISTS `sequence_segment` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `biz_key` VARCHAR(64) NOT NULL COMMENT '业务键',
    `min_value` BIGINT(20) NOT NULL DEFAULT 1 COMMENT '当前最小值',
    `max_value` BIGINT(20) NOT NULL COMMENT '当前最大值',
    `current_max_value` BIGINT(20) NOT NULL COMMENT '当前已分配最大值',
    `step` INT(11) NOT NULL DEFAULT 1000 COMMENT '步长',
    `version` INT(11) NOT NULL DEFAULT 0 COMMENT '版本号(乐观锁)',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_key` (`biz_key`),
    KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='序列号段表';

-- 插入测试数据
INSERT INTO `sequence_segment` (`biz_key`, `min_value`, `max_value`, `current_max_value`, `step`) 
VALUES 
    ('order_id', 1, 1000, 1000, 1000),
    ('user_id', 1, 1000, 1000, 1000),
    ('payment_id', 1, 1000, 1000, 1000)
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;

-- 序列配置表
CREATE TABLE IF NOT EXISTS `sequence_config` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `biz_key` VARCHAR(64) NOT NULL COMMENT '业务键',
    `sequence_type` VARCHAR(32) NOT NULL COMMENT '序列类型(snowflake/segment/redis)',
    `step` INT(11) NOT NULL DEFAULT 1000 COMMENT '步长',
    `date_format` VARCHAR(32) DEFAULT NULL COMMENT '日期格式',
    `prefix` VARCHAR(32) DEFAULT NULL COMMENT '前缀',
    `suffix` VARCHAR(32) DEFAULT NULL COMMENT '后缀',
    `min_value` BIGINT(20) NOT NULL DEFAULT 1 COMMENT '起始值',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `description` VARCHAR(256) DEFAULT NULL COMMENT '描述',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_key` (`biz_key`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='序列配置表';

-- 序列使用统计表
CREATE TABLE IF NOT EXISTS `sequence_statistics` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `biz_key` VARCHAR(64) NOT NULL COMMENT '业务键',
    `sequence_type` VARCHAR(32) NOT NULL COMMENT '序列类型',
    `current_value` BIGINT(20) NOT NULL DEFAULT 0 COMMENT '当前值',
    `total_count` BIGINT(20) NOT NULL DEFAULT 0 COMMENT '总生成次数',
    `date` DATE NOT NULL COMMENT '统计日期',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_key_date` (`biz_key`, `date`),
    KEY `idx_date` (`date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='序列使用统计表';
