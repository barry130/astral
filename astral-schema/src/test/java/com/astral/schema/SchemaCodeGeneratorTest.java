package com.astral.schema;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SchemaCodeGenerator 索引差异、默认值与注释字面量回归（MySQL / PostgreSQL 双语义）。 */
class SchemaCodeGeneratorTest {
    @Test
    void generatedDecimalFieldHasItsJavaImport() {
        TableSchema schema = table();
        schema.getFields().get(1).setFieldType("BigDecimal");
        assertTrue(SchemaCodeGenerator.generateEntity(schema).contains("import java.math.BigDecimal;"));
    }

    @Test
    void createSqlQuotesIndexNamesAndColumnsForBothDialects() {
        IndexSchema index = index(null, true, "name");
        TableSchema schema = table(index);

        String pg = SchemaCodeGenerator.generateCreateSql(schema, SqlDialect.POSTGRESQL);
        assertTrue(
                pg.contains("CREATE UNIQUE INDEX IF NOT EXISTS \"uk_demo_user_name\" ON \"demo_user\"(\"name\");"), pg);

        String mysql = SchemaCodeGenerator.generateCreateSql(schema, SqlDialect.MYSQL);
        assertTrue(mysql.contains("CREATE UNIQUE INDEX `uk_demo_user_name` ON `demo_user`(`name`);"), mysql);
    }

    @Test
    void alterSqlAddsIndex() {
        TableSchema oldSchema = table();
        TableSchema newSchema = table(index("idx_demo_user_email", false, "email"));

        String pg = SchemaCodeGenerator.generateAlterSql(oldSchema, newSchema, SqlDialect.POSTGRESQL);
        assertTrue(pg.contains("CREATE INDEX IF NOT EXISTS \"idx_demo_user_email\" ON \"demo_user\"(\"email\");"), pg);
        String mysql = SchemaCodeGenerator.generateAlterSql(oldSchema, newSchema, SqlDialect.MYSQL);
        assertTrue(mysql.contains("CREATE INDEX `idx_demo_user_email` ON `demo_user`(`email`);"), mysql);
    }

    @Test
    void alterSqlDropsUnnamedIndexUsingTableScopedAutoName() {
        TableSchema oldSchema = table(index(null, false, "email"));
        TableSchema newSchema = table();

        String pg = SchemaCodeGenerator.generateAlterSql(oldSchema, newSchema, SqlDialect.POSTGRESQL);
        assertTrue(pg.contains("DROP INDEX IF EXISTS \"idx_demo_user_email\";"), pg);

        String mysql = SchemaCodeGenerator.generateAlterSql(oldSchema, newSchema, SqlDialect.MYSQL);
        assertTrue(mysql.contains("DROP INDEX `idx_demo_user_email` ON `demo_user`;"), mysql);
    }

    @Test
    void alterSqlRebuildsIndexWhenUniquenessOrColumnOrderChanges() {
        TableSchema oldSchema = table(index("idx_demo_user_email", false, "email"));
        TableSchema uniqueSchema = table(index("idx_demo_user_email", true, "email"));
        String uniqueSql = SchemaCodeGenerator.generateAlterSql(oldSchema, uniqueSchema, SqlDialect.POSTGRESQL);
        assertTrue(uniqueSql.contains("DROP INDEX IF EXISTS \"idx_demo_user_email\";"), uniqueSql);
        assertTrue(uniqueSql.contains("CREATE UNIQUE INDEX IF NOT EXISTS \"idx_demo_user_email\""), uniqueSql);
        String uniqueMysql = SchemaCodeGenerator.generateAlterSql(oldSchema, uniqueSchema, SqlDialect.MYSQL);
        assertTrue(uniqueMysql.contains("DROP INDEX `idx_demo_user_email` ON `demo_user`;"), uniqueMysql);
        assertTrue(
                uniqueMysql.contains("CREATE UNIQUE INDEX `idx_demo_user_email` ON `demo_user`(`email`);"),
                uniqueMysql);

        TableSchema ordered = table(index("idx_demo_user_name_email", false, "name", "email"));
        TableSchema reordered = table(index("idx_demo_user_name_email", false, "email", "name"));
        String orderSql = SchemaCodeGenerator.generateAlterSql(ordered, reordered, SqlDialect.POSTGRESQL);
        assertTrue(orderSql.contains("DROP INDEX IF EXISTS \"idx_demo_user_name_email\";"), orderSql);
        assertTrue(orderSql.contains("ON \"demo_user\"(\"email\", \"name\");"), orderSql);
        String orderMysql = SchemaCodeGenerator.generateAlterSql(ordered, reordered, SqlDialect.MYSQL);
        assertTrue(orderMysql.contains("DROP INDEX `idx_demo_user_name_email` ON `demo_user`;"), orderMysql);
        assertTrue(orderMysql.contains("ON `demo_user`(`email`, `name`);"), orderMysql);
    }

