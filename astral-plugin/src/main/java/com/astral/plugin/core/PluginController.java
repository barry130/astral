package com.astral.plugin.core;

import com.astral.common.result.Result;
import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginConfigProvider;
import com.astral.plugin.api.PluginFrontendExtension;
import com.astral.plugin.api.PluginRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "插件管理")
@RestController
@RequestMapping("/api/v1/admin/plugin")
@RequiredArgsConstructor
public class PluginController {

    private final PluginRegistry pluginRegistry;
    private final List<PluginFrontendExtension> frontendExtensions;
    private final List<PluginConfigProvider> configProviders;

    @Operation(summary = "获取所有插件")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> listPlugins() {
        List<Map<String, Object>> result = pluginRegistry.getAllPlugins().stream()
                .map(this::toPluginMap)
                .collect(Collectors.toList());
        return Result.success(result);
    }

    @Operation(summary = "获取已启用的插件")
    @GetMapping("/enabled")
    public Result<List<Map<String, Object>>> getEnabledPlugins() {
        List<Map<String, Object>> result = pluginRegistry.getEnabledPlugins().stream()
                .map(this::toPluginMap)
                .collect(Collectors.toList());
        return Result.success(result);
    }

    @Operation(summary = "启用插件")
    @PostMapping("/{pluginId}/enable")
    public Result<Void> enablePlugin(@PathVariable String pluginId) {
        pluginRegistry.enablePlugin(pluginId);
        return Result.success();
    }

    @Operation(summary = "禁用插件")
    @PostMapping("/{pluginId}/disable")
    public Result<Void> disablePlugin(@PathVariable String pluginId) {
        pluginRegistry.disablePlugin(pluginId);
        return Result.success();
    }

    @Operation(summary = "获取前端导航扩展（仅返回已启用插件的导航项）")
    @GetMapping("/nav-extensions")
    public Result<List<PluginFrontendExtension.NavItem>> getNavExtensions() {
        List<PluginFrontendExtension.NavItem> items = frontendExtensions.stream()
                .filter(ext -> pluginRegistry.isEnabled(ext.getPluginId()))
                .flatMap(ext -> ext.getNavItems().stream())
                .collect(Collectors.toList());
        return Result.success(items);
    }

    @Operation(summary = "获取插件默认配置")
    @GetMapping("/configs")
    public Result<List<Map<String, Object>>> getPluginConfigs() {
        List<Map<String, Object>> result = configProviders.stream().map(cp -> {
            Map<String, Object> map = new HashMap<>();
            map.put("pluginId", cp.getPluginId());
            map.put("configPrefix", cp.getConfigPrefix());
            map.put("defaultConfig", cp.getDefaultConfig());
            return map;
        }).collect(Collectors.toList());
        return Result.success(result);
    }

    private Map<String, Object> toPluginMap(AstralPlugin plugin) {
        Map<String, Object> map = new HashMap<>();
        map.put("pluginId", plugin.getPluginId());
        map.put("pluginName", plugin.getPluginName());
        map.put("version", plugin.getVersion());
        map.put("description", plugin.getDescription());
        map.put("enabled", pluginRegistry.isEnabled(plugin.getPluginId()));
        map.put("required", plugin.isRequired());
        return map;
    }
}