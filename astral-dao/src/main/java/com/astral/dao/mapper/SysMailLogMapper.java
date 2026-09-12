package com.astral.dao.mapper;

import com.astral.dao.entity.SysMailLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysMailLogMapper extends BaseMapper<SysMailLog> {

    /** 统计某插件下某收件人当日已成功发送的邮件数（status=1） */
    @Select("SELECT COUNT(*) FROM sys_mail_log WHERE to_email = #{toEmail} AND plugin_id = #{pluginId} AND status = 1 AND send_time >= CURRENT_DATE")
    Long countSuccessToday(@Param("toEmail") String toEmail, @Param("pluginId") String pluginId);
}
