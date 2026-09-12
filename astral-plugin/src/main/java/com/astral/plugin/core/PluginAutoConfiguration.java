package com.astral.plugin.core;

import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "astral.plugins.enabled", havingValue = "true", matchIfMissing = true)
public class PluginAutoConfiguration {

    private final PluginRegistry pluginRegistry;
    private final ObjectProvider<AstralPlugin> pluginsProvider;

    @PostConstruct
    public void init() {
        List<AstralPlugin> discoveredPlugins = pluginsProvider.stream().toList();
        log.info("Discovered {} plugins", discoveredPlugins.size());
        for (AstralPlugin plugin : discoveredPlugins) {
            pluginRegistry.register(plugin);
        }
    }
}