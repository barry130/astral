package com.astral.ai.common;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 调用方信息解析。
 *
 * <p>{@code /api/v1/app/**} 在宿主 {@code AuthInterceptor} 里是<b>匿名放行</b>的（由各插件自行
 * 校验登录），所以 AI 控制器不能用「能进到方法里」来推断已登录——必须显式取值并判空。</p>
 *
 * <p>管理端 {@code /api/v1/admin/**} 由宿主做身份门禁，但仍统一走这里，避免两套写法。</p>
 */
public final class AiCaller {

    /** 宿主拦截器写入的登录用户属性 */
    private static final String ATTR_USER_ID = "userId";

    private AiCaller() {
    }

    /**
     * 取当前登录用户 ID。
     *
     * <p>优先读拦截器写入的请求属性（避免重复解析 Token），回落 Sa-Token 会话。</p>
     *
     * @throws AiException 未登录时抛出 AI004
     */
    public static Long requireUserId(HttpServletRequest request) {
        Long userId = userIdOrNull(request);
        if (userId == null) {
            throw new AiException("AI004");
        }
        return userId;
    }

    /** 取当前登录用户 ID，未登录返回 null（用于匿名可用的只读接口） */
    public static Long userIdOrNull(HttpServletRequest request) {
        if (request != null) {
            Object attr = request.getAttribute(ATTR_USER_ID);
            Long parsed = parse(attr);
            if (parsed != null) {
                return parsed;
            }
        }
        Object loginId = StpUtil.getLoginIdDefaultNull();
        return parse(loginId);
    }

    /**
     * 取客户端 IP。
     *
     * <p>{@code trustedProxies} 由宿主配置 {@code astral.web.trusted-proxies} 决定，
     * 插件不再自行信任任意 {@code X-Forwarded-For}，避免伪造。</p>
     */
    public static String clientIp(HttpServletRequest request, String trustedProxies) {
        if (request == null) {
            return null;
        }
        return ClientIp.resolve(request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr(),
                trustedProxies == null ? ClientIp.DEFAULT_TRUSTED_PROXIES : trustedProxies);
    }

    private static Long parse(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
