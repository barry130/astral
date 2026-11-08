package com.astral.plugin.core;

import com.astral.dao.entity.SysPlugin;
import com.astral.dao.mapper.SysPluginMapper;
import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 插件状态持久化仓库
 * <p>将插件启停状态保存到 {@code sys_plugin} 表，重启后自动恢复。
 * 通过唯一插件键的原子 UPSERT 保存状态，主键由全局序列自动填充。</p>
 */
@Component
public class PluginStateRepository {

    @Resource
    private SysPluginMapper pluginMapper;

    /**
     * 从数据库加载所有插件的启停状态
     */
    public Map<String, Boolean> loadStates() {
        Map<String, Boolean> states = new HashMap<>();
        for (var plugin : pluginMapper.selectList(null)) {
            states.put(plugin.getPluginId(), Integer.valueOf(1).equals(plugin.getEnabled()));
        }
        return states;
    }

    /**
     * 保存/更新插件状态（幂等）
     */
    public void saveState(String pluginId, boolean enabled) {
        var entity = new SysPlugin();
        entity.setPluginId(pluginId);
        entity.setEnabled(enabled ? 1 : 0);
        if (pluginMapper.saveState(entity) != 1) throw new IllegalStateException("Plugin state was not persisted");
    }
}
