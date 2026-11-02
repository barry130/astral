package com.astral.ai.tool.declarative;

import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.ai.common.AiException;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 声明式 HTTP 工具执行器。
 *
 * <p>{@code executor_type='HTTP'} 的工具没有 Java 处理器：本类按 {@code ai_tool.executor_config}
 * 里的模板把入参渲染成一个 HTTP 请求并执行。执行面被刻意收窄，安全边界全部写死在代码里、
 * 不随配置走：</p>
 * <ul>
 *   <li>host/port 必须是字面量，模板只允许出现在 path/query/header/body——从源头杜绝
 *       「URL 拼出来指到别的主机」；</li>
 *   <li>SSRF 护栏：禁私网 / 环回 / 链路本地（含云元数据 169.254.169.254）/ 组播 / 全零地址，
 *       可选 {@code astral.plugins.ai.http-tool.allowed-hosts} 白名单进一步收紧；
 *       保存时与每次执行时都校验（执行时重新解析 DNS）；</li>
 *   <li>不跟随重定向（java.net.http 默认 NEVER，避免「重定向进内网」）；</li>
 *   <li>超时与响应体积有硬上限；{@code ${secret:key}} 在配置里只存引用，
 *       运行时才从 {@code astral.plugins.ai.http-tool.secrets} 或同名环境变量取值。</li>
 * </ul>
 *
 * <p>残余风险（文档已注明）：私网拦截按「解析到的每个地址」执行，域名型地址存在 DNS
 * rebinding 的理论绕过面——对安全性要求高的部署应显式配置 allow-hosts 白名单。</p>
 */
@Slf4j
public class HttpToolExecutor implements AiToolHandler {

    /** 声明式 HTTP 工具的 handlerKey（与代码处理器命名空间隔离） */
    public static final String HANDLER_KEY = "declarative:http";

