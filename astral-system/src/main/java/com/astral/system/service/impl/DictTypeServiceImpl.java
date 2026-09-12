package com.astral.system.service.impl;

import com.astral.dao.entity.DictType;
import com.astral.dao.mapper.DictTypeMapper;
import com.astral.system.service.DictTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class DictTypeServiceImpl extends ServiceImpl<DictTypeMapper, DictType> implements DictTypeService {

    private Cache<String, List<DictType>> cache;

    @PostConstruct
    public void init() {
        cache = Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public List<DictType> getAllCached() {
        return cache.get("all", k -> list());
    }

    @Override
    public void evictCache() {
        cache.invalidateAll();
    }
}
