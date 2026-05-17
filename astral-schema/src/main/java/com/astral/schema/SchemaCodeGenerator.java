package com.astral.schema;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Schema代码生成器
 * <p>
 * 根据 {@link TableSchema} 定义，自动生成以下类型的Java代码和SQL：
 * <ul>
 *   <li>Entity实体类：包含MyBatis-Plus注解的Java实体</li>
 *   <li>Mapper接口：继承BaseMapper的MyBatis-Plus Mapper</li>
 *   <li>Service接口：继承IService的Service层接口</li>
 *   <li>ServiceImpl实现类：继承ServiceImpl的Service实现</li>
 *   <li>Controller控制器：包含分页查询、CRUD等标准REST接口</li>
 *   <li>建表SQL：CREATE TABLE语句</li>
 *   <li>变更SQL：ALTER TABLE语句（对比新旧Schema差异）</li>
 * </ul>
 * </p>
 * <p>
 * 所有生成方法均为静态方法，无状态，线程安全。
 * </p>
 */
public class SchemaCodeGenerator {

    /**
     * 生成Entity实体类代码
     * <p>
     * 根据Schema定义生成包含MyBatis-Plus注解的Java实体类，包括：
     * <ul>
     *   <li>@TableName 表名映射</li>
     *   <li>@TableId 主键策略</li>
     *   <li>@TableLogic 逻辑删除标记</li>
     *   <li>@Version 乐观锁标记</li>
     *   <li>@TableField 列名映射和自动填充策略</li>
     *   <li>@JsonProperty JSON序列化控制</li>
     *   <li>非数据库字段（exist = false）</li>
     * </ul>
     * </p>
     *
     * @param schema 表Schema定义
     * @return 生成的Entity类源代码
     */
    public static String generateEntity(TableSchema schema) {
        StringBuilder sb = new StringBuilder();
        String packageName = schema.getPackageName();
        String className = schema.getClassName();

        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.baomidou.mybatisplus.annotation.*;\n");
        sb.append("import com.fasterxml.jackson.annotation.JsonProperty;\n");
        sb.append("import lombok.Data;\n\n");
        
        boolean hasLocalDate = schema.getFields().stream().anyMatch(f -> "LocalDate".equals(f.getFieldType()));
        boolean hasLocalDateTime = schema.getFields().stream().anyMatch(f -> "LocalDateTime".equals(f.getFieldType()));
        
        if (hasLocalDate) {
            sb.append("import java.time.LocalDate;\n");
        }
        if (hasLocalDateTime) {
            sb.append("import java.time.LocalDateTime;\n");
        }
        if (hasLocalDate || hasLocalDateTime) {
            sb.append("\n");
        }
        sb.append("import java.util.List;\n\n");
        sb.append("@Data\n");
        sb.append("@TableName(\"").append(schema.getTableName()).append("\")\n");
        sb.append("public class ").append(className).append(" {\n\n");

        for (FieldSchema field : schema.getFields()) {
            if (Boolean.TRUE.equals(field.getIsPrimaryKey())) {
                sb.append("    @TableId(type = IdType.").append(field.getMybatisPlusIdType() != null ? field.getMybatisPlusIdType() : "AUTO").append(")\n");
            }
            if (Boolean.TRUE.equals(field.getIsLogicDelete())) {
                sb.append("    @TableLogic\n");
            }
            if (Boolean.TRUE.equals(field.getIsVersion())) {
                sb.append("    @Version\n");
            }
            if (Boolean.TRUE.equals(field.getIsJsonIgnore())) {
                sb.append("    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)\n");
            }
            boolean hasColumnName = !field.getColumnName().equals(field.getFieldName());
            boolean hasAutoFill = field.getIsAutoFill() != null;
            if (hasColumnName || hasAutoFill) {
                sb.append("    @TableField(");
                if (hasColumnName && hasAutoFill) {
                    sb.append("value = \"").append(field.getColumnName()).append("\", fill = FieldFill.").append(field.getIsAutoFill()).append(")");
                } else if (hasColumnName) {
                    sb.append("\"").append(field.getColumnName()).append("\")");
                } else {
                    sb.append("fill = FieldFill.").append(field.getIsAutoFill()).append(")");
                }
                sb.append("\n");
            }
            sb.append("    private ").append(field.getFieldType()).append(" ").append(field.getFieldName()).append(";\n\n");
        }

        if (schema.getNonDbFields() != null) {
            for (FieldSchema field : schema.getNonDbFields()) {
                sb.append("    @TableField(exist = false)\n");
                sb.append("    private ").append(field.getFieldType()).append(" ").append(field.getFieldName()).append(";\n\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 生成Mapper接口代码
     * <p>
     * 生成继承 {@code BaseMapper<Entity>} 的MyBatis-Plus Mapper接口，
     * 自带基础CRUD方法，无需手动编写SQL。
     * </p>
     *
     * @param schema 表Schema定义
     * @return 生成的Mapper接口源代码
     */
    public static String generateMapper(TableSchema schema) {
        StringBuilder sb = new StringBuilder();
        String mapperPackage = schema.getMapperPackageName();
        String entityPackage = schema.getPackageName();
        String className = schema.getClassName();

        sb.append("package ").append(mapperPackage).append(";\n\n");
        sb.append("import ").append(entityPackage).append(".").append(className).append(";\n");
        sb.append("import com.baomidou.mybatisplus.core.mapper.BaseMapper;\n");
        sb.append("import org.apache.ibatis.annotations.Mapper;\n\n");
        sb.append("@Mapper\n");
        sb.append("public interface ").append(className).append("Mapper extends BaseMapper<").append(className).append("> {\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 生成Service接口代码
     * <p>
     * 生成继承 {@code IService<Entity>} 的Service层接口，
     * 自带通用Service方法。
     * </p>
     *
     * @param schema 表Schema定义
     * @return 生成的Service接口源代码
     */
    public static String generateService(TableSchema schema) {
        StringBuilder sb = new StringBuilder();
        String servicePackage = schema.getServicePackageName();
        String entityPackage = schema.getPackageName();
        String className = schema.getClassName();

        sb.append("package ").append(servicePackage).append(";\n\n");
        sb.append("import ").append(entityPackage).append(".").append(className).append(";\n");
        sb.append("import com.baomidou.mybatisplus.extension.service.IService;\n\n");
        sb.append("public interface ").append(className).append("Service extends IService<").append(className).append("> {\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 生成ServiceImpl实现类代码
     * <p>
     * 生成继承 {@code ServiceImpl<Mapper, Entity>} 的Service实现类，
     * 自动注入Mapper并实现Service接口。
     * </p>
     *
     * @param schema 表Schema定义
     * @return 生成的ServiceImpl源代码
     */
    public static String generateServiceImpl(TableSchema schema) {
        StringBuilder sb = new StringBuilder();
        String servicePackage = schema.getServicePackageName();
        String entityPackage = schema.getPackageName();
        String mapperPackage = schema.getMapperPackageName();
        String className = schema.getClassName();

        sb.append("package ").append(servicePackage).append(".impl;\n\n");
        sb.append("import ").append(entityPackage).append(".").append(className).append(";\n");
        sb.append("import ").append(mapperPackage).append(".").append(className).append("Mapper;\n");
        sb.append("import ").append(servicePackage).append(".").append(className).append("Service;\n");
        sb.append("import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;\n");
        sb.append("import org.springframework.stereotype.Service;\n\n");
        sb.append("@Service\n");
        sb.append("public class ").append(className).append("ServiceImpl extends ServiceImpl<").append(className).append("Mapper, ").append(className).append("> implements ").append(className).append("Service {\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 生成Controller控制器代码
     * <p>
     * 生成包含标准RESTful CRUD接口的Controller类，包括：
     * <ul>
     *   <li>GET /page - 分页查询</li>
     *   <li>GET /{id} - 根据ID查询</li>
     *   <li>POST - 创建</li>
     *   <li>PUT /{id} - 更新</li>
     *   <li>DELETE /{id} - 删除</li>
     * </ul>
     * 路径格式：/api/v1/{module}/{tableName去除前缀}
     * </p>
     *
     * @param schema 表Schema定义
     * @return 生成的Controller源代码
     */
    public static String generateController(TableSchema schema) {
        StringBuilder sb = new StringBuilder();
        String controllerPackage = schema.getControllerPackageName();
        String servicePackage = schema.getServicePackageName();
        String entityPackage = schema.getPackageName();
        String className = schema.getClassName();
        String tableName = schema.getTableName();
        String basePath = "/api/v1/" + schema.getModuleName() + "/" + tableName.replace("sys_", "").replace("sequence_", "");

        sb.append("package ").append(controllerPackage).append(";\n\n");
        sb.append("import ").append(entityPackage).append(".").append(className).append(";\n");
        sb.append("import ").append(servicePackage).append(".").append(className).append("Service;\n");
        sb.append("import com.astral.common.result.Result;\n");
        sb.append("import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;\n");
        sb.append("import com.baomidou.mybatisplus.extension.plugins.pagination.Page;\n");
        sb.append("import io.swagger.v3.oas.annotations.Operation;\n");
        sb.append("import io.swagger.v3.oas.annotations.tags.Tag;\n");
        sb.append("import lombok.RequiredArgsConstructor;\n");
        sb.append("import org.springframework.web.bind.annotation.*;\n\n");
        sb.append("@Tag(name = \"").append(schema.getTableComment()).append("\")\n");
        sb.append("@RestController\n");
        sb.append("@RequestMapping(\"").append(basePath).append("\")\n");
        sb.append("@RequiredArgsConstructor\n");
        sb.append("public class ").append(className).append("Controller {\n\n");
        sb.append("    private final ").append(className).append("Service ").append(lowercaseFirst(className)).append("Service;\n\n");

        sb.append("    @Operation(summary = \"分页查询\")\n");
        sb.append("    @GetMapping(\"/page\")\n");
        sb.append("    public Result<Page<").append(className).append(">> page(@RequestParam(defaultValue = \"1\") Integer pageNum,\n");
        sb.append("                                               @RequestParam(defaultValue = \"10\") Integer pageSize) {\n");
        sb.append("        Page<").append(className).append("> page = new Page<>(pageNum, pageSize);\n");
        sb.append("        return Result.success(").append(lowercaseFirst(className)).append("Service.page(page));\n");
        sb.append("    }\n\n");

        sb.append("    @Operation(summary = \"根据ID查询\")\n");
        sb.append("    @GetMapping(\"/{id}\")\n");
        sb.append("    public Result<").append(className).append("> getById(@PathVariable Long id) {\n");
        sb.append("        return Result.success(").append(lowercaseFirst(className)).append("Service.getById(id));\n");
        sb.append("    }\n\n");

        sb.append("    @Operation(summary = \"创建\")\n");
        sb.append("    @PostMapping\n");
        sb.append("    public Result<Void> create(@RequestBody ").append(className).append(" entity) {\n");
        sb.append("        ").append(lowercaseFirst(className)).append("Service.save(entity);\n");
        sb.append("        return Result.success();\n");
        sb.append("    }\n\n");

        sb.append("    @Operation(summary = \"更新\")\n");
        sb.append("    @PutMapping(\"/{id}\")\n");
        sb.append("    public Result<Void> update(@PathVariable Long id, @RequestBody ").append(className).append(" entity) {\n");
        sb.append("        entity.setId(id);\n");
        sb.append("        ").append(lowercaseFirst(className)).append("Service.updateById(entity);\n");
        sb.append("        return Result.success();\n");
        sb.append("    }\n\n");

        sb.append("    @Operation(summary = \"删除\")\n");
        sb.append("    @DeleteMapping(\"/{id}\")\n");
        sb.append("    public Result<Void> delete(@PathVariable Long id) {\n");
        sb.append("        ").append(lowercaseFirst(className)).append("Service.removeById(id);\n");
        sb.append("        return Result.success();\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 生成建表SQL
     * <p>
     * 根据Schema定义生成 CREATE TABLE IF NOT EXISTS 语句，包括：
     * <ul>
     *   <li>自动添加 id BIGINT AUTO_INCREMENT PRIMARY KEY 作为主键</li>
     *   <li>根据字段类型映射为对应的SQL类型</li>
     *   <li>NOT NULL约束（必填字段）</li>
     *   <li>AUTO_INCREMENT（自增主键）</li>
     *   <li>DEFAULT默认值</li>
     *   <li>字段注释</li>
     *   <li>索引创建语句（普通索引和唯一索引）</li>
     * </ul>
     * </p>
     *
     * @param schema 表Schema定义
     * @return 生成的建表SQL
     */
    public static String generateCreateSql(TableSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(schema.getTableName()).append(" (\n");

        List<String> columnDefs = schema.getFields().stream()
                .map(f -> {
                    StringBuilder col = new StringBuilder();
                    col.append("    ").append(f.getColumnName()).append(" ");
                    col.append(getSqlType(f));
                    if (Boolean.TRUE.equals(f.getIsRequired()) && !Boolean.TRUE.equals(f.getIsPrimaryKey())) {
                        col.append(" NOT NULL");
                    }
                    if (Boolean.TRUE.equals(f.getIsPrimaryKey()) && Boolean.TRUE.equals(f.getIsAutoIncrement())) {
                        col.append(" AUTO_INCREMENT");
                    }
                    if (f.getDefaultValue() != null) {
                        col.append(" DEFAULT ").append(f.getDefaultValue());
                    }
                    if (f.getComment() != null) {
                        col.append(" -- ").append(f.getComment());
                    }
                    return col.toString();
                })
                .collect(Collectors.toList());

        columnDefs.add(0, "    id BIGINT AUTO_INCREMENT PRIMARY KEY");
        sb.append(String.join(",\n", columnDefs));
        sb.append("\n);\n");

        if (schema.getIndexes() != null) {
            for (IndexSchema index : schema.getIndexes()) {
                if (index.isUnique()) {
                    sb.append("CREATE UNIQUE INDEX ").append(index.getIndexName())
                            .append(" ON ").append(schema.getTableName())
                            .append("(").append(String.join(", ", index.getColumns())).append(");\n");
                } else {
                    sb.append("CREATE INDEX ").append(index.getIndexName())
                            .append(" ON ").append(schema.getTableName())
                            .append("(").append(String.join(", ", index.getColumns())).append(");\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 将JDBC类型映射为SQL类型
     * <p>
     * 根据字段的JDBC类型和长度配置，返回对应的MySQL数据类型。
     * 默认VARCHAR长度为255，DECIMAL精度为(10,2)。
     * </p>
     *
     * @param field 字段Schema
     * @return SQL类型字符串
     */
    private static String getSqlType(FieldSchema field) {
        return switch (field.getJdbcType()) {
            case "BIGINT" -> "BIGINT";
            case "INT", "INTEGER" -> "INT";
            case "TINYINT" -> "TINYINT";
            case "SMALLINT" -> "SMALLINT";
            case "VARCHAR", "LONGVARCHAR", "CHAR" -> "VARCHAR(" + (field.getLength() != null ? field.getLength() : 255) + ")";
            case "TEXT", "CLOB" -> "TEXT";
            case "TIMESTAMP", "DATETIME", "DATE", "TIME" -> "TIMESTAMP";
            case "BOOLEAN" -> "BOOLEAN";
            case "DECIMAL", "NUMERIC" -> "DECIMAL(10,2)";
            case "FLOAT" -> "FLOAT";
            case "DOUBLE" -> "DOUBLE";
            case "BINARY", "VARBINARY", "LONGVARBINARY", "BLOB" -> "BLOB";
            default -> "VARCHAR(255)";
        };
    }

    /**
     * 生成表结构变更SQL
     * <p>
     * 对比新旧Schema的差异，生成对应的 ALTER TABLE 语句：
     * <ul>
     *   <li>ADD COLUMN：新增字段</li>
     *   <li>DROP COLUMN：删除字段</li>
     *   <li>MODIFY COLUMN：修改字段类型/约束</li>
     * </ul>
     * 如果无变更，返回 "-- 无变更"。
     * </p>
     *
     * @param oldSchema 旧Schema定义
     * @param newSchema 新Schema定义
     * @return 变更SQL语句
     */
    public static String generateAlterSql(TableSchema oldSchema, TableSchema newSchema) {
        StringBuilder sb = new StringBuilder();
        String tableName = newSchema.getTableName();

        List<String> oldColumnNames = oldSchema.getFields().stream()
                .map(FieldSchema::getColumnName)
                .collect(Collectors.toList());
        List<String> newColumnNames = newSchema.getFields().stream()
                .map(FieldSchema::getColumnName)
                .collect(Collectors.toList());

        for (FieldSchema newField : newSchema.getFields()) {
            if (!oldColumnNames.contains(newField.getColumnName())) {
                sb.append("ALTER TABLE ").append(tableName)
                        .append(" ADD COLUMN ").append(newField.getColumnName())
                        .append(" ").append(getSqlType(newField));
                if (Boolean.TRUE.equals(newField.getIsRequired()) && !Boolean.TRUE.equals(newField.getIsPrimaryKey())) {
                    sb.append(" NOT NULL");
                }
                if (newField.getDefaultValue() != null) {
                    sb.append(" DEFAULT ").append(newField.getDefaultValue());
                }
                if (newField.getComment() != null) {
                    sb.append(" COMMENT '").append(newField.getComment()).append("'");
                }
                sb.append(";\n");
            }
        }

        for (FieldSchema oldField : oldSchema.getFields()) {
            if (!newColumnNames.contains(oldField.getColumnName())) {
                sb.append("ALTER TABLE ").append(tableName)
                        .append(" DROP COLUMN ").append(oldField.getColumnName())
                        .append(";\n");
            }
        }

        for (FieldSchema newField : newSchema.getFields()) {
            FieldSchema oldField = oldSchema.getFields().stream()
                    .filter(f -> f.getColumnName().equals(newField.getColumnName()))
                    .findFirst()
                    .orElse(null);
            if (oldField != null && !fieldsEqual(oldField, newField)) {
                sb.append("ALTER TABLE ").append(tableName)
                        .append(" MODIFY COLUMN ").append(newField.getColumnName())
                        .append(" ").append(getSqlType(newField));
                if (Boolean.TRUE.equals(newField.getIsRequired()) && !Boolean.TRUE.equals(newField.getIsPrimaryKey())) {
                    sb.append(" NOT NULL");
                }
                if (newField.getDefaultValue() != null) {
                    sb.append(" DEFAULT ").append(newField.getDefaultValue());
                }
                if (newField.getComment() != null) {
                    sb.append(" COMMENT '").append(newField.getComment()).append("'");
                }
                sb.append(";\n");
            }
        }

        return sb.length() > 0 ? sb.toString() : "-- 无变更";
    }

    /**
     * 比较两个字段的核心属性是否相等
     * <p>
     * 仅比较影响SQL定义的关键属性：类型、长度、必填、默认值、注释、Java类型。
     * 不比较主键、自增等不影响MODIFY COLUMN的属性。
     * </p>
     *
     * @param oldField 旧字段
     * @param newField 新字段
     * @return 是否相等
     */
    private static boolean fieldsEqual(FieldSchema oldField, FieldSchema newField) {
        return java.util.Objects.equals(oldField.getJdbcType(), newField.getJdbcType())
                && java.util.Objects.equals(oldField.getLength(), newField.getLength())
                && java.util.Objects.equals(oldField.getIsRequired(), newField.getIsRequired())
                && java.util.Objects.equals(oldField.getDefaultValue(), newField.getDefaultValue())
                && java.util.Objects.equals(oldField.getComment(), newField.getComment())
                && java.util.Objects.equals(oldField.getFieldType(), newField.getFieldType());
    }

    /**
     * 将类名首字母小写
     * <p>
     * 用于生成Service变量名，如 "User" -> "user"。
     * </p>
     *
     * @param str 原始字符串
     * @return 首字母小写后的字符串
     */
    private static String lowercaseFirst(String str) {
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }
}
