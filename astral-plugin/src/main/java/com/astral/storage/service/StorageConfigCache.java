package com.astral.storage.service;

import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Duration;

/**
 * 存储配置进程内只读缓存（Caffeine，TTL 60s）
 * <p>上传回执登记、URL 签发/取回/删除、Worker 拉取任务等高频文件操作原先每次都
 * {@code selectById} 一轮 sys_storage_config；{@code QtMediaService} 的默认配置解析
 * 也是每次上传一条 {@code selectOne}。本组件集中缓存这两类<b>服务端内部</b>读取。</p>
 * <p><b>注意</b>：缓存的是未打码的原始行（provider_options 含对象存储凭证），仅供
 * 服务端直连 Provider 使用，绝不能直接返回给任何接口；管理端列表/详情走
 * {@code StorageConfigService} 的直查 + redactSecret，不经过本缓存。</p>
 * <p>所有配置写入（create/update/delete/setDefault/test）都调用 {@link #evictAll()}
 * 立即失效，TTL 60s 兜底。单实例部署，无跨进程一致性问题。</p>
 */
@Component
public class StorageConfigCache {

    /** 默认启用配置的缓存键（单键） */
    private static final String KEY_DEFAULT = "defaultEnabled";

    @Resource
    private StorageConfigMapper configMapper;

    /** 按 ID 的配置行缓存（含凭证原文，仅服务端内部使用） */
    private final Cache<Long, StorageConfigEntity> byIdCache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /** 默认启用配置缓存（单键） */
    private final Cache<String, StorageConfigEntity> defaultCache = Caffeine.newBuilder()
            .maximumSize(4)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /** 按 ID 查配置（未打码原文）；查不到返回 null，语义与 selectById 一致，由调用方决定报错 */
    public StorageConfigEntity getById(Long id) {
        if (id == null) {
            return null;
        }
        return byIdCache.get(id, k -> configMapper.selectById(k));
    }

    /** 默认启用的配置（is_default=1 + status=ENABLED）；无则返回 null，由调用方决定报错 */
    public StorageConfigEntity getDefaultEnabled() {
        return defaultCache.get(KEY_DEFAULT, k -> configMapper.selectOne(new LambdaQueryWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getIsDefault, 1)
                .eq(StorageConfigEntity::getStatus, StorageConfigEntity.STATUS_ENABLED)
                .last("LIMIT 1")));
    }

    /** 任何 sys_storage_config 写入后调用：失效全部配置缓存 */
    public void evictAll() {
        byIdCache.invalidateAll();
        defaultCache.invalidateAll();
    }
}
