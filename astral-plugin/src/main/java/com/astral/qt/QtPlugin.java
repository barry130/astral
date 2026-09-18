package com.astral.qt;

import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginFrontendExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 轻听（App）后端 API 插件
 * <p>
 * 参考 qt 后端项目，为 qt-uniappx 前端提供完整用户 / 签到 / 收藏 / 公告 / 版本更新 API。
 * 默认开启（astral.plugins.qt.enabled=true，允许在插件管理页禁用）。
 * </p>
 */
@Slf4j
@Component
public class QtPlugin implements AstralPlugin, PluginFrontendExtension {

    @Override
    public String getPluginId() {
        return "qt";
    }

    @Override
    public String getPluginName() {
        return "轻听音乐";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "轻听音乐 App 后端 API：用户登录注册、邮箱验证码、签到、收藏歌单/歌曲同步、App公告与版本更新";
    }

    @Override
    public List<String> getApiPrefixes() {
        return List.of("/api/v1/app/user", "/api/v1/app", "/api/v1/admin/qt", "/api/v1/user");
    }

    @Override
    public boolean isRequired() {
        // 轻听插件为可选插件，允许在后台禁用
        return false;
    }

    @Override
    public void onEnable() {
        log.info("[QtPlugin] 轻听音乐 API 已启用");
    }

    @Override
    public void onDisable() {
        log.warn("[QtPlugin] 轻听音乐 API 已禁用，/api/v1/app/user、/api/v1/app、/api/v1/admin/qt 接口不可用");
    }

    // ==================== 前端导航扩展 ====================

    @Override
    public List<NavItem> getNavItems() {
        return List.of(
                new NavItem("轻听API", "/dashboard/qt", "CustomerServiceOutlined", 200)
        );
    }
}