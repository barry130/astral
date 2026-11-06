package com.astral.ai.tool.declarative;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 极简 MCP 客户端（Streamable HTTP 传输，2025-03-26 规范）。
 *
 * <p>只实现本项目需要的三个方法：{@code initialize} / {@code tools/list} / {@code tools/call}，
 * 外加会话关闭。选择手写 JSON-RPC 而不是引入官方 SDK，是为了不给插件模块拖进
 * 一套新的 HTTP/Jackson 依赖栈——请求体、会话头、SSE 分帧都可以在百来行内说清楚，
 * 也便于单测覆盖。约定：</p>
 * <ul>
 *   <li>请求一律 POST，{@code Accept: application/json, text/event-stream}；</li>
 *   <li>{@code initialize} 的响应头 {@code Mcp-Session-Id} 在后续请求里原样带回；</li>
 *   <li>响应既可能是普通 JSON，也可能是 SSE（每帧 {@code data: {...}}），两种都要能解析；</li>
 *   <li>地址沿用 HTTP 工具的 SSRF 护栏（私网/环回/链路本地/云元数据一律拒绝，可选白名单收紧）。</li>
 * </ul>
 */
@Slf4j
public class McpClient implements AutoCloseable {

    /** 握手使用的协议版本 */
    public static final String PROTOCOL_VERSION = "2025-03-26";

    /** 客户端标识（出现在 initialize 的 clientInfo 里，便于远端排障） */
    private static final String CLIENT_NAME = "astral-ai";

    private static final String CLIENT_VERSION = "1.0.0";

    private static final int DEFAULT_TIMEOUT_SECONDS = 20;
    private static final int MAX_TOOLS = 500;

    private final URI endpoint;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final Duration timeout;
    private final AtomicLong nextId = new AtomicLong(1);
    private String sessionId;

