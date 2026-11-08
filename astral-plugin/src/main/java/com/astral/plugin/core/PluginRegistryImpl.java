package com.astral.plugin.core;

import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@DependsOnDatabaseInitialization
public class PluginRegistryImpl implements PluginRegistry {

    private final Map<String, AstralPlugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pluginStates = new ConcurrentHashMap<>();
    private final Map<String, Boolean> desiredStates = new ConcurrentHashMap<>();

    @Resource
    private PluginStateRepository stateRepository;

    @Override
    public synchronized void register(AstralPlugin plugin) {
        String id = plugin.getPluginId();
        plugins.put(id, plugin);

        // 优先使用持久化的状态，其次使用插件默认值
        boolean enabled = plugin.isRequired() || desiredStates.computeIfAbsent(id, key -> plugin.isEnabled());
        pluginStates.put(id, false);

        if (enabled) {
            try {
                plugin.onEnable();
                pluginStates.put(id, true);
                log.info("Plugin enabled: {} ({})", plugin.getPluginName(), id);
            } catch (Exception e) {
                log.error("Failed to enable plugin: {}", id, e);
                pluginStates.put(id, false);
                if (plugin.isRequired()) {
                    throw new IllegalStateException("Required plugin could not start: " + id, e);
                }
            }
        }
        log.info("Plugin registered: {} (v{})", plugin.getPluginName(), plugin.getVersion());
    }

    @Override
    public synchronized void unregister(String pluginId) {
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
    public synchronized void enablePlugin(String pluginId) {
        AstralPlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            throw com.astral.common.exception.BusinessException.of("插件不存在: " + pluginId);
        }
        try {
            stateRepository.saveState(pluginId, true);
            desiredStates.put(pluginId, true);
            plugin.onEnable();
            pluginStates.put(pluginId, true);
            log.info("Plugin enabled: {}", pluginId);
        } catch (Exception e) {
            // 不再吞异常：状态未变更却向调用方返回成功会误导运维（D-PL-01）。
            log.error("Failed to enable plugin: {}", pluginId, e);
            throw com.astral.common.exception.BusinessException.of("插件启用失败: " + plugin.getPluginName());
        }
    }

    @Override
    public synchronized void disablePlugin(String pluginId) {
        AstralPlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            throw com.astral.common.exception.BusinessException.of("插件不存在: " + pluginId);
        }
        if (plugin.isRequired()) {
            throw new com.astral.common.exception.BusinessException("PLUGIN002", plugin.getPluginName());
        }
        try {
            stateRepository.saveState(pluginId, false);
            desiredStates.put(pluginId, false);
            // Close the API gate once durable disable succeeds, even when a cleanup hook fails.
            pluginStates.put(pluginId, false);
            plugin.onDisable();
            log.info("Plugin disabled: {}", pluginId);
        } catch (Exception e) {
            // 同 enablePlugin：失败必须上抛，避免「看似禁用成功、实际仍在运行」（D-PL-01）。
            log.error("Failed to disable plugin: {}", pluginId, e);
            throw com.astral.common.exception.BusinessException.of("插件禁用失败: " + plugin.getPluginName());
        }
    }

    @PostConstruct
    public synchronized void init() {
        // 启动时从数据库加载持久化的插件状态
        Map<String, Boolean> persisted = stateRepository.loadStates();
        if (!persisted.isEmpty()) {
            log.info("Plugin registry restored {} plugin states from DB", persisted.size());
            persisted.forEach(desiredStates::put);
        }
        log.info("Plugin registry initialized");
    }
}
