-- 内容管理（CMS）插件数据字典：所有枚举取值由字典统一提供，前端下拉不得硬编码。
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('cms_content_status', 'CMS 内容状态', 'draft 草稿 / published 已发布 / hidden 隐藏'),
           ('cms_comment_status', 'CMS 评论状态', 'pending 待审核 / approved 已通过 / rejected 已拒绝'),
           ('cms_comment_target_type', 'CMS 评论目标类型', 'post 文章 / page 页面')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

WITH seed(dict_code, dict_value, dict_label, dict_sort) AS (
    VALUES ('cms_content_status', 'draft', '草稿', 1),
           ('cms_content_status', 'published', '已发布', 2),
           ('cms_content_status', 'hidden', '隐藏', 3),

           ('cms_comment_status', 'pending', '待审核', 1),
           ('cms_comment_status', 'approved', '已通过', 2),
           ('cms_comment_status', 'rejected', '已拒绝', 3),

           ('cms_comment_target_type', 'post', '文章', 1),
           ('cms_comment_target_type', 'page', '页面', 2)
), missing AS (
    SELECT t.id AS type_id, s.* FROM seed s JOIN sys_dict_type t ON t.dict_code = s.dict_code
    WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id = t.id AND d.dict_value = s.dict_value)
)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + ROW_NUMBER() OVER (ORDER BY dict_code, dict_sort),
       type_id, dict_label, dict_value, dict_sort, 1 FROM missing;

UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_type_id';
UPDATE sequence_segment
SET max_value = GREATEST(max_value, (SELECT COALESCE(MAX(id), 0) FROM sys_dict_data) + step), update_time = CURRENT_TIMESTAMP
WHERE biz_key = 'sys_dict_data_id';
