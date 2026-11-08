package com.astral.ai.provider;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.provider.AiProviderDtos.ChatRequest;
import com.astral.ai.provider.AiProviderDtos.ChatResult;
import com.astral.ai.provider.AiProviderDtos.HealthResult;
import com.astral.ai.provider.AiProviderDtos.Message;
import com.astral.ai.provider.AiProviderDtos.StreamListener;
import com.astral.ai.provider.AiProviderDtos.ToolCall;
import com.astral.ai.provider.AiProviderDtos.ToolSpec;
import com.astral.ai.security.AiUrlGuard;
import com.astral.common.http.BoundedHttpTransport;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI 兼容协议的提供方实现。
 *
 * <p>通过共享有界 HTTP 传输连接，厂商差异（路径、鉴权头、字段名）
 * 通过 {@code base_url} 与 {@code extra_parameters_json} 吸收。安全要点：</p>
 * <ul>
 *   <li>不记录请求头与密钥；日志只打 traceId 与状态码；</li>
 *   <li>连接/读取/整体超时都有上限，避免长挂；</li>
 *   <li>流式解析按 SSE 逐行读取，遇到 {@code [DONE]} 收尾；</li>
 *   <li>错误响应只返回固定原因，不反射上游正文或连接凭据。</li>
 * </ul>
 */
@Slf4j
@Component
public class OpenAiCompatibleProvider implements AiProvider {

    /** 提供方类型编码 */
    public static final String TYPE = "OPENAI_COMPATIBLE";

