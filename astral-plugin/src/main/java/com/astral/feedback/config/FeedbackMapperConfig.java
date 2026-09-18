package com.astral.feedback.config;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 反馈插件 Mapper 扫描配置
 * <p>独立于功能开关：Mapper Bean 始终注册，避免插件关闭时服务注入失败。</p>
 */
@Slf4j
@Configuration
@MapperScan("com.astral.feedback.mapper")
public class FeedbackMapperConfig {
    static {
        log.info("[FeedbackPlugin] 反馈 Mapper 扫描已注册 (com.astral.feedback.mapper)");
    }
}
