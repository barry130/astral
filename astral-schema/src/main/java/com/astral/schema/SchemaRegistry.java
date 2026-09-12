package com.astral.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Schema注册中心
 * <p>
 * 负责加载、缓存和管理所有表的Schema定义。支持两种加载方式：
 * <ul>
 *   <li>内置Schema：从classpath的 schema/ 目录加载</li>
 *   <li>外部Schema：从 data/schemas/ 目录加载（支持运行时动态添加）</li>
 * </ul>
 * </p>
 * <p>
 * Schema数据以ConcurrentHashMap缓存，线程安全。提供按表名查询、按模块查询、
 * 新增、更新、删除等CRUD操作。
 * </p>
 */
public class SchemaRegistry {
    private static final Logger log = LoggerFactory.getLogger(SchemaRegistry.class);
    /** classpath中Schema文件的目录路径 */
    private static final String SCHEMA_PATH = "schema/";
    /** Schema缓存，以表名为key，线程安全 */
    private static final Map<String, TableSchema> SCHEMA_CACHE = new ConcurrentHashMap<>();
    /** JSON序列化器，启用格式化输出 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 外部Schema文件存储目录 */
    private static Path externalSchemaDir;

    static {
        MAPPER.registerModule(new JavaTimeModule());
        MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 初始化Schema注册中心
     * <p>
     * 执行流程：
     * 1. 创建外部Schema目录（data/schemas/）
     * 2. 从classpath加载内置Schema（支持jar与目录中的 schema/ 资源）
     * 3. 从外部目录加载动态Schema
     * </p>
     *
     * @throws RuntimeException 初始化失败时抛出
     */
    public static void init() {
        try {
            externalSchemaDir = Paths.get("data", "schemas");
            Files.createDirectories(externalSchemaDir);

            loadClasspathSchemas();
            loadExternalSchemas();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize SchemaRegistry", e);
        }
    }

    /**
     * 从classpath加载所有内置Schema
     * <p>
     * 通过枚举 classpath 上所有 {@code schema/*.json} 资源加载（兼容 jar 与目录打包形式），
     * 不依赖目录列表（directory listing）能力，可稳定运行于可执行 jar 场景。
     * </p>
     */
    private static void loadClasspathSchemas() {
        try {
            Enumeration<URL> urls = SchemaRegistry.class.getClassLoader().getResources(SCHEMA_PATH);
            Set<String> seenJars = new HashSet<>();
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    // 目录形式（IDE / exploded 模式）：枚举目录下的 .json 文件
                    try {
                        Files.list(Paths.get(url.toURI()))
                                .filter(p -> p.toString().endsWith(".json"))
                                .forEach(p -> loadSchemaInternally(p.getFileName().toString()));
                    } catch (Exception e) {
                        log.warn("Cannot list schema dir: {}", url, e);
                    }
                } else if ("jar".equals(protocol)) {
                    // jar 形式：解析 jar 中 schema/ 下的 .json 条目
                    String jarPart = extractJarPath(url);
                    if (jarPart != null && seenJars.add(jarPart)) {
                        listJarSchemaEntries(jarPart);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan classpath schemas", e);
        }
    }

    /**
     * 从 jar URL 中提取 jar 文件路径
     */
    private static String extractJarPath(URL url) {
        String s = url.toString();
        int idx = s.indexOf(".jar!");
        if (idx < 0) return null;
        String path = s.substring(0, idx + 4);
        if (path.startsWith("jar:")) path = path.substring(4);
        if (path.startsWith("file:")) path = path.substring(5);
        return path;
    }

    /**
     * 枚举 jar 内 schema/ 开头的 .json 条目并加载
     */
    private static void listJarSchemaEntries(String jarPath) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jarPath)) {
            jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(java.util.jar.JarEntry::getName)
                    .filter(name -> name.startsWith(SCHEMA_PATH) && name.endsWith(".json"))
                    .map(name -> name.substring(SCHEMA_PATH.length()))
                    .forEach(SchemaRegistry::loadSchemaInternally);
        } catch (Exception e) {
            log.warn("Cannot read jar schemas: {}", jarPath, e);
        }
    }

    /**
     * 从classpath读取JSON文件并解析为TableSchema对象，使用putIfAbsent确保
     * 已存在的Schema不会被覆盖（外部Schema通过put方法覆盖）。
     *
     * @param filename Schema文件名（如 "sys_user.json"）
     */
    private static void loadSchemaInternally(String filename) {
        String path = SCHEMA_PATH + filename;
        try (InputStream is = SchemaRegistry.class.getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                TableSchema schema = MAPPER.readValue(is, TableSchema.class);
                // 跳过废弃标记文件（仅有 deprecated/description，无 tableName 的占位 JSON）
                if (schema.getTableName() == null || schema.getTableName().isBlank()) {
                    log.info("Skip schema file without tableName: {}", filename);
                    return;
                }
                SCHEMA_CACHE.putIfAbsent(schema.getTableName(), schema);
            }
        } catch (IOException e) {
            // Schema file not found, skip
        }
    }

    /**
     * 加载外部目录中的Schema文件
     * <p>
     * 扫描 data/schemas/ 目录下所有.json文件，解析后加入缓存。
     * 外部Schema会覆盖同名的内置Schema（因为使用put而非putIfAbsent）。
     * </p>
     */
    private static void loadExternalSchemas() {
        try {
            Files.list(externalSchemaDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            TableSchema schema = MAPPER.readValue(p.toFile(), TableSchema.class);
                            SCHEMA_CACHE.put(schema.getTableName(), schema);
                        } catch (IOException e) {
                            // Skip invalid files
                        }
                    });
        } catch (IOException e) {
            // Directory might not exist
        }
    }

    /**
     * 根据表名获取Schema
     *
     * @param tableName 表名
     * @return 表Schema，不存在时返回null
     */
    public static TableSchema getSchema(String tableName) {
        return SCHEMA_CACHE.get(tableName);
    }

    /**
     * 获取所有Schema
     *
     * @return 所有表Schema的列表
     */
    public static List<TableSchema> getAllSchemas() {
        return new ArrayList<>(SCHEMA_CACHE.values());
    }

    /**
     * 根据模块名获取Schema列表
     *
     * @param moduleName 模块名
     * @return 属于该模块的表Schema列表
     */
    public static List<TableSchema> getSchemasByModule(String moduleName) {
        return SCHEMA_CACHE.values().stream()
                .filter(s -> moduleName.equals(s.getModuleName()))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有表名
     *
     * @return 所有已注册的表名集合
     */
    public static Set<String> getAllTableNames() {
        return SCHEMA_CACHE.keySet();
    }

    /**
     * 检查是否包含指定表
     *
     * @param tableName 表名
     * @return 是否已注册
     */
    public static boolean containsTable(String tableName) {
        return SCHEMA_CACHE.containsKey(tableName);
    }

    /**
     * 更新表Schema
     * <p>
     * 将新的Schema写入外部目录的JSON文件，并更新内存缓存。
     * </p>
     *
     * @param tableName 表名
     * @param newSchema 新的Schema定义
     * @return 旧的Schema定义
     * @throws IOException 表不存在时抛出
     */
    public static TableSchema updateSchema(String tableName, TableSchema newSchema) throws IOException {
        if (!SCHEMA_CACHE.containsKey(tableName)) {
            throw new IOException("Table schema not found: " + tableName);
        }

        TableSchema oldSchema = SCHEMA_CACHE.get(tableName);
        newSchema.setTableName(tableName);

        Path filePath = externalSchemaDir.resolve(tableName + ".json");
        MAPPER.writeValue(filePath.toFile(), newSchema);

        SCHEMA_CACHE.put(tableName, newSchema);
        return oldSchema;
    }

    /**
     * 添加新的表Schema
     * <p>
     * 将Schema写入外部目录的JSON文件，并加入内存缓存。
     * </p>
     *
     * @param schema 表Schema定义
     * @throws IOException 表已存在时抛出
     */
    public static void addSchema(TableSchema schema) throws IOException {
        if (SCHEMA_CACHE.containsKey(schema.getTableName())) {
            throw new IOException("Table schema already exists: " + schema.getTableName());
        }

        Path filePath = externalSchemaDir.resolve(schema.getTableName() + ".json");
        MAPPER.writeValue(filePath.toFile(), schema);

        SCHEMA_CACHE.put(schema.getTableName(), schema);
    }

    /**
     * 删除表Schema
     * <p>
     * 删除外部目录中的JSON文件，并从内存缓存中移除。
     * </p>
     *
     * @param tableName 表名
     * @throws IOException 表不存在时抛出
     */
    public static void deleteSchema(String tableName) throws IOException {
        if (!SCHEMA_CACHE.containsKey(tableName)) {
            throw new IOException("Table schema not found: " + tableName);
        }

        Path filePath = externalSchemaDir.resolve(tableName + ".json");
        Files.deleteIfExists(filePath);

        SCHEMA_CACHE.remove(tableName);
    }

    /**
     * 获取外部Schema目录路径
     *
     * @return 外部Schema目录路径
     */
    public static Path getExternalSchemaDir() {
        return externalSchemaDir;
    }
}
