package com.astral.feedback.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 反馈插件 Web 配置
 * <p>注册 App 端 Sa-Token 认证拦截器，仅作用于 /api/v1/app/feedback/**。
 * 反馈 App 端没有公开接口（提交/详情/回复/公开列表都需登录），因此不设白名单。</p>
 * <p>/api/v1/admin/** 不由本插件拦截，由宿主 Sa-Token 管理员鉴权处理；
 * 通知的 App 端与管理端接口已随通知能力收归宿主（对外路径不变，由宿主通知模块
 * 自己的 WebConfig 挂载），不要在本类里再挂它们的路径。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@Configuration
@ConditionalOnProperty(name = "astral.plugins.feedback.enabled", havingValue = "true", matchIfMissing = true)
public class FeedbackWebConfig implements WebMvcConfigurer {

    @Resource
    private FeedbackAuthInterceptor feedbackAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(feedbackAuthInterceptor)
                .addPathPatterns("/api/v1/app/feedback/**");
    }
}
