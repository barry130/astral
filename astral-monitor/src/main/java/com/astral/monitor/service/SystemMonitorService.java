package com.astral.monitor.service;

import com.astral.monitor.dto.SystemMonitorDTO;

/**
 * 系统监控服务接口
 * <p>
 * 提供系统级别的监控信息采集功能，包括CPU、内存、磁盘、线程等指标。
 * </p>
 */
public interface SystemMonitorService {
    /**
     * 获取系统监控信息
     *
     * @return 系统监控数据传输对象
     */
    SystemMonitorDTO getSystemInfo();
}