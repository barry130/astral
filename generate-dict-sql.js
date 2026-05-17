const fs = require('fs');
const path = require('path');

const schemaDir = 'F:\\JavaFile\\xulie\\astral-schema\\src\\main\\resources\\schema';
const outputFile = 'F:\\JavaFile\\xulie\\sql\\dict-init.sql';

// 字段枚举值定义（根据代码分析）
const fieldEnums = {
    // 数据类型
    dataType: [
        { label: 'String', value: 'String', sort: 1 },
        { label: 'Character', value: 'Character', sort: 2 },
        { label: 'Clob', value: 'Clob', sort: 3 },
        { label: 'Byte', value: 'Byte', sort: 4 },
        { label: 'Short', value: 'Short', sort: 5 },
        { label: 'Integer', value: 'Integer', sort: 6 },
        { label: 'Long', value: 'Long', sort: 7 },
        { label: 'Float', value: 'Float', sort: 8 },
        { label: 'Double', value: 'Double', sort: 9 },
        { label: 'BigDecimal', value: 'BigDecimal', sort: 10 },
        { label: 'LocalDate', value: 'LocalDate', sort: 11 },
        { label: 'LocalTime', value: 'LocalTime', sort: 12 },
        { label: 'LocalDateTime', value: 'LocalDateTime', sort: 13 },
        { label: 'Date', value: 'Date', sort: 14 },
        { label: 'Timestamp', value: 'Timestamp', sort: 15 },
        { label: 'Boolean', value: 'Boolean', sort: 16 },
        { label: 'byte[]', value: 'byte[]', sort: 17 },
        { label: 'Blob', value: 'Blob', sort: 18 },
    ],
    // JDBC类型
    jdbcType: [
        { label: 'VARCHAR', value: 'VARCHAR', sort: 1 },
        { label: 'CHAR', value: 'CHAR', sort: 2 },
        { label: 'LONGVARCHAR', value: 'LONGVARCHAR', sort: 3 },
        { label: 'TEXT', value: 'TEXT', sort: 4 },
        { label: 'CLOB', value: 'CLOB', sort: 5 },
        { label: 'TINYINT', value: 'TINYINT', sort: 6 },
        { label: 'SMALLINT', value: 'SMALLINT', sort: 7 },
        { label: 'INT', value: 'INT', sort: 8 },
        { label: 'INTEGER', value: 'INTEGER', sort: 9 },
        { label: 'BIGINT', value: 'BIGINT', sort: 10 },
        { label: 'FLOAT', value: 'FLOAT', sort: 11 },
        { label: 'DOUBLE', value: 'DOUBLE', sort: 12 },
        { label: 'DECIMAL', value: 'DECIMAL', sort: 13 },
        { label: 'NUMERIC', value: 'NUMERIC', sort: 14 },
        { label: 'DATE', value: 'DATE', sort: 15 },
        { label: 'TIME', value: 'TIME', sort: 16 },
        { label: 'TIMESTAMP', value: 'TIMESTAMP', sort: 17 },
        { label: 'DATETIME', value: 'DATETIME', sort: 18 },
        { label: 'BOOLEAN', value: 'BOOLEAN', sort: 19 },
        { label: 'BINARY', value: 'BINARY', sort: 20 },
        { label: 'VARBINARY', value: 'VARBINARY', sort: 21 },
        { label: 'LONGVARBINARY', value: 'LONGVARBINARY', sort: 22 },
        { label: 'BLOB', value: 'BLOB', sort: 23 },
    ],
    // 通用状态
    status: [
        { label: '禁用', value: '0', sort: 1, description: '停用/失败/已吊销' },
        { label: '启用', value: '1', sort: 2, description: '正常/成功/有效' },
    ],
    // 逻辑删除
    deleted: [
        { label: '未删除', value: '0', sort: 1 },
        { label: '已删除', value: '1', sort: 2 },
    ],
    // 序列类型
    sequenceType: [
        { label: '雪花算法', value: 'SNOWFLAKE', sort: 1 },
        { label: '号段模式', value: 'SEGMENT', sort: 2 },
        { label: 'Redis模式', value: 'REDIS', sort: 3 },
        { label: '数据库模式', value: 'DATABASE', sort: 4 },
        { label: '简单模式', value: 'SIMPLE', sort: 5 },
    ],
    // 配置类型
    configType: [
        { label: '内置', value: '1', sort: 1 },
        { label: '自定义', value: '2', sort: 2 },
    ],
    // 权限类型
    type: [
        { label: '目录', value: '1', sort: 1 },
        { label: '菜单', value: '2', sort: 2 },
        { label: '按钮', value: '3', sort: 3 },
    ],
    // 是否默认
    isDefault: [
        { label: '否', value: '0', sort: 1 },
        { label: '是', value: '1', sort: 2 },
    ],
    // 是否启用（布尔）
    enabled: [
        { label: '禁用', value: 'false', sort: 1 },
        { label: '启用', value: 'true', sort: 2 },
    ],
    // 节点状态
    nodeStatus: [
        { label: '在线', value: 'ONLINE', sort: 1 },
        { label: '离线', value: 'OFFLINE', sort: 2 },
        { label: '过期', value: 'EXPIRED', sort: 3 },
    ],
    // 登录类型
    loginType: [
        { label: '密码登录', value: 'PASSWORD', sort: 1 },
    ],
    // HTTP方法
    requestMethod: [
        { label: 'GET', value: 'GET', sort: 1 },
        { label: 'POST', value: 'POST', sort: 2 },
        { label: 'PUT', value: 'PUT', sort: 3 },
        { label: 'DELETE', value: 'DELETE', sort: 4 },
        { label: 'PATCH', value: 'PATCH', sort: 5 },
    ],
    // 自动填充
    autoFill: [
        { label: '不填充', value: 'DEFAULT', sort: 1 },
        { label: '插入时', value: 'INSERT', sort: 2 },
        { label: '更新时', value: 'UPDATE', sort: 3 },
        { label: '插入和更新', value: 'INSERT_UPDATE', sort: 4 },
    ],
    // 角色编码
    roleCode: [
        { label: '系统管理员', value: 'ADMIN', sort: 1 },
        { label: '普通用户', value: 'USER', sort: 2 },
    ],
};

