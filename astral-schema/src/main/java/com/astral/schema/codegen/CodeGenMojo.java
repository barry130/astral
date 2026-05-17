package com.astral.schema.codegen;

import com.astral.schema.SchemaCodeGenerator;
import com.astral.schema.TableSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven代码生成插件
 * <p>
 * 自定义Maven Mojo，在构建时根据JSON Schema文件自动生成Entity类。
 * 绑定到 Maven 的 generate-sources 阶段，可通过以下命令触发：
 * </p>
 * <pre>
 * mvn generate-sources
 * </pre>
 * <p>
 * 支持参数配置：
 * <ul>
 *   <li>outputDirectory：代码输出目录，默认 ${project.basedir}/src/main/java</li>
 *   <li>schemaDirectory：Schema文件目录，默认 ../astral-schema/src/main/resources/schema</li>
 *   <li>skip：是否跳过代码生成，默认 false</li>
 * </ul>
 * </p>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class CodeGenMojo extends AbstractMojo {

    /** 代码输出目录 */
    @Parameter(defaultValue = "${project.basedir}/src/main/java", property = "outputDir", required = true)
    private File outputDirectory;

    /** Schema文件所在目录 */
    @Parameter(defaultValue = "${project.basedir}/../astral-schema/src/main/resources/schema", property = "schemaDir")
    private File schemaDirectory;

    /** 是否跳过代码生成 */
    @Parameter(defaultValue = "false", property = "skipCodeGen")
    private boolean skip;

    /** JSON序列化器 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 执行代码生成
     * <p>
     * 执行流程：
     * 1. 检查是否跳过
     * 2. 加载所有Schema文件
     * 3. 为每个Schema生成Entity代码
     * 4. 仅当文件内容发生变化时才写入（避免不必要的编译触发）
     * </p>
     *
     * @throws MojoExecutionException 执行失败时抛出
     */
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Code generation skipped.");
            return;
        }

        mapper.registerModule(new JavaTimeModule());

        List<TableSchema> schemas = loadSchemas();
        if (schemas.isEmpty()) {
            getLog().warn("No schemas found, skipping code generation.");
            return;
        }

        getLog().info("Found " + schemas.size() + " schemas, generating Entity classes...");

        Path baseDir = outputDirectory.toPath();

        for (TableSchema schema : schemas) {
            try {
                String entityCode = SchemaCodeGenerator.generateEntity(schema);
                Path entityPath = baseDir.resolve(schema.getPackageName().replace('.', '/') + "/" + schema.getClassName() + ".java");
                writeIfChanged(entityPath, entityCode);
                getLog().info("  Generated Entity: " + schema.getClassName());
            } catch (Exception e) {
                getLog().error("  Failed to generate Entity for " + schema.getTableName() + ": " + e.getMessage());
            }
        }

        getLog().info("Entity generation complete.");
    }

    /**
     * 加载所有Schema文件
     * <p>
     * 扫描Schema目录下所有.json文件，解析为TableSchema对象列表。
     * 解析失败的文件会被跳过并记录警告日志。
     * </p>
     *
     * @return Schema列表
     */
    private List<TableSchema> loadSchemas() {
        List<TableSchema> schemas = new ArrayList<>();

        if (schemaDirectory != null && schemaDirectory.exists()) {
            try {
                Files.list(schemaDirectory.toPath())
                        .filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> {
                            try {
                                TableSchema schema = mapper.readValue(p.toFile(), TableSchema.class);
                                schemas.add(schema);
                            } catch (IOException e) {
                                getLog().warn("Failed to load schema from " + p + ": " + e.getMessage());
                            }
                        });
            } catch (IOException e) {
                getLog().warn("Failed to list schema directory: " + e.getMessage());
            }
        }

        return schemas;
    }

    /**
     * 仅在内容发生变化时写入文件
     * <p>
     * 比较新内容与现有文件内容，只有不同时才写入。
     * 这样可以避免不必要的文件时间戳更新，减少Maven增量编译的触发。
     * </p>
     *
     * @param path    文件路径
     * @param content 新内容
     * @throws IOException IO异常
     */
    private void writeIfChanged(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path) || !Files.readString(path).equals(content)) {
            Files.writeString(path, content);
        }
    }
}
