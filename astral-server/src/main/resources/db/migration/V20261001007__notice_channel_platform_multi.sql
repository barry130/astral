-- ============================================
-- 通知渠道（sys_notice.channel）值域对齐统计平台，并支持多选
--
-- 背景：改造前 channel 是一套「端」值 app | pc | web | all，与统计平台
-- stat_platform（app-android | app-ios | app-windows | web）口径不同粒度也不同：
-- app 其实是 Android + iOS 的合并端，pc 只是 app-windows 的另一个名字。
-- 运营想在管理端「只投 iOS、不投 Android」这种诉求无法表达。
--
-- 现约定：channel 存逗号分隔的平台集合，平台取值与 stat_platform、X-App-Ut 同源，
-- 另加一个本表独有的 all = 不限平台（**不写进 stat_platform 字典**，否则它会在
-- 统计筛选里变成"真实平台"）。示例：'app-android,app-ios'、'app-windows'、'all'。
--
-- 老客户端兼容：已发布的 App/PC 客户端把 channel=pc 这类旧值写死在二进制里，
-- 服务端继续认识它们（映射见 NoticeChannel / 下面的存量数据迁移）。
--   旧 app → app-android,app-ios（缺省值，qt-uniappx 一份包两端）
--   旧 pc  → app-windows
--   旧 web → web（新老同值，无需迁移）
--   旧 all → all（不限平台）
--
-- 列宽：最长合法值 'app-android,app-ios,app-windows,web' = 35，取 64 留余量。
-- ============================================

-- 1) 列宽 16 → 64
ALTER TABLE sys_notice ALTER COLUMN channel TYPE VARCHAR(64);

-- 2) 存量数据迁移（幂等：已是新值的行不会被这些条件命中）
UPDATE sys_notice SET channel = 'app-android,app-ios' WHERE channel = 'app';
UPDATE sys_notice SET channel = 'app-windows'         WHERE channel = 'pc';
-- 空渠道按「老数据未填」处理，落到与旧 DEFAULT 'app' 等价的集合
UPDATE sys_notice SET channel = 'app-android,app-ios' WHERE channel IS NULL OR channel = '';

-- 3) 默认值随值域调整（新通知缺省仍等价于旧的 app）
ALTER TABLE sys_notice ALTER COLUMN channel SET DEFAULT 'app-android,app-ios';

-- 4) 字典 notice_channel 重写为平台值 + all
--    平台部分刻意与 stat_platform 保持同值；这里保留一份是本表独有的（多了 all），
--    同时给「字典加载失败/老管理端」留一个可用的回退源。新增平台时两处都要改。
UPDATE sys_dict_type SET description = '通知投放平台（平台取值与 stat_platform 同源；all=不限平台）'
WHERE dict_code = 'notice_channel';

-- 127 原 app  → Android
UPDATE sys_dict_data SET dict_label = 'Android', dict_value = 'app-android', dict_sort = 1
WHERE id = 127 AND dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'notice_channel');
-- 137 原 pc   → Windows
UPDATE sys_dict_data SET dict_label = 'Windows', dict_value = 'app-windows', dict_sort = 3
WHERE id = 137 AND dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'notice_channel');
-- 128 原 web  → Web（值不变，仅统一排序与文案）
UPDATE sys_dict_data SET dict_label = 'Web', dict_value = 'web', dict_sort = 4
WHERE id = 128 AND dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'notice_channel');
-- 129 原 all  → 全部平台
UPDATE sys_dict_data SET dict_label = '全部平台', dict_value = 'all', dict_sort = 5
WHERE id = 129 AND dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'notice_channel');
-- 新增 iOS
--   ⚠️ 这里绝不能写死 id。sys_dict_data.id 是全局 PK 且**没有序列**（必须显式给值），
--   原先写死 9123 并注释「当前最大已用 id 为 9122」——但版本号更小的权限重构迁移
--   （V202609300xx / V202610010xx）已经把 id 用到 9130，其中 9123 被 permission_type 占用，
--   于是这条 INSERT 必然 key 冲突、整个迁移失败，且因为撞的是更早版本的行，
--   在任何已应用权限迁移的库上（新旧库都是）都无法成功。
--   改为取当前最大 id + 1，并用 NOT EXISTS 保证重复执行不会插出第二行。
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) + 1 FROM sys_dict_data),
       t.id, 'iOS', 'app-ios', 2, 1, 'iOS App'
FROM sys_dict_type t
WHERE t.dict_code = 'notice_channel'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_data d
      WHERE d.dict_type_id = t.id AND d.dict_value = 'app-ios'
  );
