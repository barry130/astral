-- ============================================
-- 问题反馈插件表结构（自动创建，幂等）
-- sys_feedback / sys_feedback_reply
-- 主键由全局序列（业务键=表名_id，如 sys_feedback_id）自动填充，故不设自增
-- PostgreSQL 方言；prod 为 MySQL profile 时需手动执行
-- 注意：通知表（原 3.3 段）不在此处建表——通知能力已收归宿主，
--       建表权在 astral-server/src/main/resources/db/migration/V20261027001__sys_notice_to_host.sql
-- ============================================

-- 3.1 反馈主表
CREATE TABLE IF NOT EXISTS sys_feedback (
    id            BIGINT PRIMARY KEY,                      -- 序列 sys_feedback_id
    user_id       BIGINT NOT NULL,                         -- sys_user.id（APP 用户）
    type          VARCHAR(16) NOT NULL,                    -- issue 问题 | request 需求
    title         VARCHAR(128) NOT NULL,
    content       VARCHAR(5000) NOT NULL,
    contact       VARCHAR(64),                             -- 联系方式，可空
    status        VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending提出|received已接收|resolved已解决|published已发布|deprecated已废弃
    is_public     BOOLEAN NOT NULL DEFAULT FALSE,          -- 公开(全体可见)/私有(仅本人)；published 时 App 才展示
    device        VARCHAR(128),                            -- 设备型号
    os            VARCHAR(64),                             -- 系统版本
    app_version   VARCHAR(32),                             -- App 版本（如 3.0.0）
    platform      VARCHAR(16),                             -- android | ios
    ip            VARCHAR(64),                             -- 提交 IP（服务端取）
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_time   TIMESTAMP                                -- 软删
);
CREATE INDEX IF NOT EXISTS idx_feedback_user ON sys_feedback(user_id);
CREATE INDEX IF NOT EXISTS idx_feedback_status ON sys_feedback(status);
CREATE INDEX IF NOT EXISTS idx_feedback_type ON sys_feedback(type);
CREATE INDEX IF NOT EXISTS idx_feedback_create_time ON sys_feedback(create_time);

-- 3.2 回复表（双向、扁平）
CREATE TABLE IF NOT EXISTS sys_feedback_reply (
    id            BIGINT PRIMARY KEY,                      -- 序列 sys_feedback_reply_id
    feedback_id   BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,                         -- 发送者（用户/管理员都是 sys_user.id）
    content       VARCHAR(2000) NOT NULL,
    reply_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP  -- 用于日期筛选
);
CREATE INDEX IF NOT EXISTS idx_reply_feedback ON sys_feedback_reply(feedback_id);
CREATE INDEX IF NOT EXISTS idx_reply_time ON sys_feedback_reply(reply_time);
CREATE INDEX IF NOT EXISTS idx_reply_user ON sys_feedback_reply(user_id);

-- 字段注释（幂等，启动可重复执行）
COMMENT ON COLUMN sys_feedback.id IS '主键ID';
COMMENT ON COLUMN sys_feedback.user_id IS '提交用户ID(sys_user.id)';
COMMENT ON COLUMN sys_feedback.type IS '类型(issue问题/request需求)';
COMMENT ON COLUMN sys_feedback.title IS '标题';
COMMENT ON COLUMN sys_feedback.content IS '内容';
COMMENT ON COLUMN sys_feedback.contact IS '联系方式';
COMMENT ON COLUMN sys_feedback.status IS '状态(pending/received/resolved/published/deprecated)';
COMMENT ON COLUMN sys_feedback.is_public IS '是否公开(发布后App展示)';
COMMENT ON COLUMN sys_feedback.device IS '设备型号';
COMMENT ON COLUMN sys_feedback.os IS '系统版本';
COMMENT ON COLUMN sys_feedback.app_version IS 'App版本';
COMMENT ON COLUMN sys_feedback.platform IS '平台(android/ios)';
COMMENT ON COLUMN sys_feedback.ip IS '提交IP';
COMMENT ON COLUMN sys_feedback.create_time IS '创建时间';
COMMENT ON COLUMN sys_feedback.update_time IS '更新时间';
COMMENT ON COLUMN sys_feedback.delete_time IS '软删除时间';
COMMENT ON COLUMN sys_feedback_reply.id IS '主键ID';
COMMENT ON COLUMN sys_feedback_reply.feedback_id IS '反馈ID';
COMMENT ON COLUMN sys_feedback_reply.user_id IS '发送者ID(用户/管理员均为sys_user.id)';
COMMENT ON COLUMN sys_feedback_reply.content IS '回复内容';
COMMENT ON COLUMN sys_feedback_reply.reply_time IS '回复时间';
