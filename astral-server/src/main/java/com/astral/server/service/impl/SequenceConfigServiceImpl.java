package com.astral.server.service.impl;

import com.astral.dao.entity.SequenceConfig;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.server.service.SequenceConfigService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 序列配置服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供序列配置实体的标准CRUD操作</p>
 */
@Service
public class SequenceConfigServiceImpl extends ServiceImpl<SequenceConfigMapper, SequenceConfig> implements SequenceConfigService {
}
