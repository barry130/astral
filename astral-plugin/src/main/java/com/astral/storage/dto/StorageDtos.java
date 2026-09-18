package com.astral.storage.dto;

import java.util.List;
import java.util.Map;

/**
 * storage 插件 DTO 集合（请求/响应均为不可变 record）
 */
public final class StorageDtos {

    private StorageDtos() {
    }

    // ==================== 存储配置 ====================

    public record ConfigCreateReq(String name, String providerType, String chatId, String workerBaseUrl,
                                  Map<String, String> providerOptions, Long maxFileSize, String remark) {
    }

    public record ConfigUpdateReq(String chatId, String workerBaseUrl, Map<String, String> providerOptions,
                                  Long maxFileSize, String remark, String status) {
    }

    public record ConfigTestResp(String healthStatus, String message, String botUsername) {
    }

    // ==================== 文件夹与授权 ====================

    public record FolderCreateReq(Long parentId, String folderName, Long configId, String visibility) {
    }

    public record FolderUpdateReq(String folderName, String visibility, String status, Long configId) {
    }

    public record FolderPermRow(String subjectType, String subjectId, String permissions) {
    }

    public record FolderPermSaveReq(List<FolderPermRow> rows) {
    }

    /** 用户侧「我的文件夹」视图：文件夹基础信息 + 当前用户在该文件夹上的权限集合（ALL 或逗号分隔权限名） */
    public record FolderMineView(Long id, String folderName, String folderPath, String visibility,
                                 String status, Long storageConfigId, String myPermissions) {
    }

    // ==================== 上传与下载 ====================

    public record UploadTicketReq(Long folderId, String fileName, String contentType, long sizeBytes) {
    }

    /** 上传凭证视图（form 仅 UPYUN 表单直传非空：policy/authorization 随凭证下发） */
    public record TicketView(String uploadUrl, String method, String formField,
                             String uploadId, long expiresAt, TicketFormView form) {
    }

    /** 表单直传附加字段（UPYUN）：随 upload-ticket 一并下发给浏览器，multipart POST 提交 */
    public record TicketFormView(String policy, String authorization) {
    }

    public record DownloadUrlView(String url, long expiresAt) {
    }

    /** 永久公开链接（不携带过期时间；仅 PUBLIC 可见文件且 Provider 支持时可签发） */
    public record PermanentUrlView(String url) {
    }

    /** R2/S3 直传完成后的浏览器回执：凭 uploadId 登记文件 */
    public record RegisterUploadReq(String uploadId) {
    }

    // ==================== Worker 回调与回源 ====================

    public record WorkerCallbackReq(String uploadId, String messageId, String telegramFileId,
                                    String fileUniqueId, long sizeBytes, String contentType,
                                    String fileName) {
    }

    /** 下载回源元数据（Worker 据此调用 Telegram getFile 并设置响应头） */
    public record OriginFileView(String publicId, long contentVersion, String status, String visibility,
                                 String contentType, long sizeBytes, String fileName, String telegramFileId) {
    }

    public record WorkerTaskView(long taskId, String chatId, String messageId, String publicId) {
    }

    public record WorkerAckReq(boolean success, String errorMessage) {
    }
}
