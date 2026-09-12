package com.astral.plugin.core;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 插件状态持久化仓库
 * <p>将插件启停状态保存到 {@code sys_plugin} 表，重启后自动恢复。
 * 使用 SELECT + INSERT/UPDATE 保证幂等。</p>
 */
@Slf4j
@Component
public class PluginStateRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 从数据库加载所有插件的启停状态
     */
    public Map<String, Boolean> loadStates() {
        try {
            return jdbcTemplate.query(
                    "SELECT plugin_id, enabled FROM sys_plugin",
                    (rs, rowNum) -> Map.entry(
                            rs.getString("plugin_id"),
                            rs.getInt("enabled") == 1
                    )
            ).stream().collect(
                    HashMap::new,
                    (m, e) -> m.put(e.getKey(), e.getValue()),
                    HashMap::putAll
            );
        } catch (Exception e) {
            log.warn("[PluginState] 加载插件状态失败（表可能不存在）: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 保存/更新插件状态（幂等）
     */
    public void saveState(String pluginId, boolean enabled) {
        try {
            // 先尝试更新
            int updated = jdbcTemplate.update(
                    "UPDATE sys_plugin SET enabled = ?, update_time = CURRENT_TIMESTAMP WHERE plugin_id = ?",
                    enabled ? 1 : 0, pluginId
            );
            // 未更新到则插入
            if (updated == 0) {
                // 计算下一个可用 ID（无自增，用 MAX + 1）
                Long maxId = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(id), 0) FROM sys_plugin", Long.class
                );
                long nextId = (maxId == null ? 0L : maxId) + 1;
                jdbcTemplate.update(
                        "INSERT INTO sys_plugin (id, plugin_id, enabled, update_time) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                        nextId, pluginId, enabled ? 1 : 0
                );
            }
        } catch (Exception e) {
            log.warn("[PluginState] 保存插件状态失败: pluginId={}, enabled={}, err={}", pluginId, enabled, e.getMessage());
        }
    }
}