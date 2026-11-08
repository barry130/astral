package com.astral.plugin.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.plugin.api.AstralPlugin;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 覆盖插件启停失败必须上抛（D-PL-01）：不再吞异常后返回「成功」。
 */
class PluginRegistryImplFailureTest {

    private PluginRegistryImpl registry;
    private Map<String, AstralPlugin> plugins;
    private PluginStateRepository repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new PluginRegistryImpl();
        plugins = (Map<String, AstralPlugin>) ReflectionTestUtils.getField(registry, "plugins");
        repository = mock(PluginStateRepository.class);
        ReflectionTestUtils.setField(registry, "stateRepository", repository);
    }

    @Test
    void enablePluginPropagatesFailure() {
        AstralPlugin plugin = mock(AstralPlugin.class);
        when(plugin.getPluginName()).thenReturn("Demo");
        doThrow(new IllegalStateException("boom")).when(plugin).onEnable();
        plugins.put("demo", plugin);

        assertThrows(BusinessException.class, () -> registry.enablePlugin("demo"));
        assertFalse(registry.isEnabled("demo"));
    }

    @Test
    void disablePluginPropagatesFailure() {
        AstralPlugin plugin = mock(AstralPlugin.class);
        when(plugin.getPluginName()).thenReturn("Demo");
        doThrow(new IllegalStateException("boom")).when(plugin).onDisable();
        plugins.put("demo", plugin);

        assertThrows(BusinessException.class, () -> registry.disablePlugin("demo"));
    }

    @Test
    void disableRequiredPluginStillRejected() {
        AstralPlugin plugin = mock(AstralPlugin.class);
        when(plugin.getPluginName()).thenReturn("Sequence");
        when(plugin.isRequired()).thenReturn(true);
        plugins.put("sequence", plugin);

        assertThrows(BusinessException.class, () -> registry.disablePlugin("sequence"));
    }

    @Test
    void persistenceFailureNeverEnablesAnUnpersistedPlugin() {
        var plugin = mock(AstralPlugin.class);
        when(plugin.getPluginId()).thenReturn("demo");
        registry.register(plugin);
        doThrow(new IllegalStateException("fixture DB failure"))
                .when(repository)
                .saveState("demo", true);
        assertThrows(BusinessException.class, () -> registry.enablePlugin("demo"));
        assertFalse(registry.isEnabled("demo"));
        verify(plugin, never()).onEnable();
    }

    @Test
    void startupHookFailureDoesNotReplaceDesiredEnabledState() {
        when(repository.loadStates()).thenReturn(Map.of("demo", true));
        registry.init();
        var failing = mock(AstralPlugin.class);
        when(failing.getPluginId()).thenReturn("demo");
        doThrow(new IllegalStateException("fixture transient failure"))
                .when(failing)
                .onEnable();
        registry.register(failing);
        assertFalse(registry.isEnabled("demo"));
        verify(repository, never()).saveState("demo", false);
        registry.unregister("demo");
        var recovered = mock(AstralPlugin.class);
        when(recovered.getPluginId()).thenReturn("demo");
        registry.register(recovered);
        assertTrue(registry.isEnabled("demo"));
    }

    @Test
    void requiredPluginFailureStopsStartup() {
        var plugin = mock(AstralPlugin.class);
        when(plugin.getPluginId()).thenReturn("sequence");
        when(plugin.isRequired()).thenReturn(true);
        doThrow(new IllegalStateException("fixture initialization failure"))
                .when(plugin)
                .onEnable();
        assertThrows(IllegalStateException.class, () -> registry.register(plugin));
        assertFalse(registry.isEnabled("sequence"));
    }
}
