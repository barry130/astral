package com.astral.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Schema到Entity的自动同步器
 * <p>
 * 在应用启动时，根据 astral-schema 模块中的 JSON Schema 文件自动生成/更新
 * astral-dao 模块中的 Entity 类文件。同时会删除没有对应Schema的Entity文件，
 * 保持Entity与Schema的一致性。
 * </p>
 * <p>
 * 同步策略：
 * <ul>
 *   <li>如果Entity文件不存在或内容发生变化，则重新生成</li>
 *   <li>如果Entity文件没有对应的Schema定义，则删除该Entity文件</li>
 * </ul>
 * </p>
 */
public class SchemaEntitySync {
    private static final Logger log = LoggerFactory.getLogger(SchemaEntitySync.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.registerModule(new JavaTimeModule());
    }

    /**
     * 启动时同步Entity
     * <p>
     * 执行流程：
     * 1. 自动查找项目根目录（向上最多4级）
     * 2. 读取 astral-schema/src/main/resources/schema/ 下的所有JSON文件
     * 3. 为每个Schema生成Entity代码，与现有文件比较后决定是否更新
     * 4. 删除没有对应Schema的Entity文件
     * </p>
     */
    public static void syncOnStartup() {
        try {
            String projectDir = findProjectRoot();
            if (projectDir == null) {
                log.warn("Cannot find project root, skipping entity sync");
                return;
            }
            Path schemaDir = Paths.get(projectDir, "astral-schema", "src", "main", "resources", "schema");
            Path entityDir = Paths.get(projectDir, "astral-dao", "src", "main", "java", "com", "astral", "dao", "entity");

            if (!Files.exists(schemaDir)) {
                log.warn("Schema directory not found: {}", schemaDir);
                return;
            }

            Files.createDirectories(entityDir);
            
            Set<String> expectedEntities = new HashSet<>();

            try (Stream<Path> schemaPaths = Files.list(schemaDir)) {
                schemaPaths.filter(p -> p.toString().endsWith(".json")).forEach(schemaPath -> {
                    try {
                        TableSchema schema = MAPPER.readValue(schemaPath.toFile(), TableSchema.class);
                        String entityCode = SchemaCodeGenerator.generateEntity(schema);
                        String entityFileName = schema.getClassName() + ".java";
                        Path entityPath = entityDir.resolve(entityFileName);
                        expectedEntities.add(entityFileName);

                        if (!Files.exists(entityPath) || !Files.readString(entityPath).equals(entityCode)) {
                            Files.writeString(entityPath, entityCode);
                            log.info("Entity updated/created: {}", entityFileName);
                        }
                    } catch (IOException e) {
                        log.error("Failed to process schema: {}", schemaPath, e);
                    }
                });
            }

            try (Stream<Path> entityPaths = Files.list(entityDir)) {
                entityPaths.filter(p -> p.toString().endsWith(".java")).forEach(entityPath -> {
                    try {
                        if (!expectedEntities.contains(entityPath.getFileName().toString())) {
                            Files.delete(entityPath);
                            log.info("Entity deleted (no matching schema): {}", entityPath.getFileName());
                        }
                    } catch (IOException e) {
                        log.error("Failed to delete entity: {}", entityPath, e);
                    }
                });
            }

        } catch (IOException e) {
            log.error("Failed to sync entities on startup", e);
        }
    }

    /**
     * 自动查找项目根目录
     * <p>
     * 从当前工作目录开始，向上最多查找4级，直到找到包含
     * astral-schema/src/main/resources/schema 目录的路径。
     * </p>
     *
     * @return 项目根目录路径，找不到时返回null
     */
    private static String findProjectRoot() {
        Path current = Paths.get(System.getProperty("user.dir"));
        for (int i = 0; i < 4; i++) {
            Path schemaDir = current.resolve("astral-schema").resolve("src").resolve("main").resolve("resources").resolve("schema");
            if (Files.exists(schemaDir)) {
                log.info("Found project root at: {}", current);
                return current.toString();
            }
            current = current.getParent();
            if (current == null) break;
        }
        log.warn("Project root not found starting from: {}", System.getProperty("user.dir"));
        return null;
    }
}
