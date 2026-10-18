package com.astral.ai.provider;

import java.util.List;

/**
 * AI 提供方数据结构。
 *
 * <p>只用不可变 record，保证「请求一旦构造就不再被改写」——流式回调与重试都在
 * 同一份快照上进行，避免并发下参数漂移。</p>
 */
public final class AiProviderDtos {

    private AiProviderDtos() {
    }

    /** 对话消息（OpenAI 兼容格式） */
    public record Message(String role, String content, String toolCallId, String toolName,
                          List<ToolCall> toolCalls) {

        public static Message system(String content) {
            return new Message("system", content, null, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null, null);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content, null, null, null);
        }

        public static Message assistantToolCalls(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, null, toolCalls);
        }

        public static Message tool(String toolCallId, String toolName, String content) {
            return new Message("tool", content, toolCallId, toolName, null);
        }
    }

    /** 模型请求的工具调用 */
    public record ToolCall(String id, String name, String arguments) {
    }

    /** 工具声明（发给模型） */
    public record ToolSpec(String name, String description, String parametersJson) {
    }

    /** 一次对话请求 */
    public record ChatRequest(
            String baseUrl,
            String apiKey,
            String modelCode,
            List<Message> messages,
            List<ToolSpec> tools,
            Double temperature,
            Double topP,
            Integer maxTokens,
            String responseFormatJson,
            String extraParametersJson,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxRetries,
            String traceId
    ) {
    }

    /** 非流式结果 */
    public record ChatResult(
            String content,
            String finishReason,
            int promptTokens,
            int completionTokens,
            List<ToolCall> toolCalls
    ) {
        public static ChatResult empty() {
            return new ChatResult("", "stop", 0, 0, List.of());
        }
    }

    /** 健康检查结果 */
    public record HealthResult(boolean healthy, String message, long latencyMs) {
        public static HealthResult ok(long latencyMs) {
            return new HealthResult(true, "连接正常", latencyMs);
        }

        public static HealthResult fail(String message) {
            return new HealthResult(false, message, 0L);
        }
    }

    /** 流式回调 */
    public interface StreamListener {

        /** 收到增量文本 */
        void onDelta(String text);

        /** 收到增量推理内容（部分模型支持，默认忽略） */
        default void onReasoning(String text) {
        }

        /** 流正常结束 */
        void onCompleted(ChatResult result);

        /** 流出错（连接中断、上游报错等） */
        void onError(Throwable error);
    }
}
