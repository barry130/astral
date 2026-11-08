package com.astral.ai.tool.declarative;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 声明式 SQL 工具执行器（只读查询）。
 *
 * <p>后台可以配置一条「带具名参数的 SELECT」作为工具，模型只负责填参数、拿结果。
 * 执行面全部写死在代码里、不随配置放开：</p>
 * <ul>
 *   <li><b>只允许单条 SELECT / WITH … SELECT</b>：多语句、以及任何写关键字（INSERT/UPDATE/
 *       DELETE/MERGE/INTO/CREATE/ALTER/DROP/TRUNCATE/GRANT/CALL/COPY/LOCK…）在保存和执行时
 *       都会被拒绝——包括 {@code WITH x AS (INSERT …)} 这种数据修改型 CTE；</li>
 *   <li><b>只用具名占位符 {@code #{name}} 绑定</b>：参数一律走 {@link PreparedStatement}，
 *       不存在把入参拼进 SQL 文本的路径；占位符必须出现在参数结构里；</li>
 *   <li><b>连接只读</b>：{@code setReadOnly(true)} + 手动事务末尾 rollback，双保险；</li>
 *   <li><b>结果收窄</b>：{@code maxRows} 默认 50、硬上限 200；{@code columns} 必须显式声明，
 *       查询结果只回传声明的列，避免以后表结构变化把新列漏给模型；</li>
 *   <li>超时默认 5 秒、上限 15 秒。</li>
 * </ul>
 */
@Slf4j
public class SqlToolExecutor implements AiToolHandler {

    /** 声明式 SQL 工具的 handlerKey */
    public static final String HANDLER_KEY = "declarative:sql";

    /** 具名参数占位符（与 MyBatis 的 #{} 语义一致，但不经过 MyBatis） */
    static final Pattern PARAM = Pattern.compile("#\\{([A-Za-z0-9_\\-.]+)}");

    /** 写操作 / DDL / 会话变更关键字（大小写不敏感，按词边界匹配） */
    static final Set<String> FORBIDDEN_WORDS = Set.of(
            "INSERT",
            "UPDATE",
            "DELETE",
            "MERGE",
            "UPSERT",
            "INTO",
            "RETURNING",
            "CREATE",
            "ALTER",
            "DROP",
            "TRUNCATE",
            "RENAME",
            "GRANT",
            "REVOKE",
            "CALL",
            "EXEC",
            "EXECUTE",
            "DO",
            "COPY",
            "LOCK",
            "VACUUM",
            "REINDEX",
            "REFRESH",
            "SET",
            "RESET",
            "COMMENT",
            "LISTEN",
            "NOTIFY",
            "DISCARD",
            "PREPARE",
            "DEALLOCATE",
            "ANALYZE");

    static final Pattern COLUMN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private static final int DEFAULT_MAX_ROWS = 50;
    private static final int MAX_MAX_ROWS = 200;
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 15;
    private static final int SUMMARY_LIMIT = 1500;

    private final AiToolEntity tool;
    private final ObjectNode config;
    private final JdbcTemplate jdbcTemplate;
    private final AiProperties properties;

    private SqlToolExecutor(AiToolEntity tool, ObjectNode config, JdbcTemplate jdbcTemplate, AiProperties properties) {
        this.tool = tool;
        this.config = config;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 按工具记录构造执行器。
     *
     * @param tool          工具记录
     * @param jdbcTemplate  应用数据源（{@code null} 表示当前环境没有 JDBC，工具视为不可用）
     * @param properties    插件配置
     * @return 执行器；配置非法或缺少数据源时返回 {@code null}
     */
    public static SqlToolExecutor create(AiToolEntity tool, JdbcTemplate jdbcTemplate, AiProperties properties) {
        if (jdbcTemplate == null) {
            log.warn("[AI] 声明式 SQL 工具 {} 不可用：当前环境没有 JDBC 数据源", tool.getToolCode());
            return null;
        }
        ObjectNode config = AiJson.readObject(tool.getExecutorConfig());
        if (config == null || config.isEmpty()) {
            log.warn("[AI] 声明式 SQL 工具 {} 的 executor_config 缺失或非法", tool.getToolCode());
            return null;
        }
        try {
            requireValidShape(config, properties);
        } catch (AiException e) {
            log.warn("[AI] 声明式 SQL 工具 {} 配置非法，已跳过: {}", tool.getToolCode(), e.getMessage());
            return null;
        }
        return new SqlToolExecutor(tool, config, jdbcTemplate, properties);
    }

    // ==================== 保存期校验 ====================

    /**
     * 保存/更新声明式 SQL 工具时的完整校验（结构 + 只读护栏 + 具名参数）。
     *
     * @param executorConfigJson executor_config JSON 文本
     * @param argumentsSchema    参数 JSON Schema 文本（可空）
     */
    public static void validate(String executorConfigJson, String argumentsSchema) {
        validate(executorConfigJson, argumentsSchema, null);
    }

    public static void validate(String executorConfigJson, String argumentsSchema, AiProperties properties) {
        JsonNode parsed = executorConfigJson == null ? null : AiJson.read(executorConfigJson);
        if (!(parsed instanceof ObjectNode config) || config.isEmpty()) {
            throw new AiException("AI008", "执行配置必须是 JSON 对象");
        }
        requireValidShape(config, properties);
        Set<String> declared = HttpToolExecutor.declaredArgs(argumentsSchema);
        for (String name : collectParams(config)) {
            if (!declared.contains(name)) {
                throw new AiException("AI008", "SQL 使用了参数结构未声明的占位符: #{" + name + "}");
            }
        }
    }

    /** 结构与只读护栏校验，供 create() 与 validate() 复用 */
    static void requireValidShape(ObjectNode config) {
        requireValidShape(config, null);
    }

    private static void requireValidShape(ObjectNode config, AiProperties properties) {
        String sql = AiJson.text(config, "sql", "");
        if (sql.isBlank()) {
            throw new AiException("AI008", "sql 不能为空");
        }
        String stripped = stripLiteralsAndComments(sql).trim();
        if (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).trim();
        }
        if (stripped.indexOf(';') >= 0) {
            throw new AiException("AI008", "只允许单条 SQL 语句（发现多余的分号）");
        }
        String head = firstWord(stripped);
        if (!"SELECT".equals(head) && !"WITH".equals(head)) {
            throw new AiException("AI008", "只允许 SELECT / WITH … SELECT 查询，当前以 " + head + " 开头");
        }
        for (String word : words(stripped)) {
            if (FORBIDDEN_WORDS.contains(word)) {
                throw new AiException("AI008", "SQL 含有不允许的关键字: " + word + "（SQL 工具只支持只读查询）");
            }
        }
        int maxRows = AiJson.intValue(config, "maxRows", DEFAULT_MAX_ROWS);
        if (maxRows < 1 || maxRows > MAX_MAX_ROWS) {
            throw new AiException("AI008", "maxRows 必须在 1 到 " + MAX_MAX_ROWS + " 之间");
        }
        int timeout = AiJson.intValue(config, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        if (timeout < 1 || timeout > MAX_TIMEOUT_SECONDS) {
            throw new AiException("AI008", "timeoutSeconds 必须在 1 到 " + MAX_TIMEOUT_SECONDS + " 之间");
        }
        JsonNode columns = config.get("columns");
        if (!(columns instanceof ArrayNode array) || array.isEmpty()) {
            throw new AiException("AI008", "columns 必须是非空数组（显式声明要回传给模型的列）");
        }
        for (JsonNode column : array) {
            if (!column.isString() || !COLUMN_NAME.matcher(column.asString()).matches()) {
                throw new AiException("AI008", "columns 里的列名必须是字母开头的标识符: " + column);
            }
        }
        SqlReadPolicy.validate(sql, properties);
    }

    /** 去掉 SQL 字符串字面量与注释，避免里面的关键字/分号造成误判 */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'' || current == '"') {
                char quote = current;
                index++;
                while (index < sql.length()) {
                    char inner = sql.charAt(index);
                    if (inner == quote) {
                        if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                            index += 2;
                            continue;
                        }
                        index++;
                        break;
                    }
                    if (inner == '\\' && quote == '\'' && index + 1 < sql.length()) {
                        index += 2;
                        continue;
                    }
                    index++;
                }
                out.append(" '' ");
                continue;
            }
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < sql.length() && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) {
                    index++;
                }
                index = Math.min(sql.length(), index + 2);
                continue;
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private static String firstWord(String text) {
        Matcher matcher = Pattern.compile("[A-Za-z]+").matcher(text);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : "";
    }

    /** 按非标识符字符切词并大写，用于关键字黑名单匹配 */
    static List<String> words(String text) {
        List<String> result = new ArrayList<>();
        for (String token : text.split("[^A-Za-z_]+")) {
            if (!token.isBlank()) {
                result.add(token.toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** 收集 SQL 里出现的具名参数 */
    static Set<String> collectParams(ObjectNode config) {
        Set<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = PARAM.matcher(AiJson.text(config, "sql", ""));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    // ==================== AiToolHandler ====================

    @Override
    public AiToolDef definition() {
        return new AiToolDef(
                tool.getToolCode(),
                tool.getName(),
                tool.getDescription(),
                HANDLER_KEY,
                tool.getRiskLevel(),
                tool.getApprovalPolicy(),
                AiJson.splitCsv(tool.getClientTypes()),
                AiJson.splitCsv(tool.getPermissionCodes()),
                tool.getArgumentsSchema(),
                tool.getResultSchema(),
                tool.getSideEffects());
    }

    @Override
    public AiToolResult execute(AiToolContext context) {
        try {
            return doExecute(context);
        } catch (AiException e) {
            return AiToolResult.failed(e.getMessage());
        } catch (Exception e) {
            log.warn("[AI] SQL 工具 {} 执行失败: {}", tool.getToolCode(), e.getClass().getSimpleName());
            return AiToolResult.failed("SQL 查询失败，请检查来源列配置和参数");
        }
    }

    private AiToolResult doExecute(AiToolContext context) throws SQLException {
        String sql = AiJson.text(config, "sql", "");
        ObjectNode args = context.arguments() == null ? AiJson.mapper().createObjectNode() : context.arguments();
        List<Object> params = new ArrayList<>();
        String prepared = toPrepared(sql, args, params);
        prepared = SqlReadPolicy.prepare(prepared, properties);
        int maxRows = clamp(config, "maxRows", DEFAULT_MAX_ROWS, MAX_MAX_ROWS);
        int timeout = clamp(config, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            throw new AiException("AI008", "当前环境没有可用的数据源");
        }
        ArrayNode rows = AiJson.mapper().createArrayNode();
        List<String> columns = declaredColumns();
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(prepared)) {
                statement.setMaxRows(maxRows + 1);
                statement.setQueryTimeout(timeout);
                for (int index = 0; index < params.size(); index++) {
                    statement.setObject(index + 1, params.get(index));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next() && rows.size() < maxRows) {
                        rows.add(readRow(resultSet, columns));
                    }
                }
            } finally {
                // 只读 + 未提交事务直接回滚：即使驱动忽略了 setReadOnly，也留不下写入
                connection.rollback();
            }
        }
        ObjectNode payload = AiJson.mapper().createObjectNode();
        payload.set("rows", rows);
        payload.put("rowCount", rows.size());
        String summary = rows.isEmpty()
                ? "查询没有匹配的数据"
                : "查询到 " + rows.size() + " 行：" + AiJson.truncate(AiJson.write(rows), SUMMARY_LIMIT);
        return AiToolResult.ok(summary, payload);
    }

    /** 把 #{name} 换成 ? 并按顺序绑定参数（唯一允许的参数通道） */
    static String toPrepared(String sql, ObjectNode args, List<Object> params) {
        Matcher matcher = PARAM.matcher(sql);
        StringBuilder prepared = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            JsonNode value = args == null ? null : args.get(name);
            if (value == null || value.isNull()) {
                throw new AiException("AI008", "缺少参数: " + name);
            }
            if (value.isObject() || value.isArray()) {
                throw new AiException("AI008", "SQL 参数只支持标量: " + name);
            }
            params.add(bindValue(value));
            matcher.appendReplacement(prepared, "?");
        }
        matcher.appendTail(prepared);
        return prepared.toString();
    }

    private static Object bindValue(JsonNode value) {
        if (value.isString()) {
            return value.asString();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        return value.asString();
    }

    private List<String> declaredColumns() {
        List<String> columns = new ArrayList<>();
        JsonNode array = config.get("columns");
        if (array instanceof ArrayNode nodes) {
            for (JsonNode node : nodes) {
                columns.add(node.asString().toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    /** 只回传声明的列（大小写不敏感），值按 JDBC 类型转成 JSON */
    private static ObjectNode readRow(ResultSet resultSet, List<String> columns) throws SQLException {
        ObjectNode row = AiJson.mapper().createObjectNode();
        ResultSetMetaData meta = resultSet.getMetaData();
        for (int index = 1; index <= meta.getColumnCount(); index++) {
            String label = meta.getColumnLabel(index);
            if (label == null) {
                continue;
            }
            String normalized = label.toLowerCase(Locale.ROOT);
            if (!columns.contains(normalized)) {
                continue;
            }
            row.set(normalized, toJson(resultSet.getObject(index)));
        }
        return row;
    }

    private static JsonNode toJson(Object value) {
        if (value == null) {
            return AiJson.mapper().nullNode();
        }
        if (value instanceof byte[] bytes) {
            return AiJson.mapper().valueToTree(Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof Timestamp timestamp) {
            return AiJson.mapper().valueToTree(timestamp.toLocalDateTime().toString());
        }
        if (value instanceof java.sql.Date date) {
            return AiJson.mapper().valueToTree(date.toLocalDate().toString());
        }
        if (value instanceof java.sql.Time time) {
            return AiJson.mapper().valueToTree(time.toLocalTime().toString());
        }
        if (value instanceof Temporal || value instanceof java.util.Date || value instanceof java.util.UUID) {
            return AiJson.mapper().valueToTree(value.toString());
        }
        if (value instanceof BigDecimal decimal) {
            return AiJson.mapper().valueToTree(decimal);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return AiJson.mapper().valueToTree(value);
        }
        throw new AiException("AI008", "SQL 返回了未支持的复合数据类型，请显式选择标量列");
    }

    private static int clamp(ObjectNode config, String field, int def, int max) {
        int value = AiJson.intValue(config, field, def);
        return Math.max(1, Math.min(max, value));
    }
}