    /** 允许的 HTTP 方法（写方法由 Service 强制 HIGH + ADMIN_CONFIRM，见 AiToolService） */
    static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** ${参数} / ${secret:键} 占位符（允许一个冒号段承载 secret: 前缀） */
    static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+(?::[A-Za-z0-9_.\\-]+)?)}");

    /** http(s) 绝对地址：scheme://authority + 可选的 path（可含 ?query） */
    static final Pattern URL_SHAPE = Pattern.compile("^(https?)://([^/?#]+)(/.*)?$",
            Pattern.CASE_INSENSITIVE);

    /** host 形态：域名段（字母数字点横线）或 [IPv6] + 可选 :端口 */
    static final Pattern AUTHORITY_SHAPE = Pattern.compile("^([A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:]+])(:\\d+)?$");

    /** 简化 JSON 路径段（data.items / 0.name）与 secret 键共用 */
    static final Pattern KEY_SHAPE = Pattern.compile("[A-Za-z0-9_\\-.]+");

    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int MIN_TIMEOUT_MS = 500;
    private static final int MAX_TIMEOUT_MS = 30000;
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 262144;
    private static final int MIN_MAX_RESPONSE_BYTES = 4096;
    private static final int MAX_MAX_RESPONSE_BYTES = 1048576;
    private static final int SUMMARY_LIMIT = 2000;

    private final AiToolEntity tool;
    private final ObjectNode config;
    private final AiProperties.HttpTool guard;
    private final HttpClient httpClient;

    private HttpToolExecutor(AiToolEntity tool, ObjectNode config, AiProperties.HttpTool guard,
                             HttpClient httpClient) {
        this.tool = tool;
        this.config = config;
        this.guard = guard;
        this.httpClient = httpClient;
    }

    /**
     * 按工具记录构造执行器。
     *
     * <p>{@code executor_config} 缺失或结构非法时返回 {@code null}，由调用方按
     * 「处理器不可用」跳过该绑定并告警——坏配置不能拖垮整轮对话。</p>
     */
    public static HttpToolExecutor create(AiToolEntity tool, AiProperties properties) {
        ObjectNode config = AiJson.readObject(tool.getExecutorConfig());
        if (config == null || config.isEmpty()) {
            log.warn("[AI] 声明式 HTTP 工具 {} 的 executor_config 缺失或非法", tool.getToolCode());
            return null;
        }
        try {
            requireValidShape(config);
        } catch (AiException e) {
            log.warn("[AI] 声明式 HTTP 工具 {} 配置非法，已跳过: {}", tool.getToolCode(), e.getMessage());
            return null;
        }
        return new HttpToolExecutor(tool, config, properties.getHttpTool(), defaultClient());
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ==================== 保存期校验 ====================

    /**
     * 保存/更新声明式 HTTP 工具时的完整校验（结构 + 参数占位符 + SSRF 护栏）。
     *
     * @param executorConfigJson executor_config JSON 文本
     * @param argumentsSchema    参数 JSON Schema 文本（可空）
     * @param allowedHosts       白名单（来自配置，可空）
     */
    public static void validate(String executorConfigJson, String argumentsSchema, List<String> allowedHosts) {
        JsonNode parsed = executorConfigJson == null ? null : AiJson.read(executorConfigJson);
        if (!(parsed instanceof ObjectNode config) || config.isEmpty()) {
            throw new AiException("AI008", "执行配置必须是 JSON 对象");
        }
        requireValidShape(config);
        Set<String> declared = declaredArgs(argumentsSchema);
        for (String key : collectPlaceholders(config)) {
            if (key.startsWith("secret:")) {
                String secretKey = key.substring("secret:".length());
                if (secretKey.isBlank() || !KEY_SHAPE.matcher(secretKey).matches()) {
                    throw new AiException("AI008", "secret 引用非法: ${" + key + "}");
                }
            } else if (!declared.contains(key)) {
                throw new AiException("AI008",
                        "模板使用了参数结构未声明的占位符: ${" + key + "}");
            }
        }
        guardHost(hostOf(config), allowedHosts);
    }

    /** 结构校验（方法/URL/路径/超时/体积/body 形态），供 create() 与 validate() 复用 */
    static void requireValidShape(ObjectNode config) {
        String method = AiJson.text(config, "method", "GET").toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new AiException("AI008", "method 仅支持 GET/POST/PUT/PATCH/DELETE: " + method);
        }
        String url = AiJson.text(config, "url", "");
        Matcher shape = URL_SHAPE.matcher(url);
        if (!shape.matches()) {
            throw new AiException("AI008", "url 必须是 http(s) 绝对地址: " + url);
        }
        String authority = shape.group(2);
        if (authority.contains("${")) {
            throw new AiException("AI008", "host 必须是字面量，模板只允许出现在 path/query/header/body");
        }
        if (!AUTHORITY_SHAPE.matcher(authority).matches()) {
            throw new AiException("AI008", "authority 形态非法（host[:port]）: " + authority);
        }
        int timeout = AiJson.intValue(config, "timeoutMs", DEFAULT_TIMEOUT_MS);
        if (timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS) {
            throw new AiException("AI008",
                    "timeoutMs 必须在 " + MIN_TIMEOUT_MS + " 到 " + MAX_TIMEOUT_MS + " 毫秒之间");
        }
        int maxBytes = AiJson.intValue(config, "maxResponseBytes", DEFAULT_MAX_RESPONSE_BYTES);
        if (maxBytes < MIN_MAX_RESPONSE_BYTES || maxBytes > MAX_MAX_RESPONSE_BYTES) {
            throw new AiException("AI008",
                    "maxResponseBytes 必须在 " + MIN_MAX_RESPONSE_BYTES + " 到 " + MAX_MAX_RESPONSE_BYTES + " 字节之间");
        }
        for (String field : List.of("summaryPath", "payloadPath")) {
            String path = AiJson.text(config, field, "");
            if (!path.isEmpty() && !KEY_SHAPE.matcher(path).matches()) {
                throw new AiException("AI008", field + " 只允许字母数字点横线下划线: " + path);
            }
        }
        JsonNode headers = config.get("headers");
        if (headers != null && !headers.isNull()
                && (!(headers instanceof ObjectNode) || !allTextValues((ObjectNode) headers))) {
            throw new AiException("AI008", "headers 必须是字符串键值对象");
        }
        JsonNode query = config.get("query");
        if (query != null && !query.isNull()
                && (!(query instanceof ObjectNode) || !allTextValues((ObjectNode) query))) {
            throw new AiException("AI008", "query 必须是字符串键值对象");
        }
        JsonNode body = config.get("body");
        if (body != null && !body.isNull() && !body.isString()) {
            throw new AiException("AI008", "body 必须是字符串模板（占位符按 JSON 规则渲染）");
        }
        if (body != null && !body.isNull() && !WRITE_METHODS.contains(method)
                && !body.asString().isBlank()) {
            throw new AiException("AI008", method + " 请求不允许携带 body");
        }
    }

    private static boolean allTextValues(ObjectNode object) {
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull() || !value.isString()) {
                return false;
            }
        }
        return true;
    }

    /** 收集配置里出现的全部占位符名（url path+query、headers、query、body） */
    static Set<String> collectPlaceholders(ObjectNode config) {
        Set<String> found = new java.util.LinkedHashSet<>();
        String url = AiJson.text(config, "url", "");
        Matcher shape = URL_SHAPE.matcher(url);
        if (shape.matches()) {
            collectIn(shape.group(3) == null ? "" : shape.group(3), found);
        }
        for (String field : List.of("headers", "query")) {
            JsonNode node = config.get(field);
            if (node instanceof ObjectNode object) {
                for (Map.Entry<String, JsonNode> entry : object.properties()) {
                    collectIn(entry.getValue().asString(), found);
                }
            }
        }
        JsonNode body = config.get("body");
        if (body != null && body.isString()) {
            collectIn(body.asString(), found);
        }
        return found;
    }

    private static void collectIn(String template, Set<String> found) {
        if (template == null) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
    }

    /** arguments_schema.properties 里声明的参数名集合 */
    static Set<String> declaredArgs(String argumentsSchema) {
        Set<String> names = new java.util.LinkedHashSet<>();
        JsonNode schema = argumentsSchema == null ? null : AiJson.read(argumentsSchema);
        JsonNode properties = schema == null ? null : schema.get("properties");
        if (properties != null && properties.isObject()) {
            for (Map.Entry<String, JsonNode> entry : properties.properties()) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    // ==================== SSRF 护栏 ====================

    /** 主机护栏：白名单（可选）+ 禁私网/环回/链路本地/组播/全零，执行时按最新 DNS 重新解析 */
    static void guardHost(String host, List<String> allowedHosts) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new AiException("AI008", "URL 缺少主机名");
        }
        if (allowedHosts != null && !allowedHosts.isEmpty()) {
            boolean matched = false;
            for (String entry : allowedHosts) {
                String candidate = entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
                int colon = candidate.indexOf(':');
                if (colon > 0 && !candidate.startsWith("[")) {
                    candidate = candidate.substring(0, colon);
                }
                if (candidate.equals(normalized)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw new AiException("AI008", "目标主机不在白名单内: " + normalized);
            }
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(normalized)) {
                requirePublicAddress(address);
            }
        } catch (UnknownHostException e) {
            throw new AiException("AI008", "无法解析主机: " + normalized);
        }
    }

    private static void requirePublicAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalIpv6 = bytes != null && bytes.length == 16
                && (bytes[0] == (byte) 0xfc || bytes[0] == (byte) 0xfd);
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress() || uniqueLocalIpv6) {
            throw new AiException("AI008", "禁止调用私网/环回/链路本地地址: " + address.getHostAddress());
        }
    }

    /** 从 executor_config 里提取 host（去端口与 IPv6 方括号） */
    static String hostOf(ObjectNode config) {
        Matcher shape = URL_SHAPE.matcher(AiJson.text(config, "url", ""));
        if (!shape.matches()) {
            return "";
        }
        String authority = shape.group(2);
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close > 0 ? authority.substring(1, close) : authority;
        }
        int colon = authority.lastIndexOf(':');
        return colon > 0 ? authority.substring(0, colon) : authority;
    }

    // ==================== AiToolHandler ====================

    @Override
    public AiToolDef definition() {
        return new AiToolDef(tool.getToolCode(), tool.getName(), tool.getDescription(), HANDLER_KEY,
                tool.getRiskLevel(), tool.getApprovalPolicy(), AiJson.splitCsv(tool.getClientTypes()),
                AiJson.splitCsv(tool.getPermissionCodes()), tool.getArgumentsSchema(),
                tool.getResultSchema(), tool.getSideEffects());
    }

    @Override
    public AiToolResult execute(AiToolContext context) {
        try {
            return doExecute(context);
        } catch (AiException e) {
            return AiToolResult.failed(e.getMessage());
        } catch (Exception e) {
            log.warn("[AI] HTTP 工具 {} 执行失败: {}", tool.getToolCode(), e.getMessage());
            return AiToolResult.failed(e.getMessage() == null ? "HTTP 工具执行失败" : e.getMessage());
        }
    }

    private AiToolResult doExecute(AiToolContext context) throws Exception {
        String method = AiJson.text(config, "method", "GET").toUpperCase(Locale.ROOT);
        Function<String, String> secrets = key -> lookupSecret(key, guard.getSecrets());
        ObjectNode args = context.arguments() == null
                ? AiJson.mapper().createObjectNode() : context.arguments();

        String url = AiJson.text(config, "url", "");
        Matcher shape = URL_SHAPE.matcher(url);
        if (!shape.matches()) {
            throw new AiException("AI008", "url 非法: " + url);
        }
        String base = shape.group(1).toLowerCase(Locale.ROOT) + "://" + shape.group(2);
        String tail = shape.group(3) == null ? "" : shape.group(3);
        int queryStart = tail.indexOf('?');
        String pathPart = queryStart >= 0 ? tail.substring(0, queryStart) : tail;
        String queryPart = queryStart >= 0 ? tail.substring(queryStart + 1) : "";
        String renderedPath = render(pathPart, args, secrets, true);
        String renderedQuery = renderQuery(queryPart, args, secrets);
        URI uri = URI.create(base + renderedPath + (renderedQuery.isBlank() ? "" : "?" + renderedQuery));

        // 每次执行都重新过 SSRF 护栏（DNS 可能变化，不能只信保存时的校验）
        guardHost(hostOf(config), guard.getAllowedHosts());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(clamp(config, "timeoutMs", DEFAULT_TIMEOUT_MS)))
                .header("User-Agent", "astral-ai-http-tool/1.0");
        boolean hasContentType = false;
        JsonNode headersNode = config.get("headers");
        if (headersNode instanceof ObjectNode headers) {
            for (Map.Entry<String, JsonNode> entry : headers.properties()) {
                String value = render(entry.getValue().asString(), args, secrets, false);
                if (value.contains("\n") || value.contains("\r")) {
                    throw new AiException("AI008", "请求头包含换行符: " + entry.getKey());
                }
                builder.header(entry.getKey(), value);
                if ("content-type".equalsIgnoreCase(entry.getKey())) {
                    hasContentType = true;
                }
            }
        }
        String body = null;
        if (WRITE_METHODS.contains(method)) {
            JsonNode bodyNode = config.get("body");
            if (bodyNode != null && bodyNode.isString() && !bodyNode.asString().isBlank()) {
                body = renderJson(bodyNode.asString(), args, secrets);
                if (!hasContentType) {
                    builder.header("Content-Type", "application/json; charset=utf-8");
                }
            }
        }
        HttpRequest request = (body == null
                ? builder.method(method, HttpRequest.BodyPublishers.noBody())
                : builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)))
                .build();
        int maxBytes = clamp(config, "maxResponseBytes", DEFAULT_MAX_RESPONSE_BYTES);
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = response.body();
        if (bytes != null && bytes.length > maxBytes) {
            throw new AiException("AI008", "响应超过体积上限 " + maxBytes + " 字节");
        }
        String text = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        if (response.statusCode() >= 400) {
            return AiToolResult.failed("HTTP " + response.statusCode() + ": " + AiJson.truncate(text, 300));
        }
        return buildSuccess(text, response.headers().firstValue("content-type").orElse(""));
    }

    /** 组装成功结果：payload 取 payloadPath 或整个响应；summary 取 summaryPath 或截断后的响应文本 */
    private AiToolResult buildSuccess(String text, String contentType) {
        ObjectNode payload = AiJson.mapper().createObjectNode();
        JsonNode parsed = contentType.toLowerCase(Locale.ROOT).contains("json") ? AiJson.read(text) : null;
        String payloadPath = AiJson.text(config, "payloadPath", "");
        JsonNode extracted = payloadPath.isEmpty() ? null : extractPath(parsed, payloadPath);
        if (extracted != null) {
            if (extracted instanceof ObjectNode object) {
                payload = object;
            } else {
                payload.set(payloadPath.contains(".") ? payloadPath.substring(payloadPath.lastIndexOf('.') + 1)
                        : payloadPath, extracted);
            }
        } else if (parsed instanceof ObjectNode object) {
            payload = object;
        } else if (!text.isBlank()) {
            payload.put("text", text);
        }
        String summaryPath = AiJson.text(config, "summaryPath", "");
        JsonNode summaryNode = summaryPath.isEmpty() ? null : extractPath(parsed, summaryPath);
        String summary;
        if (summaryNode != null) {
            summary = summaryNode.isString() ? summaryNode.asString() : AiJson.truncate(AiJson.write(summaryNode), SUMMARY_LIMIT);
        } else if (text.isBlank()) {
            summary = "HTTP 调用成功（无响应体）";
        } else {
            summary = AiJson.truncate(text, SUMMARY_LIMIT);
        }
        return AiToolResult.ok(summary, payload);
    }

    /** 简化 JSON 路径取值：对象按字段、数组按下标；取不到返回 null */
    static JsonNode extractPath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            if (current.isObject()) {
                current = current.get(segment);
            } else if (current.isArray() && segment.matches("\\d+")) {
                current = current.get(Integer.parseInt(segment));
            } else {
                return null;
            }
        }
        return current == null || current.isNull() ? null : current;
    }

    // ==================== 模板渲染 ====================

    /** 渲染 ${...} 占位符；urlEncode 时对替换值做百分号编码（+ → %20） */
    static String render(String template, ObjectNode args, Function<String, String> secrets, boolean urlEncode) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = key.startsWith("secret:")
                    ? secrets.apply(key.substring("secret:".length())) : argText(args, key);
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(urlEncode ? encode(value) : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 渲染 query 串：按 & 拆键值对，键与值分别渲染并编码 */
    static String renderQuery(String queryPart, ObjectNode args, Function<String, String> secrets) {
        if (queryPart == null || queryPart.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String pair : queryPart.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            if (result.length() > 0) {
                result.append('&');
            }
            result.append(encode(render(key, args, secrets, false)))
                    .append('=')
                    .append(encode(render(value, args, secrets, false)));
        }
        return result.toString();
    }

    /** body 模板渲染：字符串参数按 JSON 转义，对象/数组/数字/布尔原样内联 */
    static String renderJson(String template, ObjectNode args, Function<String, String> secrets) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement;
            if (key.startsWith("secret:")) {
                replacement = AiJson.mapper()
                        .writeValueAsString(secrets.apply(key.substring("secret:".length())));
            } else {
                JsonNode arg = args == null ? null : args.get(key);
                if (arg == null || arg.isNull()) {
                    throw new AiException("AI008", "缺少参数: " + key);
                }
                replacement = arg.isObject() || arg.isArray() || arg.isNumber() || arg.isBoolean()
                        ? AiJson.write(arg) : AiJson.mapper().writeValueAsString(arg.asString());
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 取标量参数文本；对象/数组参数序列化为 JSON 文本；缺失直接报错 */
    private static String argText(ObjectNode args, String key) {
        JsonNode arg = args == null ? null : args.get(key);
        if (arg == null || arg.isNull()) {
            throw new AiException("AI008", "缺少参数: " + key);
        }
        return arg.isObject() || arg.isArray() ? AiJson.write(arg) : arg.asString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 密钥解析：优先配置 {@code astral.plugins.ai.http-tool.secrets.{key}}，其次同名环境变量。
     * 两处都没有就报错——模板里只存引用，明文永远不落库。
     */
    static String lookupSecret(String key, Map<String, String> configured) {
        String value = configured == null ? null : configured.get(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        if (value == null || value.isBlank()) {
            throw new AiException("AI008", "找不到密钥: " + key
                    + "（请在 astral.plugins.ai.http-tool.secrets 或同名环境变量里配置）");
        }
        return value;
    }

    /** 上限内收敛（执行期宽容，校验期在 requireValidShape 里报错） */
    private static int clamp(ObjectNode config, String field, int def) {
        int min = "timeoutMs".equals(field) ? MIN_TIMEOUT_MS : MIN_MAX_RESPONSE_BYTES;
        int max = "timeoutMs".equals(field) ? MAX_TIMEOUT_MS : MAX_MAX_RESPONSE_BYTES;
        int value = AiJson.intValue(config, field, def);
        return Math.max(min, Math.min(max, value));
    }
}
