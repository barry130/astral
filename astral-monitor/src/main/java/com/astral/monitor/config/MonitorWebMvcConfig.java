package com.astral.monitor.config;

import com.astral.monitor.service.RequestConcurrencyCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * monitor 模块的 Web MVC 配置
 * <p>
 * 注册并发请求计数器。多个模块各自实现 {@link WebMvcConfigurer} 互不影响，
 * Spring 会合并所有实现（astral-server 侧的认证 / 限流拦截器不受此影响）。
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class MonitorWebMvcConfig implements WebMvcConfigurer {

    /** 并发请求计数器（同时是拦截器） */
    private final RequestConcurrencyCounter requestConcurrencyCounter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 全路径统计，含静态资源与错误页 —— 这些同样是正在占用服务资源的请求
        registry.addInterceptor(requestConcurrencyCounter).addPathPatterns("/**");
    }
}
