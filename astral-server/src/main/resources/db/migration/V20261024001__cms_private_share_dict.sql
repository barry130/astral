-- 内容管理（CMS）插件：新增「私密」内容状态 + 分享目标类型字典。
-- 与其他字典一样，枚举取值由字典统一提供，前端下拉不得硬编码。
-- 幂等：dict_type 按 dict_code 守卫，dict_data 按 (dict_type_id, dict_value) 守卫。

WITH seed(dict_code, dict_name, description) AS (
    VALUES ('cms_share_target_type', 'CMS 分享目标类型', 'post 文章 / page 页面')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

-- 已存在的 cms_content_status 更新描述（补上 private 说明）
UPDATE sys_dict_type
SET description = 'draft 草稿 / published 已发布 / private 私密 / hidden 隐藏'
WHERE dict_code = 'cms_content_status';

WITH seed(dict_code, dict_value, dict_label, dict_sort) AS (
    VALUES ('cms_content_status', 'private', '私密', 3),
           ('cms_content_status', 'hidden', '隐藏', 4),

           ('cms_share_target_type', 'post', '文章', 1),
           ('cms_share_target_type', 'page', '页面', 2)
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = s.dict_code
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_code, dict_sort),
       type_id, dict_label, dict_value, dict_sort, 1 FROM missing;

-- hidden 原本排第 3，现在 private 插入到它前面，统一按语义顺序重排一次（幂等）
UPDATE sys_dict_data d
SET dict_sort = s.dict_sort
FROM (VALUES ('private', 3), ('hidden', 4)) AS s(dict_value, dict_sort)
WHERE d.dict_value = s.dict_value
  AND d.dict_type_id = (SELECT id FROM sys_dict_type WHERE dict_code = 'cms_content_status')
  AND d.dict_sort <> s.dict_sort;

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
