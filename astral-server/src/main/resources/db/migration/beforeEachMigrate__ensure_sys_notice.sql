-- 早期迁移先修改通知表，宿主正式建表版本晚于这些修改。
-- 在 Flyway 完成空库/baseline 判定、取得迁移锁后补齐缺失的表。
-- 已有表保持原样；数据转换、索引与注释仍由原版本处理，不修改历史 checksum。
DO $$
BEGIN
    IF to_regclass(format('%I.%I', current_schema(), 'sys_notice')) IS NULL THEN
        CREATE TABLE IF NOT EXISTS sys_notice (
            id BIGINT PRIMARY KEY,
            channel VARCHAR(96) NOT NULL DEFAULT 'app-android,app-ios',
            notice_type VARCHAR(16) NOT NULL DEFAULT 'announce',
            scene VARCHAR(64),
            user_id BIGINT,
            feedback_id BIGINT,
            display BIGINT NOT NULL DEFAULT 4,
            title VARCHAR(128) NOT NULL,
            content VARCHAR(65535),
            url VARCHAR(512),
            is_show BIGINT NOT NULL DEFAULT 1,
            is_top BIGINT NOT NULL DEFAULT 0,
            dialog_closable BIGINT NOT NULL DEFAULT 1,
            first_login_only BIGINT NOT NULL DEFAULT 0,
            marquee BIGINT NOT NULL DEFAULT 0,
            read_seconds BIGINT,
            terms_version VARCHAR(32),
            effective_start TIMESTAMP,
            effective_end TIMESTAMP,
            version_min BIGINT,
            version_max BIGINT,
            audience VARCHAR(16) NOT NULL DEFAULT 'ALL',
            read_time TIMESTAMP,
            create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
    END IF;
END $$;
