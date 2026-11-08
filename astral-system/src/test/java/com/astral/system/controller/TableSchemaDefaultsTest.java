package com.astral.system.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.schema.SchemaRegistry;
import com.astral.system.dto.CreateTableRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class TableSchemaDefaultsTest {
    @TempDir
    Path directory;

    @Test
    void generatedBusinessTableUsesGlobalSequenceRatherThanDatabaseIdentity() throws Exception {
        Path output = directory.resolve("process.log");
        Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        TableSchemaDefaultsTest.class.getName())
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), Files.readString(output));
            var schema = new ObjectMapper()
                    .readTree(
                            directory.resolve("data/schemas/audit_fixture.json").toFile());
            var id = schema.get("fields").get(0);
            assertEquals("INPUT", id.get("mybatisPlusIdType").asString());
            assertFalse(id.get("isAutoIncrement").asBoolean());
            assertTrue(Files.readString(directory.resolve("entity.txt")).contains("IdType.INPUT"));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        SchemaRegistry.init();
        TableSchemaController controller = new TableSchemaController();
        CreateTableRequest request = new CreateTableRequest();
        request.setTableName("audit_fixture");
        request.setIncludeCommonFields(true);
        controller.createSchema(request);
        Files.writeString(
                Path.of("entity.txt"),
                controller.generateEntity("audit_fixture").getData());
    }
}
