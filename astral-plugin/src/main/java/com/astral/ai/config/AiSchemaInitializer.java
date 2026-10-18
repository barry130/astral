package com.astral.ai.config;

import com.astral.ai.AiPropertiesHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * AI 插件启动建表。
 *
 * <p>与 {@code FeedbackSchemaInitializer} 同一策略：脚本幂等（{@code CREATE TABLE IF NOT EXISTS}），
 * 生产环境的标准路径是 {@code astral-server/src/main/resources/db/migration/V20261019002__ai_schema.sql}，
 * 这里是给「插件直接跑在存量库上」的兜底。失败必须抛出，不允许静默降级，
 * 否则后续所有 AI 请求都会以表不存在告败。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "astral.plugins.ai.enabled", havingValue = "true", matchIfMissing = true)
public class AiSchemaInitializer {

    @Resource
    private DataSource dataSource;

    @Resource
    private AiProperties aiProperties;

    @PostConstruct
    public void init() {
        if (!aiProperties.isAutoSchema()) {
            log.info("[AiSchemaInitializer] astral.plugins.ai.auto-schema=false，跳过插件建表");
            return;
        }
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setContinueOnError(true);
            populator.setIgnoreFailedDrops(true);
            populator.addScript(new ClassPathResource("sql/ai-schema.sql"));
            populator.execute(dataSource);
            AiPropertiesHolder.markSchemaReady();
            log.info("[AiSchemaInitializer] AI 表结构初始化完成");
        } catch (Exception e) {
            log.error("[AiSchemaInitializer] AI 表结构初始化失败", e);
            throw e;
        }
    }
}
