package com.astral.server.service.impl;

import com.astral.dao.entity.DictType;
import com.astral.dao.mapper.DictTypeMapper;
import com.astral.server.service.DictTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 字典类型服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供字典类型实体的标准CRUD操作</p>
 */
@Service
public class DictTypeServiceImpl extends ServiceImpl<DictTypeMapper, DictType> implements DictTypeService {
}
