-- ============================================
-- 轻听(App) API 插件表结构（自动创建）
-- H2 / MySQL 兼容：创建 qt_* 表，实体主键由全局序列(astral_id)生成，故不设自增
-- 说明：轻听 App 用户已并入宿主 sys_user（user_type='APP'），鉴权统一走 Sa-Token，
--       故不再单独建 qt_user / qt_user_token 表。
-- ============================================

CREATE TABLE IF NOT EXISTS qt_app_notice (
    id BIGINT PRIMARY KEY,
    type BIGINT NOT NULL DEFAULT 0,
    url VARCHAR(512),
    uid VARCHAR(64),
    title VARCHAR(128),
    content VARCHAR(65535),
    is_show BIGINT NOT NULL DEFAULT 1,
    is_top BIGINT NOT NULL DEFAULT 0,
    dialog_closable BIGINT NOT NULL DEFAULT 1,
    first_login_only BIGINT NOT NULL DEFAULT 0,
    marquee BIGINT NOT NULL DEFAULT 0,
    effective_start TIMESTAMP,
    effective_end TIMESTAMP,
    version_min BIGINT,
    version_max BIGINT,
    audience VARCHAR(16) NOT NULL DEFAULT 'ALL',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qt_notice_show ON qt_app_notice(is_show);

-- 已有表（旧结构）补列：幂等，兼容已存在的数据表
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS content VARCHAR(65535);
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS is_top BIGINT NOT NULL DEFAULT 0;
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS dialog_closable BIGINT NOT NULL DEFAULT 1;
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS first_login_only BIGINT NOT NULL DEFAULT 0;
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS marquee BIGINT NOT NULL DEFAULT 0;
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS effective_start TIMESTAMP;
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS effective_end TIMESTAMP;
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS version_min BIGINT;
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS version_max BIGINT;
-- 旧结构历史表类型迁移：VARCHAR -> BIGINT（版本号，非语义化版本名）
ALTER TABLE qt_app_notice ALTER COLUMN version_min TYPE BIGINT USING (NULLIF(version_min, '')::BIGINT);
ALTER TABLE qt_app_notice ALTER COLUMN version_max TYPE BIGINT USING (NULLIF(version_max, '')::BIGINT);
ALTER TABLE qt_app_notice ADD COLUMN IF NOT EXISTS audience VARCHAR(16) NOT NULL DEFAULT 'ALL';

CREATE TABLE IF NOT EXISTS qt_app_notice_read (
    id BIGINT PRIMARY KEY,
    notice_id BIGINT NOT NULL DEFAULT 0,
    user_id BIGINT NOT NULL DEFAULT 0,
    read_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_qt_notice_read ON qt_app_notice_read(notice_id, user_id);

CREATE TABLE IF NOT EXISTS qt_app_update (
    id BIGINT PRIMARY KEY,
    version_code BIGINT NOT NULL,
    type BIGINT NOT NULL,
    version_name VARCHAR(64),
    version_info VARCHAR(512),
    update_type VARCHAR(32),
    download_url VARCHAR(1024),
    channel VARCHAR(16) NOT NULL DEFAULT 'stable',
    download_mode VARCHAR(16) NOT NULL DEFAULT 'app',
    browser_url VARCHAR(512),
    is_github BIGINT NOT NULL DEFAULT 0,
    is_force SMALLINT NOT NULL DEFAULT 0,
    is_published SMALLINT NOT NULL DEFAULT 0,
    file_size BIGINT,
    md5 VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qt_update_type ON qt_app_update(type, version_code);

-- 已有表（旧结构）补列：幂等，兼容已存在的数据表
ALTER TABLE qt_app_update ADD COLUMN IF NOT EXISTS channel VARCHAR(16) NOT NULL DEFAULT 'stable';
ALTER TABLE qt_app_update ADD COLUMN IF NOT EXISTS download_mode VARCHAR(16) NOT NULL DEFAULT 'app';
-- 版本更新下载改造(UPDATE_DESIGN.md)：双链接 + GitHub 加速
ALTER TABLE qt_app_update ADD COLUMN IF NOT EXISTS browser_url VARCHAR(512);
ALTER TABLE qt_app_update ADD COLUMN IF NOT EXISTS is_github BIGINT NOT NULL DEFAULT 0;
ALTER TABLE qt_app_update ADD COLUMN IF NOT EXISTS is_force SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE qt_app_update ADD COLUMN IF NOT EXISTS is_published SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE qt_app_update ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE qt_app_update ADD COLUMN IF NOT EXISTS md5 VARCHAR(64);

-- GitHub 加速前缀配置表（UPDATE_DESIGN.md §1.2，多行，探测顺序按 sort 升序）
CREATE TABLE IF NOT EXISTS qt_github_accel (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(64),
    prefix_url  VARCHAR(256) NOT NULL,
    is_show     BIGINT NOT NULL DEFAULT 1,
    sort        BIGINT NOT NULL DEFAULT 0,
    remark      VARCHAR(255),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS qt_user_daka (
    id BIGINT PRIMARY KEY,
    uid BIGINT NOT NULL,
    data DATE NOT NULL,
    integral BIGINT NOT NULL DEFAULT 0,
    is_use_code BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qt_daka_uid_data ON qt_user_daka(uid, data);

CREATE TABLE IF NOT EXISTS qt_like_playlist (
    id BIGINT PRIMARY KEY,
    uid BIGINT NOT NULL,
    pid VARCHAR(64) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    pic_url VARCHAR(256),
    name VARCHAR(128),
    is_import SMALLINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP,
    updated_seq BIGINT,
    updated_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qt_like_playlist ON qt_like_playlist(uid, deleted_at);
-- 收藏同步(LIKE_SYNC_DESIGN.md)：用户维度 seq 游标索引
ALTER TABLE qt_like_playlist ADD COLUMN IF NOT EXISTS updated_seq BIGINT;
ALTER TABLE qt_like_playlist ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_like_playlist_uid_seq ON qt_like_playlist(uid, updated_seq);
-- 历史重复数据去重（旧全量同步并发/重试产生重复行）：同 uid+pid+platform 保留1条（未删除优先、更新时间新者优先）
DELETE FROM qt_like_playlist WHERE id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY uid, pid, platform ORDER BY (deleted_at IS NULL) DESC, update_time DESC, id DESC) AS rn
        FROM qt_like_playlist
    ) t WHERE t.rn > 1
);
-- 全量唯一索引：支撑收藏 upsert（ON CONFLICT 命中软删行并复活，幂等防重）
CREATE UNIQUE INDEX IF NOT EXISTS uk_like_playlist_key ON qt_like_playlist(uid, pid, platform);

CREATE TABLE IF NOT EXISTS qt_like_song (
    id BIGINT PRIMARY KEY,
    uid BIGINT NOT NULL,
    sid VARCHAR(64) NOT NULL,
    pid VARCHAR(64),
    platform VARCHAR(16) NOT NULL,
    name VARCHAR(128),
    singer VARCHAR(128),
    album VARCHAR(128),
    hash VARCHAR(128),
    pic_url VARCHAR(2048),
    deleted_at TIMESTAMP,
    updated_seq BIGINT,
    updated_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qt_like_song ON qt_like_song(uid, sid, platform, deleted_at);
-- 收藏同步(LIKE_SYNC_DESIGN.md)：用户维度 seq 游标索引
ALTER TABLE qt_like_song ADD COLUMN IF NOT EXISTS updated_seq BIGINT;
ALTER TABLE qt_like_song ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
-- 收藏歌曲封面入库与多端同步(LIKE_SONG_PIC_SYNC_DESIGN.md)：封面 URL 快照，可空
ALTER TABLE qt_like_song ADD COLUMN IF NOT EXISTS pic_url VARCHAR(2048);
CREATE INDEX IF NOT EXISTS idx_like_song_uid_seq ON qt_like_song(uid, updated_seq);
-- 历史重复数据去重（旧全量同步并发/重试产生重复行）：同 uid+sid+platform+pid 保留1条（未删除优先、更新时间新者优先）
DELETE FROM qt_like_song WHERE id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY uid, sid, platform, pid ORDER BY (deleted_at IS NULL) DESC, update_time DESC, id DESC) AS rn
        FROM qt_like_song
    ) t WHERE t.rn > 1
);
-- 全量唯一索引：支撑收藏 upsert（ON CONFLICT 命中软删行并复活，幂等防重）
CREATE UNIQUE INDEX IF NOT EXISTS uk_like_song_key ON qt_like_song(uid, sid, platform);
-- 歌单维度收藏（LIKE_SYNC_DESIGN §2.1 pid）：同一歌曲可同时收藏到多个歌单（每歌单一行）。
-- 迁移：历史 NULL pid 归一为 ''（不归属具体歌单），再以 (uid,sid,platform,pid) 重建唯一索引。
UPDATE qt_like_song SET pid = '' WHERE pid IS NULL;
ALTER TABLE qt_like_song ALTER COLUMN pid SET DEFAULT '';
DROP INDEX IF EXISTS uk_like_song_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_like_song_key_pid ON qt_like_song(uid, sid, platform, pid);

-- 字段注释（幂等，启动可重复执行）
COMMENT ON COLUMN qt_app_notice.id IS '主键ID';
COMMENT ON COLUMN qt_app_notice.type IS '展示渠道位掩码(1-开屏弹窗 2-通告栏 4-消息中心,可叠加)';
COMMENT ON COLUMN qt_app_notice.url IS '点击后跳转链接';
COMMENT ON COLUMN qt_app_notice.uid IS '预留用户ID(历史字段)';
COMMENT ON COLUMN qt_app_notice.title IS '公告标题';
COMMENT ON COLUMN qt_app_notice.content IS '公告正文(可承载富文本)';
COMMENT ON COLUMN qt_app_notice.is_show IS '是否启用(0-隐藏 1-展示)';
COMMENT ON COLUMN qt_app_notice.is_top IS '是否置顶(0-否 1-是)';
COMMENT ON COLUMN qt_app_notice.dialog_closable IS '开屏弹窗是否可关闭(仅type含开屏生效)';
COMMENT ON COLUMN qt_app_notice.first_login_only IS '仅首次登录弹出(仅type含开屏生效)';
COMMENT ON COLUMN qt_app_notice.marquee IS '通告栏是否跑马灯滚动(仅type含通告栏生效)';
COMMENT ON COLUMN qt_app_notice.effective_start IS '生效时间(为空不限制)';
COMMENT ON COLUMN qt_app_notice.effective_end IS '失效时间(为空不限制)';
COMMENT ON COLUMN qt_app_notice.version_min IS '生效APP版本号下限(版本号,如300对应3.0.0)';
COMMENT ON COLUMN qt_app_notice.version_max IS '生效APP版本号上限(版本号)';
COMMENT ON COLUMN qt_app_notice.audience IS '可见人群(ALL全部/LOGGED_IN仅登录/NOT_LOGGED_IN仅游客)';
COMMENT ON COLUMN qt_app_notice.create_time IS '创建时间';
COMMENT ON COLUMN qt_app_notice.update_time IS '更新时间';
COMMENT ON COLUMN qt_app_notice_read.id IS '主键ID';
COMMENT ON COLUMN qt_app_notice_read.notice_id IS '公告ID';
COMMENT ON COLUMN qt_app_notice_read.user_id IS '用户ID';
COMMENT ON COLUMN qt_app_notice_read.read_time IS '已读时间';
COMMENT ON COLUMN qt_app_update.id IS '主键ID';
COMMENT ON COLUMN qt_app_update.version_code IS '版本号';
COMMENT ON COLUMN qt_app_update.type IS '客户端平台类型(1101-Android 1102-iOS 1103-Windows)';
COMMENT ON COLUMN qt_app_update.version_name IS '版本名称';
COMMENT ON COLUMN qt_app_update.version_info IS '版本信息';
COMMENT ON COLUMN qt_app_update.update_type IS '更新提示方式(1-弹窗 2-红点 3-无提示)';
COMMENT ON COLUMN qt_app_update.download_url IS '直链下载地址(GitHub时填原始release链接;历史&&多链接已废弃)';
COMMENT ON COLUMN qt_app_update.browser_url IS '浏览器下载地址(可空,双链接之一)';
COMMENT ON COLUMN qt_app_update.is_github IS '直链是否为GitHub链接(1-是参与加速拼接 0-否)';
COMMENT ON COLUMN qt_app_update.download_mode IS '下载方式(已废弃保留:新逻辑按双链接并存,不再按模式二选一)';
COMMENT ON COLUMN qt_app_update.is_published IS '是否发布(1-已发布App端收到更新通知 0-未发布仅本地测试不推送不校验非官方)';
COMMENT ON COLUMN qt_app_update.create_time IS '创建时间';
COMMENT ON COLUMN qt_app_update.update_time IS '更新时间';
COMMENT ON COLUMN qt_user_daka.id IS '主键ID';
COMMENT ON COLUMN qt_user_daka.uid IS '用户ID';
COMMENT ON COLUMN qt_user_daka.data IS '打卡日期';
COMMENT ON COLUMN qt_user_daka.integral IS '积分';
COMMENT ON COLUMN qt_user_daka.is_use_code IS '是否使用打卡码(0-否 1-是)';
COMMENT ON COLUMN qt_user_daka.create_time IS '创建时间';
COMMENT ON COLUMN qt_user_daka.update_time IS '更新时间';
COMMENT ON COLUMN qt_like_playlist.id IS '主键ID';
COMMENT ON COLUMN qt_like_playlist.uid IS '用户ID';
COMMENT ON COLUMN qt_like_playlist.pid IS '歌单ID';
COMMENT ON COLUMN qt_like_playlist.platform IS '平台(1-网易云 2-QQ音乐 3-酷狗 4-酷我 5-其他)';
COMMENT ON COLUMN qt_like_playlist.pic_url IS '封面图';
COMMENT ON COLUMN qt_like_playlist.name IS '歌单名称';
COMMENT ON COLUMN qt_like_playlist.is_import IS '是否导入(0-否 1-是)';
COMMENT ON COLUMN qt_like_playlist.deleted_at IS '删除时间';
COMMENT ON COLUMN qt_like_playlist.updated_seq IS '用户收藏变更序号(多端同步游标,用户维度递增)';
COMMENT ON COLUMN qt_like_playlist.updated_at IS '收藏状态变更时间';
COMMENT ON COLUMN qt_like_playlist.create_time IS '创建时间';
COMMENT ON COLUMN qt_like_playlist.update_time IS '更新时间';
COMMENT ON COLUMN qt_like_song.id IS '主键ID';
COMMENT ON COLUMN qt_like_song.uid IS '用户ID';
COMMENT ON COLUMN qt_like_song.sid IS '歌曲ID';
COMMENT ON COLUMN qt_like_song.pid IS '歌曲所属歌单ID(空串=不归属具体歌单,同一歌曲可收藏到多个歌单)';
COMMENT ON COLUMN qt_like_song.platform IS '平台(1-网易云 2-QQ音乐 3-酷狗 4-酷我 5-其他)';
COMMENT ON COLUMN qt_like_song.name IS '歌曲名';
COMMENT ON COLUMN qt_like_song.singer IS '歌手';
COMMENT ON COLUMN qt_like_song.album IS '专辑';
COMMENT ON COLUMN qt_like_song.hash IS '歌曲Hash';
COMMENT ON COLUMN qt_like_song.pic_url IS '歌曲封面图片地址(URL快照,可空)';
COMMENT ON COLUMN qt_like_song.deleted_at IS '删除时间';
COMMENT ON COLUMN qt_like_song.updated_seq IS '用户收藏变更序号(多端同步游标,用户维度递增)';
COMMENT ON COLUMN qt_like_song.updated_at IS '收藏状态变更时间';
COMMENT ON COLUMN qt_like_song.create_time IS '创建时间';
COMMENT ON COLUMN qt_like_song.update_time IS '更新时间';
COMMENT ON COLUMN qt_email_code.id IS '主键ID';
COMMENT ON COLUMN qt_email_code.email IS '邮箱';
COMMENT ON COLUMN qt_email_code.body IS '功能标识';
COMMENT ON COLUMN qt_email_code.code IS '验证码';
COMMENT ON COLUMN qt_email_code.expire_time IS '过期时间';
COMMENT ON COLUMN qt_email_code.used IS '是否使用(0-未使用 1-已使用)';
COMMENT ON COLUMN qt_email_code.create_time IS '创建时间';
COMMENT ON COLUMN qt_email_code.update_time IS '更新时间';
COMMENT ON COLUMN qt_github_accel.id IS '主键ID';
COMMENT ON COLUMN qt_github_accel.name IS '节点名称(如ghfast)';
COMMENT ON COLUMN qt_github_accel.prefix_url IS '加速前缀(最终地址=前缀+原始链接直接拼接)';
COMMENT ON COLUMN qt_github_accel.is_show IS '是否启用(1-启用参与App端探测 0-停用)';
COMMENT ON COLUMN qt_github_accel.sort IS '排序(探测顺序,小在前)';
COMMENT ON COLUMN qt_github_accel.remark IS '备注';
COMMENT ON COLUMN qt_github_accel.create_time IS '创建时间';
COMMENT ON COLUMN qt_github_accel.update_time IS '更新时间';
