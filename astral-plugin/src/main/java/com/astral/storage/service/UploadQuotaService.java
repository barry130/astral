package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageUploadChargeEntity;
import com.astral.storage.entity.StorageUploadOperationEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageUploadChargeMapper;
import com.astral.storage.mapper.StorageUploadOperationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上传配额：用量 = 直传消费记录 + 当日占用中的上传操作（§9.1）。
 *
 * <p>两段式上传登记出的文件带 {@code upload_operation_id}，其额度由操作的 {@code quota_charge}
 * 表达（HELD 占用 / CONSUMED 已消耗 / REFUNDED 已退回），因此文件侧只统计历史直传路径
 * （{@code upload_operation_id IS NULL}），避免同一次上传被计两次。</p>
 *
 * <p>并发：同一 {@code (folderId, actorId)} 的配额判定在 {@code storage-quota:{folderId}:{actorId}}
 * 顾问锁内串行，锁顺序见 §8.4（… → 配额 → uploadId/操作）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadQuotaService {

    private final StorageUploadOperationMapper operationMapper;
    private final StorageFileMapper fileMapper;
    private final StorageUploadChargeMapper chargeMapper;

    /** 当日已用量（文件 + 占用中的操作） */
    public long usage(Long folderId, String actorId, LocalDate day) {
        long files = fileMapper.selectCount(new LambdaQueryWrapper<StorageFileEntity>()
                .eq(StorageFileEntity::getFolderId, folderId)
                .eq(StorageFileEntity::getUploaderId, actorId)
                .isNull(StorageFileEntity::getUploadOperationId)
                .notExists("SELECT 1 FROM sys_storage_upload_charge c WHERE c.file_id = sys_storage_file.id")
                .ne(StorageFileEntity::getStatus, StorageFileEntity.STATUS_FAILED)
                .ge(StorageFileEntity::getCreateTime, day.atStartOfDay())
                .lt(StorageFileEntity::getCreateTime, day.plusDays(1).atStartOfDay()));
        long operations = operationMapper.selectCount(new LambdaQueryWrapper<StorageUploadOperationEntity>()
                .eq(StorageUploadOperationEntity::getFolderId, folderId)
                // 配额是「用户维度」的：USER 与 PLUGIN 的 actorId 各自独立命名空间，
                // 不限定 actorType 会让同名 pluginId/userId 互相占用额度（甚至互相拒绝上传）。
                .eq(StorageUploadOperationEntity::getActorType, StorageUploadOperationEntity.ACTOR_USER)
                .eq(StorageUploadOperationEntity::getActorId, actorId)
                .eq(StorageUploadOperationEntity::getQuotaDay, day)
                .in(
                        StorageUploadOperationEntity::getQuotaCharge,
                        List.of(
                                StorageUploadOperationEntity.CHARGE_HELD,
                                StorageUploadOperationEntity.CHARGE_CONSUMED)));
        long charges = chargeMapper.selectCount(new LambdaQueryWrapper<StorageUploadChargeEntity>()
                .eq(StorageUploadChargeEntity::getFolderId, folderId)
                .eq(StorageUploadChargeEntity::getActorId, actorId)
                .eq(StorageUploadChargeEntity::getQuotaDay, day)
                .eq(StorageUploadChargeEntity::getCharge, true));
        return files + operations + charges;
    }

    /** Called inside the file registration transaction, after acquiring the shared quota lock. */
    public void recordLegacy(StorageFileEntity file, boolean exempt) {
        var charge = new StorageUploadChargeEntity();
        charge.setUploadId(file.getUploadId());
        charge.setFileId(file.getId());
        charge.setFolderId(file.getFolderId());
        charge.setActorId(file.getUploaderId());
        charge.setQuotaDay(LocalDate.now());
        charge.setCharge(!exempt);
        chargeMapper.insert(charge);
    }

    /** Content rejection is an explicit refund; deleting/moving a successful file is not. */
    @Transactional(rollbackFor = Exception.class)
    public boolean rejectLegacyFile(Long fileId) {
        // Both facts commit together. A concurrent delete or completed rejection must not gain a refund.
        int changed = fileMapper.update(
                null,
                new LambdaUpdateWrapper<StorageFileEntity>()
                        .eq(StorageFileEntity::getId, fileId)
                        .eq(StorageFileEntity::getStatus, StorageFileEntity.STATUS_AVAILABLE)
                        .set(StorageFileEntity::getStatus, StorageFileEntity.STATUS_FAILED)
                        .set(StorageFileEntity::getUpdateTime, LocalDateTime.now()));
        if (changed == 0) return false;
        chargeMapper.update(
                null,
                new LambdaUpdateWrapper<StorageUploadChargeEntity>()
                        .eq(StorageUploadChargeEntity::getFileId, fileId)
                        .set(StorageUploadChargeEntity::getCharge, false));
        return true;
    }

    /**
     * 发起正文接收前预占额度：{@code HELD} 写入操作行（与状态 CAS 同一个事务）。
     * <p>额度判定不可绕过：超限直接 STORAGE032，不做排队（无队列语义，§11.1）。</p>
     */
    public void reserve(StorageUploadOperationEntity operation, long dailyLimit) {
        LocalDate day = LocalDate.now();
        if (dailyLimit <= 0) {
            operation.setQuotaDay(day);
            operation.setQuotaCharge(StorageUploadOperationEntity.CHARGE_NONE);
            operation.setQuotaExempt(1);
            return;
        }
        fileMapper.lockUploadKey("storage-quota:" + operation.getFolderId() + ":" + operation.getActorId());
        // 同一操作在同一自然日已预占过额度（例如 NOT_SENT 后切目标重试）：直接复用已有 HELD，
        // 否则 usage() 会把本操作自己算进去，dailyLimit=1 时重试必然 STORAGE032 死锁。
        if (StorageUploadOperationEntity.CHARGE_HELD.equals(operation.getQuotaCharge())
                && day.equals(operation.getQuotaDay())) {
            return;
        }
        // 跨日重试：旧日的 HELD 不再属于今天，先退回，避免旧行永远占用旧日额度。
        if (StorageUploadOperationEntity.CHARGE_HELD.equals(operation.getQuotaCharge())) {
            operationMapper.update(
                    null,
                    new LambdaUpdateWrapper<StorageUploadOperationEntity>()
                            .eq(StorageUploadOperationEntity::getId, operation.getId())
                            .eq(StorageUploadOperationEntity::getQuotaCharge, StorageUploadOperationEntity.CHARGE_HELD)
                            .set(
                                    StorageUploadOperationEntity::getQuotaCharge,
                                    StorageUploadOperationEntity.CHARGE_REFUNDED)
                            .set(StorageUploadOperationEntity::getUpdateTime, LocalDateTime.now()));
        }
        long used = usage(operation.getFolderId(), operation.getActorId(), day);
        if (used >= dailyLimit) {
            throw new BusinessException("STORAGE032");
        }
        operation.setQuotaDay(day);
        operation.setQuotaCharge(StorageUploadOperationEntity.CHARGE_HELD);
        operation.setQuotaExempt(0);
    }
}
