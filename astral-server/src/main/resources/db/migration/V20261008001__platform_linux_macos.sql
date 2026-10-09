-- ============================================================
-- 平台字典新增 Linux(1104 / app-linux) 与 macOS(1105 / app-macos)
--
-- 背景：qt-pc 从 Windows 单平台扩展为 Windows / Linux / macOS 三平台
-- （Tauri 桌面端，同一份代码按 target_os 分平台打包）。客户端侧对应两处取值：
--   CLIENT_UT    原 app-windows  → 按平台 app-linux / app-macos
--   UPDATE_TYPE  原 1103         → 按平台 1104 / 1105
-- 服务端代码侧已同步放开（QtAppUpdate.TYPE_LINUX/TYPE_MACOS 与 SUPPORTED_TYPES、
-- QtSourceRelease.PLATFORM_LINUX/PLATFORM_MACOS、ClientHeaders.UT_LINUX/UT_MACOS、
-- NoticeChannel.PLATFORMS、NoticeConstants.CHANNEL_LINUX/CHANNEL_MACOS），
-- 本脚本补齐它们依赖的三处字典，否则：
--   1. stat_platform 缺两值 → 管理端统计筛选与「按平台」下拉看不到 Linux/macOS 上报；
--   2. qt_update_platform 缺 1104/1105 → 后台上传 Linux/macOS 安装包时平台选不出来
--      （接口校验已放行，字典无值等于功能不可用）；
--   3. notice_channel 缺两值 → 站内通知定向桌面平台时管理端选不出这两个平台
--      （NoticeChannel 白名单已放行，缺字典只影响可选项）。
--
-- 排序：三处统一为 android1 / ios2 / windows3 / linux4 / macos5，web 与 all 顺延，
-- 与 NoticeChannel.PLATFORMS 及前端兜底文案表（feedback.ts / statistics.ts）顺序一致。
--
-- 取号：sys_dict_data.id 是全局 PK 且**没有序列**，一律动态取号
-- （MAX(id) + ROW_NUMBER()），并以「dict_type_id + dict_value」为业务键做
-- NOT EXISTS 守卫，重复执行不会插出第二行。教训见：
--   20261001007 —— 写死 id 9123 撞 permission_type，整个迁移必然失败；
--   20260930006 —— 硬编码 id 已占用时 ON CONFLICT DO NOTHING 静默跳过，
--                  数据行全部落空而 Flyway 仍记 success。
--
-- 不改历史脚本：20261001007 里「最长合法值 'app-android,app-ios,app-windows,web' = 35」
-- 是当时口径（且改动会导致 Flyway 校验和失配）。新增两值后最长合法值为
-- 'app-android,app-ios,app-windows,app-linux,app-macos,web' = 55，仍小于列宽 64。
-- ============================================================

-- 1) stat_platform：新增 app-linux(sort4) / app-macos(sort5)，web 顺延到 sort6
UPDATE sys_dict_data SET dict_sort = 6
WHERE dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'stat_platform')
  AND dict_value = 'web'
  AND dict_sort <> 6;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY v.d_sort),
       t.id, v.d_label, v.d_value, v.d_sort, 1, v.d_desc
FROM sys_dict_type t
JOIN (
    SELECT 'Linux' AS d_label, 'app-linux' AS d_value, 4 AS d_sort, 'Linux 桌面端' AS d_desc
    UNION ALL
    SELECT 'macOS', 'app-macos', 5, 'macOS 桌面端'
) v ON 1 = 1
WHERE t.dict_code = 'stat_platform'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_data d
      WHERE d.dict_type_id = t.id AND d.dict_value = v.d_value
  );

-- 2) qt_update_platform：新增 1104 Linux(sort4) / 1105 macOS(sort5)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY v.d_sort),
       t.id, v.d_label, v.d_value, v.d_sort, 1, v.d_desc
FROM sys_dict_type t
JOIN (
    SELECT 'Linux' AS d_label, '1104' AS d_value, 4 AS d_sort, 'Linux 桌面端' AS d_desc
    UNION ALL
    SELECT 'macOS', '1105', 5, 'macOS 桌面端'
) v ON 1 = 1
WHERE t.dict_code = 'qt_update_platform'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_data d
      WHERE d.dict_type_id = t.id AND d.dict_value = v.d_value
  );

-- 3) notice_channel：新增 app-linux(sort4) / app-macos(sort5)，web → 6、all → 7
--    （all = 不限平台，始终保持最后；web 排在各客户端平台之后）
UPDATE sys_dict_data SET dict_sort = 6
WHERE dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'notice_channel')
  AND dict_value = 'web'
  AND dict_sort <> 6;

UPDATE sys_dict_data SET dict_sort = 7
WHERE dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'notice_channel')
  AND dict_value = 'all'
  AND dict_sort <> 7;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY v.d_sort),
       t.id, v.d_label, v.d_value, v.d_sort, 1, v.d_desc
FROM sys_dict_type t
JOIN (
    SELECT 'Linux' AS d_label, 'app-linux' AS d_value, 4 AS d_sort, 'Linux 桌面端' AS d_desc
    UNION ALL
    SELECT 'macOS', 'app-macos', 5, 'macOS 桌面端'
) v ON 1 = 1
WHERE t.dict_code = 'notice_channel'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_data d
      WHERE d.dict_type_id = t.id AND d.dict_value = v.d_value
  );