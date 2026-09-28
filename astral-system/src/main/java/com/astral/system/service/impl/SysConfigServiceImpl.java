package com.astral.system.service.impl;

import com.astral.dao.entity.SysConfig;
import com.astral.dao.mapper.SysConfigMapper;
import com.astral.system.service.SysConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class SysConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements SysConfigService {

    private Cache<String, String> valueCache;

    @PostConstruct
    public void init() {
        valueCache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();
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
        valueCache.invalidateAll();
    }
}
