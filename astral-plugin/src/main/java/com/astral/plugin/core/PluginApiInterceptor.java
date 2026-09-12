package com.astral.plugin.core;

import com.astral.common.exception.BusinessException;
import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

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

    private static final List<String> MANAGED_SUFFIXES = List.of("/enable", "/disable", "/toggle");

    private final PluginRegistry pluginRegistry;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        // 插件启停管理路径不拦截，允许已禁用的插件重新启用
        if (isManagedEndpoint(path)) {
            return true;
        }
        List<AstralPlugin> plugins = pluginRegistry.getAllPlugins();
        for (AstralPlugin plugin : plugins) {
            if (pluginRegistry.isEnabled(plugin.getPluginId())) {
                continue;
            }
            for (String prefix : plugin.getApiPrefixes()) {
                if (prefix != null && !prefix.isEmpty() && path.startsWith(prefix)) {
                    throw new BusinessException("PLUGIN001", plugin.getPluginName());
                }
            }
        }
        return true;
    }

    /**
     * 判断是否为插件启停管理端点
     */
    private boolean isManagedEndpoint(String path) {
        // 匹配 /api/v1/admin/plugin/{pluginId}/{action}
        for (String suffix : MANAGED_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}