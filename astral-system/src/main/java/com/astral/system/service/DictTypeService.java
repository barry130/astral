package com.astral.system.service;

import com.astral.dao.entity.DictType;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

public interface DictTypeService extends IService<DictType> {

    List<DictType> getAllCached();

    void evictCache();
}
