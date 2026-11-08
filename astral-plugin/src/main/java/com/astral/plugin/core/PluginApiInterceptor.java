package com.astral.plugin.core;

import com.astral.common.exception.BusinessException;
import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * 插件 API 拦截器
 * <p>
 * 拦截所有请求，若请求路径命中某个已禁用插件声明的 API 前缀，
 * 则抛出 PLUGIN001 业务异常，阻止访问该插件的接口。
 * 插件自身的启停管理路径（/enable、/disable、/toggle）会被跳过，确保
 * 已禁用的插件仍可通过管理端重新启用。
 * </p>
 */
@RequiredArgsConstructor
public class PluginApiInterceptor implements HandlerInterceptor {

    private final PluginRegistry pluginRegistry;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = ServletRequestPathUtils.hasCachedPath(request)
                ? ServletRequestPathUtils.getCachedPathValue(request)
                : request.getRequestURI().substring(request.getContextPath().length());
        Object mapping = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String pattern = mapping == null ? null : mapping.toString();
        // 插件启停管理路径不拦截，允许已禁用的插件重新启用
        if ("POST".equals(request.getMethod()) && isManagedEndpoint(path)) {
            return true;
        }
        List<AstralPlugin> plugins = pluginRegistry.getAllPlugins();
        for (AstralPlugin plugin : plugins) {
            if (pluginRegistry.isEnabled(plugin.getPluginId())) {
                continue;
            }
            for (String prefix : plugin.getApiPrefixes()) {
                if (under(path, prefix) || under(pattern, prefix)) {
                    throw new BusinessException("PLUGIN001", plugin.getPluginName());
                }
            }
        }
        return true;
    }

    private static boolean under(String path, String prefix) {
        if (path == null || prefix == null || prefix.isEmpty()) return false;
        String boundary = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return path.equals(boundary) || path.startsWith(boundary + "/");
    }

    /**
     * 判断是否为插件启停管理端点
     */
    private boolean isManagedEndpoint(String path) {
        return path.matches("/api/v1/admin/plugin/[^/]+/(enable|disable)");
    }
}
