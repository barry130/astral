package com.astral.system.service.impl;

import com.astral.dao.entity.DictType;
import com.astral.dao.mapper.DictTypeMapper;
import com.astral.system.service.DictTypeService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class DictTypeServiceImpl extends ServiceImpl<DictTypeMapper, DictType> implements DictTypeService {
}
