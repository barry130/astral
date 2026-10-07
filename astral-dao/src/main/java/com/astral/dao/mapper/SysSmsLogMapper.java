package com.astral.dao.mapper;

import com.astral.dao.entity.SysSmsLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysSmsLogMapper extends BaseMapper<SysSmsLog> {

    /** 统计某插件下某手机号当日已成功发送的短信数（status=1） */
    @Select("SELECT COUNT(*) FROM sys_sms_log WHERE phone = #{phone} AND plugin_id = #{pluginId} AND status = 1 AND send_time >= CURRENT_DATE")
    Long countSuccessToday(@Param("phone") String phone, @Param("pluginId") String pluginId);
}
