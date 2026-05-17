-- ============================================
-- SQL Server 初始化脚本
-- ============================================

-- 创建序列号段表
IF NOT EXISTS (SELECT * FROM sysobjects WHERE name='sequence_segment' AND xtype='U')
BEGIN
    CREATE TABLE sequence_segment (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        biz_key NVARCHAR(64) NOT NULL UNIQUE,
        min_value BIGINT NOT NULL DEFAULT 1,
        max_value BIGINT NOT NULL,
        current_max_value BIGINT NOT NULL,
        step INT NOT NULL DEFAULT 1000,
        version INT NOT NULL DEFAULT 0,
        create_time DATETIME NOT NULL DEFAULT GETDATE(),
        update_time DATETIME NOT NULL DEFAULT GETDATE()
    );
    
    CREATE INDEX idx_segment_biz_key ON sequence_segment(biz_key);
    CREATE INDEX idx_segment_update_time ON sequence_segment(update_time);
END
GO

-- 创建序列配置表
IF NOT EXISTS (SELECT * FROM sysobjects WHERE name='sequence_config' AND xtype='U')
BEGIN
    CREATE TABLE sequence_config (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        biz_key NVARCHAR(64) NOT NULL UNIQUE,
        sequence_type NVARCHAR(32) NOT NULL,
        step INT NOT NULL DEFAULT 1000,
        date_format NVARCHAR(32),
        prefix NVARCHAR(32),
        suffix NVARCHAR(32),
        min_value BIGINT NOT NULL DEFAULT 1,
        enabled BIT NOT NULL DEFAULT 1,
        description NVARCHAR(256),
        create_time DATETIME NOT NULL DEFAULT GETDATE(),
        update_time DATETIME NOT NULL DEFAULT GETDATE()
    );
    
    CREATE INDEX idx_config_enabled ON sequence_config(enabled);
END
GO

-- 创建序列使用统计表
IF NOT EXISTS (SELECT * FROM sysobjects WHERE name='sequence_statistics' AND xtype='U')
BEGIN
    CREATE TABLE sequence_statistics (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        biz_key NVARCHAR(64) NOT NULL,
        sequence_type NVARCHAR(32) NOT NULL,
        current_value BIGINT NOT NULL DEFAULT 0,
        total_count BIGINT NOT NULL DEFAULT 0,
        stat_date DATE NOT NULL,
        create_time DATETIME NOT NULL DEFAULT GETDATE(),
        update_time DATETIME NOT NULL DEFAULT GETDATE()
    );
    
    CREATE UNIQUE INDEX uk_statistics_biz_date ON sequence_statistics(biz_key, stat_date);
    CREATE INDEX idx_statistics_date ON sequence_statistics(stat_date);
END
GO

-- 创建触发器自动更新时间戳
IF OBJECT_ID('trg_segment_update_time', 'TR') IS NOT NULL
    DROP TRIGGER trg_segment_update_time
GO

CREATE TRIGGER trg_segment_update_time
ON sequence_segment
AFTER UPDATE
AS
BEGIN
    UPDATE sequence_segment
    SET update_time = GETDATE()
    FROM sequence_segment s
    INNER JOIN inserted i ON s.id = i.id
END
GO

-- 插入测试数据
IF NOT EXISTS (SELECT 1 FROM sequence_segment WHERE biz_key = 'order_id')
BEGIN
    INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step)
    VALUES 
        ('order_id', 1, 1000, 1000, 1000),
        ('user_id', 1, 1000, 1000, 1000),
        ('payment_id', 1, 1000, 1000, 1000);
END
GO
