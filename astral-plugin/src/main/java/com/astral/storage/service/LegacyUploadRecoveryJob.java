package com.astral.storage.service;

import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageUploadTicketEntity;
import com.astral.storage.mapper.StorageUploadTicketMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Retries local registration from durable receipts without sending any new remote upload. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegacyUploadRecoveryJob {
    private final StorageUploadTicketMapper ticketMapper;
    private final StorageFileService fileService;
    private final ObjectMapper json;

    @Scheduled(fixedDelay = 60_000)
    public void recover() {
        for (var ticket : ticketMapper.selectList(new LambdaQueryWrapper<StorageUploadTicketEntity>()
                .eq(StorageUploadTicketEntity::getState, "REMOTE_SUCCEEDED")
                .isNotNull(StorageUploadTicketEntity::getReceiptJson)
                .orderByAsc(StorageUploadTicketEntity::getUpdateTime)
                .last("LIMIT 20"))) {
            try {
                var request = json.readValue(ticket.getReceiptJson(), StorageDtos.WorkerCallbackReq.class);
                var file = fileService.registerFromCallback(request);
                fileService.verifyContentAfterRegistration(file.getPublicId());
            } catch (RuntimeException e) {
                log.warn(
                        "直传回执登记待恢复: uploadId={}, reason={}",
                        ticket.getUploadId(),
                        e.getClass().getSimpleName());
                // Rotate failed rows to the end; permanent failures must not starve later receipts.
                ticketMapper.update(
                        null,
                        new LambdaUpdateWrapper<StorageUploadTicketEntity>()
                                .eq(StorageUploadTicketEntity::getId, ticket.getId())
                                .eq(StorageUploadTicketEntity::getState, "REMOTE_SUCCEEDED")
                                .set(StorageUploadTicketEntity::getUpdateTime, java.time.LocalDateTime.now()));
            }
        }
    }
}
