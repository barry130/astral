package com.astral.system.service.impl;

import com.astral.dao.entity.SysConfig;
import com.astral.dao.mapper.SysConfigMapper;
import com.astral.system.service.SysConfigService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 系统配置服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供系统配置实体的标准CRUD操作</p>
 */
@Service
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements SysConfigService {
}