let sql = `-- ============================================
-- 数据字典初始化脚本（字段维度 - 枚举值）
-- ============================================

-- 清空现有数据
DELETE FROM sys_dict_data;
DELETE FROM sys_dict_type;
`;

const files = fs.readdirSync(schemaDir).filter(f => f.endsWith('.json'));

// 收集所有字段，按字段名合并
const fieldMap = new Map();

for (const file of files) {
    const content = fs.readFileSync(path.join(schemaDir, file), 'utf8');
    const table = JSON.parse(content);
    
    for (const field of table.fields) {
        const fieldName = field.fieldName;
        
        if (!fieldMap.has(fieldName)) {
            fieldMap.set(fieldName, {
                ...field,
                tables: []
            });
        }
        
        const existing = fieldMap.get(fieldName);
        existing.tables.push(`${table.tableName}(${table.tableComment})`);
    }
}

// 生成 SQL
for (const [fieldName, field] of fieldMap) {
    const comment = (field.comment || '').replace(/\(.*\)/g, '').replace(/'/g, "''").trim();
    const length = field.length || 0;
    
    // 字典类型：字段名
    const dictCode = fieldName;
    const dictName = comment;
    
    sql += `\n-- 字典类型: ${dictCode}\n`;
    sql += `INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)\n`;
    sql += `VALUES ('${dictCode}', '${dictName}', '${field.fieldType}', '${field.jdbcType}', ${length}, '${comment}', 1);\n`;
    
    // 字典数据：枚举值（只有有枚举值的字段才插入）
    const enums = fieldEnums[fieldName];
    if (enums && enums.length > 0) {
        for (const item of enums) {
            const desc = (item.description || '').replace(/'/g, "''");
            sql += `INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, status, description)\n`;
            sql += `SELECT id, '${item.label}', '${item.value}', ${item.sort}, 1, '${desc}'\n`;
            sql += `FROM sys_dict_type WHERE dict_code = '${dictCode}';\n`;
        }
    }
}

fs.writeFileSync(outputFile, sql, 'utf8');
console.log(`Generated: ${outputFile}`);
console.log(`Unique fields: ${fieldMap.size}`);
console.log(`Fields with enums: ${Object.keys(fieldEnums).length}`);
