$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$schemaDir = "F:\JavaFile\xulie\astral-schema\src\main\resources\schema"
$outputFile = "F:\JavaFile\xulie\sql\dict-init.sql"

$sql = @"
-- ============================================
-- 数据字典初始化脚本（基于表结构自动生成）
-- ============================================

-- 清空现有数据
DELETE FROM sys_dict_data;
DELETE FROM sys_dict_type;

"@

$tables = @()
Get-ChildItem -Path $schemaDir -Filter "*.json" | ForEach-Object {
    $content = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
    $tables += ($content | ConvertFrom-Json)
}

foreach ($table in $tables) {
    $tableName = $table.tableName
    $tableComment = $table.tableComment
    $dictCode = "fields:$tableName"
    $dictName = "$tableComment - 字段定义"
    
    $sql += @"

-- 字典类型: $tableComment
INSERT INTO sys_dict_type (dict_code, dict_name, data_type, jdbc_type, data_length, description, status)
VALUES ('$dictCode', '$dictName', 'String', 'VARCHAR', 255, '$tableComment', 1);

"@

    $fieldIndex = 0
    foreach ($field in $table.fields) {
        $fieldIndex++
        $fieldName = $field.fieldName
        $columnName = $field.columnName
        $fieldType = $field.fieldType
        $jdbcType = $field.jdbcType
        $comment = ($field.comment -replace "'", "''")
        $length = if ($field.length) { $field.length } else { 0 }
        $isPk = if ($field.isPrimaryKey) { 1 } else { 0 }
        $isRequired = if ($field.isRequired) { 1 } else { 0 }
        $isAutoIncrement = if ($field.isAutoIncrement) { 1 } else { 0 }
        $defaultValue = if ($field.defaultValue) { ($field.defaultValue -replace "'", "''") } else { "" }
        $isLogicDelete = if ($field.isLogicDelete) { 1 } else { 0 }
        $isVersion = if ($field.isVersion) { 1 } else { 0 }
        $isAutoFill = if ($field.isAutoFill) { $field.isAutoFill } else { "" }
        
        $metaDesc = "$comment | 默认:$defaultValue | 自增:$isAutoIncrement | 逻辑删除:$isLogicDelete | 乐观锁:$isVersion | 自动填充:$isAutoFill"
        
        $sql += @"
INSERT INTO sys_dict_data (dict_type_id, dict_label, dict_value, dict_sort, css_class, list_class, is_default, status, description)
SELECT id, '$fieldName', '$columnName', $fieldIndex, '$fieldType', '$jdbcType', $isPk, $isRequired, '长度:$length | $metaDesc'
FROM sys_dict_type WHERE dict_code = '$dictCode';

"@
    }
}

[System.IO.File]::WriteAllText($outputFile, $sql, [System.Text.Encoding]::UTF8)
Write-Output "Generated: $outputFile"
