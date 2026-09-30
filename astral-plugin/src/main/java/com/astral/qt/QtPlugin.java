package com.astral.qt;

import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PermissionProvider;
import com.astral.plugin.api.PluginFrontendExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
public class QtPlugin implements AstralPlugin, PluginFrontendExtension, PermissionProvider {

    /** 轻听管理权限（后台 /api/v1/admin/qt/**） */
    public static final String PERM_ADMIN = "admin:qt:admin";

    /** 结果级权限：版本更新 beta 渠道可见资格 */
    public static final String PERM_UPDATE_CHANNEL_BETA = "user:qt:update:channel:beta";

    /** 结果级权限：音源包 beta 渠道可见资格 */
    public static final String PERM_SOURCE_CHANNEL_BETA = "user:qt:source:channel:beta";

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

    // ==================== 权限声明（启动自动登记到 sys_permission） ====================

    /**
     * 轻听权限声明。
     *
     * <p>两个 beta 渠道权限是<b>结果级权限</b>（DATA）：它们授予的是「可见 beta 渠道」的资格，
     * 由 {@code DataScopeResolver} 解析成可见渠道集合后交给业务决策——正式版版本号更高时，
     * 持有该权限的用户收到的仍是正式版。</p>
     */
    @Override
    public List<PermissionDef> getPermissions() {
        return List.of(
                new PermissionDef(PERM_ADMIN, "轻听管理", "qt", TYPE_API,
                        "轻听插件后台管理（用户/公告/版本更新/音源包/加速节点）"),
                new PermissionDef(PERM_UPDATE_CHANNEL_BETA, "轻听测试版接收资格(版本更新)", "qt", TYPE_DATA,
                        "可看到 beta 渠道的版本更新；正式版版本号更高时仍收到正式版"),
                new PermissionDef(PERM_SOURCE_CHANNEL_BETA, "轻听测试版接收资格(音源包)", "qt", TYPE_DATA,
                        "可看到 beta 渠道的音源包；正式包版本号更高时仍收到正式包")
        );
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
                NavItem.pluginPage("轻听API", "/dashboard/qt", "CustomerServiceOutlined", PERM_ADMIN, 200)
        );
    }
}
