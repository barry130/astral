package com.astral.schema.codegen;

import com.astral.schema.SchemaCodeGenerator;
import com.astral.schema.TableSchema;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
 *   <li>skip：是否跳过代码生成，默认 false（-DskipCodeGen=true 时保留既有实体）</li>
 * </ul>
 * </p>
 * <p>生成失败必须让构建失败：坏 JSON、空目录、非法类名或写文件失败都不能只打日志后继续。</p>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class CodeGenMojo extends AbstractMojo {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

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

    public CodeGenMojo() {}

    CodeGenMojo(File outputDirectory, File schemaDirectory) {
        this(outputDirectory, schemaDirectory, false);
    }

    CodeGenMojo(File outputDirectory, File schemaDirectory, boolean skip) {
        this.outputDirectory = outputDirectory;
        this.schemaDirectory = schemaDirectory;
        this.skip = skip;
    }

    /**
     * 执行代码生成
     * <p>
     * 执行流程：
     * 1. 检查是否跳过
     * 2. 加载并校验全部Schema文件（任何坏文件立即失败）
     * 3. 生成全部Entity代码并校验输出路径
     * 4. 全部成功后仅写入内容发生变化的文件（避免不必要的编译触发）
     * </p>
     *
     * @throws MojoExecutionException 执行失败时抛出
     */
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Code generation skipped.");
            return;
        }
        if (outputDirectory == null) {
            throw new MojoExecutionException("Code generation failed: outputDirectory is not configured");
        }

        List<TableSchema> schemas = loadSchemas();
        getLog().info("Found " + schemas.size() + " schemas, generating Entity classes...");

        Path baseDir = outputDirectory.toPath().toAbsolutePath().normalize();
        Map<Path, String> outputs = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (TableSchema schema : schemas) {
            try {
                String entityCode = SchemaCodeGenerator.generateEntity(schema);
                Path entityPath = resolveEntityPath(baseDir, schema);
                if (outputs.putIfAbsent(entityPath, entityCode) != null) {
                    throw new IllegalArgumentException("Entity 输出路径重复: " + entityPath);
                }
            } catch (Exception e) {
                String table = schema == null ? "<null>" : schema.getTableName();
                failures.add(table + ": " + e.getMessage());
                getLog().error("  Failed to generate Entity for " + table + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new MojoExecutionException(
                    "Entity generation failed (" + failures.size() + "): " + String.join("; ", failures));
        }

        for (Map.Entry<Path, String> entry : outputs.entrySet()) {
            try {
                writeIfChanged(entry.getKey(), entry.getValue());
                getLog().info("  Generated Entity: " + entry.getKey().getFileName());
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to write " + entry.getKey() + ": " + e.getMessage(), e);
            }
        }
        getLog().info("Entity generation complete: " + outputs.size() + " files.");
    }

    /**
     * 加载所有Schema文件；缺失目录、空目录、坏 JSON、非法类名均直接失败。
     */
    private List<TableSchema> loadSchemas() throws MojoExecutionException {
        if (schemaDirectory == null || !schemaDirectory.isDirectory()) {
            throw new MojoExecutionException("Schema directory not found: " + schemaDirectory);
        }
        Path dir = schemaDirectory.toPath();
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to list schema directory " + dir + ": " + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            throw new MojoExecutionException("No schema JSON files found in " + dir);
        }

        List<TableSchema> schemas = new ArrayList<>();
        for (Path path : files) {
            TableSchema schema;
            try {
                schema = mapper.readValue(path.toFile(), TableSchema.class);
            } catch (JacksonException e) {
                throw new MojoExecutionException("Invalid schema JSON " + path + ": " + e.getMessage(), e);
            }
            validateSchemaIdentity(schema, path);
            schemas.add(schema);
        }
        return schemas;
    }

    private void validateSchemaIdentity(TableSchema schema, Path source) throws MojoExecutionException {
        if (schema == null) {
            throw new MojoExecutionException("Schema is null: " + source);
        }
        if (schema.getTableName() == null || schema.getTableName().isBlank()) {
            throw new MojoExecutionException("Schema tableName is blank: " + source);
        }
        if (schema.getClassName() == null
                || !CLASS_NAME_PATTERN.matcher(schema.getClassName()).matches()) {
            throw new MojoExecutionException(
                    "Schema className is invalid: " + schema.getClassName() + " (" + source + ")");
        }
        if (!PACKAGE_PATTERN.matcher(schema.getPackageName()).matches()) {
            throw new MojoExecutionException(
                    "Schema packageName is invalid: " + schema.getPackageName() + " (" + source + ")");
        }
    }

    /** 生成输出路径，并强制其位于配置的输出根目录内（禁止 ../ 或绝对路径穿越）。 */
    private Path resolveEntityPath(Path baseDir, TableSchema schema) {
        String packageName = schema.getPackageName();
        if (!PACKAGE_PATTERN.matcher(packageName).matches()) {
            throw new IllegalArgumentException("非法包名: " + packageName);
        }
        String className = schema.getClassName();
        if (className == null || !CLASS_NAME_PATTERN.matcher(className).matches()) {
            throw new IllegalArgumentException("非法类名: " + className);
        }
        Path relative = Path.of(packageName.replace('.', File.separatorChar), className + ".java");
        Path target = baseDir.resolve(relative).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("Entity 输出路径越界: " + target);
        }
        return target;
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
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(path) || !Files.readString(path).equals(content)) {
            Files.writeString(path, content);
        }
    }
}
