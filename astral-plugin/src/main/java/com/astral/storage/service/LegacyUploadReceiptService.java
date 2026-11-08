package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageTaskEntity;
import com.astral.storage.entity.StorageUploadTicketEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageFolderMapper;
import com.astral.storage.mapper.StorageTaskMapper;
import com.astral.storage.mapper.StorageUploadTicketMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Durable direct-upload context; the Redis copy is a cache, never the only remote receipt. */
@Service
@RequiredArgsConstructor
public class LegacyUploadReceiptService {
    private final StorageUploadTicketMapper ticketMapper;
    private final StorageFileMapper fileMapper;
    private final StorageFolderMapper folderMapper;
    private final StorageConfigMapper configMapper;
    private final StorageTaskMapper taskMapper;
    private final StorageTargetResolver targetResolver;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class)
    public void reserve(String payload, long folderVersion, long configVersion) {
        JsonNode context = json.readTree(payload);
        long folderId = context.path("folderId").asLong();
        fileMapper.lockUploadKey(StorageFolderService.securityLockKey(folderId));
        var config = configMapper.lockForSend(context.path("configId").asLong());
        if (config == null) throw new BusinessException("STORAGE002");
        var folder = folderMapper.selectById(folderId);
        targetResolver.requireCurrentCandidate(
                folder,
                context.path("uploaderId").asText(null),
                context.path("adminExempt").asBoolean(false),
                folderId,
                folderVersion,
                config.getId(),
                configVersion);
        ticketMapper.insert(newTicket(context, payload));
    }

    /** Long-lived context supports callbacks after expiry; it does not extend a remote upload signature. */
    public String payload(String uploadId) {
        var ticket = find(uploadId);
        return ticket == null ? null : ticket.getPayloadJson();
    }

    public record Receipt(StorageDtos.WorkerCallbackReq request, boolean committed) {}

    public boolean wasCommitted(String uploadId) {
        var ticket = find(uploadId);
        return ticket != null && "COMMITTED".equals(ticket.getState());
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Receipt receive(JsonNode context, StorageDtos.WorkerCallbackReq receipt) {
        long folderId = context.path("folderId").asLong();
        fileMapper.lockUploadKey(StorageFolderService.securityLockKey(folderId));
        fileMapper.lockUploadKey("storage-upload:" + receipt.uploadId());
        var ticket = find(receipt.uploadId());
        if (ticket == null) {
            // Adopt still-valid tickets issued by the previous release before their Redis entry expires.
            ticket = newTicket(context, json.writeValueAsString(context));
            ticketMapper.insert(ticket);
        }
        String serialized = json.writeValueAsString(receipt);
        if (ticket.getReceiptJson() != null) {
            var authoritative = json.readValue(ticket.getReceiptJson(), StorageDtos.WorkerCallbackReq.class);
            if (!receipt.telegramFileId().equals(authoritative.telegramFileId())
                    || !receipt.messageId().equals(authoritative.messageId())) {
                enqueueDuplicate(context.path("chatId").asText(), receipt);
            }
            return new Receipt(
                    authoritative,
                    !"REMOTE_SUCCEEDED".equals(ticket.getState()) && !"PENDING".equals(ticket.getState()));
        }
        ticketMapper.update(
                null,
                new LambdaUpdateWrapper<StorageUploadTicketEntity>()
                        .eq(StorageUploadTicketEntity::getId, ticket.getId())
                        .set(StorageUploadTicketEntity::getReceiptJson, serialized)
                        .set(StorageUploadTicketEntity::getState, "REMOTE_SUCCEEDED")
                        .set(StorageUploadTicketEntity::getUpdateTime, LocalDateTime.now()));
        if (folderMapper.selectById(folderId) == null) {
            // The historical folder may already be gone: retain the receipt and schedule remote cleanup.
            var task = new StorageTaskEntity();
            task.setTaskType(StorageTaskEntity.TYPE_DELETE_REMOTE);
            task.setPayloadJson(json.writeValueAsString(java.util.Map.of(
                    "chatId",
                    context.path("chatId").asText(),
                    "messageId",
                    receipt.messageId(),
                    "fileId",
                    receipt.telegramFileId(),
                    "uploadId",
                    receipt.uploadId())));
            task.setStatus(StorageTaskEntity.STATUS_PENDING);
            task.setRetryCount(0);
            taskMapper.insert(task);
            ticketMapper.update(
                    null,
                    new LambdaUpdateWrapper<StorageUploadTicketEntity>()
                            .eq(StorageUploadTicketEntity::getId, ticket.getId())
                            .set(StorageUploadTicketEntity::getState, "CLEANUP_PENDING"));
        }
        return new Receipt(receipt, false);
    }

    public void cleanupAcknowledged(String payload, boolean success) {
        var task = json.readTree(payload);
        String uploadId = task.path("uploadId").asText(null);
        if (uploadId == null) return;
        var ticket = find(uploadId);
        if (ticket == null || ticket.getReceiptJson() == null) return;
        var receipt = json.readValue(ticket.getReceiptJson(), StorageDtos.WorkerCallbackReq.class);
        var context = json.readTree(ticket.getPayloadJson());
        if (!receipt.messageId().equals(task.path("messageId").asText())
                || !receipt.telegramFileId().equals(task.path("fileId").asText())
                || !context.path("chatId").asText().equals(task.path("chatId").asText())) return;
        ticketMapper.update(
                null,
                new LambdaUpdateWrapper<StorageUploadTicketEntity>()
                        .eq(StorageUploadTicketEntity::getUploadId, uploadId)
                        .eq(StorageUploadTicketEntity::getState, "CLEANUP_PENDING")
                        .set(StorageUploadTicketEntity::getState, success ? "CLEANED" : "CLEANUP_FAILED")
                        .set(StorageUploadTicketEntity::getUpdateTime, LocalDateTime.now()));
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void reject(JsonNode context, StorageDtos.WorkerCallbackReq receipt) {
        fileMapper.lockUploadKey(
                StorageFolderService.securityLockKey(context.path("folderId").asLong()));
        fileMapper.lockUploadKey("storage-upload:" + receipt.uploadId());
        var ticket = find(receipt.uploadId());
        if (ticket == null || !"REMOTE_SUCCEEDED".equals(ticket.getState())) return;
        enqueueDuplicate(context.path("chatId").asText(), receipt);
        ticketMapper.update(
                null,
                new LambdaUpdateWrapper<StorageUploadTicketEntity>()
                        .eq(StorageUploadTicketEntity::getId, ticket.getId())
                        .eq(StorageUploadTicketEntity::getState, "REMOTE_SUCCEEDED")
                        .set(StorageUploadTicketEntity::getState, "CLEANUP_PENDING")
                        .set(StorageUploadTicketEntity::getUpdateTime, LocalDateTime.now()));
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void receiveReplay(StorageFileEntity file, StorageDtos.WorkerCallbackReq receipt) {
        JsonNode locator = json.readTree(file.getProviderLocatorJson());
        if (receipt.telegramFileId().equals(locator.path("fileId").asText())
                && receipt.messageId().equals(locator.path("messageId").asText())) return;
        // A repeated ticket may have produced another Telegram object. Never attach that object to the old URL.
        fileMapper.lockUploadKey("storage-upload:" + receipt.uploadId());
        enqueueDuplicate(locator.path("chatId").asText(), receipt);
    }

    private void enqueueDuplicate(String chatId, StorageDtos.WorkerCallbackReq receipt) {
        String payload = json.writeValueAsString(java.util.Map.of(
                "chatId",
                chatId,
                "messageId",
                receipt.messageId(),
                "fileId",
                receipt.telegramFileId(),
                "uploadId",
                receipt.uploadId()));
        if (taskMapper.selectCount(new LambdaQueryWrapper<StorageTaskEntity>()
                        .eq(StorageTaskEntity::getTaskType, StorageTaskEntity.TYPE_DELETE_REMOTE)
                        .eq(StorageTaskEntity::getPayloadJson, payload))
                > 0) return;
        var task = new StorageTaskEntity();
        task.setTaskType(StorageTaskEntity.TYPE_DELETE_REMOTE);
        task.setPayloadJson(payload);
        task.setRetryCount(0);
        task.setStatus(StorageTaskEntity.STATUS_PENDING);
        taskMapper.insert(task);
    }

    /** Must join the registration transaction so success cannot get ahead of the file row. */
    public void committed(StorageFileEntity file) {
        ticketMapper.update(
                null,
                new LambdaUpdateWrapper<StorageUploadTicketEntity>()
                        .eq(StorageUploadTicketEntity::getUploadId, file.getUploadId())
                        .set(StorageUploadTicketEntity::getFileId, file.getId())
                        .set(StorageUploadTicketEntity::getState, "COMMITTED")
                        .set(StorageUploadTicketEntity::getUpdateTime, LocalDateTime.now()));
    }

    public boolean hasPending(Long folderId) {
        return ticketMapper.selectCount(new LambdaQueryWrapper<StorageUploadTicketEntity>()
                        .eq(StorageUploadTicketEntity::getFolderId, folderId)
                        .and(w -> w.eq(StorageUploadTicketEntity::getState, "REMOTE_SUCCEEDED")
                                .or(pending -> pending.eq(StorageUploadTicketEntity::getState, "PENDING")
                                        .gt(StorageUploadTicketEntity::getExpiresAt, LocalDateTime.now()))))
                > 0;
    }

    private StorageUploadTicketEntity find(String uploadId) {
        return ticketMapper.selectOne(new LambdaQueryWrapper<StorageUploadTicketEntity>()
                .eq(StorageUploadTicketEntity::getUploadId, uploadId));
    }

    private StorageUploadTicketEntity newTicket(JsonNode context, String payload) {
        var ticket = new StorageUploadTicketEntity();
        ticket.setUploadId(context.path("uploadId").asText());
        ticket.setFolderId(context.path("folderId").asLong());
        ticket.setActorId(context.path("uploaderId").asText(null));
        ticket.setPayloadJson(payload);
        ticket.setState("PENDING");
        long expiry = context.path("exp").asLong(Instant.now().getEpochSecond());
        // Keep the folder until the credential expires plus the bounded worker request/retry window.
        ticket.setExpiresAt(
                LocalDateTime.ofInstant(Instant.ofEpochSecond(expiry).plusSeconds(300), ZoneId.systemDefault()));
        return ticket;
    }
}
