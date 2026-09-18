package com.astral.server.config;

import com.astral.server.interceptor.ApiRequestMetricInterceptor;
import com.astral.server.interceptor.AuthInterceptor;
import com.astral.server.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    /** 统计开关关闭时拦截器 Bean 不存在，用 ObjectProvider 可选注入 */
    private final ObjectProvider<ApiRequestMetricInterceptor> apiRequestMetricInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 全局统一认证拦截器（宿主管理区 + 轻听插件用户区合并，含 token 自动续期）；
        //    免认证白名单内置在 AuthInterceptor.PUBLIC_PATHS，QtRestResp/Result 两种 401
        //    结构按区域自动区分。先于限流注册：确保 userId 属性已设置，user 类型限流才能正确识别。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**");

        // 2. 接口指标采集拦截器（认证之后注册；采集入口不统计，防自举；
        //    astral.stat.enabled=false 时 Bean 不存在，自动跳过）
        ApiRequestMetricInterceptor metricInterceptor = apiRequestMetricInterceptor.getIfAvailable();
        if (metricInterceptor != null) {
            registry.addInterceptor(metricInterceptor)
                    .addPathPatterns("/api/**")
                    .excludePathPatterns(
                            "/api/v1/stat/report",
                            "/api/v1/app/stat/report",
                            "/swagger-ui/**",
                            "/v3/api-docs/**",
                            "/swagger-ui.html",
                            "/doc.html",
                            "/actuator/**"
                    );
        }

        // 3. 限流拦截器（认证之后，user 属性已就绪）
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/public-key",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/doc.html"
                );
    }
}