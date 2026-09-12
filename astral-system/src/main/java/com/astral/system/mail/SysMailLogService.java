package com.astral.system.mail;

import com.astral.dao.entity.SysMailLog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;
import java.util.Map;

public interface SysMailLogService extends IService<SysMailLog> {

    /** 分页查询（支持收件人/状态/插件/账户/时间范围过滤） */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysMailLog> pageWithFilter(
            int pageNum, int pageSize, Long accountId, String pluginId, String toEmail,
            Integer status, LocalDateTime start, LocalDateTime end);

    /** 统计概览：总数/成功/失败/今日各项 */
    Map<String, Object> statistics();
}
