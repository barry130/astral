package com.astral.ai.provider;

import com.astral.ai.common.AiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 提供方实现注册表。
 *
 * <p>按 {@code ai_provider.provider_type} 路由到具体实现。当前内置
 * {@code OPENAI_COMPATIBLE}；如需接入签名差异较大的厂商，实现 {@link AiProvider}
 * 并声明 {@code @Component} 即可，无需改动编排逻辑。</p>
 */
@Slf4j
@Component
public class AiProviderRegistry {

    private final Map<String, AiProvider> providers = new HashMap<>();

    public AiProviderRegistry(List<AiProvider> implementations) {
        for (AiProvider provider : implementations) {
            providers.put(provider.type().toUpperCase(java.util.Locale.ROOT), provider);
        }
        log.info("[AiProviderRegistry] 已注册提供方实现: {}", providers.keySet());
    }

    /**
     * 按类型取实现。
     *
     * @param type 提供方类型
     * @return 实现
     */
    public AiProvider require(String type) {
        if (type == null || type.isBlank()) {
            throw new AiException("AI006", "未指定提供方类型");
        }
        AiProvider provider = providers.get(type.trim().toUpperCase(java.util.Locale.ROOT));
        if (provider == null) {
            throw new AiException("AI006", "不支持的提供方类型: " + type);
        }
        return provider;
    }

    /** 已支持的类型集合（供后台校验） */
    public Set<String> supportedTypes() {
        return new TreeSet<>(providers.keySet());
    }
}
