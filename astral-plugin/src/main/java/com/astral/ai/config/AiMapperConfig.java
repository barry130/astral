package com.astral.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * AI 插件 Mapper 扫描。
 *
 * <p>独立于插件功能开关：Mapper Bean 始终注册，避免插件关闭时其它 Bean 注入失败。
 * 与 {@code StorageMapperConfig} / {@code QtMapperConfig} 保持同一策略。</p>
 */
@Slf4j
@Configuration
@MapperScan("com.astral.ai.mapper")
public class AiMapperConfig {

    static {
        log.info("[AiMapperConfig] 扫描 com.astral.ai.mapper");
    }
}
