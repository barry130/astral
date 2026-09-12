package com.astral.system.controller;

import com.astral.common.error.ErrorCodes;
import com.astral.common.result.Result;
import com.astral.schema.FieldSchema;
import com.astral.schema.SchemaCodeGenerator;
import com.astral.schema.SchemaRegistry;
import com.astral.schema.TableSchema;
import com.astral.schema.SqlDialect;
import com.astral.system.dto.CreateTableRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 表结构管理控制器
 * <p>提供表结构的查看、创建、删除、更新功能，以及代码生成（Entity/Mapper/Service/Controller）和建表SQL生成功能</p>
 */
@Tag(name = "表结构管理")
@RestController
@RequestMapping("/api/v1/admin/system/table-schema")
public class TableSchemaController {

    /**
     * 获取所有表结构定义
     *
     * @return 全部表结构列表
     */
    @Operation(summary = "获取所有表结构")
    @GetMapping
    public Result<List<TableSchema>> getAllSchemas() {
        return Result.success(SchemaRegistry.getAllSchemas());
    }

    /**
     * 根据表名获取表结构定义
     *
     * @param tableName 表名
     * @return 表结构定义，不存在时返回错误
     */
    @Operation(summary = "根据表名获取表结构")
    @GetMapping("/{tableName}")
    public Result<TableSchema> getSchema(@PathVariable String tableName) {
        TableSchema schema = SchemaRegistry.getSchema(tableName);
        if (schema == null) {
            return Result.error("SYS009");
        }
        return Result.success(schema);
    }

    /**
     * 按模块获取表结构定义
     *
     * @param moduleName 模块名称
     * @return 指定模块下的表结构列表
     */
    @Operation(summary = "按模块获取表结构")
    @GetMapping("/module/{moduleName}")
    public Result<List<TableSchema>> getSchemasByModule(@PathVariable String moduleName) {
        return Result.success(SchemaRegistry.getSchemasByModule(moduleName));
    }

    /**
     * 获取所有表名（仅返回表名列表，不包含详细结构）
     *
     * @return 全部表名列表
     */
    @Operation(summary = "获取所有表名")
    @GetMapping("/names")
    public Result<List<String>> getAllTableNames() {
        return Result.success(List.copyOf(SchemaRegistry.getAllTableNames()));
    }

    /**
     * 创建新表结构
     * <p>支持包含通用字段（id、create_time、update_time、deleted）的选项</p>
     *
     * @param request 创建表结构请求
     * @return 创建的表结构
     * @throws IOException 写入表结构文件时可能抛出IO异常
     */
    @Operation(summary = "创建新表结构")
    @PostMapping
    public Result<TableSchema> createSchema(@RequestBody CreateTableRequest request) throws IOException {
        if (request.getTableName() == null || request.getTableName().isBlank()) {
            return Result.error("SYS010");
        }
        if (SchemaRegistry.containsTable(request.getTableName())) {
            return Result.error("SYS011", request.getTableName());
        }

        TableSchema schema = new TableSchema();
        schema.setTableName(request.getTableName());
        schema.setTableComment(request.getTableComment() != null ? request.getTableComment() : "");
        schema.setModuleName(request.getModuleName() != null ? request.getModuleName() : "system");
        // 将表名转换为驼峰命名的类名
        schema.setClassName(toClassName(request.getTableName()));

        List<FieldSchema> fields = new ArrayList<>();
        // 如果选择包含通用字段，则添加id、create_time、update_time、deleted
        if (Boolean.TRUE.equals(request.getIncludeCommonFields())) {
            fields.addAll(createDefaultFields());
        }
        schema.setFields(fields);

        SchemaRegistry.addSchema(schema);
        return Result.success(schema);
    }

    /**
     * 删除表结构
     *
     * @param tableName 表名
     * @return 操作结果
     * @throws IOException 删除表结构文件时可能抛出IO异常
     */
    @Operation(summary = "删除表结构")
    @DeleteMapping("/{tableName}")
    public Result<Void> deleteSchema(@PathVariable String tableName) throws IOException {
        if (!SchemaRegistry.containsTable(tableName)) {
            return Result.error("SYS009");
        }
        SchemaRegistry.deleteSchema(tableName);
        return Result.success();
    }

