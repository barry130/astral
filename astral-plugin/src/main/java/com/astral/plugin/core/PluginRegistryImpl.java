package com.astral.plugin.core;

import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PluginRegistryImpl implements PluginRegistry {

    private final Map<String, AstralPlugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pluginStates = new ConcurrentHashMap<>();

    @Resource
    private PluginStateRepository stateRepository;

    @Override
    public void register(AstralPlugin plugin) {
        String id = plugin.getPluginId();
        plugins.put(id, plugin);

        // 优先使用持久化的状态，其次使用插件默认值
        boolean enabled = pluginStates.getOrDefault(id, plugin.isEnabled());
        pluginStates.put(id, enabled);

        if (enabled) {
            try {
                plugin.onEnable();
                log.info("Plugin enabled: {} ({})", plugin.getPluginName(), id);
            } catch (Exception e) {
                log.error("Failed to enable plugin: {}", id, e);
                pluginStates.put(id, false);
                stateRepository.saveState(id, false);
            }
        }
        log.info("Plugin registered: {} (v{})", plugin.getPluginName(), plugin.getVersion());
    }

    @Override
    public void unregister(String pluginId) {
        AstralPlugin plugin = plugins.remove(pluginId);
        if (plugin != null) {
            try {
                plugin.onDisable();
            } catch (Exception e) {
                log.warn("Error disabling plugin: {}", pluginId, e);
            }
            pluginStates.remove(pluginId);
            log.info("Plugin unregistered: {}", pluginId);
        }
    }

    @Override
    public Optional<AstralPlugin> getPlugin(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId));
    }

    @Override
    public List<AstralPlugin> getAllPlugins() {
        return new ArrayList<>(plugins.values());
    }

    @Override
    public List<AstralPlugin> getEnabledPlugins() {
        return plugins.values().stream()
                .filter(p -> pluginStates.getOrDefault(p.getPluginId(), false))
                .collect(Collectors.toList());
    }

    @Override
    public boolean isEnabled(String pluginId) {
        return pluginStates.getOrDefault(pluginId, false);
    }

    @Override
    public void enablePlugin(String pluginId) {
        AstralPlugin plugin = plugins.get(pluginId);
        if (plugin != null) {
            try {
                plugin.onEnable();
                pluginStates.put(pluginId, true);
                stateRepository.saveState(pluginId, true);
                log.info("Plugin enabled: {}", pluginId);
            } catch (Exception e) {
                log.error("Failed to enable plugin: {}", pluginId, e);
            }
        }
    }

    @Override
    public void disablePlugin(String pluginId) {
        AstralPlugin plugin = plugins.get(pluginId);
        if (plugin != null) {
            if (plugin.isRequired()) {
                throw new com.astral.common.exception.BusinessException("PLUGIN002", plugin.getPluginName());
            }
            try {
                plugin.onDisable();
                pluginStates.put(pluginId, false);
                stateRepository.saveState(pluginId, false);
                log.info("Plugin disabled: {}", pluginId);
            } catch (Exception e) {
                log.error("Failed to disable plugin: {}", pluginId, e);
            }
        }
    }

    @PostConstruct
    public void init() {
        // 启动时从数据库加载持久化的插件状态
        Map<String, Boolean> persisted = stateRepository.loadStates();
        if (!persisted.isEmpty()) {
            log.info("Plugin registry restored {} plugin states from DB", persisted.size());
            persisted.forEach(pluginStates::put);
        }
        log.info("Plugin registry initialized");
    }
}