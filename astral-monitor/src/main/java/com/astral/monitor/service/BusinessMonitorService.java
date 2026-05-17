package com.astral.monitor.service;

import com.astral.monitor.dto.BusinessMonitorDTO;

/**
 * 业务监控服务接口
 * <p>
 * 提供业务级别的监控信息采集功能，包括序列生成统计、配置数量等指标。
 * </p>
 */
public interface BusinessMonitorService {
    /**
     * 获取业务监控信息
     *
     * @return 业务监控数据传输对象
     */
    BusinessMonitorDTO getBusinessInfo();
}