package com.astral.cms;

import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginFrontendExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内容管理（CMS）插件：博客 / Wiki
 * <p>
 * 提供文章（Markdown）、分类、标签、层级页面（Wiki 目录）、匿名评论（审核制）
 * 与站点设置；App 端公开读取接口供独立部署的博客前台（astral-blog，可绑定单独域名）消费。
 * 默认开启（astral.plugins.cms.enabled=true，允许在插件管理页禁用）。
 * </p>
 */
@Slf4j
@Component
public class CmsPlugin implements AstralPlugin, PluginFrontendExtension {

    @Override
    public String getPluginId() {
        return "cms";
    }

    @Override
    public String getPluginName() {
        return "内容管理";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "博客/Wiki 内容管理：文章、分类、标签、层级页面（Wiki）、匿名评论（审核制）与站点设置";
    }

    @Override
    public List<String> getApiPrefixes() {
        // 仅声明本插件拥有的 2 条路由前缀，勿写宽泛前缀（禁用时会按前缀连坐拦截）。
        return List.of("/api/v1/app/cms", "/api/v1/admin/cms");
    }

    @Override
    public boolean isRequired() {
        return false;
    }

    @Override
    public void onEnable() {
        log.info("[CmsPlugin] 内容管理 API 已启用");
    }

    @Override
    public void onDisable() {
        log.warn("[CmsPlugin] 内容管理 API 已禁用，/api/v1/app/cms、/api/v1/admin/cms 接口不可用");
    }

    // ==================== 前端导航扩展 ====================

    @Override
    public List<NavItem> getNavItems() {
        return List.of(
                NavItem.pluginPage("内容管理", "/dashboard/cms", "FileTextOutlined", "admin:cms:view", 220)
        );
    }
}
