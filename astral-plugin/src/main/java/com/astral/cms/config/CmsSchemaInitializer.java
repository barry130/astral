package com.astral.cms.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * 内容管理插件表结构初始化器
 * <p>
 * 应用启动时自动执行 cms-schema.sql（CREATE TABLE IF NOT EXISTS，幂等）。
 * 受 astral.plugins.cms.enabled 控制：插件关闭时跳过建表。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "astral.plugins.cms.enabled", havingValue = "true", matchIfMissing = true)
public class CmsSchemaInitializer {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setContinueOnError(true);
            populator.setIgnoreFailedDrops(true);
            populator.addScript(new ClassPathResource("sql/cms-schema.sql"));
            populator.execute(jdbcTemplate.getDataSource());
            log.info("[CmsPlugin] 内容管理表结构初始化完成（cms_post/cms_category/cms_tag/cms_post_tag/cms_page/cms_comment/cms_setting）");
        } catch (Exception e) {
            log.error("[CmsPlugin] 内容管理表结构初始化失败", e);
            throw e;
        }
    }
}
