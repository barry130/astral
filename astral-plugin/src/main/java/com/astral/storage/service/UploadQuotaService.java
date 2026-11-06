package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageUploadOperationEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageUploadOperationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 上传配额：用量 = 当日已登记文件 + 当日占用中的上传操作（§9.1）。
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

    /** 当日已用量（文件 + 占用中的操作） */
    public long usage(Long folderId, String actorId, LocalDate day) {
        long files = fileMapper.selectCount(new LambdaQueryWrapper<StorageFileEntity>()
                .eq(StorageFileEntity::getFolderId, folderId)
                .eq(StorageFileEntity::getUploaderId, actorId)
                .isNull(StorageFileEntity::getUploadOperationId)
                .ne(StorageFileEntity::getStatus, StorageFileEntity.STATUS_FAILED)
                .ge(StorageFileEntity::getCreateTime, day.atStartOfDay())
                .lt(StorageFileEntity::getCreateTime, day.plusDays(1).atStartOfDay()));
        long operations = operationMapper.selectCount(new LambdaQueryWrapper<StorageUploadOperationEntity>()
                .eq(StorageUploadOperationEntity::getFolderId, folderId)
                .eq(StorageUploadOperationEntity::getActorId, actorId)
                .eq(StorageUploadOperationEntity::getQuotaDay, day)
                .in(
                        StorageUploadOperationEntity::getQuotaCharge,
                        List.of(
                                StorageUploadOperationEntity.CHARGE_HELD,
                                StorageUploadOperationEntity.CHARGE_CONSUMED)));
        return files + operations;
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
        long used = usage(operation.getFolderId(), operation.getActorId(), day);
        if (used >= dailyLimit) {
            throw new BusinessException("STORAGE032");
        }
        operation.setQuotaDay(day);
        operation.setQuotaCharge(StorageUploadOperationEntity.CHARGE_HELD);
        operation.setQuotaExempt(0);
    }
}
