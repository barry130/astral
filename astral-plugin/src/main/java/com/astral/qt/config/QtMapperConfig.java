package com.astral.qt.config;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 轻听插件 Mapper 扫描配置
 * <p>独立于功能开关：Mapper Bean 始终注册，避免插件关闭时服务注入失败。</p>
 */
@Slf4j
@Configuration
@MapperScan("com.astral.qt.mapper")
public class QtMapperConfig {
    static {
        log.info("[QtPlugin] 轻听 Mapper 扫描已注册 (com.astral.qt.mapper)");
    }
}