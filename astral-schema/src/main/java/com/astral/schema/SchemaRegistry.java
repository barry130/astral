package com.astral.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
     * 通过枚举 classpath 上所有 {@code schema/*.json} 资源加载（兼容 IDE 目录、普通 jar 与
     * Spring Boot 可执行 jar 三种打包形式），不依赖目录列表（directory listing）能力。
     * </p>
     */
    private static void loadClasspathSchemas() {
        try {
            Enumeration<URL> urls = SchemaRegistry.class.getClassLoader().getResources(SCHEMA_PATH);
            Set<String> seen = new HashSet<>();
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    // 目录形式（IDE / exploded 模式）：枚举目录下的 .json 文件
                    try (Stream<Path> files = Files.list(Paths.get(url.toURI()))) {
                        files.filter(p -> p.toString().endsWith(".json"))
                                .sorted()
                                .forEach(SchemaRegistry::loadSchemaFile);
                    } catch (Exception e) {
                        log.warn("Cannot list schema dir: {}", url, e);
                    }
                } else if ("jar".equals(protocol)) {
                    // jar 形式：枚举 jar 内 schema/ 下的 .json 条目
                    if (seen.add(url.toString())) {
                        listJarSchemaEntries(url);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan classpath schemas", e);
        }
    }

    /**
     * 枚举 jar 内 schema/ 开头的 .json 条目并加载
     * <p>
     * 必须经 {@link JarURLConnection} 取 JarFile，不能按 URL 文本截出路径再 new JarFile：
     * Spring Boot 可执行 jar 中资源地址形如
     * {@code jar:nested:/app/app.jar/!BOOT-INF/lib/astral-plugin-1.0.0.jar!/schema/}，
     * 其中的 nested: 路径不是文件系统路径，直接构造 JarFile 必然失败，
     * 表现为启动日志 "Cannot read jar schemas"、表结构管理页与序列预置全部为空。
     * </p>
     */
    private static void listJarSchemaEntries(URL dirUrl) {
        try {
            URLConnection connection = dirUrl.openConnection();
            if (!(connection instanceof JarURLConnection jarConnection)) {
                log.warn("Cannot read jar schemas (unsupported connection): {}", dirUrl);
                return;
            }
            // 不关闭 JarFile：连接默认走缓存，句柄与 classloader 共用，关闭会影响后续类加载
            JarFile jarFile = jarConnection.getJarFile();
            jarFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> {
                        String name = entry.getName();
                        return name.startsWith(SCHEMA_PATH) && name.endsWith(".json");
                    })
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .forEach(entry -> loadSchemaEntry(jarFile, entry));
        } catch (Exception e) {
            log.warn("Cannot read jar schemas: {}", dirUrl, e);
        }
    }

    /** 从外部目录文件加载Schema */
    private static void loadSchemaFile(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            loadSchema(is, file.getFileName().toString());
        } catch (IOException e) {
            log.warn("Cannot read schema file: {}", file, e);
        }
    }

    /** 从jar条目加载Schema */
    private static void loadSchemaEntry(JarFile jarFile, JarEntry entry) {
        try (InputStream is = jarFile.getInputStream(entry)) {
            loadSchema(is, entry.getName().substring(SCHEMA_PATH.length()));
        } catch (IOException e) {
            log.warn("Cannot read schema entry: {}", entry.getName(), e);
        }
    }

    /**
     * 解析JSON并登记Schema，使用putIfAbsent确保已存在的Schema不会被覆盖
     * （外部Schema通过put方法覆盖）。
     *
     * @param is       Schema内容流
     * @param filename Schema文件名（如 "sys_user.json"），仅用于日志
     */
    private static void loadSchema(InputStream is, String filename) {
        try {
            TableSchema schema = MAPPER.readValue(is, TableSchema.class);
            // 跳过废弃标记文件（仅有 deprecated/description，无 tableName 的占位 JSON）
            if (schema.getTableName() == null || schema.getTableName().isBlank()) {
                log.info("Skip schema file without tableName: {}", filename);
                return;
            }
            SCHEMA_CACHE.putIfAbsent(schema.getTableName(), schema);
        } catch (IOException e) {
            log.warn("Cannot parse schema file: {}", filename, e);
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
