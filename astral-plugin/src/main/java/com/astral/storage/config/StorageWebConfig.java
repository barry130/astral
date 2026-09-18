package com.astral.storage.config;

import com.astral.storage.security.StorageWorkerAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * storage 插件 Web 配置
 * <p>
 * 注册 Worker 服务身份 HMAC 拦截器，仅作用于两个 Worker 专属子树：
 * <ul>
 *   <li>/api/v1/all/storage/worker/** —— 上传结果回调、删除任务拉取/确认</li>
 *   <li>/api/v1/all/storage/origin/** —— 下载回源元数据查询</li>
 * </ul>
 * 这两个前缀已在宿主 AuthInterceptor 中精确排除 satoken 校验（AUTH: STORAGE_WORKER_PREFIX），
 * 本拦截器按 STORAGE017 校验 HMAC。其余 /api/v1/all/storage/** 仍走 satoken 登录认证。
 * </p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "astral.plugins.storage.enabled", havingValue = "true", matchIfMissing = true)
public class StorageWebConfig implements WebMvcConfigurer {

    private final StorageWorkerAuthInterceptor storageWorkerAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(storageWorkerAuthInterceptor)
                .addPathPatterns("/api/v1/all/storage/worker/**", "/api/v1/all/storage/origin/**");
    }
}
