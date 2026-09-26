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

-- ============================================================================
-- 音源包（source bundle）热更新 —— 2026-09-18
-- 一个音源包同时服务多个平台（platforms）；版本号/版本名由后端按规则生成，
-- 客户端与发布脚本一律不上送（见 QtSourceService#nextVersionCode）。
-- app_version_codes / artifacts 用 TEXT 存 JSON 而不是 jsonb：两列都不参与 SQL 过滤，
-- 用 TEXT 可避开 PG jsonb 与 JDBC/MyBatis 的类型处理坑，序列化统一在服务层用 Jackson 做。
-- ============================================================================

CREATE TABLE IF NOT EXISTS qt_source_release (
    id                  BIGINT PRIMARY KEY,
    source_version_code BIGINT NOT NULL,
    source_version_name VARCHAR(64),
    platforms           VARCHAR(64) NOT NULL,
    host_api_version    BIGINT NOT NULL DEFAULT 1,
    app_version_codes   TEXT NOT NULL DEFAULT '',
    channel             VARCHAR(16) NOT NULL DEFAULT 'stable',
    notes               VARCHAR(1024),
    artifacts           TEXT NOT NULL DEFAULT '',
    rollback_to         BIGINT,
    is_bad              SMALLINT NOT NULL DEFAULT 0,
    is_published        SMALLINT NOT NULL DEFAULT 0,
    published_at        TIMESTAMP,
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 已建库的幂等补列（新建表时上面已含该列）
ALTER TABLE qt_source_release ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;

-- 唯一键用 (版本号, 渠道)：一个包覆盖多平台，platform 不能再参与唯一性
CREATE UNIQUE INDEX IF NOT EXISTS uk_qt_source_release_code
    ON qt_source_release (source_version_code, channel);

CREATE INDEX IF NOT EXISTS idx_qt_source_release_lookup
    ON qt_source_release (channel, is_published, source_version_code DESC);

CREATE TABLE IF NOT EXISTS qt_source_report (
    id                  BIGINT PRIMARY KEY,
    platform            BIGINT NOT NULL DEFAULT 0,
    app_version_code    BIGINT NOT NULL DEFAULT 0,
    source_version_code BIGINT NOT NULL DEFAULT 0,
    result              VARCHAR(32) NOT NULL DEFAULT '',
    detail              VARCHAR(1024),
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_qt_source_report_lookup
    ON qt_source_report (source_version_code, result);

COMMENT ON TABLE qt_source_release IS '音源包发布记录(一个包可服务多个平台,版本号由后端生成)';
COMMENT ON COLUMN qt_source_release.id IS '主键ID';
COMMENT ON COLUMN qt_source_release.source_version_code IS '音源包版本号(yyyyMMddNN,后端生成,更新判定的唯一依据)';
COMMENT ON COLUMN qt_source_release.source_version_name IS '音源包版本名(yyyy.MM.dd.N,后端由版本号派生,仅展示)';
COMMENT ON COLUMN qt_source_release.platforms IS '适用平台(逗号分隔,1101安卓/1102iOS/1103Windows)';
COMMENT ON COLUMN qt_source_release.host_api_version IS '需要的宿主契约版本(高于客户端支持上限则不加载)';
COMMENT ON COLUMN qt_source_release.app_version_codes IS '按平台准入的应用版本号(JSON,如{"1103":[102],"1101":[304]},平台缺省或空数组=不限制)';
COMMENT ON COLUMN qt_source_release.channel IS '发布渠道(stable正式,所有用户可收到/beta测试,仅qt_admin或qt_tester权限用户可收到,正式版版本号更高时所有用户收到正式版)';
COMMENT ON COLUMN qt_source_release.notes IS '更新说明(客户端设置页展示)';
COMMENT ON COLUMN qt_source_release.artifacts IS '产物清单(JSON数组,当前生效全集:[{path,version,url}])';
COMMENT ON COLUMN qt_source_release.rollback_to IS '指定回退到的版本号(可空)';
COMMENT ON COLUMN qt_source_release.is_bad IS '是否标记坏包(1-是,客户端回退并拉黑该版本)';
COMMENT ON COLUMN qt_source_release.is_published IS '是否发布(1-已发布,客户端可拉到)';
COMMENT ON COLUMN qt_source_release.published_at IS '发布时间(未发布为空,manifest 里作为 publishedAt 返回)';
COMMENT ON COLUMN qt_source_release.create_time IS '创建时间';
COMMENT ON COLUMN qt_source_release.update_time IS '更新时间';
COMMENT ON TABLE qt_source_report IS '音源包装载结果上报(装机分布统计与坏包发现)';
COMMENT ON COLUMN qt_source_report.id IS '主键ID';
COMMENT ON COLUMN qt_source_report.platform IS '客户端平台(1101/1102/1103)';
COMMENT ON COLUMN qt_source_report.app_version_code IS '客户端应用版本号';
COMMENT ON COLUMN qt_source_report.source_version_code IS '装载的音源包版本号';
COMMENT ON COLUMN qt_source_report.result IS '结果(ok成功/smoke_failed冒烟自检失败)';
COMMENT ON COLUMN qt_source_report.detail IS '失败详情(可空)';
COMMENT ON COLUMN qt_source_report.create_time IS '创建时间';
COMMENT ON COLUMN qt_source_report.update_time IS '更新时间';
