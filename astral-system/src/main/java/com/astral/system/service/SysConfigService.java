package com.astral.system.service;

import com.astral.dao.entity.SysConfig;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

public interface SysConfigService extends IService<SysConfig> {

    String getConfigValue(String configKey);

    void evictCache();
}
