package com.astral.system.notice;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.result.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * App 端通知认证拦截器（本模块自己的一层，不依赖任何插件）。
 *
 * <p>解析 {@code satoken} 请求头，通过宿主 Sa-Token 校验登录态；未携带 / 无效 / 过期时
 * 返回 <b>HTTP 200 + body {@code {code:401,msg:"登录状态已失效"}}</b>
 * （App 端只读 body 的 {@code code}，据此清除登录态）。认证通过后把 userId 写入
 * request attribute 供控制器使用（与宿主 {@code AuthInterceptor} 的属性名一致）。</p>
 *
 * <p>只拦截需登录的 App 接口：由 {@link com.astral.system.config.NoticeWebConfig} 挂在
 * {@code /api/v1/app/message/**} 上，并排除公开的 {@code /api/v1/app/message/active}。
 * 因此本类不再内置白名单判断 —— 拦截范围由注册处唯一决定，避免两处清单漂移。</p>
 */
@Slf4j
@Component
public class AppNoticeAuthInterceptor implements HandlerInterceptor {

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
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
            // HTTP 200：App 端约定只看 body.code，非 200 状态码会被 axios 拦截器当网络错误
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
