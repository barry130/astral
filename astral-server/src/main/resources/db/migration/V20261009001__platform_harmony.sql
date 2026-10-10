-- ============================================================
-- 平台字典新增 HarmonyOS（1106 / app-harmony）
--
-- 背景：qt-uniappx 从「安卓一份包」扩展到鸿蒙原生包。鸿蒙不是安卓的换皮——
-- 播放器、JS 引擎、系统能力全部是自研的第二份平台实现（AVPlayer + AVSession +
-- AUDIO_PLAYBACK 长时任务），安装包、音源产物、统计口径都必须能独立区分，
-- 因此需要独立的平台号与平台标识。
--
-- 号段已满，只能取 1106：
--   1101 安卓 / 1102 iOS / 1103 Windows / 1104 Linux / 1105 macOS
-- （1104 在 V20261008001 已给 Linux，鸿蒙不能复用）
--
-- 服务端代码侧已同步放开：QtAppUpdate.TYPE_HARMONY 与 SUPPORTED_TYPES、
-- QtSourceRelease.PLATFORM_HARMONY 与 SUPPORTED_PLATFORMS、ClientHeaders.UT_HARMONY、
-- NoticeConstants.CHANNEL_HARMONY、NoticeChannel.PLATFORMS、
-- NotifyRuleController.KNOWN_PLATFORMS。本脚本补齐它们依赖的字典与列宽，否则：
--   1. stat_platform 缺 app-harmony → 管理端统计筛选与「按平台」下拉看不到鸿蒙上报
--      （ClientHeaders.normalizeUt 会把白名单外的值静默归一成空串，等于鸿蒙流量全丢）；
--   2. qt_update_platform 缺 1106 → 后台上传鸿蒙安装包时平台选不出来
--      （接口校验已放行，字典无值等于功能不可用）；
--   3. notice_channel 缺 app-harmony → 站内通知定向鸿蒙时管理端选不出该平台。
--
-- ⚠️ 列宽必须一起抬（本次迁移的第二个理由）：
--   sys_notice.channel 与 sys_notify_rule.platform 存的是逗号分隔的平台集合，
--   最长合法值 = 全选拼接。加入 app-harmony 前是 55 字符（V20261008001 注释里算过），
--   加入后变成 67 字符，而两列都是 VARCHAR(64) —— 一旦运营全选平台投放，
--   PostgreSQL 会直接报 22001（value too long for type character varying(64)）。
--   两列统一抬到 96 留余量；NoticeChannel.MAX_STORED_LENGTH 同步改为 96。
--
-- 排序：三处统一为 android1 / ios2 / windows3 / linux4 / macos5 / harmony6，
-- web 与 all 顺延，与 NoticeChannel.PLATFORMS 及前端兜底文案表
-- （feedback.ts / statistics.ts / SourceReleasesTab.tsx）顺序一致。
--
-- 取号：sys_dict_data.id 是全局 PK 且**没有序列**，一律动态取号
-- （MAX(id) + ROW_NUMBER()），并以「dict_type_id + dict_value」为业务键做
-- NOT EXISTS 守卫，重复执行不会插出第二行。教训见：
--   20261001007 —— 写死 id 9123 撞 permission_type，整个迁移必然失败；
--   20260930006 —— 硬编码 id 已占用时 ON CONFLICT DO NOTHING 静默跳过，
--                  数据行全部落空而 Flyway 仍记 success。
--
-- 不改历史脚本：V20261001007 / V20261008001 里的列宽与「最长合法值」注释是当时口径
-- （且改动会导致 Flyway 校验和失配），由本脚本向前修正。
-- ============================================================

-- 1) 列宽：逗号分隔平台集合，最长合法值 67 → 抬到 96
--
-- ⚠️ 必须先判表存在：sys_notice 不是本迁移链建的表——它由 feedback 插件的
--    FeedbackSchemaInitializer（@PostConstruct，晚于 Flyway；astral.plugins.feedback.enabled=false
--    时不注册）执行 sql/feedback-schema.sql 建表。全新空库上本迁移执行时它可能还不存在，
--    裸 ALTER 会让迁移失败、应用起不来（同款教训见 V20261001008 对 qt_user_daka 的处理）。
--    表缺失时跳过是安全的：feedback-schema.sql 里 channel 已是 VARCHAR(96)，
--    建表时直接就是目标宽度。sys_notify_rule 由 V20261002005 建表，正常一定存在，一并守卫。
DO $$
BEGIN
    IF to_regclass('sys_notice') IS NOT NULL THEN
        ALTER TABLE sys_notice ALTER COLUMN channel TYPE VARCHAR(96);
    ELSE
        RAISE NOTICE '[migration] sys_notice 尚未创建（feedback 插件建表），channel 宽度由 feedback-schema.sql 兜底';
    END IF;

    IF to_regclass('sys_notify_rule') IS NOT NULL THEN
        ALTER TABLE sys_notify_rule ALTER COLUMN platform TYPE VARCHAR(96);
    ELSE
        RAISE NOTICE '[migration] sys_notify_rule 尚未创建，platform 宽度由 V20261002005 兜底';
    END IF;
END $$;

-- 2) stat_platform：新增 app-harmony(sort6)，web 顺延到 sort7
UPDATE sys_dict_data SET dict_sort = 7
WHERE dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'stat_platform')
  AND dict_value = 'web'
  AND dict_sort <> 7;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY v.d_sort),
       t.id, v.d_label, v.d_value, v.d_sort, 1, v.d_desc
FROM sys_dict_type t
JOIN (
    SELECT 'HarmonyOS' AS d_label, 'app-harmony' AS d_value, 6 AS d_sort, 'HarmonyOS 原生包' AS d_desc
) v ON 1 = 1
WHERE t.dict_code = 'stat_platform'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_data d
      WHERE d.dict_type_id = t.id AND d.dict_value = v.d_value
  );

-- 3) qt_update_platform：新增 1106 HarmonyOS(sort6)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY v.d_sort),
       t.id, v.d_label, v.d_value, v.d_sort, 1, v.d_desc
FROM sys_dict_type t
JOIN (
    SELECT 'HarmonyOS' AS d_label, '1106' AS d_value, 6 AS d_sort, 'HarmonyOS 原生包' AS d_desc
) v ON 1 = 1
WHERE t.dict_code = 'qt_update_platform'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_data d
      WHERE d.dict_type_id = t.id AND d.dict_value = v.d_value
  );

-- 4) notice_channel：新增 app-harmony(sort6)，web → 7、all → 8
--    （all = 不限平台，始终保持最后；web 排在各客户端平台之后）
UPDATE sys_dict_data SET dict_sort = 7
WHERE dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'notice_channel')
  AND dict_value = 'web'
  AND dict_sort <> 7;

UPDATE sys_dict_data SET dict_sort = 8
WHERE dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'notice_channel')
  AND dict_value = 'all'
  AND dict_sort <> 8;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY v.d_sort),
       t.id, v.d_label, v.d_value, v.d_sort, 1, v.d_desc
FROM sys_dict_type t
JOIN (
    SELECT 'HarmonyOS' AS d_label, 'app-harmony' AS d_value, 6 AS d_sort, 'HarmonyOS 原生包' AS d_desc
) v ON 1 = 1
WHERE t.dict_code = 'notice_channel'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_data d
      WHERE d.dict_type_id = t.id AND d.dict_value = v.d_value
  );
