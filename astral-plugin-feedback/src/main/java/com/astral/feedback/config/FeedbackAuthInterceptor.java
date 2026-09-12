package com.astral.feedback.config;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.feedback.common.FeedbackRestResp;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 反馈插件 App 端认证拦截器
 * <p>解析 {@code satoken} 请求头，通过宿主 Sa-Token 校验登录态；
 * 未携带 / 无效 / 过期时返回 {@code 401}（App 端据此清除登录态）。
 * 认证通过后把 userId 写入 request attribute 供控制器使用。</p>
 * <p>仅拦截需登录的 App 接口（/api/v1/app/feedback/**、/api/v1/app/message/** 的登录子集）；
 * 公开接口（/api/v1/app/message/active）在控制器层放行。</p>
 */
@Slf4j
@Component
public class FeedbackAuthInterceptor implements HandlerInterceptor {

    /** 免认证的 App 路径（公开） */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/app/message/active"
    );

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 仅拦截 App 端接口；公开路径直接放行；非 App 端路径（管理端）由宿主拦截器处理
        if (!uri.startsWith("/api/v1/app/") || PUBLIC_PATHS.contains(uri)) {
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
                    FeedbackRestResp.error(401, "登录状态已失效")
            ));
            return false;
        }

        request.setAttribute("userId", userId);
        return true;
    }
}
