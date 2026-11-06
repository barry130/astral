package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 直传凭证的渠道解析：统一走 {@link StorageTargetResolver}（授权绑定子集 → 文件夹绑定），
 * 取首个候选。CUSTOM_HTTP 只能走两段式上传（第三方 API 收正文），不支持浏览器直传。
 */
@Component
@RequiredArgsConstructor
public class StorageTicketTargetService {

    private final StorageTargetResolver targetResolver;
    private final StorageConfigMapper configMapper;

    public StorageConfigEntity requireDirectUploadConfig(StorageFolderEntity folder, String userId) {
        StorageTargetResolver.Candidate candidate =
                targetResolver.resolveForUpload(folder, userId).firstCandidate();
        StorageConfigEntity config = configMapper.selectById(candidate.configId());
        if (config == null) {
            throw new BusinessException("STORAGE002");
        }
        if (HttpUploadGateway.PROVIDER_CUSTOM_HTTP.equals(config.getProviderType())) {
            throw new BusinessException("STORAGE039", "该渠道只支持两段式上传，不能下发浏览器直传凭证");
        }
        if (!StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
            throw new BusinessException("STORAGE002");
        }
        return config;
    }
}