    @Test
    void defaultValuesAndCommentsAreEscaped() {
        TableSchema schema = table();
        FieldSchema name = schema.getFields().get(1);
        name.setDefaultValue("O'Reilly");
        name.setComment("it's the user's name");

        String pg = SchemaCodeGenerator.generateCreateSql(schema, SqlDialect.POSTGRESQL);
        assertTrue(pg.contains("DEFAULT 'O''Reilly'"), pg);
        assertTrue(pg.contains("COMMENT ON COLUMN \"demo_user\".\"name\" IS 'it''s the user''s name';"), pg);

        TableSchema oldSchema = table();
        String alter = SchemaCodeGenerator.generateAlterSql(oldSchema, schema, SqlDialect.POSTGRESQL);
        assertTrue(alter.contains("SET DEFAULT 'O''Reilly'"), alter);
        assertTrue(alter.contains("IS 'it''s the user''s name'"), alter);

        // JSON 里保留外层单引号的默认值必须还原 '' 转义后再统一转义，不能原样拼回 SQL。
        name.setDefaultValue("'O''Reilly'");
        String quoted = SchemaCodeGenerator.generateCreateSql(schema, SqlDialect.POSTGRESQL);
        assertTrue(quoted.contains("DEFAULT 'O''Reilly'"), quoted);
        String quotedMysql = SchemaCodeGenerator.generateCreateSql(schema, SqlDialect.MYSQL);
        assertTrue(quotedMysql.contains("DEFAULT 'O''Reilly'"), quotedMysql);

        // 带引号的恶意默认值只能作为字符串字面量，不能形成第二条 DDL 语句。
        name.setDefaultValue("'x'; DROP TABLE demo_user; --'");
        String injected = SchemaCodeGenerator.generateCreateSql(schema, SqlDialect.POSTGRESQL);
        assertTrue(injected.contains("DEFAULT 'x''; DROP TABLE demo_user; --'"), injected);
    }

    @Test
    void alterSqlKeepsKeywordDefaultUntouchedAndRejectsUnknownIndexColumn() {
        TableSchema schema = table();
        schema.getFields().get(1).setDefaultValue("CURRENT_TIMESTAMP");
        String pg = SchemaCodeGenerator.generateCreateSql(schema, SqlDialect.POSTGRESQL);
        assertTrue(pg.contains("DEFAULT CURRENT_TIMESTAMP"), pg);

        TableSchema bad = table(index("idx_demo_user_missing", false, "missing_column"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaCodeGenerator.generateCreateSql(bad, SqlDialect.POSTGRESQL));
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaCodeGenerator.generateCreateSql(table(index("bad-name", false, "name")), SqlDialect.MYSQL));
    }

    @Test
    void rejectsMaliciousIdentifiersBeforeBuildingDdl() {
        TableSchema badTable = table();
        badTable.setTableName("demo_user; DROP TABLE sys_user; --");
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaCodeGenerator.generateCreateSql(badTable, SqlDialect.POSTGRESQL));

        TableSchema badColumn = table();
        badColumn.getFields().get(1).setColumnName("name\"; DROP TABLE demo_user; --");
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaCodeGenerator.generateCreateSql(badColumn, SqlDialect.MYSQL));

        TableSchema badIndex = table(index("idx_demo_user_name", false, "name"));
        badIndex.getIndexes().get(0).setIndexName("idx_demo_user_name; DROP TABLE demo_user; --");
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaCodeGenerator.generateCreateSql(badIndex, SqlDialect.POSTGRESQL));
    }

    @Test
    void autoIndexNameTruncationStaysWithinIdentifierWhitelist() {
        // 超长索引名截断后追加 hashCode；hash 为负时不能生成带 '-' 的非法标识符。
        TableSchema schema = table();
        schema.setTableName("a".repeat(60));
        schema.getFields().add(field("c", "VARCHAR", 8));
        schema.setIndexes(List.of(index(null, false, "c")));

        String pg = SchemaCodeGenerator.generateCreateSql(schema, SqlDialect.POSTGRESQL);
        assertTrue(pg.contains("\"idx_" + "a".repeat(47) + "_d2bc0966\""), pg);
    }

    @Test
    void generatedEntityImportsAreSortedAndFreeOfStarImports() {
        TableSchema schema = table();
        schema.setClassName("DemoUser");
        schema.getFields().get(1).setIsRequired(true);

        String entity = SchemaCodeGenerator.generateEntity(schema);
        String[] expectedImports = {
            "import com.baomidou.mybatisplus.annotation.IdType;",
            "import com.baomidou.mybatisplus.annotation.TableId;",
            "import com.baomidou.mybatisplus.annotation.TableName;",
            "import jakarta.validation.constraints.NotNull;",
            "import jakarta.validation.constraints.Size;",
            "import lombok.Data;"
        };
        int previous = -1;
        for (String expected : expectedImports) {
            int current = entity.indexOf(expected);
            assertTrue(current > previous, "import 缺失或顺序错误: " + expected + "\n" + entity);
            previous = current;
        }
        assertTrue(entity.indexOf("@Data") > previous, entity);
        assertTrue(!entity.contains("import com.baomidou.mybatisplus.annotation.*;"), entity);
    }

    private static TableSchema table(IndexSchema... indexes) {
        TableSchema schema = new TableSchema();
        schema.setTableName("demo_user");
        schema.setTableComment("demo table");
        List<FieldSchema> fields = new ArrayList<>();
        FieldSchema id = field("id", "BIGINT", null);
        id.setIsPrimaryKey(true);
        id.setIsAutoIncrement(true);
        fields.add(id);
        fields.add(field("name", "VARCHAR", 32));
        fields.add(field("email", "VARCHAR", 128));
        schema.setFields(fields);
        schema.setIndexes(List.of(indexes));
        return schema;
    }

    private static FieldSchema field(String column, String jdbcType, Integer length) {
        FieldSchema field = new FieldSchema();
        field.setColumnName(column);
        field.setFieldName(column);
        field.setJdbcType(jdbcType);
        field.setLength(length);
        return field;
    }

    private static IndexSchema index(String name, boolean unique, String... columns) {
        IndexSchema index = new IndexSchema();
        index.setIndexName(name);
        index.setUnique(unique);
        index.setColumns(List.of(columns));
        return index;
    }
}
