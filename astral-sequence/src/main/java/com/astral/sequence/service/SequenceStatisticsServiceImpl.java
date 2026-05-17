package com.astral.sequence.service.impl;

import com.astral.dao.entity.SequenceStatistics;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import com.astral.sequence.service.SequenceStatisticsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 序列统计服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供序列统计实体的标准CRUD操作</p>
 */
@Service
public class SequenceStatisticsServiceImpl extends ServiceImpl<SequenceStatisticsMapper, SequenceStatistics> implements SequenceStatisticsService {
}
