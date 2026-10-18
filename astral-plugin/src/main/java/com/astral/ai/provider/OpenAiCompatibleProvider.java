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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容协议的提供方实现。
 *
 * <p>用 JDK {@link HttpClient} 直连，不引入额外 SDK——厂商差异（路径、鉴权头、字段名）
 * 通过 {@code base_url} 与 {@code extra_parameters_json} 吸收。安全要点：</p>
 * <ul>
 *   <li>不记录请求头与密钥；日志只打 traceId 与状态码；</li>
 *   <li>连接/读取/整体超时都有上限，避免长挂；</li>
 *   <li>流式解析按 SSE 逐行读取，遇到 {@code [DONE]} 收尾；</li>
 *   <li>上游错误体截断后写入异常消息，避免把整页 HTML 灌进日志。</li>
 * </ul>
 */
@Slf4j
@Component
public class OpenAiCompatibleProvider implements AiProvider {

    /** 提供方类型编码 */
    public static final String TYPE = "OPENAI_COMPATIBLE";

    /** 非流式响应体上限 4MB，防止异常上游打爆内存 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** 上游错误信息截断长度 */
    private static final int MAX_ERROR_CHARS = 400;

    /** 空闲读超时默认值：上游 90 秒不吐一个字就当中断（配置里给了 readTimeoutMs 时以配置为准） */
    private static final int DEFAULT_READ_TIMEOUT_MS = 90_000;

