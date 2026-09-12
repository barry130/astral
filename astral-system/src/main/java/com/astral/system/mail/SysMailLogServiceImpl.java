package com.astral.system.mail;

import com.astral.dao.entity.SysMailLog;
import com.astral.dao.mapper.SysMailLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class SysMailLogServiceImpl extends ServiceImpl<SysMailLogMapper, SysMailLog>
        implements SysMailLogService {

    @Override
    public Page<SysMailLog> pageWithFilter(int pageNum, int pageSize, Long accountId, String pluginId,
                                           String toEmail, Integer status, LocalDateTime start, LocalDateTime end) {
        LambdaQueryWrapper<SysMailLog> wrapper = new LambdaQueryWrapper<>();
        if (accountId != null) {
            wrapper.eq(SysMailLog::getAccountId, accountId);
        }
        if (pluginId != null && !pluginId.isBlank()) {
            wrapper.eq(SysMailLog::getPluginId, pluginId);
        }
        if (toEmail != null && !toEmail.isBlank()) {
            wrapper.like(SysMailLog::getToEmail, toEmail);
        }
        if (status != null) {
            wrapper.eq(SysMailLog::getStatus, status);
        }
        if (start != null) {
            wrapper.ge(SysMailLog::getSendTime, start);
        }
        if (end != null) {
            wrapper.le(SysMailLog::getSendTime, end);
        }
        wrapper.orderByDesc(SysMailLog::getSendTime);
        return page(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public Map<String, Object> statistics() {
        Map<String, Object> map = new HashMap<>();
        map.put("total", count());
        map.put("success", count(new LambdaQueryWrapper<SysMailLog>().eq(SysMailLog::getStatus, 1)));
        map.put("fail", count(new LambdaQueryWrapper<SysMailLog>().eq(SysMailLog::getStatus, 0)));
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        map.put("todayTotal", count(new LambdaQueryWrapper<SysMailLog>().ge(SysMailLog::getSendTime, startOfDay)));
        map.put("todaySuccess", count(new LambdaQueryWrapper<SysMailLog>().ge(SysMailLog::getSendTime, startOfDay).eq(SysMailLog::getStatus, 1)));
        map.put("todayFail", count(new LambdaQueryWrapper<SysMailLog>().ge(SysMailLog::getSendTime, startOfDay).eq(SysMailLog::getStatus, 0)));
        return map;
    }
}
