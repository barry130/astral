-- ============================================================
-- 版本更新「一版本多产物」：历史单产物回填到 qt_app_update_artifact
--
-- 背景：一版本多产物（见 V20261010001）上线前，每个版本只有一个安装包，
-- 地址直接写在主表 qt_app_update 的 download_url / browser_url。
-- 现在客户端按「平台 + 架构」挑产物，主表那一份若不在产物表里：
--   ① 后台编辑该版本时看不到任何产物行——而 PUT /updates/{id}/artifacts 是
--      **全量覆盖**语义，管理员改完别处一点保存，这条历史地址就被清掉了；
--   ② 主表兜底字段虽仍在（旧客户端还能下），但后台已失去编辑它的入口。
-- 所以必须把主表单产物补成产物表的一行。
--
-- 为什么「建表 + 回填」放在同一个 Flyway 脚本里：
--   qt_* 插件表由 QtSchemaInitializer（@PostConstruct）执行 qt-schema.sql 创建，
--   而 Initializer 跑在 Flyway **之后**——Flyway 脚本里直接 INSERT 一张此刻
--   还不存在的表会失败并阻断启动。故本脚本先 CREATE TABLE IF NOT EXISTS
--   （与 qt-schema.sql:45-59 的 DDL 逐字一致），再回填；
--   Initializer 随后执行同一句 CREATE TABLE IF NOT EXISTS 只是 no-op。
--   这段 DDL 与 qt-schema.sql 是同一份定义的两个入口，改列时两处都要改。
--
-- 迁移规则：
--   · 只迁「主表有下载地址 + 产物表里还没有任何行」的版本（NOT EXISTS 守卫，
--     重复执行不会插出第二行）；
--   · platform 取主表 type（主表一行本来就只属于一个平台）；
--   · arch 留空 = 不限架构：历史单包没有架构信息，留空后 x64 / x86 / arm64
--     客户端都能命中它（匹配档位「platform 精确 + arch 不限」）；
--   · sort = 0（同档位内第一条）。
--
-- 取号：qt_app_update_artifact.id 由宿主 SequenceMetaObjectHandler 按
--   qt_app_update_artifact_id 取号（IdType=INPUT，预留段 [1,1000000]），
--   故本脚本显式写 id = 既有 MAX(id) + ROW_NUMBER()（落在预留段内），
--   并按规范把 sequence_segment.max_value 抬到 MAX(id)+1000 以上，
--   避免发号器爬上来撞主键（教训见 V20261001005）。
-- ============================================================

CREATE TABLE IF NOT EXISTS qt_app_update_artifact (
    id BIGINT PRIMARY KEY,
    update_id BIGINT NOT NULL,
    platform BIGINT,
    arch VARCHAR(16),
    download_url VARCHAR(1024),
    browser_url VARCHAR(512),
    is_github BIGINT NOT NULL DEFAULT 0,
    file_size BIGINT,
    md5 VARCHAR(64),
    sort BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qt_update_artifact ON qt_app_update_artifact(update_id, platform);

DO $$
DECLARE
    v_base     BIGINT;
    v_migrated INTEGER;
BEGIN
    -- 全新空库：qt_app_update 也是插件表（Initializer 建，晚于 Flyway），此刻不存在，
    -- 因而也没有任何历史地址可迁——表缺失时跳过，子表随后由 qt-schema.sql 兜底建。
    IF to_regclass('qt_app_update') IS NULL THEN
        RAISE NOTICE '[migration] qt_app_update 尚未创建（qt 插件表由 QtSchemaInitializer 建表），跳过历史产物回填';
        RETURN;
    END IF;

    SELECT COALESCE(MAX(id), 0) INTO v_base FROM qt_app_update_artifact;

    INSERT INTO qt_app_update_artifact
        (id, update_id, platform, arch, download_url, browser_url, is_github, file_size, md5, sort, create_time, update_time)
    SELECT
        v_base + ROW_NUMBER() OVER (ORDER BY u.id),
        u.id,
        u.type,
        NULL,
        u.download_url,
        u.browser_url,
        COALESCE(u.is_github, 0),
        u.file_size,
        u.md5,
        0,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    FROM qt_app_update u
    WHERE (COALESCE(u.download_url, '') <> '' OR COALESCE(u.browser_url, '') <> '')
      AND NOT EXISTS (SELECT 1 FROM qt_app_update_artifact a WHERE a.update_id = u.id);

    GET DIAGNOSTICS v_migrated = ROW_COUNT;
    IF v_migrated > 0 THEN
        RAISE NOTICE '[migration] 历史版本产物回填 % 条（platform=主表 type，arch 留空=不限架构）', v_migrated;
    END IF;

    -- 显式写了主键就要抬号段上限（存量库有行才有效；全新库此时无该 biz_key 行，
    -- 由 SequencePlugin 启动预置 [1,1000000] 并做高水位对齐）
    UPDATE sequence_segment
    SET max_value   = GREATEST(max_value, (SELECT MAX(id) + 1000 FROM qt_app_update_artifact)),
        update_time = CURRENT_TIMESTAMP
    WHERE biz_key = 'qt_app_update_artifact_id';
END $$;