    private McpClient(URI endpoint, Map<String, String> headers, Duration timeout) {
        this.endpoint = endpoint;
        this.headers = headers;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 建立连接并完成 initialize 握手。
     *
     * @param url           MCP 端点（必须是无模板的 http(s) 字面量地址）
     * @param headersJson   附加请求头 JSON（值可写 ${secret:key}），可空
     * @param allowedHosts  主机白名单（来自配置，可空表示只做私网拦截）
     * @param secrets       ${secret:key} 的取值来源
     * @param timeoutSeconds 连接与读超时（秒）
     * @return 已握手的客户端
     */
    public static McpClient connect(
            String url,
            String headersJson,
            List<String> allowedHosts,
            Map<String, String> secrets,
            Integer timeoutSeconds) {
        URI endpoint = requireEndpoint(url, allowedHosts);
        int seconds = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : Math.max(3, Math.min(60, timeoutSeconds));
        McpClient client = new McpClient(endpoint, renderHeaders(headersJson, secrets), Duration.ofSeconds(seconds));
        client.initialize();
        return client;
    }

    // ==================== 对外方法 ====================

    /** 远端工具定义 */
    public record McpToolInfo(
            String name, String title, String description, JsonNode inputSchema, Boolean readOnlyHint) {}

    /** 列出远端全部工具（自动翻页） */
    public List<McpToolInfo> listTools() {
        List<McpToolInfo> tools = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            ObjectNode params = AiJson.mapper().createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params);
            JsonNode items = result == null ? null : result.get("tools");
            if (items instanceof ArrayNode array) {
                for (JsonNode item : array) {
                    McpToolInfo info = toolInfo(item);
                    if (info != null) {
                        tools.add(info);
                    }
                }
            }
            cursor = AiJson.text(result, "nextCursor", null);
            guard++;
        } while (cursor != null && !cursor.isBlank() && guard < 20 && tools.size() < MAX_TOOLS);
        return tools;
    }

    /**
     * 调用一个远端工具。
     *
     * @param toolName  远端工具名
     * @param arguments 参数（按远端 inputSchema）
     * @return 工具结果：{@code text} 为拼接后的文本内容，{@code payload} 为原始 result
     */
    public McpCallResult callTool(String toolName, ObjectNode arguments) {
        ObjectNode params = AiJson.mapper().createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null ? AiJson.mapper().createObjectNode() : arguments);
        JsonNode result = request("tools/call", params);
        boolean error = AiJson.bool(result, "isError", false);
        StringBuilder text = new StringBuilder();
        JsonNode content = result == null ? null : result.get("content");
        if (content instanceof ArrayNode array) {
            for (JsonNode item : array) {
                String type = AiJson.text(item, "type", "");
                if ("text".equals(type)) {
                    String value = AiJson.text(item, "text", "");
                    if (!value.isBlank()) {
                        text.append(text.isEmpty() ? "" : "\n").append(value);
                    }
                } else if (!type.isBlank()) {
                    text.append(text.isEmpty() ? "" : "\n")
                            .append("[")
                            .append(type)
                            .append("]");
                }
            }
        }
        ObjectNode payload = AiJson.mapper().createObjectNode();
        if (result != null) {
            payload.set("content", result.get("content"));
            payload.put("isError", error);
        }
        return new McpCallResult(error, text.toString(), payload);
    }

    /** 工具调用结果 */
    public record McpCallResult(boolean error, String text, ObjectNode payload) {}

    @Override
    public void close() {
        if (sessionId == null) {
            return;
        }
        try {
            HttpRequest request =
                    baseRequest().header("Mcp-Session-Id", sessionId).DELETE().build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("[AI] 关闭 MCP 会话失败（忽略）: {}", e.getMessage());
        }
    }

    // ==================== 握手与传输 ====================

    private void initialize() {
        ObjectNode params = AiJson.mapper().createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", AiJson.mapper().createObjectNode());
        ObjectNode clientInfo = AiJson.mapper().createObjectNode();
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);
        params.set("clientInfo", clientInfo);
        request("initialize", params);
        // 规范要求握手后发一条通知，服务端回 202 且没有响应体
        notify("notifications/initialized");
    }

    private JsonNode request(String method, ObjectNode params) {
        long id = nextId.getAndIncrement();
        ObjectNode body = AiJson.mapper().createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.set("params", params == null ? AiJson.mapper().createObjectNode() : params);
        HttpResponse<String> response = send(AiJson.write(body), false);
        JsonNode envelope = parseEnvelope(response, id);
        JsonNode error = envelope.get("error");
        if (error != null && !error.isNull()) {
            throw new AiException("AI008", "MCP " + method + " 失败: " + AiJson.text(error, "message", "远端返回未知错误"));
        }
        return envelope.get("result");
    }

    private void notify(String method) {
        ObjectNode body = AiJson.mapper().createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        try {
            send(AiJson.write(body), true);
        } catch (AiException e) {
            log.debug("[AI] MCP 通知 {} 发送失败（忽略）: {}", method, e.getMessage());
        }
    }

    private HttpResponse<String> send(String body, boolean notification) {
        HttpRequest.Builder builder = baseRequest()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw new AiException("AI008", "连接 MCP 服务器超时: " + endpoint.getHost());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("AI008", "调用 MCP 服务器被中断");
        } catch (Exception e) {
            throw new AiException("AI008", "调用 MCP 服务器失败: " + describe(e));
        }
        String newSession = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        if (newSession != null && !newSession.isBlank()) {
            sessionId = newSession;
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new AiException("AI008", "MCP 服务器拒绝访问（HTTP " + status + "），请检查请求头里的凭据");
        }
        if (status == 404) {
            throw new AiException("AI008", "MCP 端点不存在（HTTP 404）: " + endpoint);
        }
        if (status == 405) {
            throw new AiException("AI008", "该地址不支持 Streamable HTTP（HTTP 405），请确认是 MCP 端点");
        }
        if (status >= 400) {
            String hint = AiJson.truncate(response.body(), 200);
            throw new AiException("AI008", "MCP 服务器返回 HTTP " + status + (hint == null ? "" : "：" + hint));
        }
        if (notification) {
            return response;
        }
        return response;
    }

    private HttpRequest.Builder baseRequest() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    /** 解析响应体：普通 JSON 或 SSE 帧，取 id 匹配的那条 JSON-RPC 报文 */
    static JsonNode parseEnvelope(HttpResponse<String> response, long id) {
        return parseEnvelope(response.headers().firstValue("Content-Type").orElse(""), response.body(), id);
    }

    /**
     * 解析响应文本（纯函数，便于单测）。
     *
     * @param contentType 响应头 Content-Type
     * @param body        响应体
     * @param id          期望的 JSON-RPC id
     * @return 匹配的报文
     */
    static JsonNode parseEnvelope(String contentType, String body, long id) {
        String contentType2 = contentType == null ? "" : contentType;
        if (contentType2.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
            JsonNode matched = firstSseMatching(body, id);
            if (matched != null) {
                return matched;
            }
            throw new AiException("AI008", "MCP 响应里没有 id=" + id + " 的报文");
        }
        JsonNode node = AiJson.read(body);
        if (node == null || !node.isObject()) {
            throw new AiException("AI008", "MCP 响应不是合法 JSON: " + AiJson.truncate(body, 200));
        }
        JsonNode nodeId = node.get("id");
        if (nodeId == null || nodeId.asLong() != id) {
            throw new AiException("AI008", "MCP 响应的 id 不匹配（期望 " + id + "）");
        }
        return node;
    }

    /** 在 SSE 帧里找 id 匹配的 data 报文 */
    static JsonNode firstSseMatching(String body, long id) {
        for (String frame : splitSseFrames(body)) {
            JsonNode node = AiJson.read(frame);
            if (node == null || !node.isObject()) {
                continue;
            }
            JsonNode nodeId = node.get("id");
            if (nodeId != null && nodeId.isNumber() && nodeId.asLong() == id) {
                return node;
            }
        }
        return null;
    }

    /** 把 SSE 响应拆成 data 负载列表（事件之间以空行分隔） */
    static List<String> splitSseFrames(String body) {
        List<String> payloads = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return payloads;
        }
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\r?\n")) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    payloads.add(data.toString());
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring(5).trim();
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value);
            } else if (line.startsWith(":")) {
                // 注释帧（如心跳），跳过
                continue;
            }
        }
        if (!data.isEmpty()) {
            payloads.add(data.toString());
        }
        return payloads;
    }

    // ==================== 配置解析 ====================

    /** 校验端点地址：必须是 http(s) 字面量、host 不能是模板、必须通过 SSRF 护栏 */
    public static URI requireEndpoint(String url, List<String> allowedHosts) {
        if (url == null || url.isBlank()) {
            throw new AiException("AI008", "MCP 地址不能为空");
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new AiException("AI008", "MCP 地址必须是 http(s) 地址");
        }
        if (trimmed.contains("${")) {
            throw new AiException("AI008", "MCP 地址不支持占位符，请填写字面量地址");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new AiException("AI008", "MCP 地址格式非法: " + trimmed);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new AiException("AI008", "MCP 地址缺少主机名: " + trimmed);
        }
        HttpToolExecutor.guardHost(host, allowedHosts);
        return uri;
    }

    /** 渲染附加请求头（值里的 ${secret:key} 从配置取，不落库） */
    static Map<String, String> renderHeaders(String headersJson, Map<String, String> secrets) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode node = AiJson.read(headersJson);
        if (!(node instanceof ObjectNode object)) {
            return result;
        }
        Map<String, String> configured = secrets == null ? Map.of() : secrets;
        ObjectNode args = AiJson.mapper().createObjectNode();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            String key = entry.getKey();
            if (!HttpToolExecutor.KEY_SHAPE.matcher(key).matches()) {
                throw new AiException("AI008", "请求头名称非法: " + key);
            }
            String rendered = HttpToolExecutor.render(
                    value.asString(), args, name -> HttpToolExecutor.lookupSecret(name, configured), false);
            result.put(key, rendered);
        }
        return result;
    }

    static McpToolInfo toolInfo(JsonNode item) {
        String name = AiJson.text(item, "name", null);
        if (name == null || name.isBlank()) {
            return null;
        }
        String title = AiJson.text(item, "title", null);
        String description = AiJson.text(item, "description", null);
        JsonNode schema = item == null ? null : item.get("inputSchema");
        JsonNode annotations = item == null ? null : item.get("annotations");
        Boolean readOnlyHint = null;
        if (annotations != null
                && annotations.has("readOnlyHint")
                && annotations.get("readOnlyHint").isBoolean()) {
            readOnlyHint = annotations.get("readOnlyHint").asBoolean();
        }
        return new McpToolInfo(name, title, description, schema, readOnlyHint);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
