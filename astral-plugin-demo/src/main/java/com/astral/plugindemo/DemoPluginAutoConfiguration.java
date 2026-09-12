package com.astral.plugindemo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "astral.plugins.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoPluginAutoConfiguration {

    @Bean
    public DemoPlugin demoPlugin() {
        log.info("[DemoPluginAutoConfiguration] Demo plugin auto-configuration loaded");
        return new DemoPlugin();
    }
}