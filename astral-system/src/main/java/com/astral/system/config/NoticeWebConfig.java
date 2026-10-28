package com.astral.system.config;

import com.astral.system.notice.AppNoticeAuthInterceptor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 通知模块 Web 配置：注册 App 端通知认证拦截器。
 *
 * <p>作用的路径与原反馈插件版一字不变：{@code /api/v1/app/message/**}，
 * 公开的 {@code /api/v1/app/message/active}（游客可见的开屏/通告栏）放行。
 * {@code /api/v1/admin/message/**} 不由本类拦截，走宿主 {@code AuthInterceptor}
 * 的 Sa-Token 管理员鉴权与身份门禁。</p>
 *
 * <p><b>顺序</b>：{@code HIGHEST_PRECEDENCE + 40}。宿主 {@code WebMvcConfig} 无 order
 * （= LOWEST_PRECEDENCE），插件侧 {@code PluginWebConfig} 是 HIGHEST、
 * {@code FeedbackWebConfig} 是 +30。本拦截器只挂在一个独立的 app 前缀上，
 * 与它们没有路径交集，取 +40 只是为了让「插件拦截器 → 宿主通知拦截器」的先后
 * 在日志里稳定可读，避免同 order 下的注册顺序依赖。</p>
 *
 * <p><b>这是 astral-system 模块的第一个 {@code WebMvcConfigurer}</b>：宿主通知能力
 * 必须在反馈插件被禁用时依然生效，所以它不能挂在插件的 Web 配置里。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
@Configuration
public class NoticeWebConfig implements WebMvcConfigurer {

    @Resource
    private AppNoticeAuthInterceptor appNoticeAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(appNoticeAuthInterceptor)
                .addPathPatterns("/api/v1/app/message/**")
                .excludePathPatterns("/api/v1/app/message/active");
    }
}
