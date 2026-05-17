package com.astral.monitor.service.impl;

import com.astral.common.util.SequenceMetrics;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import com.astral.monitor.dto.BusinessMonitorDTO;
import com.astral.monitor.service.BusinessMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 业务监控服务实现类
 * <p>
 * 采集序列生成器相关的业务监控指标，包括：
 * <ul>
 *   <li>序列生成总数：从 SequenceMetrics 工具类获取</li>
 *   <li>序列生成QPS：从 SequenceMetrics 工具类获取</li>
 *   <li>序列配置数量：从数据库查询</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessMonitorServiceImpl implements BusinessMonitorService {

    /** 序列配置Mapper，用于查询序列配置数量 */
    private final SequenceConfigMapper sequenceConfigMapper;
    /** 序列统计Mapper（预留，当前未使用） */
    private final SequenceStatisticsMapper sequenceStatisticsMapper;

    /**
     * 获取业务监控信息
     * <p>
     * 从 SequenceMetrics 静态工具类获取序列生成的累计总数和QPS，
     * 从数据库查询当前活跃的序列配置数量。
     * </p>
     *
     * @return 业务监控数据传输对象
     */
    @Override
    public BusinessMonitorDTO getBusinessInfo() {
        BusinessMonitorDTO dto = new BusinessMonitorDTO();
        
        dto.setSequenceGenerationTotal(SequenceMetrics.getTotalGenerated());
        dto.setSequenceGenerationQps(SequenceMetrics.getQps());
        
        long activeConfigs = sequenceConfigMapper.selectCount(null);
        dto.setConfigCount(activeConfigs);
        
        dto.setCacheHitRatio(null);
        
        return dto;
    }
}
