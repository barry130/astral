-- ============================================================================
-- 通知表建表权收归宿主：sys_notice
--
-- 【收权原因】
--   本表原先由 feedback 插件的 FeedbackSchemaInitializer（@PostConstruct 执行
--   astral-plugin/src/main/resources/sql/feedback-schema.sql 的 3.3 段）建表。
--   后果是「禁用反馈插件 = 全平台站内通知能力消失」：通知的查询/发布/事件投递
--   早已是宿主能力（astral-system 的 com.astral.system.notice / notify 包、
--   NotifyPublisher + sys_notify_rule 订阅规则），只有建表权还挂在插件上。
--   本迁移把建表权搬到宿主 Flyway，插件 SQL 不再碰这张表。
--
-- 【存量库 = no-op】
--   表与 6 个索引在存量库上全部已存在，本文件每一句都是 IF NOT EXISTS /
--   守卫判断，重复执行不改变任何既有结构、不触碰任何业务数据（308 行通知一条不动）。
--
-- 【DDL 来源】
--   逐字取自被删除的 feedback-schema.sql 3.3 段：列顺序、类型、NOT NULL、默认值、
--   6 个索引名与索引列完全相同。唯一改动是 notice_type 的注释文本
--   （原「announce公告|feedback反馈|request需求」→「announce公告|notice通知（历史别名…）」），
--   与 V20261027002 的字典口径保持一致；注释只影响 COMMENT，不影响任何数据与约束。
--   列语义以 astral-schema/src/main/resources/schema/sys_notice.json 为准。
--
-- 【已知遗留（本需求不处理）】
--   V20261001007__notice_channel_platform_multi.sql 与 V20261002006__unify_notice.sql
--   里有裸 `ALTER TABLE sys_notice ...`（无 to_regclass 守卫），所以「全新空库从头跑
--   迁移链」仍会在那两条失败（它们早于本迁移）。本条只负责把「谁建表」从插件改成宿主；
--   全新库一键起需要另行处理那两条历史迁移（不改历史文件的前提下无法在本条内解决）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sys_notice (
    id               BIGINT PRIMARY KEY,                   -- 序列 sys_notice_id
    -- 新增维度
    channel          VARCHAR(96) NOT NULL DEFAULT 'app-android,app-ios', -- 逗号分隔平台集合：app-android|app-ios|app-windows|app-linux|app-macos|app-harmony|web，或 all=不限平台（全选拼接 67 字符，留余量取 96）
    notice_type      VARCHAR(16) NOT NULL DEFAULT 'announce', -- announce公告|notice通知（历史别名 feedback反馈|request需求，读侧归并为通知）
    scene            VARCHAR(64),                          -- 站内信业务场景（站内信模板/事件注册表 scene）；广播公告为空
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
    read_seconds     BIGINT,                               -- 开屏弹窗读秒（仅首次启动免责声明用；NULL/0=不读秒）
    terms_version    VARCHAR(32),                          -- 条款版本号（仅首次启动免责声明用；版本变化则重新弹一次）
    effective_start  TIMESTAMP,
    effective_end    TIMESTAMP,
    version_min      BIGINT,                               -- 版本码（如 300）
    version_max      BIGINT,
    audience         VARCHAR(16) NOT NULL DEFAULT 'ALL',
    read_time        TIMESTAMP,                            -- 站内信已读时间（NULL=未读）；广播公告已读仍由客户端缓存判定
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_notice_channel ON sys_notice(channel);
CREATE INDEX IF NOT EXISTS idx_notice_type ON sys_notice(notice_type);
CREATE INDEX IF NOT EXISTS idx_notice_user ON sys_notice(user_id);
CREATE INDEX IF NOT EXISTS idx_notice_show ON sys_notice(is_show, is_top);
CREATE INDEX IF NOT EXISTS idx_notice_create_time ON sys_notice(create_time);
CREATE INDEX IF NOT EXISTS idx_sys_notice_user_read ON sys_notice(user_id, read_time);

-- ----------------------------------------------------------------------------
-- 列注释（与 3.3 段 COMMENT ON COLUMN 逐条对应）
-- 用 DO 块 + to_regclass 守卫：给不存在的表 COMMENT 会直接报错中断 Flyway，
-- 而这条迁移必须在「表已存在的存量库」和「表由本迁移刚建出的全新库」上都安全。
-- COMMENT 是 utility 语句，plpgsql 里不能静态写，故用 EXECUTE + format(%I, %L)。
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF to_regclass('sys_notice') IS NULL THEN
        RETURN;
    END IF;
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'id', '主键ID');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'channel', '投放平台(逗号分隔:app-android/app-ios/app-windows/app-linux/app-macos/app-harmony/web,或all=不限平台)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'notice_type', '类型(announce公告/notice通知;历史别名feedback/request归并为通知)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'scene', '站内信业务场景(站内信模板/事件注册表scene,公告为空)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'user_id', '点对点目标用户ID(NULL=广播)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'feedback_id', '关联反馈ID');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'display', '展示位掩码(1开屏/2通告栏/4消息中心,可叠加)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'title', '标题');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'content', '内容');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'url', '点击跳转链接');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'is_show', '是否启用(0-隐藏 1-展示)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'is_top', '是否置顶(0-否 1-是)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'dialog_closable', '开屏弹窗是否可关闭');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'first_login_only', '仅首次登录弹出');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'marquee', '通告栏是否跑马灯');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'read_seconds', '开屏弹窗读秒(仅首次启动免责声明用;NULL或0=不读秒,确认键立即可点)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'terms_version', '条款版本号(仅首次启动免责声明用;客户端记住已接受的版本,版本变化则重新弹一次)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'effective_start', '生效时间(空不限)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'effective_end', '失效时间(空不限)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'version_min', '生效版本码下限(如300)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'version_max', '生效版本码上限');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'audience', '可见人群(ALL/LOGGED_IN/NOT_LOGGED_IN)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'read_time', '站内信已读时间(NULL=未读;广播公告不用,App 端已读在前端缓存)');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'create_time', '创建时间');
    EXECUTE format('COMMENT ON COLUMN sys_notice.%I IS %L', 'update_time', '更新时间');
END $$;
