package com.astral.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
     * 2. 从classpath加载内置Schema
     * 3. 从外部目录加载动态Schema
     * </p>
     *
     * @throws RuntimeException 初始化失败时抛出
     */
    public static void init() {
        try {
            externalSchemaDir = Paths.get("data", "schemas");
            Files.createDirectories(externalSchemaDir);

            Enumeration<URL> resources = SchemaRegistry.class.getClassLoader().getResources(SCHEMA_PATH);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                loadSchemasFromUrl(url);
            }

            loadExternalSchemas();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize SchemaRegistry", e);
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
     * 从URL加载Schema文件
     * <p>
     * 尝试解析目录列表，如果失败则回退到加载已知Schema列表。
     * </p>
     *
     * @param url classpath资源URL
     * @throws IOException IO异常
     */
    private static void loadSchemasFromUrl(URL url) throws IOException {
        try (InputStream is = url.openStream()) {
            if (is != null) {
                byte[] bytes = is.readAllBytes();
                String content = new String(bytes);
                String[] files = content.split("\n");
                for (String file : files) {
                    file = file.trim();
                    if (file.endsWith(".json")) {
                        loadSchema(file);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore if not a directory listing
        }

        // Fallback: load known schemas
        loadKnownSchemas();
    }

    /**
     * 加载已知的内置Schema文件
     * <p>
     * 包含系统所有核心表的Schema定义：用户、角色、权限、字典、配置、Token、
     * 序列相关表、日志表等。
     * </p>
     */
    private static void loadKnownSchemas() {
        String[] schemaFiles = {
            "sys_user.json", "sys_role.json", "sys_permission.json",
            "sys_user_role.json", "sys_role_permission.json",
            "sys_dict_type.json", "sys_dict_data.json",
            "sys_config.json", "sys_token.json",
            "sequence_config.json", "sequence_segment.json",
            "sequence_history.json", "sequence_statistics.json",
            "sys_login_log.json", "sys_operate_log.json"
        };

        for (String file : schemaFiles) {
            loadSchema(file);
        }
    }

    /**
     * 加载单个Schema文件
     * <p>
     * 从classpath读取JSON文件并解析为TableSchema对象，使用putIfAbsent确保
     * 已存在的Schema不会被覆盖（外部Schema通过put方法覆盖）。
     * </p>
     *
     * @param filename Schema文件名（如 "sys_user.json"）
     */
    public static void loadSchema(String filename) {
        try {
            String path = SCHEMA_PATH + filename;
            try (InputStream is = SchemaRegistry.class.getClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                    TableSchema schema = MAPPER.readValue(is, TableSchema.class);
                    SCHEMA_CACHE.putIfAbsent(schema.getTableName(), schema);
                }
            }
        } catch (IOException e) {
            // Schema file not found, skip
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
