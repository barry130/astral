package com.astral.ai.provider;

import com.astral.ai.provider.AiProviderDtos.ChatRequest;
import com.astral.ai.provider.AiProviderDtos.ChatResult;
import com.astral.ai.provider.AiProviderDtos.HealthResult;
import com.astral.ai.provider.AiProviderDtos.StreamListener;

/**
 * AI 提供方抽象（设计文档 §5.1）。
 *
 * <p>一期只实现 OpenAI 兼容协议（覆盖 OpenAI、DeepSeek、通义、智谱、Moonshot、
 * Ollama、vLLM 等），因此接口本身保持极小；新增厂商时实现本接口并注册为 Spring Bean，
 * 由 {@code AiProviderRegistry} 按 {@code provider_type} 路由。</p>
 */
public interface AiProvider {

    /**
     * 提供方类型编码，需与 {@code ai_provider.provider_type} 及字典一致。
     *
     * @return 类型编码，如 {@code OPENAI_COMPATIBLE}
     */
    String type();

    /**
     * 非流式对话。
     *
     * @param request 请求快照
     * @return 结果
     */
    ChatResult chat(ChatRequest request);

    /**
     * 流式对话。实现必须保证：正常结束调 {@code onCompleted}，异常调 {@code onError}，
     * 且两者最多被调用一次。
     *
     * @param request  请求快照
     * @param listener 回调
     */
    void stream(ChatRequest request, StreamListener listener);

    /**
     * 连接与凭证检查。
     *
     * @param request 使用最小请求探测
     * @return 健康结果
     */
    HealthResult testConnection(ChatRequest request);
}
