package com.astral.plugindemo;

import com.astral.plugin.api.AstralPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 示例插件：演示插件化架构的使用方式。
 * <p>
 * 后续集成外部后端（如 uniappx 业务后端）时，只需：
 * <ol>
 *   <li>新建 Maven 模块并依赖 astral-plugin-api + astral-common</li>
 *   <li>实现 {@link AstralPlugin} 并注册到 Spring 容器（@Component 或 @Service）</li>
 *   <li>在插件内提供自己的 Controller / Service / Mapper</li>
 *   <li>通过 {@code astral.plugins.demo.enabled} 控制是否启用</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
public class DemoPlugin implements AstralPlugin {

    @Override
    public String getPluginId() {
        return "demo";
    }

    @Override
    public String getPluginName() {
        return "示例插件";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "演示插件化架构的示例插件，展示生命周期回调";
    }

    @Override
    public List<String> getApiPrefixes() {
        return List.of("/api/v1/admin/plugin/demo");
    }

    @Override
    public void onEnable() {
        log.info("[DemoPlugin] enabled");
    }

    @Override
    public void onDisable() {
        log.info("[DemoPlugin] disabled");
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}