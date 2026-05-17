package com.astral.monitor.service;

import com.astral.monitor.dto.JvmMonitorDTO;

/**
 * JVM监控服务接口
 * <p>
 * 提供JVM级别的监控信息采集功能，包括堆内存、GC、线程等指标。
 * </p>
 */
public interface JvmMonitorService {
    /**
     * 获取JVM监控信息
     *
     * @return JVM监控数据传输对象
     */
    JvmMonitorDTO getJvmInfo();
}