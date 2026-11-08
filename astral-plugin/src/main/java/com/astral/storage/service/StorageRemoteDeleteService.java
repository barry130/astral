package com.astral.storage.service;

import com.astral.storage.config.StorageAsyncConfig;
import com.astral.storage.entity.StorageConfigEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 远端对象删除（删除类远端操作唯一实现）。
 * <p>用户侧删除立即生效（数据库行直接删除），远端对象清理经此异步执行、尽力而为：
 * 成功记 OK 审计；失败记 ERROR 审计与 WARN 日志（后端留痕），任何情况不向用户回传错误。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageRemoteDeleteService {

    private final S3ObjectService s3ObjectService;
    private final CosApiService cosApiService;
    private final OssApiService ossApiService;
    private final UpyunApiService upyunApiService;
    private final StorageAuditService auditService;

    /**
     * 异步远端删除（删除主流程事务提交后调度）。
     *
     * @param publicId 文件公开 ID（审计用；库行可能已删除）
     * @param config   删除时快照的存储配置；null 或已停用时远端清理记失败留痕
     * @param key      删除时快照的对象键；locator 损坏时为 null，远端清理记失败留痕
     * @param actorId  发起删除的用户（审计用）
     */
    @Async(StorageAsyncConfig.REMOTE_DELETE_EXECUTOR)
    public void deleteQuietly(
            String publicId, String providerType, StorageConfigEntity config, String key, String actorId) {
        try {
            deleteObject(providerType, config, key);
            auditService.record(
                    "FILE_DELETE_REMOTE", "SYSTEM", actorId, "FILE", publicId, "provider=" + providerType, "OK");
        } catch (Exception e) {
            log.warn(
                    "[Storage] 远端对象删除失败（后端留痕，不影响用户侧删除结果）: publicId={}, provider={}, key={}, err={}",
                    publicId,
                    providerType,
                    key,
                    e.getMessage());
            auditService.record(
                    "FILE_DELETE_REMOTE",
                    "SYSTEM",
                    actorId,
                    "FILE",
                    publicId,
                    "provider=" + providerType + ",err=" + e.getMessage(),
                    "ERROR");
        }
    }

    /** 按 Provider DELETE 对象（同步；验证失败清理等内部路径复用） */
    public void deleteObject(String providerType, StorageConfigEntity config, String key) {
        if (ProviderSupport.isS3Family(providerType)) {
            s3ObjectService.deleteObject(config, key);
        } else if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
            cosApiService.deleteObject(config, key);
        } else if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
            ossApiService.deleteObject(config, key);
        } else if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
            upyunApiService.deleteObject(config, key);
        } else {
            // TELEGRAM 走任务表由 Worker 删除、CUSTOM_HTTP 无远端删除接口，都不应进入本方法；
            // 未知渠道必须失败留痕，不能静默返回后记一条「删除成功」审计。
            throw new IllegalStateException("provider does not support direct remote delete: " + providerType);
        }
    }
}
