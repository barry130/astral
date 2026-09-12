package com.astral.plugin.api;

import java.util.Map;

public interface PluginConfigProvider {

    Map<String, Object> getDefaultConfig();

    default String getConfigPrefix() { return getPluginId(); }
    String getPluginId();
}