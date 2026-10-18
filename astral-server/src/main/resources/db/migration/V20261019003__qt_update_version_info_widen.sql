-- 发版公告（version_info）加宽：VARCHAR(512) -> VARCHAR(5000)
-- 背景：发版公告已改为 HTML 富文本（qt-pc「轻听桌面版 更新公告」整页 HTML），
--   512 字符装不下，INSERT 直接报 22001 value too long for type character varying(512)。
-- 注意：qt_app_update 是 qt 插件表，由 QtSchemaInitializer 建表、晚于 Flyway，
--   全新空库执行本脚本时表还不存在（to_regclass 守卫跳过，qt-schema.sql 已同步改为
--   VARCHAR(5000)，全新库直接建出正确宽度）。

DO $$
BEGIN
    IF to_regclass('qt_app_update') IS NOT NULL THEN
        EXECUTE 'ALTER TABLE qt_app_update ALTER COLUMN version_info TYPE VARCHAR(5000)';
        RAISE NOTICE '[migration] qt_app_update.version_info 已加宽到 VARCHAR(5000)';
    ELSE
        RAISE NOTICE '[migration] qt_app_update 尚未创建（qt 插件表由 QtSchemaInitializer 建表），跳过加宽';
    END IF;
END
$$;
