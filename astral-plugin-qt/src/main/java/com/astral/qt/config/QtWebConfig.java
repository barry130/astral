package com.astral.qt.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 轻听插件 Web 配置
 * <ul>
 *   <li>认证拦截已合并进宿主全局 {@code com.astral.server.interceptor.AuthInterceptor}
 *       （/api/v1/user/**、/api/v1/app/user/** 由统一拦截器按 QtRestResp 结构处理 401，
 *       免认证白名单见其 PUBLIC_PATHS），本配置不再注册插件级拦截器</li>
 *   <li>开放本地头像静态资源映射：/files/qt-upload/** → ./data/qt-upload/</li>
 * </ul>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@Configuration
@ConditionalOnProperty(name = "astral.plugins.qt.enabled", havingValue = "true", matchIfMissing = true)
public class QtWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadDir = Paths.get("data", "qt-upload").toAbsolutePath().normalize();
        registry.addResourceHandler("/files/qt-upload/**")
                .addResourceLocations(uploadDir.toUri().toString() + "/");
        log.info("[QtPlugin] 本地上传目录: {}", uploadDir);
    }
}
