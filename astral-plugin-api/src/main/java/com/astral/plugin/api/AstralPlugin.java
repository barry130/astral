package com.astral.plugin.api;

import java.util.Collections;
import java.util.List;

public interface AstralPlugin {

    String getPluginId();

    String getPluginName();

    String getVersion();

    String getDescription();

    /**
     * 插件对外暴露的 API 路径前缀列表
     * <p>
     * 插件被禁用后，匹配这些前缀的请求将被拦截并返回 PLUGIN001 错误。
     * 例如序列插件返回 ["/api/v1/sequence"]。
     * </p>
     *
     * @return API 路径前缀列表，空列表表示插件不暴露 REST API
     */
    default List<String> getApiPrefixes() {
        return Collections.emptyList();
    }

    default void onEnable() {}

    default void onDisable() {}

    default boolean isEnabled() { return true; }

    /**
     * 是否为系统必需插件
     * <p>
     * 必需插件不允许通过管理接口禁用（返回 PLUGIN002 错误）。
     * 例如序列插件承载全局实体 ID 生成，禁用会导致系统不可用。
     * </p>
     *
     * @return true 表示必需，管理端禁用操作将被拒绝
     */
    default boolean isRequired() { return false; }
}