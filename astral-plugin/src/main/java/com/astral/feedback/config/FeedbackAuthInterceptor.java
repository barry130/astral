package com.astral.feedback.config;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.result.Result;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 反馈插件 App 端认证拦截器
 * <p>解析 {@code satoken} 请求头，通过宿主 Sa-Token 校验登录态；
 * 未携带 / 无效 / 过期时返回 {@code 401}（App 端据此清除登录态）。
 * 认证通过后把 userId 写入 request attribute 供控制器使用。</p>
 * <p>仅拦截 /api/v1/app/feedback/**，<b>没有公开路径</b>：反馈 App 端全部接口
 * （提交、我的反馈、详情、回复、公开列表）都要求登录。
 * 原先白名单里的「生效公告」匿名接口已随通知能力收归宿主，
 * 其匿名放行由宿主通知模块的拦截器配置负责，不在本插件。</p>
 */
@Slf4j
@Component
public class FeedbackAuthInterceptor implements HandlerInterceptor {

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 仅拦截 App 端接口；非 App 端路径（管理端）由宿主拦截器处理
        if (!uri.startsWith("/api/v1/app/")) {
            return true;
        }

        // 由 SaTokenContextFilter 从 satoken 请求头解析登录态
        Long userId = null;
        try {
            Object loginId = StpUtil.getLoginIdByToken(request.getHeader("satoken"));
            if (loginId != null) {
                userId = Long.parseLong(loginId.toString());
            }
        } catch (Exception e) {
            userId = null;
        }

        if (userId == null) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.error(401, "登录状态已失效")
            ));
            return false;
        }

        request.setAttribute("userId", userId);
        return true;
    }
}
