-- 文件夹级上传策略（UPDATE_DESIGN.md §3.3）
-- 1) sys_storage_folder 增加策略 JSON 列（NULL = 未配置，行为与升级前完全一致）；
-- 2) sys_storage_file 增加登记来源 IP（浏览器回执路径落库，用于匿名预留计数与审计）；
-- 3) 登记 storage_verify_content 字典（verifyContent 进入管理端下拉，AGENTS.md 约束 3）。

ALTER TABLE sys_storage_folder
    ADD COLUMN IF NOT EXISTS upload_policy TEXT;
COMMENT ON COLUMN sys_storage_folder.upload_policy IS '上传策略JSON(requireLogin/maxSizeBytes/minSizeBytes/allowedMimes/allowedExtensions/dailyUploadLimit/forceVisibility/verifyContent/maxPixels)，空=沿用插件全局默认';

ALTER TABLE sys_storage_file
    ADD COLUMN IF NOT EXISTS uploader_ip VARCHAR(64);
COMMENT ON COLUMN sys_storage_file.uploader_ip IS '登记来源IP(浏览器回执路径填充；worker回调路径为worker地址，不落)';

-- 字典：登记后内容验证级别（id 段沿用 V20260914003 约定：类型 907，数据 9029~9031，均 < 1,000,000）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (907, 'storage_verify_content', '存储内容验证级别', 'String', 'VARCHAR', 16, '文件夹上传策略 verifyContent 字段的可选值', 1)
ON CONFLICT DO NOTHING;

INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status) VALUES
    (9029, 907, '不验证', 'none', 1, 1),
    (9030, 907, '文件头校验(magic)', 'magic', 2, 1),
    (9031, 907, '完整解码校验(full)', 'full', 3, 1)
ON CONFLICT DO NOTHING;
