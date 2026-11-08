package com.astral.schema.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeGenMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesEntityInConfiguredOutput() throws Exception {
        Path schemaDir = Files.createDirectory(tempDir.resolve("schema"));
        Path outputDir = Files.createDirectory(tempDir.resolve("out"));
        Files.writeString(schemaDir.resolve("demo_user.json"), schemaJson("DemoUser"));

        new CodeGenMojo(outputDir.toFile(), schemaDir.toFile()).execute();

        Path entity = outputDir.resolve("com/astral/dao/entity/DemoUser.java");
        assertTrue(Files.exists(entity));
        assertTrue(Files.readString(entity).contains("public class DemoUser"));
    }

    @Test
    void invalidJsonFailsBuildInsteadOfSkipping() throws Exception {
        Path schemaDir = Files.createDirectory(tempDir.resolve("schema"));
        Path outputDir = Files.createDirectory(tempDir.resolve("out"));
        Files.writeString(schemaDir.resolve("broken.json"), "{not-json");

        assertThrows(
                MojoExecutionException.class, () -> new CodeGenMojo(outputDir.toFile(), schemaDir.toFile()).execute());
    }

    @Test
    void emptySchemaDirectoryFailsBuild() throws Exception {
        Path schemaDir = Files.createDirectory(tempDir.resolve("schema"));
        Path outputDir = Files.createDirectory(tempDir.resolve("out"));

        assertThrows(
                MojoExecutionException.class, () -> new CodeGenMojo(outputDir.toFile(), schemaDir.toFile()).execute());
    }

    @Test
    void classPathTraversalIsRejectedBeforeWriting() throws Exception {
        Path schemaDir = Files.createDirectory(tempDir.resolve("schema"));
        Path outputDir = Files.createDirectory(tempDir.resolve("out"));
        Files.writeString(schemaDir.resolve("evil.json"), schemaJson("../../Evil"));

        assertThrows(
                MojoExecutionException.class, () -> new CodeGenMojo(outputDir.toFile(), schemaDir.toFile()).execute());
        assertFalse(Files.exists(tempDir.resolve("Evil.java")));
        assertFalse(Files.exists(outputDir.resolve("Evil.java")));
    }

    @Test
    void skipFlagDoesNotRequireSchemaDirectoryOrOutputDirectory() throws Exception {
        // 聚焦测试/局部构建通过 -DskipCodeGen=true 跳过生成时，缺失目录不应让构建失败。
        new CodeGenMojo(
                        tempDir.resolve("missing-out").toFile(),
                        tempDir.resolve("missing-schema").toFile(),
                        true)
                .execute();

        assertFalse(Files.exists(tempDir.resolve("missing-out")));
    }

    @Test
    void invalidSchemaAnywhereFailsBeforeAnyEntityIsWritten() throws Exception {
        Path schemaDir = Files.createDirectory(tempDir.resolve("schema"));
        Path outputDir = Files.createDirectory(tempDir.resolve("out"));
        Files.writeString(schemaDir.resolve("a_valid.json"), schemaJson("ValidUser"));
        Files.writeString(schemaDir.resolve("z_broken.json"), "{not-json");

        assertThrows(
                MojoExecutionException.class, () -> new CodeGenMojo(outputDir.toFile(), schemaDir.toFile()).execute());
        // 两阶段输出：全部 schema 解析/生成成功前不得写入任何实体，避免半成品源码。
        assertFalse(Files.exists(outputDir.resolve("com/astral/dao/entity/ValidUser.java")));
    }

    @Test
    void duplicateClassNameFailsBeforeAnyEntityIsWritten() throws Exception {
        Path schemaDir = Files.createDirectory(tempDir.resolve("schema"));
        Path outputDir = Files.createDirectory(tempDir.resolve("out"));
        Files.writeString(schemaDir.resolve("a.json"), schemaJson("DuplicateUser"));
        Files.writeString(schemaDir.resolve("b.json"), schemaJson("DuplicateUser"));

        assertThrows(
                MojoExecutionException.class, () -> new CodeGenMojo(outputDir.toFile(), schemaDir.toFile()).execute());
        assertFalse(Files.exists(outputDir.resolve("com/astral/dao/entity/DuplicateUser.java")));
    }

    private static String schemaJson(String className) {
        return """
                {
                  "tableName": "demo_user",
                  "tableComment": "demo table",
                  "moduleName": "demo",
                  "className": "%s",
                  "fields": [
                    {
                      "columnName": "id",
                      "fieldName": "id",
                      "fieldType": "Long",
                      "jdbcType": "BIGINT",
                      "isPrimaryKey": true,
                      "isAutoIncrement": true,
                      "mybatisPlusIdType": "INPUT"
                    },
                    {
                      "columnName": "name",
                      "fieldName": "name",
                      "fieldType": "String",
                      "jdbcType": "VARCHAR",
                      "length": 32
                    }
                  ]
                }
                """.formatted(className);
    }
}
