-- ============================================================
-- 迁移版本：V20261006001
-- 说明：音源包已拆成双包（meta-bundle.js / play-bundle.js），把这两个产物路径
--       补进数据字典 qt_source_artifact_path，管理端「音源包」页 artifacts 编辑行
--       的 path 下拉才能选到它们（下拉完全由该字典驱动，前端硬编码回退也已同步）。
--       旧两项 chain.json / source-bundle.js 保留但停用（status=0）：历史 release
--       的 artifacts 里仍存着这两个 path，停用只是不让它出现在新建/编辑的下拉里，
--       避免误选已废弃的单包产物；已有记录不受影响（字典只影响 UI 可选项）。
-- 幂等：数据行无业务唯一约束，用 WHERE NOT EXISTS 守卫，可重复执行。
-- id 段约定：沿用 V20260914005 的递增约定——数据项 9102~9103（预留段
--       [1, 1,000,000] 内，与运行时发号 1,000,001 起不冲突）。
-- ============================================================

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9102, id, 'meta-bundle.js', 'meta-bundle.js', 3, 1, '数据包：搜索/歌单/榜单/歌词/封面等在线数据面（在线安装，不随应用分发）'
FROM sys_dict_type WHERE dict_code = 'qt_source_artifact_path'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'meta-bundle.js');

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9103, id, 'play-bundle.js', 'play-bundle.js', 4, 1, '播放包：取链面（在线安装，不随应用分发，内嵌同款默认链）'
FROM sys_dict_type WHERE dict_code = 'qt_source_artifact_path'
AND NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = sys_dict_type.id AND d.dict_value = 'play-bundle.js');

-- 停用单包时代的两个产物（chain.json 已不再是发布物：播放包内嵌默认链，
-- 宿主安装播放包时还会主动清掉残留 chain.json）
UPDATE sys_dict_data SET status = 0
WHERE dict_value IN ('chain.json', 'source-bundle.js')
  AND dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'qt_source_artifact_path');
