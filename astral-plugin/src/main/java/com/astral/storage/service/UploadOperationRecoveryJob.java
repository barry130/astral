package com.astral.storage.service;

import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.entity.StorageUploadOperationEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageFolderMapper;
import com.astral.storage.mapper.StorageUploadOperationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 上传操作恢复任务（§8.3、§13.3）。
 *
 * <p>单实例串行、每批最多 20 条，只处理租约过期且不在本进程准入中的操作：</p>
 * <ul>
 *   <li>RECEIVING 且租约过期：未过意图 TTL → 退回 CREATED；已过期 → EXPIRED 并退回预留额度；</li>
 *   <li>SENDING：绝不自动重发（可能已在上游创建），超过 sendDeadline 后落 UNKNOWN_OUTCOME；</li>
 *   <li>REMOTE_SUCCEEDED：不重新调用上游，只用 safeResultJson 补登记本地文件并置 COMMITTED。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "astral.plugins.storage.upload-operation.recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class UploadOperationRecoveryJob {

    private static final int BATCH_SIZE = 20;

    private final StorageUploadOperationMapper operationMapper;
    private final StorageFileMapper fileMapper;
    private final StorageFolderMapper folderMapper;
    private final UploadAdmissionControl admissionControl;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(fixedDelayString = "#{${astral.plugins.storage.upload-operation.recovery-interval-seconds:60} * 1000}")
    public void recover() {
        LocalDateTime now = LocalDateTime.now();
        List<StorageUploadOperationEntity> candidates =
                operationMapper.selectList(new LambdaQueryWrapper<StorageUploadOperationEntity>()
                        .in(
                                StorageUploadOperationEntity::getState,
                                List.of(
                                        StorageUploadOperationEntity.STATE_RECEIVING,
                                        StorageUploadOperationEntity.STATE_SENDING,
                                        StorageUploadOperationEntity.STATE_REMOTE_SUCCEEDED))
                        .and(wrapper -> wrapper.isNull(StorageUploadOperationEntity::getLeaseUntil)
                                .or()
                                .lt(StorageUploadOperationEntity::getLeaseUntil, now))
                        .orderByAsc(StorageUploadOperationEntity::getLeaseUntil)
                        .last("LIMIT " + BATCH_SIZE));
        if (candidates.isEmpty()) {
            return;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        for (StorageUploadOperationEntity candidate : candidates) {
            try {
                template.executeWithoutResult(status -> recoverOne(candidate, now));
            } catch (RuntimeException ex) {
                log.warn("上传操作恢复失败: uploadId={}, reason={}", candidate.getUploadId(), ex.getMessage());
            }
        }
    }

    private void recoverOne(StorageUploadOperationEntity candidate, LocalDateTime now) {
        StorageUploadOperationEntity operation = operationMapper.selectById(candidate.getId());
        if (operation == null) {
            return;
        }
        if (StorageUploadOperationEntity.STATE_RECEIVING.equals(operation.getState())) {
            recoverReceiving(operation, now);
        } else if (StorageUploadOperationEntity.STATE_SENDING.equals(operation.getState())) {
            recoverSending(operation, now);
        } else if (StorageUploadOperationEntity.STATE_REMOTE_SUCCEEDED.equals(operation.getState())) {
            recoverRemoteSucceeded(operation, now);
        }
    }

    private void recoverReceiving(StorageUploadOperationEntity operation, LocalDateTime now) {
        if (admissionControl.isInflight(operation.getUploadId())) {
            return;
        }
        boolean intentAlive = operation.getIntentExpiresAt() != null
                && operation.getIntentExpiresAt().isAfter(now);
        LambdaUpdateWrapper<StorageUploadOperationEntity> update = Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                .eq(StorageUploadOperationEntity::getId, operation.getId())
                .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_RECEIVING)
                .set(StorageUploadOperationEntity::getLeaseToken, null)
                .set(StorageUploadOperationEntity::getLeaseUntil, null)
                .set(
                        StorageUploadOperationEntity::getVersion,
                        operation.getVersion() == null ? 1L : operation.getVersion() + 1)
                .set(StorageUploadOperationEntity::getUpdateTime, now);
        if (intentAlive) {
            update.set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_CREATED);
        } else {
            update.set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_EXPIRED)
                    .set(StorageUploadOperationEntity::getCompletedAt, now);
            if (StorageUploadOperationEntity.CHARGE_HELD.equals(operation.getQuotaCharge())) {
                update.set(StorageUploadOperationEntity::getQuotaCharge, StorageUploadOperationEntity.CHARGE_REFUNDED);
            }
        }
        if (operationMapper.update(null, update) > 0 && !intentAlive) {
            log.info("上传意图已过期: uploadId={}", operation.getUploadId());
        }
    }

    private void recoverSending(StorageUploadOperationEntity operation, LocalDateTime now) {
        // 本进程仍在发送中的操作即使超时也不能冻结（契约「不在本进程准入中」）：
        // sendDeadline 只是预算上限，同步发送路径完成后自行 CAS 落库；提前标 UNKNOWN_OUTCOME
        // 会让之后的 COMMITTED CAS 失败，成功上传被丢弃。
        if (admissionControl.isInflight(operation.getUploadId())) {
            return;
        }
        if (operation.getSendDeadline() != null && operation.getSendDeadline().isAfter(now)) {
            return;
        }
        LambdaUpdateWrapper<StorageUploadOperationEntity> update = Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                .eq(StorageUploadOperationEntity::getId, operation.getId())
                .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_SENDING)
                .set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_UNKNOWN_OUTCOME)
                .set(StorageUploadOperationEntity::getFailureCode, "RECOVERY_UNKNOWN")
                .set(StorageUploadOperationEntity::getLeaseToken, null)
                .set(StorageUploadOperationEntity::getLeaseUntil, null)
                .set(
                        StorageUploadOperationEntity::getVersion,
                        operation.getVersion() == null ? 1L : operation.getVersion() + 1)
                .set(StorageUploadOperationEntity::getUpdateTime, now);
        if (operationMapper.update(null, update) > 0) {
            log.warn("发送中的上传操作结果未知，已冻结: uploadId={}", operation.getUploadId());
        }
    }

    private void recoverRemoteSucceeded(StorageUploadOperationEntity operation, LocalDateTime now) {
        Map<String, Object> payload = readJson(operation.getSafeResultJson());
        String url = payload.get("url") == null ? null : String.valueOf(payload.get("url"));
        if (url == null || url.isBlank()) {
            LambdaUpdateWrapper<StorageUploadOperationEntity> update =
                    Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                            .eq(StorageUploadOperationEntity::getId, operation.getId())
                            .eq(
                                    StorageUploadOperationEntity::getState,
                                    StorageUploadOperationEntity.STATE_REMOTE_SUCCEEDED)
                            .set(
                                    StorageUploadOperationEntity::getState,
                                    StorageUploadOperationEntity.STATE_UNKNOWN_OUTCOME)
                            .set(StorageUploadOperationEntity::getFailureCode, "RECOVERY_NO_RESULT")
                            .set(StorageUploadOperationEntity::getUpdateTime, now);
            operationMapper.update(null, update);
            return;
        }
        StorageFolderEntity folder = folderMapper.selectById(operation.getFolderId());
        StorageFileEntity file = new StorageFileEntity();
        file.setPublicId(operation.getPublicId());
        file.setUploadId(operation.getUploadId());
        file.setUploadOperationId(operation.getId());
        file.setFolderId(operation.getFolderId());
        file.setStorageConfigId(operation.getChosenConfigId());
        file.setProviderType(HttpUploadGateway.PROVIDER_CUSTOM_HTTP);
        file.setProviderLocatorJson(operation.getSafeResultJson());
        file.setOriginalName(operation.getOriginalName());
        file.setContentType(operation.getExpectedMime());
        file.setSizeBytes(operation.getActualSize() == null ? 0L : operation.getActualSize());
        file.setChecksum(operation.getActualSha256());
        file.setContentVersion(1L);
        file.setVisibility(folder == null || folder.getVisibility() == null ? "PRIVATE" : folder.getVisibility());
        file.setStatus(StorageFileEntity.STATUS_AVAILABLE);
        file.setUploaderType(StorageUploadOperationEntity.ACTOR_USER);
        file.setUploaderId(operation.getActorId());
        file.setCreateTime(now);
        file.setUpdateTime(now);
        fileMapper.insert(file);

        LambdaUpdateWrapper<StorageUploadOperationEntity> update = Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                .eq(StorageUploadOperationEntity::getId, operation.getId())
                .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_REMOTE_SUCCEEDED)
                .set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_COMMITTED)
                .set(StorageUploadOperationEntity::getFileId, file.getId())
                .set(StorageUploadOperationEntity::getQuotaCharge, StorageUploadOperationEntity.CHARGE_CONSUMED)
                .set(StorageUploadOperationEntity::getCompletedAt, now)
                .set(
                        StorageUploadOperationEntity::getVersion,
                        operation.getVersion() == null ? 1L : operation.getVersion() + 1)
                .set(StorageUploadOperationEntity::getUpdateTime, now);
        operationMapper.update(null, update);
        log.info("已补登记远端成功但未落库的上传: uploadId={}, fileId={}", operation.getUploadId(), file.getId());
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
