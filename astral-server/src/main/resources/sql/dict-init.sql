-- ============================================
-- 数据字典初始化脚本（字段维度 - 枚举值）
-- ============================================

-- 清空现有数据
DELETE FROM sys_dict_data;
DELETE FROM sys_dict_type;

-- 字典类型: id
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (1, 'id', '主键ID', 'Long', 'BIGINT', 0, '主键ID', 1);

-- 字典类型: bizKey
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (2, 'bizKey', '业务键', 'String', 'VARCHAR', 64, '业务键', 1);

-- 字典类型: sequenceType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (3, 'sequenceType', '序列类型', 'String', 'VARCHAR', 32, '序列类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 1, id, '雪花算法', 'SNOWFLAKE', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 2, id, '号段模式', 'SEGMENT', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 3, id, 'Redis模式', 'REDIS', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 4, id, '数据库模式', 'DATABASE', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 5, id, '简单模式', 'SIMPLE', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';

-- 字典类型: step
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (4, 'step', '步长', 'Integer', 'INT', 0, '步长', 1);

-- 字典类型: dateFormat
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (5, 'dateFormat', '日期格式', 'String', 'VARCHAR', 32, '日期格式', 1);

-- 字典类型: prefix
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (6, 'prefix', '前缀', 'String', 'VARCHAR', 32, '前缀', 1);

-- 字典类型: suffix
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (7, 'suffix', '后缀', 'String', 'VARCHAR', 32, '后缀', 1);

-- 字典类型: minValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (8, 'minValue', '最小值', 'Long', 'BIGINT', 0, '最小值', 1);

-- 字典类型: enabled
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (9, 'enabled', '是否启用', 'Boolean', 'BOOLEAN', 0, '是否启用', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 6, id, '禁用', 'false', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'enabled';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 7, id, '启用', 'true', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'enabled';

-- 字典类型: description
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (10, 'description', '描述', 'String', 'VARCHAR', 256, '描述', 1);

-- 字典类型: createTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (11, 'createTime', '创建时间', 'LocalDateTime', 'TIMESTAMP', 0, '创建时间', 1);

-- 字典类型: updateTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (12, 'updateTime', '更新时间', 'LocalDateTime', 'TIMESTAMP', 0, '更新时间', 1);

-- 字典类型: currentValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (13, 'currentValue', '当前值', 'Long', 'BIGINT', 0, '当前值', 1);

-- 字典类型: totalGenerate
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (14, 'totalGenerate', '总生成数', 'Long', 'BIGINT', 0, '总生成数', 1);

-- 字典类型: sequenceValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (15, 'sequenceValue', '序列值', 'Long', 'BIGINT', 0, '序列值', 1);

-- 字典类型: maxValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (16, 'maxValue', '号段结束值', 'Long', 'BIGINT', 0, '号段结束值', 1);

-- 字典类型: currentMaxValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (17, 'currentMaxValue', '当前已分配最大值', 'Long', 'BIGINT', 0, '当前已分配最大值', 1);

-- 字典类型: version
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (18, 'version', '乐观锁版本号', 'Integer', 'INT', 0, '乐观锁版本号', 1);

-- 字典类型: lastGenerateTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (19, 'lastGenerateTime', '最后生成时间', 'LocalDateTime', 'TIMESTAMP', 0, '最后生成时间', 1);

-- 字典类型: deleted
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (20, 'deleted', '逻辑删除', 'Integer', 'TINYINT', 0, '逻辑删除', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 8, id, '未删除', '0', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'deleted';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 9, id, '已删除', '1', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'deleted';

-- 字典类型: configName
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (21, 'configName', '配置名称', 'String', 'VARCHAR', 128, '配置名称', 1);

-- 字典类型: configKey
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (22, 'configKey', '配置键', 'String', 'VARCHAR', 128, '配置键', 1);

-- 字典类型: configValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (23, 'configValue', '配置值', 'String', 'VARCHAR', 512, '配置值', 1);

-- 字典类型: configType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (24, 'configType', '类型', 'Integer', 'TINYINT', 0, '类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 10, id, '内置', '1', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'configType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 11, id, '自定义', '2', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'configType';

-- 字典类型: dictTypeId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (25, 'dictTypeId', '字典类型ID', 'Long', 'BIGINT', 0, '字典类型ID', 1);

-- 字典类型: dictLabel
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (26, 'dictLabel', '字典标签', 'String', 'VARCHAR', 128, '字典标签', 1);

-- 字典类型: dictValue
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (27, 'dictValue', '字典值', 'String', 'VARCHAR', 128, '字典值', 1);

-- 字典类型: dictSort
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (28, 'dictSort', '排序', 'Integer', 'INT', 0, '排序', 1);

-- 字典类型: cssClass
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (29, 'cssClass', 'CSS样式', 'String', 'VARCHAR', 128, 'CSS样式', 1);

-- 字典类型: listClass
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (30, 'listClass', '表格回显样式', 'String', 'VARCHAR', 128, '表格回显样式', 1);

-- 字典类型: isDefault
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (31, 'isDefault', '是否默认', 'Integer', 'TINYINT', 0, '是否默认', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 12, id, '否', '0', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'isDefault';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 13, id, '是', '1', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'isDefault';

-- 字典类型: status
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (32, 'status', '状态', 'Integer', 'TINYINT', 0, '状态', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 14, id, '禁用', '0', 1, 1, '停用/失败/已吊销'
FROM sys_dict_type WHERE dict_code = 'status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 15, id, '启用', '1', 2, 1, '正常/成功/有效'
FROM sys_dict_type WHERE dict_code = 'status';

-- 字典类型: dictCode
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (33, 'dictCode', '字典编码', 'String', 'VARCHAR', 64, '字典编码', 1);

-- 字典类型: dictName
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (34, 'dictName', '字典名称', 'String', 'VARCHAR', 128, '字典名称', 1);

-- 字典类型: dataType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (35, 'dataType', '数据类型', 'String', 'VARCHAR', 32, '数据类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 16, id, 'String', 'String', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 17, id, 'Character', 'Character', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 18, id, 'Clob', 'Clob', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 19, id, 'Byte', 'Byte', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 20, id, 'Short', 'Short', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 21, id, 'Integer', 'Integer', 6, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 22, id, 'Long', 'Long', 7, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 23, id, 'Float', 'Float', 8, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 24, id, 'Double', 'Double', 9, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 25, id, 'BigDecimal', 'BigDecimal', 10, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 26, id, 'LocalDate', 'LocalDate', 11, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 27, id, 'LocalTime', 'LocalTime', 12, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 28, id, 'LocalDateTime', 'LocalDateTime', 13, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 29, id, 'Date', 'Date', 14, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 30, id, 'Timestamp', 'Timestamp', 15, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 31, id, 'Boolean', 'Boolean', 16, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 32, id, 'byte[]', 'byte[]', 17, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 33, id, 'Blob', 'Blob', 18, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';

-- 字典类型: jdbcType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (36, 'jdbcType', 'JDBC类型', 'String', 'VARCHAR', 32, 'JDBC类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 34, id, 'VARCHAR', 'VARCHAR', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 35, id, 'CHAR', 'CHAR', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 36, id, 'LONGVARCHAR', 'LONGVARCHAR', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 37, id, 'TEXT', 'TEXT', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 38, id, 'CLOB', 'CLOB', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 39, id, 'TINYINT', 'TINYINT', 6, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 40, id, 'SMALLINT', 'SMALLINT', 7, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 41, id, 'INT', 'INT', 8, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 42, id, 'INTEGER', 'INTEGER', 9, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 43, id, 'BIGINT', 'BIGINT', 10, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 44, id, 'FLOAT', 'FLOAT', 11, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 45, id, 'DOUBLE', 'DOUBLE', 12, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 46, id, 'DECIMAL', 'DECIMAL', 13, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 47, id, 'NUMERIC', 'NUMERIC', 14, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 48, id, 'DATE', 'DATE', 15, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 49, id, 'TIME', 'TIME', 16, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 50, id, 'TIMESTAMP', 'TIMESTAMP', 17, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 51, id, 'DATETIME', 'DATETIME', 18, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 52, id, 'BOOLEAN', 'BOOLEAN', 19, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 53, id, 'BINARY', 'BINARY', 20, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 54, id, 'VARBINARY', 'VARBINARY', 21, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 55, id, 'LONGVARBINARY', 'LONGVARBINARY', 22, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 56, id, 'BLOB', 'BLOB', 23, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';

-- 字典类型: dataLength
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (37, 'dataLength', '数据长度', 'Integer', 'INT', 0, '数据长度', 1);

-- 字典类型: userId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (38, 'userId', '用户ID', 'Long', 'BIGINT', 0, '用户ID', 1);

-- 字典类型: username
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (39, 'username', '用户名', 'String', 'VARCHAR', 64, '用户名', 1);

-- 字典类型: loginType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (40, 'loginType', '登录类型', 'String', 'VARCHAR', 32, '登录类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 57, id, '密码登录', 'PASSWORD', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'loginType';

-- 字典类型: ip
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (41, 'ip', 'IP地址', 'String', 'VARCHAR', 64, 'IP地址', 1);

-- 字典类型: location
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (42, 'location', '登录地点', 'String', 'VARCHAR', 128, '登录地点', 1);

-- 字典类型: msg
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (43, 'msg', '消息', 'String', 'VARCHAR', 256, '消息', 1);

-- 字典类型: loginTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (44, 'loginTime', '登录时间', 'LocalDateTime', 'TIMESTAMP', 0, '登录时间', 1);

-- 字典类型: module
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (45, 'module', '操作模块', 'String', 'VARCHAR', 64, '操作模块', 1);

-- 字典类型: operateType
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (46, 'operateType', '操作类型', 'String', 'VARCHAR', 32, '操作类型', 1);

-- 字典类型: requestMethod
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (47, 'requestMethod', '请求方法', 'String', 'VARCHAR', 10, '请求方法', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 58, id, 'GET', 'GET', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 59, id, 'POST', 'POST', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 60, id, 'PUT', 'PUT', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 61, id, 'DELETE', 'DELETE', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 62, id, 'PATCH', 'PATCH', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';

-- 字典类型: requestUrl
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (48, 'requestUrl', '请求URL', 'String', 'VARCHAR', 256, '请求URL', 1);

-- 字典类型: requestParams
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (49, 'requestParams', '请求参数', 'String', 'TEXT', 0, '请求参数', 1);

-- 字典类型: responseResult
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (50, 'responseResult', '响应结果', 'String', 'TEXT', 0, '响应结果', 1);

-- 字典类型: errorMsg
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (51, 'errorMsg', '错误信息', 'String', 'TEXT', 0, '错误信息', 1);

-- 字典类型: executeTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (52, 'executeTime', '执行时间', 'Long', 'BIGINT', 0, '执行时间', 1);

-- 字典类型: permissionCode
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (53, 'permissionCode', '权限编码', 'String', 'VARCHAR', 64, '权限编码', 1);

-- 字典类型: permissionName
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (54, 'permissionName', '权限名称', 'String', 'VARCHAR', 128, '权限名称', 1);

-- 字典类型: url
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (55, 'url', '请求URL', 'String', 'VARCHAR', 256, '请求URL', 1);

-- 字典类型: method
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (56, 'method', '请求方法', 'String', 'VARCHAR', 10, '请求方法', 1);

-- 字典类型: parentId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (57, 'parentId', '父级ID', 'Long', 'BIGINT', 0, '父级ID', 1);

-- 字典类型: type
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (58, 'type', '类型', 'Integer', 'TINYINT', 0, '类型', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 63, id, '目录', '1', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 64, id, '菜单', '2', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 65, id, '按钮', '3', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'type';

-- 字典类型: icon
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (59, 'icon', '图标', 'String', 'VARCHAR', 64, '图标', 1);

-- 字典类型: sort
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (60, 'sort', '排序', 'Integer', 'INT', 0, '排序', 1);

-- 字典类型: roleCode
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (61, 'roleCode', '角色编码', 'String', 'VARCHAR', 64, '角色编码', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 66, id, '系统管理员', 'ADMIN', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'roleCode';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 67, id, '普通用户', 'USER', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'roleCode';

-- 字典类型: roleName
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (62, 'roleName', '角色名称', 'String', 'VARCHAR', 128, '角色名称', 1);

-- 字典类型: roleId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (63, 'roleId', '角色ID', 'Long', 'BIGINT', 0, '角色ID', 1);

-- 字典类型: permissionId
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (64, 'permissionId', '权限ID', 'Long', 'BIGINT', 0, '权限ID', 1);

-- 字典类型: token
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (65, 'token', 'Token值', 'String', 'VARCHAR', 256, 'Token值', 1);

-- 字典类型: refreshToken
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (66, 'refreshToken', '刷新Token', 'String', 'VARCHAR', 256, '刷新Token', 1);

-- 字典类型: expireTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (67, 'expireTime', '过期时间', 'LocalDateTime', 'TIMESTAMP', 0, '过期时间', 1);

-- 字典类型: loginIp
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (68, 'loginIp', '登录IP', 'String', 'VARCHAR', 64, '登录IP', 1);

-- 字典类型: password
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (69, 'password', '密码', 'String', 'VARCHAR', 128, '密码', 1);

-- 字典类型: nickname
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (70, 'nickname', '昵称', 'String', 'VARCHAR', 64, '昵称', 1);

-- 字典类型: email
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (71, 'email', '邮箱', 'String', 'VARCHAR', 128, '邮箱', 1);

-- 字典类型: phone
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (72, 'phone', '手机号', 'String', 'VARCHAR', 20, '手机号', 1);

-- 字典类型: avatar
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (73, 'avatar', '头像URL', 'String', 'VARCHAR', 256, '头像URL', 1);

-- 字典类型: pwdUpdateTime
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (74, 'pwdUpdateTime', '密码更新时间', 'LocalDateTime', 'TIMESTAMP', 0, '密码更新时间', 1);

-- ==================== 轻听(qt)插件：公告 / 版本更新 枚举（数据字典化，避免前端/后端写死） ====================

-- 字典类型: qt_notice_channel（公告展示渠道位掩码，值=2的幂，可叠加）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (75, 'qt_notice_channel', '公告展示渠道', 'Integer', 'VARCHAR', 8, '公告展示渠道位掩码，值按2的幂，type字段为各值之和', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 100, id, '开屏弹窗', '1', 1, 1, 'Splash 开屏弹窗' FROM sys_dict_type WHERE dict_code = 'qt_notice_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 101, id, '首页通告栏', '2', 2, 1, 'NoticeBar 首页顶部通告栏' FROM sys_dict_type WHERE dict_code = 'qt_notice_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 102, id, '消息中心', '4', 3, 1, 'MessageCenter 消息中心存档' FROM sys_dict_type WHERE dict_code = 'qt_notice_channel';

-- 字典类型: qt_notice_audience（可见人群，按是否登录过滤）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (76, 'qt_notice_audience', '公告可见人群', 'String', 'VARCHAR', 16, '公告可见人群：按是否登录过滤', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 106, id, '全部用户', 'ALL', 1, 1, '登录/未登录均展示' FROM sys_dict_type WHERE dict_code = 'qt_notice_audience';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 107, id, '仅登录用户', 'LOGGED_IN', 2, 1, '仅已登录用户可见' FROM sys_dict_type WHERE dict_code = 'qt_notice_audience';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 108, id, '仅游客(未登录)', 'NOT_LOGGED_IN', 3, 1, '仅未登录游客可见' FROM sys_dict_type WHERE dict_code = 'qt_notice_audience';

-- 字典类型: qt_yes_no（通用是否标志 0/1）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (77, 'qt_yes_no', '是否', 'Integer', 'VARCHAR', 2, '通用是否标志：1=是 0=否', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 109, id, '是', '1', 1, 1, '是' FROM sys_dict_type WHERE dict_code = 'qt_yes_no';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 110, id, '否', '0', 2, 1, '否' FROM sys_dict_type WHERE dict_code = 'qt_yes_no';

-- 字典类型: qt_update_platform（版本更新平台）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (78, 'qt_update_platform', '版本更新平台', 'Integer', 'VARCHAR', 8, '版本更新适用平台', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 111, id, '安卓', '1101', 1, 1, 'Android' FROM sys_dict_type WHERE dict_code = 'qt_update_platform';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 112, id, 'iOS', '1102', 2, 1, 'iOS' FROM sys_dict_type WHERE dict_code = 'qt_update_platform';

-- 字典类型: qt_update_type（提示方式）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (79, 'qt_update_type', '版本更新提示方式', 'String', 'VARCHAR', 8, '版本更新提示方式', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 113, id, '弹窗', '1', 1, 1, '弹窗提示' FROM sys_dict_type WHERE dict_code = 'qt_update_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 114, id, '红点', '2', 2, 1, '小红点提示' FROM sys_dict_type WHERE dict_code = 'qt_update_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 115, id, '无提示', '3', 3, 1, '无提示' FROM sys_dict_type WHERE dict_code = 'qt_update_type';

-- 字典类型: qt_update_channel（发布渠道）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (80, 'qt_update_channel', '版本发布渠道', 'String', 'VARCHAR', 16, '版本发布渠道', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 116, id, '正式版', 'stable', 1, 1, '正式版渠道' FROM sys_dict_type WHERE dict_code = 'qt_update_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 117, id, '测试版', 'beta', 2, 1, '测试版渠道' FROM sys_dict_type WHERE dict_code = 'qt_update_channel';

-- 字典类型: qt_update_download_mode（下载方式）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (81, 'qt_update_download_mode', '版本下载方式', 'String', 'VARCHAR', 16, '版本更新下载方式', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 118, id, '应用内下载', 'app', 1, 1, '应用内下载' FROM sys_dict_type WHERE dict_code = 'qt_update_download_mode';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 119, id, '浏览器跳转', 'browser', 2, 1, '浏览器跳转下载' FROM sys_dict_type WHERE dict_code = 'qt_update_download_mode';

-- 字典类型: qt_update_publish（版本发布状态：1 已发布 / 0 未发布；未发布仅本地测试，不推送更新通知、不校验非官方）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (86, 'qt_update_publish', '版本发布状态', 'Integer', 'VARCHAR', 2, '版本是否发布：1=已发布(用户收到更新通知) 0=未发布(仅本地测试)', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 133, id, '已发布', '1', 1, 1, '已发布：App 端收到更新通知' FROM sys_dict_type WHERE dict_code = 'qt_update_publish';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 134, id, '未发布', '0', 2, 1, '未发布：仅本地版本测试，不推送、不校验非官方' FROM sys_dict_type WHERE dict_code = 'qt_update_publish';

-- ==================== 反馈插件（astral-plugin-feedback）：状态 / 类型 / 通知 枚举 ====================

-- 字典类型: feedback_status（反馈状态机；TRANSITIONS 允许 pending→received→resolved→published，任意→deprecated，deprecated 可任意回退）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (82, 'feedback_status', '反馈状态', 'String', 'VARCHAR', 16, '反馈状态机5态', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 120, id, '提出', 'pending', 1, 1, '用户提交初始状态' FROM sys_dict_type WHERE dict_code = 'feedback_status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 121, id, '已接收', 'received', 2, 1, '管理员已接收' FROM sys_dict_type WHERE dict_code = 'feedback_status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 122, id, '已解决', 'resolved', 3, 1, '管理员已解决' FROM sys_dict_type WHERE dict_code = 'feedback_status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 123, id, '已发布', 'published', 4, 1, '公开，App 可见' FROM sys_dict_type WHERE dict_code = 'feedback_status';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 124, id, '已废弃', 'deprecated', 5, 1, '终止处理，可回退' FROM sys_dict_type WHERE dict_code = 'feedback_status';

-- 字典类型: feedback_type（反馈类型）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (83, 'feedback_type', '反馈类型', 'String', 'VARCHAR', 16, '反馈分类', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 125, id, '问题', 'issue', 1, 1, '问题反馈' FROM sys_dict_type WHERE dict_code = 'feedback_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 126, id, '需求', 'request', 2, 1, '需求建议' FROM sys_dict_type WHERE dict_code = 'feedback_type';

-- 字典类型: notice_channel（通知展示渠道）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (84, 'notice_channel', '通知渠道', 'String', 'VARCHAR', 16, '通知展示渠道', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 127, id, 'App', 'app', 1, 1, 'App 端展示' FROM sys_dict_type WHERE dict_code = 'notice_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 128, id, 'Web', 'web', 2, 1, 'Web 端展示' FROM sys_dict_type WHERE dict_code = 'notice_channel';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 129, id, '全部', 'all', 3, 1, '全部端展示' FROM sys_dict_type WHERE dict_code = 'notice_channel';
-- PC 端（桌面端）
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 137, id, 'PC', 'pc', 4, 1, 'PC/桌面端展示' FROM sys_dict_type WHERE dict_code = 'notice_channel';

-- 字典类型: notice_type（通知业务类型）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (85, 'notice_type', '通知类型', 'String', 'VARCHAR', 16, '通知业务分类', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 130, id, '公告', 'announce', 1, 1, '运营公告' FROM sys_dict_type WHERE dict_code = 'notice_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 131, id, '反馈', 'feedback', 2, 1, '反馈/回复通知' FROM sys_dict_type WHERE dict_code = 'notice_type';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 132, id, '需求', 'request', 3, 1, '需求通知' FROM sys_dict_type WHERE dict_code = 'notice_type';

-- 平台枚举：android/ios 分开的（qt_update_platform）新增 Windows(1103)
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 138, id, 'Windows', '1103', 3, 1, 'Windows 桌面端' FROM sys_dict_type WHERE dict_code = 'qt_update_platform';

-- ==================== 统计上报：平台（ut） ====================

-- 字典类型: stat_platform（统计上报平台 ut：app-android / app-ios / app-windows / web）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (88, 'stat_platform', '统计平台', 'String', 'VARCHAR', 16, '统计上报平台标识(ut)', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 139, id, 'Android', 'app-android', 1, 1, 'Android App' FROM sys_dict_type WHERE dict_code = 'stat_platform';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 140, id, 'iOS', 'app-ios', 2, 1, 'iOS App' FROM sys_dict_type WHERE dict_code = 'stat_platform';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 141, id, 'Windows', 'app-windows', 3, 1, 'Windows 桌面端' FROM sys_dict_type WHERE dict_code = 'stat_platform';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 142, id, 'Web', 'web', 4, 1, 'Web/H5' FROM sys_dict_type WHERE dict_code = 'stat_platform';

-- ==================== 表结构管理：SQL方言（生成建表/变更SQL时可选） ====================

-- 字典类型: sql_dialect（表结构管理生成SQL的目标数据库方言）
INSERT INTO sys_dict_type (id, dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES (87, 'sql_dialect', 'SQL方言', 'String', 'VARCHAR', 16, '表结构管理生成SQL的目标数据库方言', 1);
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 135, id, 'PostgreSQL', 'postgresql', 1, 1, 'COMMENT ON 注释、BIGSERIAL 自增（运行时默认）' FROM sys_dict_type WHERE dict_code = 'sql_dialect';
INSERT INTO sys_dict_data (id, dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT 136, id, 'MySQL', 'mysql', 2, 1, '内联 COMMENT、AUTO_INCREMENT 自增、ENGINE/CHARSET 表选项' FROM sys_dict_type WHERE dict_code = 'sql_dialect';
