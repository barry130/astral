package com.astral.plugin.core;

import com.astral.plugin.api.PluginRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 插件 Web 配置
 * <p>
 * 注册插件 API 拦截器，使禁用插件的接口返回 PLUGIN001 错误。
 * 拦截器优先级最高：禁用插件的请求直接拒绝，不进入认证与限流流程。
 * </p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "astral.plugins.enabled", havingValue = "true", matchIfMissing = true)
public class PluginWebConfig implements WebMvcConfigurer {

    private final PluginRegistry pluginRegistry;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PluginApiInterceptor(pluginRegistry))
                .addPathPatterns("/api/v1/**");
    }
}