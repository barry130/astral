package com.astral.server.config;

import com.astral.server.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC配置类
 * <p>注册认证拦截器，配置拦截路径和排除路径</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 认证拦截器 */
    private final AuthInterceptor authInterceptor;

    /**
     * 添加拦截器配置
     * <p>对所有/api/**路径启用认证拦截，排除登录、Swagger文档、表结构管理等无需认证的路径</p>
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        // 登录相关
                        "/api/v1/auth/login",
                        "/api/v1/auth/info",
                        // 表结构管理（代码生成工具，无需认证）
                        "/api/v1/system/table-schema/**",
                        // Swagger/OpenAPI文档
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/doc.html"
                );
    }
}