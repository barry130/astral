package com.astral.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 跨域资源共享（CORS）配置类
 * <p>配置允许的跨域请求来源、请求头、请求方法等</p>
 */
@Configuration
public class CorsConfig {

    /** 允许的跨域来源，默认http://localhost:3000（Next.js开发服务器） */
    @Value("${astral.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    /**
     * 创建CORS过滤器Bean
     * <p>允许携带凭证（Cookie/Authorization）、所有请求头和方法，预检请求缓存1小时</p>
     *
     * @return CorsFilter实例
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许携带凭证（Cookie、Authorization头等）
        config.setAllowCredentials(true);
        // 配置允许的跨域来源
        for (String origin : allowedOrigins) {
            config.addAllowedOrigin(origin);
        }
        // 允许所有请求头
        config.addAllowedHeader("*");
        // 允许所有HTTP方法（GET、POST、PUT、DELETE等）
        config.addAllowedMethod("*");
        // 预检请求（OPTIONS）缓存时间，单位秒（1小时）
        config.setMaxAge(3600L);

        // 将CORS配置应用到所有路径
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
