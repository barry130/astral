package com.astral.feedback;

import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginFrontendExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 问题反馈插件
 * <p>
 * 提供反馈/需求提交、双向对话、公开列表、状态流转与统计看板。
 * 通知能力（查询/发布/投递规则）已收归宿主通知模块，本插件只在业务节点触发事件，
 * 不持有通知服务、通知 Controller 与通知表结构。
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
        return "问题反馈插件：反馈/需求提交、双向回复、状态流转与统计看板";
    }

    @Override
    public List<String> getApiPrefixes() {
        // 仅声明本插件拥有的 2 条路由前缀（反馈 App 端 + 反馈管理端）。
        // 勿写成 /api/v1/app、/api/v1/admin 这类命名空间级前缀：插件禁用时
        // PluginApiInterceptor 会按前缀连坐拦截，殃及 Qt/Storage 与宿主全部 app/admin 接口。
        // 通知相关路由已随通知能力收归宿主通知模块，不得再出现在这里，
        // 否则「禁用反馈插件 = 通知能力消失」的耦合会复现。
        return List.of("/api/v1/app/feedback", "/api/v1/admin/feedback");
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
        log.warn("[FeedbackPlugin] 问题反馈 API 已禁用，/api/v1/app/feedback、/api/v1/admin/feedback 接口不可用");
    }

    // ==================== 前端导航扩展 ====================

    @Override
    public List<NavItem> getNavItems() {
        // 反馈管理页（类型筛选/列表标签用 feedback_type 字典，与通知无关）；
        // 插件业务页统一挂在「插件管理」下作为二级菜单（见 NavItem.PLUGIN_MANAGER_PATH）
        return List.of(
                NavItem.pluginPage("反馈管理", "/dashboard/feedback", "MessageOutlined", "admin:feedback:view", 210)
        );
    }
}
