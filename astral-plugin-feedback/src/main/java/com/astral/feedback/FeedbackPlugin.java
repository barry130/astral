package com.astral.feedback;

import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginFrontendExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 问题反馈插件（含统一通知）
 * <p>
 * 提供反馈/需求提交、双向对话、公开列表、状态流转、统计看板，以及统一通知（sys_notice）。
 * 默认开启（astral.plugins.feedback.enabled=true，允许在插件管理页禁用）。
 * </p>
 */
@Slf4j
@Component
public class FeedbackPlugin implements AstralPlugin, PluginFrontendExtension {

    @Override
    public String getPluginId() {
        return "feedback";
    }

    @Override
    public String getPluginName() {
        return "问题反馈";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "问题反馈与统一通知插件：反馈/需求提交、双向回复、状态流转、统计看板与通知管理";
    }

    @Override
    public List<String> getApiPrefixes() {
        return List.of("/api/v1/app", "/api/v1/admin");
    }

    @Override
    public boolean isRequired() {
        // 反馈插件为可选插件，允许在后台禁用
        return false;
    }

    @Override
    public void onEnable() {
        log.info("[FeedbackPlugin] 问题反馈 API 已启用");
    }

    @Override
    public void onDisable() {
        log.warn("[FeedbackPlugin] 问题反馈 API 已禁用，/api/v1/app/feedback、/api/v1/app/message、/api/v1/admin 接口不可用");
    }

    // ==================== 前端导航扩展 ====================

    @Override
    public List<NavItem> getNavItems() {
        // 反馈管理与通知管理合并为一个页签（页面内 Tabs 切换），侧边栏仅保留一个入口
        return List.of(
                new NavItem("反馈管理", "/dashboard/feedback", "MessageOutlined", 210)
        );
    }
}
