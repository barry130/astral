-- ============================================
-- 数据字典初始化脚本（字段维度 - 枚举值）
-- ============================================

-- 清空现有数据
DELETE FROM sys_dict_data;
DELETE FROM sys_dict_type;

-- 字典类型: id
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('id', '主键ID', 'Long', 'BIGINT', 0, '主键ID', 1);

-- 字典类型: bizKey
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('bizKey', '业务键', 'String', 'VARCHAR', 64, '业务键', 1);

-- 字典类型: sequenceType
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('sequenceType', '序列类型', 'String', 'VARCHAR', 32, '序列类型', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '雪花算法', 'SNOWFLAKE', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '号段模式', 'SEGMENT', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Redis模式', 'REDIS', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '数据库模式', 'DATABASE', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '简单模式', 'SIMPLE', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'sequenceType';

-- 字典类型: step
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('step', '步长', 'Integer', 'INT', 0, '步长', 1);

-- 字典类型: dateFormat
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('dateFormat', '日期格式', 'String', 'VARCHAR', 32, '日期格式', 1);

-- 字典类型: prefix
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('prefix', '前缀', 'String', 'VARCHAR', 32, '前缀', 1);

-- 字典类型: suffix
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('suffix', '后缀', 'String', 'VARCHAR', 32, '后缀', 1);

-- 字典类型: minValue
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('minValue', '最小值', 'Long', 'BIGINT', 0, '最小值', 1);

-- 字典类型: enabled
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('enabled', '是否启用', 'Boolean', 'BOOLEAN', 0, '是否启用', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '禁用', 'false', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'enabled';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '启用', 'true', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'enabled';

-- 字典类型: description
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('description', '描述', 'String', 'VARCHAR', 256, '描述', 1);

-- 字典类型: createTime
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('createTime', '创建时间', 'LocalDateTime', 'TIMESTAMP', 0, '创建时间', 1);

-- 字典类型: updateTime
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('updateTime', '更新时间', 'LocalDateTime', 'TIMESTAMP', 0, '更新时间', 1);

-- 字典类型: currentValue
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('currentValue', '当前值', 'Long', 'BIGINT', 0, '当前值', 1);

-- 字典类型: totalGenerate
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('totalGenerate', '总生成数', 'Long', 'BIGINT', 0, '总生成数', 1);

-- 字典类型: sequenceValue
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('sequenceValue', '序列值', 'Long', 'BIGINT', 0, '序列值', 1);

-- 字典类型: maxValue
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('maxValue', '号段结束值', 'Long', 'BIGINT', 0, '号段结束值', 1);

-- 字典类型: currentMaxValue
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('currentMaxValue', '当前已分配最大值', 'Long', 'BIGINT', 0, '当前已分配最大值', 1);

-- 字典类型: version
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('version', '乐观锁版本号', 'Integer', 'INT', 0, '乐观锁版本号', 1);

-- 字典类型: lastGenerateTime
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('lastGenerateTime', '最后生成时间', 'LocalDateTime', 'TIMESTAMP', 0, '最后生成时间', 1);

-- 字典类型: deleted
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('deleted', '逻辑删除', 'Integer', 'TINYINT', 0, '逻辑删除', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '未删除', '0', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'deleted';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '已删除', '1', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'deleted';

-- 字典类型: configName
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('configName', '配置名称', 'String', 'VARCHAR', 128, '配置名称', 1);

-- 字典类型: configKey
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('configKey', '配置键', 'String', 'VARCHAR', 128, '配置键', 1);

-- 字典类型: configValue
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('configValue', '配置值', 'String', 'VARCHAR', 512, '配置值', 1);

-- 字典类型: configType
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('configType', '类型', 'Integer', 'TINYINT', 0, '类型', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '内置', '1', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'configType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '自定义', '2', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'configType';

-- 字典类型: dictTypeId
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('dictTypeId', '字典类型ID', 'Long', 'BIGINT', 0, '字典类型ID', 1);

-- 字典类型: dictLabel
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('dictLabel', '字典标签', 'String', 'VARCHAR', 128, '字典标签', 1);

-- 字典类型: dictValue
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('dictValue', '字典值', 'String', 'VARCHAR', 128, '字典值', 1);

-- 字典类型: dictSort
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('dictSort', '排序', 'Integer', 'INT', 0, '排序', 1);

-- 字典类型: cssClass
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('cssClass', 'CSS样式', 'String', 'VARCHAR', 128, 'CSS样式', 1);

-- 字典类型: listClass
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('listClass', '表格回显样式', 'String', 'VARCHAR', 128, '表格回显样式', 1);

-- 字典类型: isDefault
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('isDefault', '是否默认', 'Integer', 'TINYINT', 0, '是否默认', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '否', '0', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'isDefault';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '是', '1', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'isDefault';

-- 字典类型: status
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('status', '状态', 'Integer', 'TINYINT', 0, '状态', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '禁用', '0', 1, 1, '停用/失败/已吊销'
FROM sys_dict_type WHERE dict_code = 'status';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '启用', '1', 2, 1, '正常/成功/有效'
FROM sys_dict_type WHERE dict_code = 'status';

-- 字典类型: dictCode
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('dictCode', '字典编码', 'String', 'VARCHAR', 64, '字典编码', 1);

-- 字典类型: dictName
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('dictName', '字典名称', 'String', 'VARCHAR', 128, '字典名称', 1);

-- 字典类型: dataType
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('dataType', '数据类型', 'String', 'VARCHAR', 32, '数据类型', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'String', 'String', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Character', 'Character', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Clob', 'Clob', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Byte', 'Byte', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Short', 'Short', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Integer', 'Integer', 6, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Long', 'Long', 7, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Float', 'Float', 8, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Double', 'Double', 9, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'BigDecimal', 'BigDecimal', 10, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'LocalDate', 'LocalDate', 11, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'LocalTime', 'LocalTime', 12, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'LocalDateTime', 'LocalDateTime', 13, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Date', 'Date', 14, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Timestamp', 'Timestamp', 15, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Boolean', 'Boolean', 16, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'byte[]', 'byte[]', 17, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'Blob', 'Blob', 18, 1, ''
FROM sys_dict_type WHERE dict_code = 'dataType';

-- 字典类型: jdbcType
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('jdbcType', 'JDBC类型', 'String', 'VARCHAR', 32, 'JDBC类型', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'VARCHAR', 'VARCHAR', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'CHAR', 'CHAR', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'LONGVARCHAR', 'LONGVARCHAR', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'TEXT', 'TEXT', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'CLOB', 'CLOB', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'TINYINT', 'TINYINT', 6, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'SMALLINT', 'SMALLINT', 7, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'INT', 'INT', 8, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'INTEGER', 'INTEGER', 9, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'BIGINT', 'BIGINT', 10, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'FLOAT', 'FLOAT', 11, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'DOUBLE', 'DOUBLE', 12, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'DECIMAL', 'DECIMAL', 13, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'NUMERIC', 'NUMERIC', 14, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'DATE', 'DATE', 15, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'TIME', 'TIME', 16, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'TIMESTAMP', 'TIMESTAMP', 17, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'DATETIME', 'DATETIME', 18, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'BOOLEAN', 'BOOLEAN', 19, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'BINARY', 'BINARY', 20, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'VARBINARY', 'VARBINARY', 21, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'LONGVARBINARY', 'LONGVARBINARY', 22, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'BLOB', 'BLOB', 23, 1, ''
FROM sys_dict_type WHERE dict_code = 'jdbcType';

-- 字典类型: dataLength
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('dataLength', '数据长度', 'Integer', 'INT', 0, '数据长度', 1);

-- 字典类型: userId
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('userId', '用户ID', 'Long', 'BIGINT', 0, '用户ID', 1);

-- 字典类型: username
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('username', '用户名', 'String', 'VARCHAR', 64, '用户名', 1);

-- 字典类型: loginType
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('loginType', '登录类型', 'String', 'VARCHAR', 32, '登录类型', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '密码登录', 'PASSWORD', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'loginType';

-- 字典类型: ip
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('ip', 'IP地址', 'String', 'VARCHAR', 64, 'IP地址', 1);

-- 字典类型: location
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('location', '登录地点', 'String', 'VARCHAR', 128, '登录地点', 1);

-- 字典类型: msg
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('msg', '消息', 'String', 'VARCHAR', 256, '消息', 1);

-- 字典类型: loginTime
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('loginTime', '登录时间', 'LocalDateTime', 'TIMESTAMP', 0, '登录时间', 1);

-- 字典类型: module
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('module', '操作模块', 'String', 'VARCHAR', 64, '操作模块', 1);

-- 字典类型: operateType
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('operateType', '操作类型', 'String', 'VARCHAR', 32, '操作类型', 1);

-- 字典类型: requestMethod
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('requestMethod', '请求方法', 'String', 'VARCHAR', 10, '请求方法', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'GET', 'GET', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'POST', 'POST', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'PUT', 'PUT', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'DELETE', 'DELETE', 4, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, 'PATCH', 'PATCH', 5, 1, ''
FROM sys_dict_type WHERE dict_code = 'requestMethod';

-- 字典类型: requestUrl
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('requestUrl', '请求URL', 'String', 'VARCHAR', 256, '请求URL', 1);

-- 字典类型: requestParams
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('requestParams', '请求参数', 'String', 'TEXT', 0, '请求参数', 1);

-- 字典类型: responseResult
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('responseResult', '响应结果', 'String', 'TEXT', 0, '响应结果', 1);

-- 字典类型: errorMsg
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('errorMsg', '错误信息', 'String', 'TEXT', 0, '错误信息', 1);

-- 字典类型: executeTime
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('executeTime', '执行时间', 'Long', 'BIGINT', 0, '执行时间', 1);

-- 字典类型: permissionCode
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('permissionCode', '权限编码', 'String', 'VARCHAR', 64, '权限编码', 1);

-- 字典类型: permissionName
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('permissionName', '权限名称', 'String', 'VARCHAR', 128, '权限名称', 1);

-- 字典类型: url
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('url', '请求URL', 'String', 'VARCHAR', 256, '请求URL', 1);

-- 字典类型: method
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('method', '请求方法', 'String', 'VARCHAR', 10, '请求方法', 1);

-- 字典类型: parentId
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('parentId', '父级ID', 'Long', 'BIGINT', 0, '父级ID', 1);

-- 字典类型: type
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('type', '类型', 'Integer', 'TINYINT', 0, '类型', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '目录', '1', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'type';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '菜单', '2', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'type';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '按钮', '3', 3, 1, ''
FROM sys_dict_type WHERE dict_code = 'type';

-- 字典类型: icon
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('icon', '图标', 'String', 'VARCHAR', 64, '图标', 1);

-- 字典类型: sort
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('sort', '排序', 'Integer', 'INT', 0, '排序', 1);

-- 字典类型: roleCode
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('roleCode', '角色编码', 'String', 'VARCHAR', 64, '角色编码', 1);
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '系统管理员', 'ADMIN', 1, 1, ''
FROM sys_dict_type WHERE dict_code = 'roleCode';
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)
SELECT id, '普通用户', 'USER', 2, 1, ''
FROM sys_dict_type WHERE dict_code = 'roleCode';

-- 字典类型: roleName
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('roleName', '角色名称', 'String', 'VARCHAR', 128, '角色名称', 1);

-- 字典类型: roleId
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('roleId', '角色ID', 'Long', 'BIGINT', 0, '角色ID', 1);

-- 字典类型: permissionId
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('permissionId', '权限ID', 'Long', 'BIGINT', 0, '权限ID', 1);

-- 字典类型: token
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('token', 'Token值', 'String', 'VARCHAR', 256, 'Token值', 1);

-- 字典类型: refreshToken
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('refreshToken', '刷新Token', 'String', 'VARCHAR', 256, '刷新Token', 1);

-- 字典类型: expireTime
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('expireTime', '过期时间', 'LocalDateTime', 'TIMESTAMP', 0, '过期时间', 1);

-- 字典类型: loginIp
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('loginIp', '登录IP', 'String', 'VARCHAR', 64, '登录IP', 1);

-- 字典类型: password
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('password', '密码', 'String', 'VARCHAR', 128, '密码', 1);

-- 字典类型: nickname
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('nickname', '昵称', 'String', 'VARCHAR', 64, '昵称', 1);

-- 字典类型: email
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('email', '邮箱', 'String', 'VARCHAR', 128, '邮箱', 1);

-- 字典类型: phone
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('phone', '手机号', 'String', 'VARCHAR', 20, '手机号', 1);

-- 字典类型: avatar
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('avatar', '头像URL', 'String', 'VARCHAR', 256, '头像URL', 1);

-- 字典类型: pwdUpdateTime
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('pwdUpdateTime', '密码更新时间', 'LocalDateTime', 'TIMESTAMP', 0, '密码更新时间', 1);
