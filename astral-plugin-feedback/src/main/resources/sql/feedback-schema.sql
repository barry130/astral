-- ============================================
-- 问题反馈插件表结构（自动创建，幂等）
-- sys_feedback / sys_feedback_reply / sys_notice
-- 主键由全局序列(astral_id)生成，故不设自增
-- PostgreSQL 方言；prod 为 MySQL profile 时需手动执行
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

-- 3.3 统一通知表（qt_app_notice 超集）
CREATE TABLE IF NOT EXISTS sys_notice (
    id               BIGINT PRIMARY KEY,                   -- 序列 sys_notice_id
    -- 新增维度
    channel          VARCHAR(16) NOT NULL DEFAULT 'app',   -- app | pc | web | all
    notice_type      VARCHAR(16) NOT NULL DEFAULT 'announce', -- announce公告|feedback反馈|request需求
    user_id          BIGINT,                               -- NULL=广播；有值=点对点
    feedback_id      BIGINT,                               -- 关联 sys_feedback.id
    -- 继承 qt_app_notice 投放能力（原 type 更名 display）
    display          BIGINT NOT NULL DEFAULT 4,            -- 位掩码 1=开屏 2=通告栏 4=消息中心
    title            VARCHAR(128) NOT NULL,
    content          VARCHAR(65535),
    url              VARCHAR(512),
    is_show          BIGINT NOT NULL DEFAULT 1,
    is_top           BIGINT NOT NULL DEFAULT 0,
    dialog_closable  BIGINT NOT NULL DEFAULT 1,
    first_login_only BIGINT NOT NULL DEFAULT 0,
    marquee          BIGINT NOT NULL DEFAULT 0,
    effective_start  TIMESTAMP,
    effective_end    TIMESTAMP,
    version_min      BIGINT,                               -- 版本码（如 300）
    version_max      BIGINT,
    audience         VARCHAR(16) NOT NULL DEFAULT 'ALL',
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_notice_channel ON sys_notice(channel);
CREATE INDEX IF NOT EXISTS idx_notice_type ON sys_notice(notice_type);
CREATE INDEX IF NOT EXISTS idx_notice_user ON sys_notice(user_id);
CREATE INDEX IF NOT EXISTS idx_notice_show ON sys_notice(is_show, is_top);
CREATE INDEX IF NOT EXISTS idx_notice_create_time ON sys_notice(create_time);

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
COMMENT ON COLUMN sys_notice.id IS '主键ID';
COMMENT ON COLUMN sys_notice.channel IS '渠道(app/web/all)';
COMMENT ON COLUMN sys_notice.notice_type IS '类型(announce公告/feedback反馈/request需求)';
COMMENT ON COLUMN sys_notice.user_id IS '点对点目标用户ID(NULL=广播)';
COMMENT ON COLUMN sys_notice.feedback_id IS '关联反馈ID';
COMMENT ON COLUMN sys_notice.display IS '展示位掩码(1开屏/2通告栏/4消息中心,可叠加)';
COMMENT ON COLUMN sys_notice.title IS '标题';
COMMENT ON COLUMN sys_notice.content IS '内容';
COMMENT ON COLUMN sys_notice.url IS '点击跳转链接';
COMMENT ON COLUMN sys_notice.is_show IS '是否启用(0-隐藏 1-展示)';
COMMENT ON COLUMN sys_notice.is_top IS '是否置顶(0-否 1-是)';
COMMENT ON COLUMN sys_notice.dialog_closable IS '开屏弹窗是否可关闭';
COMMENT ON COLUMN sys_notice.first_login_only IS '仅首次登录弹出';
COMMENT ON COLUMN sys_notice.marquee IS '通告栏是否跑马灯';
COMMENT ON COLUMN sys_notice.effective_start IS '生效时间(空不限)';
COMMENT ON COLUMN sys_notice.effective_end IS '失效时间(空不限)';
COMMENT ON COLUMN sys_notice.version_min IS '生效版本码下限(如300)';
COMMENT ON COLUMN sys_notice.version_max IS '生效版本码上限';
COMMENT ON COLUMN sys_notice.audience IS '可见人群(ALL/LOGGED_IN/NOT_LOGGED_IN)';
COMMENT ON COLUMN sys_notice.create_time IS '创建时间';
COMMENT ON COLUMN sys_notice.update_time IS '更新时间';

-- 8. qt_app_notice 存量一次性迁移到 sys_notice（幂等，可重复执行）
INSERT INTO sys_notice (id, channel, notice_type, user_id, feedback_id, display, title, content, url,
                        is_show, is_top, dialog_closable, first_login_only, marquee,
                        effective_start, effective_end, version_min, version_max, audience,
                        create_time, update_time)
SELECT id, 'app', 'announce', NULL, NULL, type, title, content, url,
       is_show, is_top, dialog_closable, first_login_only, marquee,
       effective_start, effective_end, version_min, version_max, audience,
       create_time, update_time
FROM qt_app_notice
WHERE NOT EXISTS (SELECT 1 FROM sys_notice s WHERE s.id = qt_app_notice.id);
