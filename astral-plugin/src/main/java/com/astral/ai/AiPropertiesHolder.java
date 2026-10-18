package com.astral.ai;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 插件运行期状态标记（不依赖 Spring 容器的极简单例）。
 *
 * <p>用于在没有注入 {@code AiProperties} 的极少数场景（拦截器、静态工具、健康检查）
 * 判断建表是否就绪，避免在表缺失时发起查询把错误放大成 500。</p>
 */
@Slf4j
public final class AiPropertiesHolder {

    private static volatile boolean schemaReady = false;

    private AiPropertiesHolder() {
    }

    public static void markSchemaReady() {
        schemaReady = true;
        log.debug("[AiPropertiesHolder] schemaReady=true");
    }

    public static boolean isSchemaReady() {
        return schemaReady;
    }
}
