package com.astral.schema;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
 *   <li>建表SQL：CREATE TABLE语句（支持MySQL / PostgreSQL方言）</li>
 *   <li>变更SQL：ALTER TABLE语句（支持MySQL / PostgreSQL方言，对比新旧Schema差异）</li>
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
                sb.append("    @TableId(type = IdType.").append(field.getMybatisPlusIdType() != null ? field.getMybatisPlusIdType() : "INPUT").append(")\n");
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
            // 自动填充策略：优先使用 schema 中声明的，否则按字段名约定
            String autoFill = field.getIsAutoFill();
            if (autoFill == null) {
                String fn = field.getFieldName();
                if ("createTime".equals(fn) || "createdAt".equals(fn)) {
                    autoFill = "INSERT";
                } else if ("updateTime".equals(fn) || "updatedAt".equals(fn)) {
                    autoFill = "INSERT_UPDATE";
                }
            }
            boolean hasAutoFill = autoFill != null;
            if (hasColumnName || hasAutoFill) {
                sb.append("    @TableField(");
                if (hasColumnName && hasAutoFill) {
                    sb.append("value = \"").append(field.getColumnName()).append("\", fill = FieldFill.").append(autoFill).append(")");
                } else if (hasColumnName) {
                    sb.append("\"").append(field.getColumnName()).append("\")");
                } else {
                    sb.append("fill = FieldFill.").append(autoFill).append(")");
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
     *   <li>索引创建语句（普通索引和唯一索引）</li>
     *   <li>表注释与字段注释（MySQL内联 COMMENT，PostgreSQL输出 COMMENT ON）</li>
     * </ul>
     * </p>
     * <p>
     * 支持 MySQL / PostgreSQL 两种方言（见 {@link SqlDialect}），默认按运行时数据库 PostgreSQL 生成。
     * </p>
     * <p>
     * 注：仅当Schema中未声明主键字段时，才自动补充主键列。
     * </p>
     *
     * @param schema 表Schema定义
     * @return 生成的建表SQL（PostgreSQL方言）
     */
    public static String generateCreateSql(TableSchema schema) {
        return generateCreateSql(schema, SqlDialect.POSTGRESQL);
    }

    /**
     * 生成建表SQL（指定方言）
     * <p>
     * 方言差异：
     * <ul>
     *   <li>MySQL：AUTO_INCREMENT 自增、内联 COMMENT、表选项 ENGINE/CHARSET</li>
     *   <li>PostgreSQL：BIGSERIAL 自增、COMMENT ON 注释语句</li>
     * </ul>
     * </p>
     *
     * @param schema  表Schema定义
     * @param dialect SQL方言，为空时按PostgreSQL处理
     * @return 生成的建表SQL
     */
    public static String generateCreateSql(TableSchema schema, SqlDialect dialect) {
        SqlDialect db = dialect == null ? SqlDialect.POSTGRESQL : dialect;
        StringBuilder sb = new StringBuilder();
        String tableName = schema.getTableName();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");

        List<String> columnDefs = schema.getFields().stream()
                .map(f -> buildColumnDef(f, db))
                .collect(Collectors.toList());

        // Schema未声明主键字段时，才补充默认自增主键列（避免与JSON中已声明的id列重复）
        boolean hasPrimaryKey = schema.getFields().stream()
                .anyMatch(f -> Boolean.TRUE.equals(f.getIsPrimaryKey()));
        if (!hasPrimaryKey) {
            columnDefs.add(0, buildDefaultIdColumnDef(db));
        }
        sb.append(String.join(",\n", columnDefs));

        if (db == SqlDialect.MYSQL) {
            sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            if (hasText(schema.getTableComment())) {
                sb.append(" COMMENT='").append(escapeSqlLiteral(schema.getTableComment())).append("'");
            }
            sb.append(";\n");
        } else {
            sb.append("\n);\n");
        }

        if (schema.getIndexes() != null) {
            for (IndexSchema index : schema.getIndexes()) {
                sb.append("CREATE ")
                        .append(index.isUnique() ? "UNIQUE " : "")
                        .append("INDEX ")
                        .append(db == SqlDialect.POSTGRESQL ? "IF NOT EXISTS " : "")
                        .append(index.getIndexName())
                        .append(" ON ").append(tableName)
                        .append("(").append(String.join(", ", index.getColumns())).append(");\n");
            }
        }

        // PostgreSQL：表注释与字段注释使用 COMMENT ON 语句（MySQL已内联在建表语句中）
        if (db == SqlDialect.POSTGRESQL) {
            sb.append(generateTableCommentSql(schema, !hasPrimaryKey));
        }

        return sb.toString();
    }

    /**
     * 构建单个字段的列定义
     *
     * @param field   字段Schema
     * @param dialect SQL方言
     * @return 列定义（含缩进）
     */
    private static String buildColumnDef(FieldSchema field, SqlDialect dialect) {
        StringBuilder col = new StringBuilder();
        boolean primaryKey = Boolean.TRUE.equals(field.getIsPrimaryKey());
        boolean autoIncrement = Boolean.TRUE.equals(field.getIsAutoIncrement());

        col.append("    ").append(field.getColumnName()).append(" ");
        col.append(getSqlType(field, dialect, autoIncrement));
        if (autoIncrement && dialect == SqlDialect.MYSQL) {
            col.append(" AUTO_INCREMENT");
        }
        if (primaryKey) {
            col.append(" PRIMARY KEY");
        } else if (Boolean.TRUE.equals(field.getIsRequired())) {
            col.append(" NOT NULL");
        }
        // 自增列（SERIAL/AUTO_INCREMENT）自带序列默认值，不再追加 DEFAULT
        if (field.getDefaultValue() != null && !(autoIncrement && dialect == SqlDialect.POSTGRESQL)) {
            col.append(" DEFAULT ").append(formatDefaultValue(field.getDefaultValue(), dialect));
        }
        if (dialect == SqlDialect.MYSQL && hasText(field.getComment())) {
            col.append(" COMMENT '").append(escapeSqlLiteral(field.getComment())).append("'");
        }
        return col.toString();
    }

    /**
     * 构建自动补充的 id 主键列定义
     *
     * @param dialect SQL方言
     * @return 列定义（含缩进）
     */
    private static String buildDefaultIdColumnDef(SqlDialect dialect) {
        if (dialect == SqlDialect.MYSQL) {
            return "    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID'";
        }
        return "    id BIGSERIAL PRIMARY KEY";
    }

    /**
     * 格式化DEFAULT子句的默认值
     * <p>
     * 数字原样输出；TRUE/FALSE/NULL/CURRENT_TIMESTAMP等关键字原样输出（MySQL下转为1/0）；
     * 其余按字符串字面量加单引号并转义。
     * </p>
     *
     * @param value   原始默认值
     * @param dialect SQL方言
     * @return 可直接拼在 DEFAULT 之后的内容
     */
    private static String formatDefaultValue(String value, SqlDialect dialect) {
        String v = value.trim();
        if (v.isEmpty()) {
            return "''";
        }
        if (v.matches("-?\\d+(\\.\\d+)?")) {
            return v;
        }
        String upper = v.toUpperCase(Locale.ROOT);
        if ("TRUE".equals(upper) || "FALSE".equals(upper)) {
            return dialect == SqlDialect.MYSQL ? ("TRUE".equals(upper) ? "1" : "0") : upper;
        }
        if ("NULL".equals(upper) || "CURRENT_TIMESTAMP".equals(upper) || "CURRENT_DATE".equals(upper)
                || "CURRENT_TIME".equals(upper) || "NOW()".equals(upper)) {
            return upper;
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v;
        }
        return "'" + escapeSqlLiteral(v) + "'";
    }

    /**
     * 生成表与字段的注释SQL
     * <p>
     * 输出标准SQL的 COMMENT ON 语句：
     * <ul>
     *   <li>COMMENT ON TABLE &lt;table&gt; IS '&lt;tableComment&gt;';</li>
     *   <li>COMMENT ON COLUMN &lt;table&gt;.&lt;column&gt; IS '&lt;comment&gt;';</li>
     * </ul>
     * 注释为空的字段跳过；注释中的单引号按SQL标准转义为两个单引号。
     * </p>
     *
     * @param schema             表Schema定义
     * @param withDefaultIdColumn 是否包含自动补充的 id 主键列注释
     * @return 注释SQL（无注释时返回空串）
     */
    private static String generateTableCommentSql(TableSchema schema, boolean withDefaultIdColumn) {
        StringBuilder sb = new StringBuilder();
        String tableName = schema.getTableName();

        if (!hasText(schema.getTableComment()) && !withDefaultIdColumn
                && schema.getFields().stream().noneMatch(f -> hasText(f.getComment()))) {
            return "";
        }

        sb.append("\n-- 表与字段注释\n");
        if (hasText(schema.getTableComment())) {
            sb.append("COMMENT ON TABLE ").append(tableName).append(" IS '")
                    .append(escapeSqlLiteral(schema.getTableComment())).append("';\n");
        }
        if (withDefaultIdColumn) {
            sb.append("COMMENT ON COLUMN ").append(tableName).append(".id IS '主键ID';\n");
        }
        for (FieldSchema field : schema.getFields()) {
            if (field.getColumnName() == null) {
                continue;
            }
            if (hasText(field.getComment())) {
                sb.append("COMMENT ON COLUMN ").append(tableName).append(".")
                        .append(field.getColumnName()).append(" IS '")
                        .append(escapeSqlLiteral(field.getComment())).append("';\n");
            }
        }

        return sb.toString();
    }

    /**
     * 生成单个字段的注释SQL（注释为空时生成 IS NULL 以清除已有注释）
     *
     * @param tableName 表名
     * @param field     字段Schema
     * @return COMMENT ON COLUMN 语句
     */
    private static String generateColumnCommentSql(String tableName, FieldSchema field) {
        StringBuilder sb = new StringBuilder();
        sb.append("COMMENT ON COLUMN ").append(tableName).append(".").append(field.getColumnName());
        if (hasText(field.getComment())) {
            sb.append(" IS '").append(escapeSqlLiteral(field.getComment())).append("'");
        } else {
            sb.append(" IS NULL");
        }
        return sb.append(";\n").toString();
    }

    /**
     * 判断字符串是否有实际内容
     *
     * @param str 待判断字符串
     * @return 非null且非空白时返回true
     */
    private static boolean hasText(String str) {
        return str != null && !str.isBlank();
    }

    /**
     * 转义SQL字符串字面量中的单引号
     *
     * @param value 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }

    /**
     * 将JDBC类型映射为SQL类型（PostgreSQL方言）
     *
     * @param field 字段Schema
     * @return SQL类型字符串
     */
    private static String getSqlType(FieldSchema field) {
        return getSqlType(field, SqlDialect.POSTGRESQL, Boolean.TRUE.equals(field.getIsAutoIncrement()));
    }

    /**
     * 将JDBC类型映射为指定方言的SQL类型
     * <p>
     * 默认VARCHAR长度为255，DECIMAL/NUMERIC精度为(10,2)。
     * </p>
     *
     * @param field         字段Schema
     * @param dialect       SQL方言
     * @param autoIncrement 是否自增主键（PostgreSQL下映射为 SERIAL / BIGSERIAL）
     * @return SQL类型字符串
     */
    private static String getSqlType(FieldSchema field, SqlDialect dialect, boolean autoIncrement) {
        String type = getSqlType(field, dialect);
        if (autoIncrement && dialect == SqlDialect.POSTGRESQL) {
            return switch (type) {
                case "BIGINT" -> "BIGSERIAL";
                case "INT" -> "SERIAL";
                case "SMALLINT" -> "SMALLSERIAL";
                default -> type;
            };
        }
        return type;
    }

    /**
     * 将JDBC类型映射为指定方言的SQL类型
     *
     * @param field   字段Schema
     * @param dialect SQL方言
     * @return SQL类型字符串
     */
    private static String getSqlType(FieldSchema field, SqlDialect dialect) {
        boolean pg = dialect == SqlDialect.POSTGRESQL;
        String jdbcType = field.getJdbcType() == null
                ? "VARCHAR"
                : field.getJdbcType().trim().toUpperCase(Locale.ROOT);
        int length = field.getLength() != null && field.getLength() > 0 ? field.getLength() : 255;
        return switch (jdbcType) {
            case "BIGINT" -> "BIGINT";
            case "INT", "INTEGER" -> "INT";
            case "TINYINT" -> pg ? "SMALLINT" : "TINYINT";
            case "SMALLINT" -> "SMALLINT";
            case "VARCHAR", "LONGVARCHAR", "CHAR" -> "VARCHAR(" + length + ")";
            case "TEXT", "CLOB" -> "TEXT";
            case "TIMESTAMP", "DATETIME" -> pg ? "TIMESTAMP" : "DATETIME";
            case "DATE" -> "DATE";
            case "TIME" -> "TIME";
            case "BOOLEAN", "BIT" -> pg ? "BOOLEAN" : "TINYINT(1)";
            case "DECIMAL", "NUMERIC" -> pg ? "NUMERIC(10,2)" : "DECIMAL(10,2)";
            case "FLOAT" -> "FLOAT";
            case "DOUBLE" -> pg ? "DOUBLE PRECISION" : "DOUBLE";
            case "VARBINARY" -> pg ? "BYTEA" : "VARBINARY(" + length + ")";
            case "BINARY", "LONGVARBINARY", "BLOB" -> pg ? "BYTEA" : "BLOB";
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
     *   <li>COMMENT ON TABLE / COMMENT ON COLUMN：表注释与字段注释变更（含清空注释 IS NULL）</li>
     * </ul>
     * 如果无变更，返回 "-- 无变更"。
     * </p>
     * <p>
     * 仅改注释时只输出 COMMENT ON 语句，不会触发字段类型变更。
     * </p>
     *
     * @param oldSchema 旧Schema定义
     * @param newSchema 新Schema定义
     * @return 变更SQL语句（PostgreSQL方言）
     */
    public static String generateAlterSql(TableSchema oldSchema, TableSchema newSchema) {
        return generateAlterSql(oldSchema, newSchema, SqlDialect.POSTGRESQL);
    }

    /**
     * 生成表结构变更SQL（指定方言）
     * <p>
     * 方言差异：
     * <ul>
     *   <li>MySQL：改列统一走 MODIFY COLUMN（注释内联在其中）</li>
     *   <li>PostgreSQL：拆分为 ALTER COLUMN TYPE / SET NOT NULL / SET DEFAULT / COMMENT ON COLUMN</li>
     * </ul>
     * </p>
     *
     * @param oldSchema 旧Schema定义
     * @param newSchema 新Schema定义
     * @param dialect   SQL方言，为空时按PostgreSQL处理
     * @return 变更SQL语句
     */
    public static String generateAlterSql(TableSchema oldSchema, TableSchema newSchema, SqlDialect dialect) {
        SqlDialect db = dialect == null ? SqlDialect.POSTGRESQL : dialect;
        StringBuilder sb = new StringBuilder();
        String tableName = newSchema.getTableName();

        List<String> oldColumnNames = oldSchema.getFields().stream()
                .map(FieldSchema::getColumnName)
                .collect(Collectors.toList());
        List<String> newColumnNames = newSchema.getFields().stream()
                .map(FieldSchema::getColumnName)
                .collect(Collectors.toList());

        // 1. 表注释变更
        if (!Objects.equals(blankToNull(oldSchema.getTableComment()), blankToNull(newSchema.getTableComment()))) {
            if (db == SqlDialect.MYSQL) {
                sb.append("ALTER TABLE ").append(tableName).append(" COMMENT = '")
                        .append(hasText(newSchema.getTableComment()) ? escapeSqlLiteral(newSchema.getTableComment()) : "")
                        .append("';\n");
            } else {
                sb.append("COMMENT ON TABLE ").append(tableName);
                if (hasText(newSchema.getTableComment())) {
                    sb.append(" IS '").append(escapeSqlLiteral(newSchema.getTableComment())).append("'");
                } else {
                    sb.append(" IS NULL");
                }
                sb.append(";\n");
            }
        }

        // 2. 新增字段
        for (FieldSchema newField : newSchema.getFields()) {
            if (!oldColumnNames.contains(newField.getColumnName())) {
                sb.append("ALTER TABLE ").append(tableName)
                        .append(" ADD COLUMN ").append(buildColumnDef(newField, db).trim())
                        .append(";\n");
                if (db == SqlDialect.POSTGRESQL && hasText(newField.getComment())) {
                    sb.append(generateColumnCommentSql(tableName, newField));
                }
            }
        }

        // 3. 删除字段
        for (FieldSchema oldField : oldSchema.getFields()) {
            if (!newColumnNames.contains(oldField.getColumnName())) {
                sb.append("ALTER TABLE ").append(tableName)
                        .append(" DROP COLUMN ").append(oldField.getColumnName())
                        .append(";\n");
            }
        }

        // 4. 修改字段
        for (FieldSchema newField : newSchema.getFields()) {
            FieldSchema oldField = oldSchema.getFields().stream()
                    .filter(f -> f.getColumnName().equals(newField.getColumnName()))
                    .findFirst()
                    .orElse(null);
            if (oldField == null || fieldsEqual(oldField, newField)) {
                continue;
            }
            boolean typeChanged = !typeEqual(oldField, newField);
            boolean requiredChanged = !Objects.equals(
                    Boolean.TRUE.equals(oldField.getIsRequired()), Boolean.TRUE.equals(newField.getIsRequired()));
            boolean defaultChanged = !Objects.equals(
                    blankToNull(oldField.getDefaultValue()), blankToNull(newField.getDefaultValue()));
            boolean commentChanged = !Objects.equals(
                    blankToNull(oldField.getComment()), blankToNull(newField.getComment()));

            if (db == SqlDialect.MYSQL) {
                // MySQL：注释内联在列定义中，任何变化都用 MODIFY COLUMN 重定义
                sb.append("ALTER TABLE ").append(tableName)
                        .append(" MODIFY COLUMN ").append(buildColumnDef(newField, db).trim())
                        .append(";\n");
                continue;
            }
            if (typeChanged) {
                sb.append("ALTER TABLE ").append(tableName)
                        .append(" ALTER COLUMN ").append(newField.getColumnName())
                        .append(" TYPE ")
                        .append(getSqlType(newField, db, Boolean.TRUE.equals(newField.getIsAutoIncrement())))
                        .append(";\n");
            }
            if (requiredChanged) {
                sb.append("ALTER TABLE ").append(tableName)
                        .append(" ALTER COLUMN ").append(newField.getColumnName())
                        .append(Boolean.TRUE.equals(newField.getIsRequired()) ? " SET NOT NULL" : " DROP NOT NULL")
                        .append(";\n");
            }
            if (defaultChanged) {
                sb.append("ALTER TABLE ").append(tableName)
                        .append(" ALTER COLUMN ").append(newField.getColumnName());
                if (hasText(newField.getDefaultValue())) {
                    sb.append(" SET DEFAULT ").append(formatDefaultValue(newField.getDefaultValue(), db));
                } else {
                    sb.append(" DROP DEFAULT");
                }
                sb.append(";\n");
            }
            if (commentChanged) {
                sb.append(generateColumnCommentSql(tableName, newField));
            }
        }

        return sb.length() > 0 ? sb.toString() : "-- 无变更";
    }

    /**
     * 空白字符串转null（用于比较时忽略空白差异）
     *
     * @param str 原始字符串
     * @return 空白串返回null，否则返回原字符串
     */
    private static String blankToNull(String str) {
        return str == null || str.isBlank() ? null : str;
    }

    /**
     * 比较两个字段的完整定义（类型/必填/默认值/注释）是否相等
     *
     * @param oldField 旧字段
     * @param newField 新字段
     * @return 是否相等
     */
    private static boolean fieldsEqual(FieldSchema oldField, FieldSchema newField) {
        return typeEqual(oldField, newField)
                && Objects.equals(Boolean.TRUE.equals(oldField.getIsRequired()), Boolean.TRUE.equals(newField.getIsRequired()))
                && Objects.equals(blankToNull(oldField.getDefaultValue()), blankToNull(newField.getDefaultValue()))
                && Objects.equals(blankToNull(oldField.getComment()), blankToNull(newField.getComment()));
    }

    /**
     * 比较两个字段的SQL类型（JDBC类型 + 长度）是否相等
     * <p>
     * 不比较主键、自增、Java类型等不影响列定义的属性。
     * </p>
     *
     * @param oldField 旧字段
     * @param newField 新字段
     * @return 是否相等
     */
    private static boolean typeEqual(FieldSchema oldField, FieldSchema newField) {
        return Objects.equals(oldField.getJdbcType(), newField.getJdbcType())
                && Objects.equals(oldField.getLength(), newField.getLength());
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
