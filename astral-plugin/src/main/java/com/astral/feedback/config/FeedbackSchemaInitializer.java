package com.astral.feedback.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * 反馈插件表结构初始化器
 * <p>
 * 应用启动时自动执行 feedback-schema.sql（CREATE TABLE IF NOT EXISTS，幂等）。
 * 受 astral.plugins.feedback.enabled 控制：插件关闭时跳过建表。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "astral.plugins.feedback.enabled", havingValue = "true", matchIfMissing = true)
public class FeedbackSchemaInitializer {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setContinueOnError(true);
            populator.setIgnoreFailedDrops(true);
            populator.addScript(new ClassPathResource("sql/feedback-schema.sql"));
            populator.execute(jdbcTemplate.getDataSource());
            log.info("[FeedbackPlugin] 反馈/通知表结构初始化完成（sys_feedback/sys_feedback_reply/sys_notice）");
        } catch (Exception e) {
            log.error("[FeedbackPlugin] 反馈/通知表结构初始化失败", e);
            throw e;
        }
    }
}
