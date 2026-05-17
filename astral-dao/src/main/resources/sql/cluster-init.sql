-- ============================================
-- 集群模式相关配置
-- ============================================

-- 集群节点配置表(可选,用于持久化节点信息)
CREATE TABLE IF NOT EXISTS `cluster_node_config` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `node_id` VARCHAR(64) NOT NULL COMMENT '节点唯一标识(UUID)',
    `worker_id` INT(11) NOT NULL COMMENT 'WorkerId(0-1023)',
    `ip_address` VARCHAR(64) NOT NULL COMMENT '节点IP地址',
    `port` INT(11) NOT NULL DEFAULT 8080 COMMENT '节点端口',
    `node_name` VARCHAR(128) COMMENT '节点名称',
    `status` VARCHAR(32) NOT NULL DEFAULT 'ONLINE' COMMENT '节点状态: ONLINE/OFFLINE',
    `max_worker_id` INT(11) NOT NULL DEFAULT 1023 COMMENT '最大WorkerId',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_node_id` (`node_id`),
    UNIQUE KEY `uk_worker_id` (`worker_id`),
    KEY `idx_status` (`status`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='集群节点配置表';

-- 插入示例配置
INSERT INTO `cluster_node_config` (`node_id`, `worker_id`, `ip_address`, `port`, `node_name`, `status`) 
VALUES 
    ('node-1', 1, '192.168.1.10', 8080, 'sequence-node-1', 'ONLINE'),
    ('node-2', 2, '192.168.1.11', 8080, 'sequence-node-2', 'ONLINE'),
    ('node-3', 3, '192.168.1.12', 8080, 'sequence-node-3', 'ONLINE')
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;
