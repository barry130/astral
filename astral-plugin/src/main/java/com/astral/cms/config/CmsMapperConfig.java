package com.astral.cms.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 内容管理插件 Mapper 注册
 */
@Configuration
@ConditionalOnProperty(name = "astral.plugins.cms.enabled", havingValue = "true", matchIfMissing = true)
@MapperScan("com.astral.cms.mapper")
public class CmsMapperConfig {
}
