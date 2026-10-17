-- =====================================================================
-- V20261011001__qt_update_platforms.sql
-- 版本更新改为「一行多平台」：qt_app_update 新增 platforms（逗号分隔平台号），
-- 删除单值 type 列，数据从 type 平移到 platforms。
--
-- 背景（与公告 sys_notice.channel 同款方案）
--   旧结构一行只承载一个 type，所以「同一版本号发安卓 + 鸿蒙」在库里是两行，
--   两行各有自己的 download_url / md5 / 是否强更，改一处要改两处，容易漂移。
--   公告渠道 V20261001007 已把「多平台」收敛成一个逗号分隔列，这里对齐同一方案：
--   一行 = 一个版本，platforms = 该版本覆盖的平台集合（如 '1101,1106'）。
--
-- 列语义
--   platforms VARCHAR(64) NOT NULL DEFAULT ''，值域 = qt_update_platform 字典：
--   1101 安卓 / 1102 iOS / 1103 Windows / 1104 Linux / 1105 macOS / 1106 鸿蒙。
--   匹配**在 Java 侧精确拆分**，绝不用 SQL LIKE —— 平台号互为子串（'1101' 与 '11011'、
--   '1103' 与 '110'）会误命中，这与 QtSourceService / FeedbackNoticeService 的取舍一致。
--
-- 破坏性说明
--   ⚠️ 本脚本 DROP COLUMN type。迁移前 type 是 NOT NULL 且被 (type, version_code)
--   索引覆盖，故不能原地加宽，只能「先建新列 → 平移数据 → 删旧列」。
--   代码侧 QtAppUpdate 的 TYPE_* 常量与 isSupportedType() 同批改名为
--   PLATFORM_* / isSupportedPlatform() 并新增 platforms CSV 工具方法，
--   必须与本次迁移**同一次部署**生效（否则实体与表结构不一致，启动即报列不存在）。
--   qt-pc / qt-uniappx 客户端契约不变：它们仍按 `type=<单值>` 请求，服务端在
--   platforms 里做包含判断即可，无需客户端改动。
--
-- 时序（重要）
--   qt_* 表由插件 QtSchemaInitializer（@PostConstruct）建，**晚于 Flyway**。
--   全新空库上本脚本执行时 qt_app_update 还不存在，此时整段跳过，表由 Initializer
--   按 qt-schema.sql 的新结构（直接就是 platforms）创建；存量库则正常迁移。
--   与 V20261001008 / V20261010002 同一守卫写法。
--
-- 幂等
--   全部语句可重复执行：ADD COLUMN IF NOT EXISTS + information_schema 判列存在
--   + DROP COLUMN IF EXISTS + CREATE INDEX IF NOT EXISTS。
--
-- 与 V20261010002 的先后依赖
--   V20261010002（历史产物回填）里 `SELECT u.type` 读的是本脚本要删的列。Flyway 按版本号
--   升序执行，20261010002 必然先于本脚本，且它已被记入 flyway_schema_history 不会重跑，
--   因此**不要**手工 repair / 重放那条迁移（重放时会因 type 已不存在而报错）。
-- =====================================================================

DO $$
DECLARE
    v_has_type      BOOLEAN;
    v_migrated      INTEGER := 0;
BEGIN
    IF to_regclass('qt_app_update') IS NULL THEN
        RAISE NOTICE '[migration] qt_app_update 尚未创建（qt 插件表由 QtSchemaInitializer 建表），跳过 platforms 改造';
        RETURN;
    END IF;

    -- 判列存在：用 pg_attribute 而不是 information_schema.columns，
    -- 免得受 search_path / 库名（astral）影响
    SELECT EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = to_regclass('qt_app_update')
          AND attname = 'type'
          AND attnum > 0
          AND NOT attisdropped
    ) INTO v_has_type;

    -- ① 新增 platforms（先不设 NOT NULL，方便存量回填）
    ALTER TABLE qt_app_update ADD COLUMN IF NOT EXISTS platforms VARCHAR(64);

    -- ② 存量平移：单值 type → platforms（type 是 BIGINT，转字符串）
    IF v_has_type THEN
        EXECUTE 'UPDATE qt_app_update
                    SET platforms = type::VARCHAR
                  WHERE (platforms IS NULL OR platforms = '''')
                    AND type IS NOT NULL';
        GET DIAGNOSTICS v_migrated = ROW_COUNT;
        RAISE NOTICE '[migration] qt_app_update.type → platforms 平移 % 行', v_migrated;
    END IF;

    -- ③ 兜底：仍为空的行补历史默认平台 1101（安卓）。
    --    正常路径下不会有这种行（旧 type 是 NOT NULL），只为「曾半途执行过」的库收口，
    --    否则下面的 SET NOT NULL 会失败并阻断启动。
    UPDATE qt_app_update SET platforms = '1101' WHERE platforms IS NULL OR platforms = '';

    -- ④ 收紧约束，与 qt-schema.sql 的建表口径一致
    ALTER TABLE qt_app_update ALTER COLUMN platforms SET DEFAULT '';
    ALTER TABLE qt_app_update ALTER COLUMN platforms SET NOT NULL;

    -- ⑤ 删除旧单值列与其索引
    --    （DROP COLUMN 会连带删掉 idx_qt_update_type，这里显式再删一次纯属兜底）
    DROP INDEX IF EXISTS idx_qt_update_type;
    IF v_has_type THEN
        ALTER TABLE qt_app_update DROP COLUMN type;
        RAISE NOTICE '[migration] qt_app_update.type 已删除';
    END IF;

    -- ⑥ 新索引：类型不再参与检索，按「版本号 + 渠道」建（后台列表按 version_code 倒序）
    CREATE INDEX IF NOT EXISTS idx_qt_update_version ON qt_app_update(version_code, channel);

    -- ⑦ 列注释（qt-schema.sql 每次启动也会刷一遍，这里让存量库立即对齐）
    EXECUTE 'COMMENT ON COLUMN qt_app_update.platforms IS ''适用平台(逗号分隔,1101安卓/1102iOS/1103Windows/1104Linux/1105macOS/1106鸿蒙)''';

    RAISE NOTICE '[migration] qt_app_update platforms 改造完成';
END $$;
