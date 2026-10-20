-- ============================================
-- 首次启动免责声明 / 开源通知改为后端下发：sys_notice 增两列
--
-- 背景：qt-pc 的「安装后首次启动弹一次」声明原本是把三段正文硬编码在
-- FirstRunDisclaimer.tsx 里，改一句话就要发版。改为复用已有公告系统：
-- 后台「消息管理」发一条开屏公告（display 含 1），客户端拉
-- /api/v1/app/message/active 渲染即可。
--
-- 复用公告表还差两个能力，各加一列：
--   read_seconds   读秒时长。「我知道了」要读满 N 秒才可点，防手滑点掉；
--                  原来写死 3 秒。NULL/0 = 不读秒（确认键立即可点）。
--   terms_version  条款版本号。客户端记住「已接受的版本」，值变了就重新弹一次
--                  —— 光有 accepted_at 时间戳做不到「改条款后重新确认」。
-- 两列只对首次启动免责声明有意义，其它公告不用，故均可空无默认值。
--
-- ⚠️ 必须用 to_regclass 守卫，不能裸 ALTER：
--   sys_notice 由 feedback 插件的 @PostConstruct 初始化器建表（见
--   astral-plugin/src/main/resources/sql/feedback-schema.sql），**晚于 Flyway 执行**。
--   在全新空库上裸 ALTER 会报 `relation "sys_notice" does not exist`，让迁移失败并
--   阻断启动；而 ADD COLUMN IF NOT EXISTS 只对「列」幂等，对「表不存在」不管。
--   表缺失时跳过是安全的：feedback-schema.sql 的 CREATE TABLE 已是带上两列的新结构。
-- ============================================

DO $$
BEGIN
    IF to_regclass('sys_notice') IS NOT NULL THEN
        ALTER TABLE sys_notice ADD COLUMN IF NOT EXISTS read_seconds BIGINT;
        ALTER TABLE sys_notice ADD COLUMN IF NOT EXISTS terms_version VARCHAR(32);

        COMMENT ON COLUMN sys_notice.read_seconds IS '开屏弹窗读秒(仅首次启动免责声明用;NULL或0=不读秒,确认键立即可点)';
        COMMENT ON COLUMN sys_notice.terms_version IS '条款版本号(仅首次启动免责声明用;客户端记住已接受的版本,版本变化则重新弹一次)';
    ELSE
        RAISE NOTICE '[migration] sys_notice 尚未创建（feedback 插件建表），read_seconds/terms_version 由 feedback-schema.sql 兜底';
    END IF;
END $$;