    /** 非流式响应体上限 4MB，防止异常上游打爆内存 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private static final int MAX_SSE_LINE_CHARS = 256 * 1024;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        try {
            ChatResult result = exchange(request, false, (status, headers, input) -> {
                requireSuccess(status);
                return parseChatResponse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            });
            log.debug(
                    "[OpenAi] chat traceId={} cost={}ms tokens={}/{}",
                    request.traceId(),
                    System.currentTimeMillis() - start,
                    result.promptTokens(),
                    result.completionTokens());
            return result;
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("AI006", "模型连接失败、响应无效或超过限制，请检查服务配置");
        }
    }

    @Override
    public void stream(ChatRequest request, StreamListener listener) {
        AtomicBoolean finished = new AtomicBoolean(false);
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        int[] tokens = {-1, -1};
        String[] finishReason = {"stop"};

        try {
            exchange(request, true, (status, headers, input) -> {
                requireSuccess(status);
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                boolean complete = false;
                String line;
                while ((line = readBoundedLine(reader)) != null) {
                    if (line.isEmpty() || line.startsWith(":")) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        complete = true;
                        break;
                    }
                    JsonNode node = AiJson.read(payload);
                    if (node == null) {
                        throw new AiException("AI006", "模型流响应格式无效");
                    }
                    JsonNode error = node.get("error");
                    if (error != null && !error.isNull()) {
                        throw new AiException("AI006", "模型服务返回错误");
                    }
                    JsonNode usage = node.get("usage");
                    if (usage != null && !usage.isNull()) {
                        tokens[0] = AiJson.intValue(usage, "prompt_tokens", tokens[0]);
                        tokens[1] = AiJson.intValue(usage, "completion_tokens", tokens[1]);
                        listener.onUsage(tokens[0], tokens[1]);
                    }
                    JsonNode choices = node.get("choices");
                    if (choices == null || !choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    JsonNode choice = choices.get(0);
                    String reason = AiJson.text(choice, "finish_reason", null);
                    if (reason != null) {
                        finishReason[0] = reason;
                        complete = true;
                    }
                    JsonNode delta = choice.get("delta");
                    if (delta == null || delta.isNull()) {
                        continue;
                    }
                    String text = AiJson.text(delta, "content", null);
                    if (text != null) {
                        content.append(text);
                        listener.onDelta(text);
                    }
                    String think = AiJson.text(delta, "reasoning_content", null);
                    if (think == null) {
                        think = AiJson.text(delta, "reasoning", null);
                    }
                    if (think != null) {
                        reasoning.append(think);
                        listener.onReasoning(think);
                    }
                    JsonNode calls = delta.get("tool_calls");
                    if (calls != null && calls.isArray()) {
                        for (JsonNode call : calls) {
                            int index = AiJson.intValue(call, "index", 0);
                            if (index < 0 || index >= 32) throw new AiException("AI006", "模型工具调用数量超过限制");
                            toolCalls
                                    .computeIfAbsent(index, key -> new ToolCallAccumulator())
                                    .merge(call);
                        }
                    }
                }
                if (!complete) throw new AiException("AI006", "模型响应提前中断，请重试");
                return null;
            });
            if (finished.compareAndSet(false, true)) {
                List<ToolCall> resolved = new ArrayList<>();
                for (ToolCallAccumulator accumulator : toolCalls.values()) {
                    ToolCall call = accumulator.build();
                    if (call != null) {
                        resolved.add(call);
                    }
                }
                listener.onCompleted(
                        new ChatResult(content.toString(), finishReason[0], tokens[0], tokens[1], resolved));
            }
        } catch (AiException e) {
            failOnce(finished, listener, e);
        } catch (Exception e) {
            failOnce(finished, listener, new AiException("AI006", "模型连接失败、响应无效或超过限制，请重试"));
        }
    }

    @Override
    public HealthResult testConnection(ChatRequest request) {
        long start = System.currentTimeMillis();
        try {
            ChatRequest probe = new ChatRequest(
                    request.baseUrl(),
                    request.apiKey(),
                    request.modelCode(),
                    List.of(Message.user("ping")),
                    null,
                    0D,
                    null,
                    8,
                    null,
                    null,
                    request.connectTimeoutMs() > 0 ? Math.min(request.connectTimeoutMs(), 8000) : 8000,
                    request.readTimeoutMs() > 0 ? Math.min(request.readTimeoutMs(), 8000) : 8000,
                    0,
                    request.traceId());
            chat(probe);
            return HealthResult.ok(System.currentTimeMillis() - start);
        } catch (Exception e) {
            return HealthResult.fail("模型连接或响应校验失败，请检查地址、凭据与模型配置");
        }
    }

    private void failOnce(AtomicBoolean finished, StreamListener listener, Throwable error) {
        if (finished.compareAndSet(false, true)) {
            listener.onError(error);
        }
    }

    private static void requireSuccess(int status) {
        if (status < 200 || status >= 300) throw new AiException("AI006", "模型服务返回 HTTP " + status);
    }

    private <T> T exchange(ChatRequest request, boolean stream, BoundedHttpTransport.StreamHandler<T> handler)
            throws java.io.IOException {
        URI target = URI.create(endpoint(request.baseUrl()));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", stream ? "text/event-stream" : "application/json");
        if (request.apiKey() != null && !request.apiKey().isBlank())
            headers.put("Authorization", "Bearer " + request.apiKey());
        if (request.traceId() != null) headers.put("X-Request-Id", request.traceId());
        int budget = request.readTimeoutMs() > 0 ? Math.min(request.readTimeoutMs(), 300000) : 90000;
        int connectBudget =
                request.connectTimeoutMs() > 0 ? Math.min(request.connectTimeoutMs(), budget) : Math.min(10000, budget);
        return BoundedHttpTransport.stream(
                target,
                "POST",
                headers,
                buildBody(request, stream),
                Duration.ofMillis(budget),
                Duration.ofMillis(connectBudget),
                MAX_RESPONSE_BYTES,
                uri -> AiUrlGuard.approve(uri, AiUrlGuard.configuredPrivateHosts()),
                handler);
    }

    private byte[] buildBody(ChatRequest request, boolean stream) {
        ObjectNode body = AiJson.mapper().createObjectNode();
        body.put("model", request.modelCode());
        body.put("stream", stream);
        if (stream) body.putObject("stream_options").put("include_usage", true);
        ArrayNode messages = body.putArray("messages");
        for (Message message : request.messages()) {
            ObjectNode item = messages.addObject();
            item.put("role", message.role());
            if (message.toolCallId() != null) {
                item.put("tool_call_id", message.toolCallId());
            }
            if (message.content() != null) {
                item.put("content", message.content());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode calls = item.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode node = calls.addObject();
                    node.put("id", call.id());
                    node.put("type", "function");
                    ObjectNode function = node.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments() == null ? "{}" : call.arguments());
                }
            }
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode node = tools.addObject();
                node.put("type", "function");
                ObjectNode function = node.putObject("function");
                function.put("name", spec.name());
                function.put("description", spec.description());
                JsonNode params = AiJson.read(spec.parametersJson());
                function.set(
                        "parameters",
                        params != null && params.isObject()
                                ? params
                                : AiJson.mapper().createObjectNode().put("type", "object"));
            }
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            body.put("top_p", request.topP());
        }
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.responseFormatJson() != null
                && !request.responseFormatJson().isBlank()) {
            JsonNode format = AiJson.read(request.responseFormatJson());
            if (format != null && format.isObject()) {
                body.set("response_format", format);
            }
        }
        // 厂商扩展参数（如 Ollama 的 options、智谱的 do_sample）直接平铺进请求体
        JsonNode extra = AiJson.read(request.extraParametersJson());
        if (extra != null && extra.isObject()) {
            for (Map.Entry<String, JsonNode> entry : extra.properties()) {
                if (java.util.Set.of(
                                        "model",
                                        "stream",
                                        "messages",
                                        "tools",
                                        "tool_choice",
                                        "max_tokens",
                                        "max_completion_tokens",
                                        "max_output_tokens",
                                        "stream_options",
                                        "n")
                                .contains(entry.getKey())
                        || body.has(entry.getKey())) {
                    continue;
                }
                body.set(entry.getKey(), entry.getValue());
            }
        }

        return AiJson.write(body).getBytes(StandardCharsets.UTF_8);
    }

    /** 拼接 completions 路径：base_url 已带 /v1 或已是完整端点都能工作 */
    static String endpoint(String baseUrl) {
        URI base = URI.create(baseUrl == null ? "" : baseUrl.trim());
        if (base.getHost() == null || base.getUserInfo() != null || base.getFragment() != null) {
            throw new AiException("AI006", "模型服务地址无效");
        }
        String path = base.getRawPath() == null ? "" : base.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.endsWith("/chat/completions")) path += "/chat/completions";
        return base.getScheme() + "://" + base.getRawAuthority() + path
                + (base.getRawQuery() == null ? "" : "?" + base.getRawQuery());
    }

    private ChatResult parseChatResponse(String body) {
        JsonNode node = AiJson.read(body);
        if (node == null) {
            throw new AiException("AI006", "上游响应不是合法 JSON");
        }
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            throw new AiException("AI006", "模型服务返回错误");
        }
        JsonNode choices = node.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new AiException("AI006", "上游未返回任何候选结果");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.get("message");
        String content = message == null ? "" : AiJson.text(message, "content", "");
        List<ToolCall> calls = new ArrayList<>();
        if (message != null) {
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    String id = AiJson.text(call, "id", null);
                    JsonNode function = call.get("function");
                    if (function == null) {
                        continue;
                    }
                    calls.add(new ToolCall(
                            id, AiJson.text(function, "name", ""), AiJson.text(function, "arguments", "{}")));
                }
            }
        }
        int promptTokens = -1;
        int completionTokens = -1;
        JsonNode usage = node.get("usage");
        if (usage != null && !usage.isNull()) {
            promptTokens = AiJson.intValue(usage, "prompt_tokens", -1);
            completionTokens = AiJson.intValue(usage, "completion_tokens", -1);
        }
        return new ChatResult(
                content == null ? "" : content,
                AiJson.text(choice, "finish_reason", "stop"),
                promptTokens,
                completionTokens,
                calls);
    }

    /** SSE 增量 tool_calls 分片累积器 */
    private static final class ToolCallAccumulator {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void merge(JsonNode node) {
            String callId = AiJson.text(node, "id", null);
            if (callId != null) {
                this.id = callId;
            }
            JsonNode function = node.get("function");
            if (function == null || function.isNull()) {
                return;
            }
            String functionName = AiJson.text(function, "name", null);
            if (functionName != null) {
                this.name = functionName;
            }
            String args = AiJson.text(function, "arguments", null);
            if (args != null) {
                this.arguments.append(args);
            }
        }

        ToolCall build() {
            if (name == null || name.isBlank()) {
                return null;
            }
            String args = arguments.isEmpty() ? "{}" : arguments.toString();
            return new ToolCall(id == null || id.isBlank() ? "call_" + UUID.randomUUID() : id, name, args);
        }
    }

    private static String readBoundedLine(BufferedReader reader) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') return line.toString();
            if (character != '\r') line.append((char) character);
            if (line.length() > MAX_SSE_LINE_CHARS) throw new java.io.IOException("模型流事件超过大小限制");
        }
        return line.isEmpty() ? null : line.toString();
    }
}
