package com.astral.server.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.astral.common.error.ErrorCodes;
import com.astral.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 * <p>基于Sa-Token实现请求认证拦截，检查用户登录状态并将用户信息设置到request属性中</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** JSON序列化器，用于将错误响应写入响应体 */
    private final ObjectMapper objectMapper;

    /**
     * 请求预处理
     * <p>检查用户是否已登录，未登录则返回401错误；已登录则将用户ID设置到request属性中</p>
     *
     * @param request HTTP请求
     * @param response HTTP响应
     * @param handler 处理器
     * @return true表示继续处理请求，false表示中断请求
     * @throws Exception JSON序列化异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 检查用户是否已登录
        if (!StpUtil.isLogin()) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            Result<?> result = Result.error("AUTH001");
            result.setCode(401);
            response.getWriter().write(objectMapper.writeValueAsString(result));
            return false;
        }
        
        // 获取登录用户ID并设置到request属性，供后续处理器使用
        Object loginId = StpUtil.getLoginId();
        if (loginId != null) {
            request.setAttribute("username", loginId.toString());
            log.debug("请求用户: {}", loginId);
        }
        
        return true;
    }
}