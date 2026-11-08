package com.astral.dao.mapper;

import com.astral.dao.entity.SysPlugin;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysPluginMapper extends BaseMapper<SysPlugin> {
    /** Entity parameter invokes the global ID fill handler; the unique plugin key handles concurrent startup. */
    @Insert("""
            INSERT INTO sys_plugin(id,plugin_id,enabled,update_time)
            VALUES(#{entity.id},#{entity.pluginId},#{entity.enabled},CURRENT_TIMESTAMP)
            ON CONFLICT(plugin_id) DO UPDATE SET enabled=EXCLUDED.enabled,update_time=EXCLUDED.update_time
            """)
    int saveState(@Param("entity") SysPlugin entity);
}
