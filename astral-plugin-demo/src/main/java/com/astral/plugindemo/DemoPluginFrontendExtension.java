package com.astral.plugindemo;

import com.astral.plugin.api.PluginFrontendExtension;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DemoPluginFrontendExtension implements PluginFrontendExtension {

    @Override
    public String getPluginId() {
        return "demo";
    }

    @Override
    public List<NavItem> getNavItems() {
        return List.of(
                new NavItem("示例插件", "/dashboard/demo", "AppstoreOutlined", 100)
        );
    }
}