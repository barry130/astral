-- 文件管理筛选项由字典统一提供；不调整存储对象或既有文件标识。
WITH seed(dict_code, dict_name, description) AS (
    VALUES ('storage_file_category', '文件管理类型', '按 MIME 筛选文件'),
           ('storage_file_sort', '文件管理排序', '文件列表排序方式')
), missing AS (
    SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code = s.dict_code)
)
INSERT INTO sys_dict_type (id, dict_code, dict_name, status, description)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_dict_type) + ROW_NUMBER() OVER (ORDER BY dict_code),
       dict_code, dict_name, 1, description
FROM missing;

WITH seed(dict_code, dict_value, dict_label, dict_sort) AS (
    VALUES ('storage_file_category', 'image', '图片', 1),
           ('storage_file_category', 'document', '文档', 2),
           ('storage_file_category', 'video', '视频', 3),
           ('storage_file_category', 'audio', '音频', 4),
           ('storage_file_category', 'archive', '压缩包', 5),
           ('storage_file_sort', 'newest', '最新上传', 1),
           ('storage_file_sort', 'oldest', '最早上传', 2),
           ('storage_file_sort', 'name', '文件名 A–Z', 3),
           ('storage_file_sort', 'size', '文件大小从大到小', 4)
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
