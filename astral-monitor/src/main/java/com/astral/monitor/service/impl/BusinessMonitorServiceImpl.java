package com.astral.monitor.service.impl;

import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.monitor.dto.BusinessMonitorDTO;
import com.astral.monitor.service.BusinessMonitorService;
import com.astral.monitor.service.RequestConcurrencyCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 业务监控服务实现类
 * <p>
 * 采集业务级别的监控指标：
 * <ul>
 *   <li>序列配置数量：从数据库查询</li>
 *   <li>并发请求数：取自 {@link RequestConcurrencyCounter} 的实时计数</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessMonitorServiceImpl implements BusinessMonitorService {

    /** 序列配置Mapper，用于查询序列配置数量 */
    private final SequenceConfigMapper sequenceConfigMapper;

    /** 并发请求计数器 */
    private final RequestConcurrencyCounter requestConcurrencyCounter;

    /**
     * 获取业务监控信息
     *
     * @return 业务监控数据传输对象
     */
    @Override
    public BusinessMonitorDTO getBusinessInfo() {
        BusinessMonitorDTO dto = new BusinessMonitorDTO();
        dto.setConfigCount(sequenceConfigMapper.selectCount(null));
        dto.setActiveRequests(requestConcurrencyCounter.getActiveRequests());
        return dto;
    }
}
