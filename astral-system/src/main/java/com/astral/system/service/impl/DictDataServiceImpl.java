package com.astral.system.service.impl;

import com.astral.dao.entity.DictData;
import com.astral.dao.mapper.DictDataMapper;
import com.astral.system.service.DictDataService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 字典数据服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供字典数据实体的标准CRUD操作</p>
 */
@Service
public class DictDataServiceImpl extends ServiceImpl<DictDataMapper, DictData> implements DictDataService {

    @Override
    public List<DictData> listByCode(String code) {
        return baseMapper.selectByDictCode(code);
    }
}
