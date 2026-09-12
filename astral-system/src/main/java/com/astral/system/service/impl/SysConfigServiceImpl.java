package com.astral.system.service.impl;

import com.astral.dao.entity.SysConfig;
import com.astral.dao.mapper.SysConfigMapper;
import com.astral.system.service.SysConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements SysConfigService {

    private Cache<String, List<SysConfig>> listCache;
    private Cache<String, String> valueCache;

    @PostConstruct
    public void init() {
        listCache = Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();
        valueCache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public List<SysConfig> getAllCached() {
        return listCache.get("all", k -> list());
    }

    @Override
    public String getConfigValue(String configKey) {
        return valueCache.get(configKey, k -> {
            SysConfig config = getOne(new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, configKey));
            return config != null ? config.getConfigValue() : null;
        });
    }

    @Override
    public void evictCache() {
        listCache.invalidateAll();
        valueCache.invalidateAll();
    }
}
