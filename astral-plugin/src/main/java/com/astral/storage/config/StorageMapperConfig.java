package com.astral.storage.config;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * storage 插件 Mapper 扫描配置
 * <p>独立于功能开关：Mapper Bean 始终注册，避免插件关闭时服务注入失败。</p>
 */
@Slf4j
@Configuration
@MapperScan("com.astral.storage.mapper")
public class StorageMapperConfig {
    static {
        log.info("[StoragePlugin] 存储 Mapper 扫描已注册 (com.astral.storage.mapper)");
    }
}