    /**
     * SSE 单行读取的执行器。
     *
     * <p>JDK {@code HttpRequest.timeout} 管不到响应体流，读阻塞时只能靠「外部掐断」，
     * 所以每次读都丢到这里的线程上，由调用线程带超时等待。用虚拟线程：一次生成只占
     * 一个读任务，不会有队列压力；命名便于 jstack 排查。</p>
     */
    private static final java.util.concurrent.ExecutorService READ_EXECUTOR =
            java.util.concurrent.Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("ai-sse-read-", 0).factory());

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        HttpClient client = buildClient(request);
        HttpRequest httpRequest = buildHttpRequest(request, false);
        try {
            HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            String body = readBody(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiException("AI006", "上游返回 " + response.statusCode() + ": " + truncate(body));
            }
            ChatResult result = parseChatResponse(body);
            log.debug("[OpenAi] chat traceId={} status={} cost={}ms tokens={}/{}",
                    request.traceId(), response.statusCode(), System.currentTimeMillis() - start,
                    result.promptTokens(), result.completionTokens());
            return result;
        } catch (AiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("AI006", "请求被中断");
        } catch (Exception e) {
            log.warn("[OpenAi] chat 失败 traceId={}: {}", request.traceId(), e.getMessage());
            throw new AiException("AI006", truncate(e.getMessage()));
        }
    }

    @Override
    public void stream(ChatRequest request, StreamListener listener) {
        HttpClient client = buildClient(request);
        HttpRequest httpRequest = buildHttpRequest(request, true);
        AtomicBoolean finished = new AtomicBoolean(false);
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
        int[] tokens = new int[2];
        String[] finishReason = {"stop"};

        try {
            HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = readBody(response.body());
                throw new AiException("AI006", "上游返回 " + response.statusCode() + ": " + truncate(body));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readLineWithIdleGuard(reader, request)) != null) {
                    if (line.isEmpty() || line.startsWith(":")) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JsonNode node = AiJson.read(payload);
                    if (node == null) {
                        continue;
                    }
                    JsonNode error = node.get("error");
                    if (error != null && !error.isNull()) {
                        throw new AiException("AI006", truncate(AiJson.text(error, "message", "上游返回错误")));
                    }
                    JsonNode usage = node.get("usage");
                    if (usage != null && !usage.isNull()) {
                        tokens[0] = AiJson.intValue(usage, "prompt_tokens", tokens[0]);
                        tokens[1] = AiJson.intValue(usage, "completion_tokens", tokens[1]);
                    }
                    JsonNode choices = node.get("choices");
                    if (choices == null || !choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    JsonNode choice = choices.get(0);
                    String reason = AiJson.text(choice, "finish_reason", null);
                    if (reason != null) {
                        finishReason[0] = reason;
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
                            toolCalls.computeIfAbsent(index, key -> new ToolCallAccumulator()).merge(call);
                        }
                    }
                }
            }
            if (finished.compareAndSet(false, true)) {
                List<ToolCall> resolved = new ArrayList<>();
                for (ToolCallAccumulator accumulator : toolCalls.values()) {
                    ToolCall call = accumulator.build();
                    if (call != null) {
                        resolved.add(call);
                    }
                }
                listener.onCompleted(new ChatResult(content.toString(), finishReason[0],
                        tokens[0], tokens[1], resolved));
            }
        } catch (AiException e) {
            failOnce(finished, listener, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failOnce(finished, listener, new AiException("AI006", "请求被中断"));
        } catch (Exception e) {
            log.warn("[OpenAi] stream 失败 traceId={}: {}", request.traceId(), e.getMessage());
            failOnce(finished, listener, new AiException("AI006", truncate(e.getMessage())));
        }
    }

    @Override
    public HealthResult testConnection(ChatRequest request) {
        long start = System.currentTimeMillis();
        try {
            ChatRequest probe = new ChatRequest(
                    request.baseUrl(), request.apiKey(), request.modelCode(),
                    List.of(Message.user("ping")),
                    null, 0D, null, 8, null, null,
                    Math.min(request.connectTimeoutMs(), 8000),
                    Math.min(request.readTimeoutMs(), 15000),
                    0, request.traceId());
            HttpClient client = buildClient(probe);
            HttpRequest httpRequest = buildHttpRequest(probe, false);
            HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            String body = readBody(response.body());
            long latency = System.currentTimeMillis() - start;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return HealthResult.ok(latency);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return HealthResult.fail("凭证无效或无权访问（HTTP " + response.statusCode() + "）");
            }
            return HealthResult.fail("HTTP " + response.statusCode() + ": " + truncate(body));
        } catch (Exception e) {
            return HealthResult.fail(truncate(e.getMessage()));
        }
    }

    private void failOnce(AtomicBoolean finished, StreamListener listener, Throwable error) {
        if (finished.compareAndSet(false, true)) {
            listener.onError(error);
        }
    }

    private HttpClient buildClient(ChatRequest request) {
        int connect = request.connectTimeoutMs() > 0 ? request.connectTimeoutMs() : 10000;
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connect))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private HttpRequest buildHttpRequest(ChatRequest request, boolean stream) {
        String url = endpoint(request.baseUrl());
        ObjectNode body = AiJson.mapper().createObjectNode();
        body.put("model", request.modelCode());
        body.put("stream", stream);
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
                function.set("parameters", params != null && params.isObject()
                        ? params : AiJson.mapper().createObjectNode().put("type", "object"));
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
        if (request.responseFormatJson() != null && !request.responseFormatJson().isBlank()) {
            JsonNode format = AiJson.read(request.responseFormatJson());
            if (format != null && format.isObject()) {
                body.set("response_format", format);
            }
        }
        // 厂商扩展参数（如 Ollama 的 options、智谱的 do_sample）直接平铺进请求体
        JsonNode extra = AiJson.read(request.extraParametersJson());
        if (extra != null && extra.isObject()) {
            for (Map.Entry<String, JsonNode> entry : extra.properties()) {
                if (body.has(entry.getKey())) {
                    continue;
                }
                body.set(entry.getKey(), entry.getValue());
            }
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(request.readTimeoutMs() > 0 ? request.readTimeoutMs() : 120000))
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        AiJson.write(body), StandardCharsets.UTF_8));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + request.apiKey());
        }
        if (request.traceId() != null) {
            builder.header("X-Request-Id", request.traceId());
        }
        return builder.build();
    }

    /** 拼接 completions 路径：base_url 已带 /v1 或已是完整端点都能工作 */
    static String endpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private String readBody(InputStream stream) {
        try (InputStream in = stream) {
            byte[] buffer = in.readNBytes(MAX_RESPONSE_BYTES);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private ChatResult parseChatResponse(String body) {
        JsonNode node = AiJson.read(body);
        if (node == null) {
            throw new AiException("AI006", "上游响应不是合法 JSON");
        }
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            throw new AiException("AI006", truncate(AiJson.text(error, "message", "上游返回错误")));
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
                    calls.add(new ToolCall(id, AiJson.text(function, "name", ""),
                            AiJson.text(function, "arguments", "{}")));
                }
            }
        }
        int promptTokens = 0;
        int completionTokens = 0;
        JsonNode usage = node.get("usage");
        if (usage != null && !usage.isNull()) {
            promptTokens = AiJson.intValue(usage, "prompt_tokens", 0);
            completionTokens = AiJson.intValue(usage, "completion_tokens", 0);
        }
        return new ChatResult(content == null ? "" : content,
                AiJson.text(choice, "finish_reason", "stop"), promptTokens, completionTokens, calls);
    }

    private static String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "上游无响应内容";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= MAX_ERROR_CHARS ? single : single.substring(0, MAX_ERROR_CHARS) + "...";
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
            return new ToolCall(id == null ? "call_" + Math.abs(name.hashCode()) : id, name, args);
        }
    }

    /**
     * 带空闲上限的 {@code readLine}。
     *
     * <p>{@link HttpRequest#timeout} 只覆盖到「收到响应头」为止，对
     * {@code BodyHandlers.ofInputStream()} 返回的响应体流不生效：上游把 SSE 头发完
     * 之后不再吐数据（网关挂住、连接被中间设备黑洞），{@code readLine()} 就会永久
     * 阻塞，把编排线程占死，界面上表现为请求一直「生成中」、token 恒为 0。</p>
     *
     * <p>这里把每次读取放到独立线程上跑，主线程最多等
     * {@code readTimeoutMs}；超时就关掉响应流（让阻塞的读立刻抛异常退出）并抛
     * {@code AI006}，把失败暴露给用户，而不是静默卡住。用「单次读取」而不是
     * 「整段耗时」作上限，是为了不误杀正常的长回答：只要上游还在吐字就一直续期。</p>
     */
    private String readLineWithIdleGuard(BufferedReader reader, ChatRequest request) throws Exception {
        int timeout = request.readTimeoutMs() > 0 ? request.readTimeoutMs() : DEFAULT_READ_TIMEOUT_MS;
        java.util.concurrent.Future<String> future =
                READ_EXECUTOR.submit(() -> reader.readLine());
        try {
            return future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new AiException("AI006", "上游 " + (timeout / 1000)
                    + " 秒内没有返回任何数据，已中断本次生成，请重试");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new AiException("AI006", truncate(String.valueOf(cause)));
        }
    }
}
