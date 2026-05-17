-- ============================================
-- PostgreSQL 初始化脚本
-- ============================================

-- 创建序列号段表
CREATE TABLE IF NOT EXISTS sequence_segment (
    id BIGSERIAL PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL UNIQUE,
    min_value BIGINT NOT NULL DEFAULT 1,
    max_value BIGINT NOT NULL,
    current_max_value BIGINT NOT NULL,
    step INTEGER NOT NULL DEFAULT 1000,
    version INTEGER NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_segment_biz_key ON sequence_segment(biz_key);
CREATE INDEX IF NOT EXISTS idx_segment_update_time ON sequence_segment(update_time);

-- 创建序列配置表
CREATE TABLE IF NOT EXISTS sequence_config (
    id BIGSERIAL PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL UNIQUE,
    sequence_type VARCHAR(32) NOT NULL,
    step INTEGER NOT NULL DEFAULT 1000,
    date_format VARCHAR(32),
    prefix VARCHAR(32),
    suffix VARCHAR(32),
    min_value BIGINT NOT NULL DEFAULT 1,
    enabled SMALLINT NOT NULL DEFAULT 1,
    description VARCHAR(256),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 创建序列使用统计表
CREATE TABLE IF NOT EXISTS sequence_statistics (
    id BIGSERIAL PRIMARY KEY,
    biz_key VARCHAR(64) NOT NULL,
    sequence_type VARCHAR(32) NOT NULL,
    current_value BIGINT NOT NULL DEFAULT 0,
    total_count BIGINT NOT NULL DEFAULT 0,
    stat_date DATE NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(biz_key, stat_date)
);

CREATE INDEX IF NOT EXISTS idx_statistics_date ON sequence_statistics(stat_date);

-- 更新时间戳触发器
CREATE OR REPLACE FUNCTION update_modified_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_time = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_segment_modtime
    BEFORE UPDATE ON sequence_segment
    FOR EACH ROW
    EXECUTE FUNCTION update_modified_column();

CREATE TRIGGER update_config_modtime
    BEFORE UPDATE ON sequence_config
    FOR EACH ROW
    EXECUTE FUNCTION update_modified_column();

CREATE TRIGGER update_statistics_modtime
    BEFORE UPDATE ON sequence_statistics
    FOR EACH ROW
    EXECUTE FUNCTION update_modified_column();

-- 插入测试数据
INSERT INTO sequence_segment (biz_key, min_value, max_value, current_max_value, step) 
VALUES 
    ('order_id', 1, 1000, 1000, 1000),
    ('user_id', 1, 1000, 1000, 1000),
    ('payment_id', 1, 1000, 1000, 1000)
ON CONFLICT (biz_key) DO UPDATE SET update_time = CURRENT_TIMESTAMP;
