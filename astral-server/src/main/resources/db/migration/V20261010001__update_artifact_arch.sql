-- ============================================================
-- 版本更新「一版本多产物」：新增 CPU 架构字典 qt_update_arch
--
-- 背景：qt_app_update 原先是「一个平台 + 一个版本号 = 一个下载地址」，
-- 而 qt-pc 的 Windows 包实际要出三份（x64 / x86 / arm64），macOS 也要
-- x86_64 与 aarch64 两份。同一 versionCode 下三个包只能存一条 download_url，
-- 结果是 x86 / arm64 用户拿到 x64 的包，MD5 与大小校验直接失败；
-- is_force=1 时更是卡在强制更新弹窗里出不来。
--
-- 改造（UPDATE_ARTIFACT_DESIGN）：下载相关的字段下沉到子表
-- qt_app_update_artifact(update_id, platform, arch, download_url, browser_url,
-- is_github, file_size, md5, sort)，一个版本可挂多份产物，
-- App 端按自身 platform + arch 命中对应行；主表同名字段保留为
-- 「旧客户端 / 未上送 arch」的兜底值。子表由 QtSchemaInitializer 幂等建表
-- （qt-schema.sql），本脚本只补它依赖的字典。
--
-- 为什么字典只放三个值：架构是可选项，留空 = 不限（移动端 APK / IPA 不区分，
-- 桌面端单架构包也留空）。列出的是「后台曾经或可能发布的架构」，
-- 未知值由前端按 dict_code 拉不到时回退展示原始字符串，不影响下发。
--
-- 取号：sys_dict_data.id 没有序列，一律动态取号（MAX(id) + ROW_NUMBER()），
-- 并以「dict_type_id + dict_value」为业务键做 NOT EXISTS 守卫，重复执行不会
-- 插出第二行。写死 id 的两个教训见 V20261001007（撞主键必然失败）与
-- V20260930006（ON CONFLICT DO NOTHING 静默跳过、数据全丢而 Flyway 记 success）。
-- ============================================================

-- 1) 字典类型：qt_update_arch（版本更新产物的 CPU 架构）
INSERT INTO sys_dict_type (id, dict_code, dict_name, value_type, db_type, max_length, description, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + 1,
       'qt_update_arch', '版本更新CPU架构', 'String', 'VARCHAR', 16,
       '版本更新产物的CPU架构(x64/x86/arm64；留空=不限架构)', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_code = 'qt_update_arch');

-- 2) 字典数据：x64 / x86 / arm64（sort 1/2/3）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY v.d_sort),
       t.id, v.d_label, v.d_value, v.d_sort, 1, v.d_desc
FROM sys_dict_type t
JOIN (
    SELECT 'x64'   AS d_label, 'x64'   AS d_value, 1 AS d_sort, '64 位 x86（Windows 主力 / macOS Intel）' AS d_desc
    UNION ALL SELECT 'x86',   'x86',   2, '32 位 x86（老机器）'
    UNION ALL SELECT 'arm64', 'arm64', 3, 'ARM 64 位（Windows on ARM / macOS Apple Silicon）'
) v ON 1 = 1
WHERE t.dict_code = 'qt_update_arch'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_data d
      WHERE d.dict_type_id = t.id AND d.dict_value = v.d_value
  );