    /**
     * 将下划线分隔的表名转换为大驼峰命名的类名
     * <p>例如：user_role -> UserRole</p>
     *
     * @param tableName 下划线分隔的表名
     * @return 大驼峰命名的类名
     */
    private String toClassName(String tableName) {
        if (tableName == null || tableName.isBlank()) return "";
        String[] parts = tableName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 创建默认通用字段列表
     * <p>包含：id（主键自增）、create_time（插入时自动填充）、update_time（更新时自动填充）、deleted（逻辑删除）</p>
     *
     * @return 默认字段列表
     */
    private List<FieldSchema> createDefaultFields() {
        List<FieldSchema> fields = new ArrayList<>();

        // 主键ID字段
        FieldSchema id = new FieldSchema();
        id.setColumnName("id");
        id.setFieldName("id");
        id.setFieldType("Long");
        id.setJdbcType("BIGINT");
        id.setComment("主键ID");
        id.setLength(20);
        id.setIsPrimaryKey(true);
        id.setIsAutoIncrement(true);
        id.setIsRequired(true);
        id.setMybatisPlusIdType("AUTO");
        fields.add(id);

        // 创建时间字段（INSERT时自动填充）
        FieldSchema createTime = new FieldSchema();
        createTime.setColumnName("create_time");
        createTime.setFieldName("createTime");
        createTime.setFieldType("LocalDateTime");
        createTime.setJdbcType("TIMESTAMP");
        createTime.setComment("创建时间");
        createTime.setIsAutoFill("INSERT");
        fields.add(createTime);

        // 更新时间字段（UPDATE时自动填充）
        FieldSchema updateTime = new FieldSchema();
        updateTime.setColumnName("update_time");
        updateTime.setFieldName("updateTime");
        updateTime.setFieldType("LocalDateTime");
        updateTime.setJdbcType("TIMESTAMP");
        updateTime.setComment("更新时间");
        updateTime.setIsAutoFill("UPDATE");
        fields.add(updateTime);

        // 逻辑删除字段
        FieldSchema deleted = new FieldSchema();
        deleted.setColumnName("deleted");
        deleted.setFieldName("deleted");
        deleted.setFieldType("Boolean");
        deleted.setJdbcType("TINYINT");
        deleted.setComment("逻辑删除");
        deleted.setDefaultValue("0");
        deleted.setIsLogicDelete(true);
        fields.add(deleted);

        return fields;
    }

    /**
     * 生成Entity实体类代码
     *
     * @param tableName 表名
     * @return Entity代码字符串
     */
    @Operation(summary = "生成Entity代码")
    @GetMapping("/{tableName}/entity")
    public Result<String> generateEntity(@PathVariable String tableName) {
        TableSchema schema = SchemaRegistry.getSchema(tableName);
        if (schema == null) {
            return Result.error("SYS009");
        }
        return Result.success(SchemaCodeGenerator.generateEntity(schema));
    }

    /**
     * 生成Mapper接口代码
     *
     * @param tableName 表名
     * @return Mapper代码字符串
     */
    @Operation(summary = "生成Mapper代码")
    @GetMapping("/{tableName}/mapper")
    public Result<String> generateMapper(@PathVariable String tableName) {
        TableSchema schema = SchemaRegistry.getSchema(tableName);
        if (schema == null) {
            return Result.error("SYS009");
        }
        return Result.success(SchemaCodeGenerator.generateMapper(schema));
    }

    /**
     * 生成Service接口代码
     *
     * @param tableName 表名
     * @return Service代码字符串
     */
    @Operation(summary = "生成Service代码")
    @GetMapping("/{tableName}/service")
    public Result<String> generateService(@PathVariable String tableName) {
        TableSchema schema = SchemaRegistry.getSchema(tableName);
        if (schema == null) {
            return Result.error("SYS009");
        }
        return Result.success(SchemaCodeGenerator.generateService(schema));
    }

    /**
     * 生成ServiceImpl实现类代码
     *
     * @param tableName 表名
     * @return ServiceImpl代码字符串
     */
    @Operation(summary = "生成ServiceImpl代码")
    @GetMapping("/{tableName}/service-impl")
    public Result<String> generateServiceImpl(@PathVariable String tableName) {
        TableSchema schema = SchemaRegistry.getSchema(tableName);
        if (schema == null) {
            return Result.error("SYS009");
        }
        return Result.success(SchemaCodeGenerator.generateServiceImpl(schema));
    }

    /**
     * 生成Controller控制器代码
     *
     * @param tableName 表名
     * @return Controller代码字符串
     */
    @Operation(summary = "生成Controller代码")
    @GetMapping("/{tableName}/controller")
    public Result<String> generateController(@PathVariable String tableName) {
        TableSchema schema = SchemaRegistry.getSchema(tableName);
        if (schema == null) {
            return Result.error("SYS009");
        }
        return Result.success(SchemaCodeGenerator.generateController(schema));
    }

    /**
     * 生成完整代码包（Entity+Mapper+Service+ServiceImpl+Controller）
     *
     * @param tableName 表名
     * @return 包含各层代码的Map，key为层级名称，value为代码字符串
     */
    @Operation(summary = "生成完整代码包（Entity+Mapper+Service+Controller）")
    @GetMapping("/{tableName}/full-code")
    public Result<Map<String, String>> generateFullCode(@PathVariable String tableName) {
        TableSchema schema = SchemaRegistry.getSchema(tableName);
        if (schema == null) {
            return Result.error("SYS009");
        }
        Map<String, String> code = Map.of(
            "entity", SchemaCodeGenerator.generateEntity(schema),
            "mapper", SchemaCodeGenerator.generateMapper(schema),
            "service", SchemaCodeGenerator.generateService(schema),
            "serviceImpl", SchemaCodeGenerator.generateServiceImpl(schema),
            "controller", SchemaCodeGenerator.generateController(schema)
        );
        return Result.success(code);
    }

    /**
     * 生成建表SQL语句
     *
     * @param tableName 表名
     * @param dialect   SQL方言（mysql / postgresql），默认postgresql
     * @return CREATE TABLE SQL语句
     */
    @Operation(summary = "生成建表SQL")
    @GetMapping("/{tableName}/sql")
    public Result<String> generateSql(@PathVariable String tableName,
                                      @RequestParam(value = "dialect", required = false) String dialect) {
        TableSchema schema = SchemaRegistry.getSchema(tableName);
        if (schema == null) {
            return Result.error("SYS009");
        }
        return Result.success(SchemaCodeGenerator.generateCreateSql(schema, SqlDialect.of(dialect)));
    }

    /**
     * 获取支持的SQL方言列表
     * <p>供前端“生成SQL”的方言下拉选择使用，字典维护见迁移脚本 V2__baseline_dict.sql 的 sql_dialect</p>
     *
     * @return 方言编码与名称列表
     */
    @Operation(summary = "获取支持的SQL方言")
    @GetMapping("/dialects")
    public Result<List<Map<String, String>>> getDialects() {
        List<Map<String, String>> dialects = Arrays.stream(SqlDialect.values())
                .map(d -> Map.of("code", d.getCode(), "name", d.getLabel()))
                .collect(Collectors.toList());
        return Result.success(dialects);
    }

    /**
     * 更新表结构
     * <p>返回ALTER SQL和更新后的Entity代码，便于对比和应用变更</p>
     *
     * @param tableName 表名
     * @param newSchema 新的表结构定义
     * @return 包含ALTER SQL和Entity代码的Map
     * @throws IOException 更新表结构文件时可能抛出IO异常
     */
    @Operation(summary = "更新表结构")
    @PutMapping("/{tableName}")
    public Result<Map<String, String>> updateSchema(@PathVariable String tableName,
                                                    @RequestBody TableSchema newSchema,
                                                    @RequestParam(value = "dialect", required = false) String dialect) throws IOException {
        TableSchema oldSchema = SchemaRegistry.getSchema(tableName);
        if (oldSchema == null) {
            return Result.error("SYS009");
        }

        TableSchema savedOldSchema = SchemaRegistry.updateSchema(tableName, newSchema);
        // 生成新旧结构的差异SQL
        String alterSql = SchemaCodeGenerator.generateAlterSql(savedOldSchema, newSchema, SqlDialect.of(dialect));
        String entityCode = SchemaCodeGenerator.generateEntity(newSchema);

        return Result.success(Map.of(
            "alterSql", alterSql,
            "entityCode", entityCode
        ));
    }

    /**
     * 生成ALTER SQL（对比新旧表结构差异）
     *
     * @param tableName 表名
     * @param newSchema 新的表结构定义
     * @param dialect   SQL方言（mysql / postgresql），默认postgresql
     * @return ALTER TABLE SQL语句
     */
    @Operation(summary = "生成ALTER SQL")
    @PostMapping("/{tableName}/alter-sql")
    public Result<String> generateAlterSql(@PathVariable String tableName,
                                           @RequestBody TableSchema newSchema,
                                           @RequestParam(value = "dialect", required = false) String dialect) {
        TableSchema oldSchema = SchemaRegistry.getSchema(tableName);
        if (oldSchema == null) {
            return Result.error("SYS009");
        }
        return Result.success(SchemaCodeGenerator.generateAlterSql(oldSchema, newSchema, SqlDialect.of(dialect)));
    }

    /**
     * 导出单个表结构为JSON
     *
     * @param tableName 表名
     * @return 表结构定义
     */
    @Operation(summary = "导出表结构JSON文件")
    @GetMapping("/{tableName}/export")
    public Result<TableSchema> exportSchema(@PathVariable String tableName) {
        TableSchema schema = SchemaRegistry.getSchema(tableName);
        if (schema == null) {
            return Result.error("SYS009");
        }
        return Result.success(schema);
    }

    /**
     * 批量导出所有表结构为JSON
     *
     * @return 全部表结构列表
     */
    @Operation(summary = "批量导出所有表结构JSON")
    @GetMapping("/export-all")
    public Result<List<TableSchema>> exportAllSchemas() {
        return Result.success(SchemaRegistry.getAllSchemas());
    }
}
