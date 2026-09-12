package com.astral.system.service;

import com.astral.dao.entity.SysConfig;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface SysConfigService extends IService<SysConfig> {

    List<SysConfig> getAllCached();

    String getConfigValue(String configKey);

    void evictCache();
}
