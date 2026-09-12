package com.astral.plugin.api;

import java.util.List;
import java.util.Optional;

public interface PluginRegistry {

    void register(AstralPlugin plugin);

    void unregister(String pluginId);

    Optional<AstralPlugin> getPlugin(String pluginId);

    List<AstralPlugin> getAllPlugins();

    List<AstralPlugin> getEnabledPlugins();

    boolean isEnabled(String pluginId);

    void enablePlugin(String pluginId);

    void disablePlugin(String pluginId);
}