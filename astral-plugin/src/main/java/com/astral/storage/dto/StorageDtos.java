package com.astral.storage.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * storage 插件 DTO 集合（请求/响应均为不可变 record）
 */
public final class StorageDtos {

    private StorageDtos() {}

    // ==================== 存储配置 ====================

    public record ConfigCreateReq(
            String name,
            String providerType,
            String chatId,
            String workerBaseUrl,
            Map<String, String> providerOptions,
            Long maxFileSize,
            String remark) {}

    public record ConfigUpdateReq(
            String chatId,
            String workerBaseUrl,
            Map<String, String> providerOptions,
            Long maxFileSize,
            String remark,
            String status) {}

    public record ConfigTestResp(String healthStatus, String message, String botUsername) {}

    // ==================== 文件夹与授权 ====================

    /** 绑定项：配置 ID + 优先级（null = 100；小值优先） */
    public record BindingReq(Long storageConfigId, Integer priority) {}

    /**
     * policy：上传策略对象（前端结构化提交，服务端归一化落 JSON；null=不改动，空对象=清除）
     * <p>configs：文件夹绑定的存储配置列表（多选 + 优先级），null=不改动，空列表=清空绑定
     * （清空后该文件夹禁止上传，无默认回落）；storageStrategy：FAILOVER/ROUND_ROBIN，null=保持/默认 FAILOVER。</p>
     */
    public record FolderCreateReq(
            Long parentId,
            String folderName,
            String visibility,
            Map<String, Object> policy,
            String storageStrategy,
            List<BindingReq> configs) {}

    /** 目录更新：strategy/configs 任一显式携带即视为重设（null = 保持不变） */
    public record FolderUpdateReq(
            String folderName,
            String visibility,
            String status,
            Map<String, Object> policy,
            String storageStrategy,
            List<BindingReq> configs) {

        /** 兼容旧调用点（仅改名字/可见性等局部更新，不动目标） */
        public FolderUpdateReq(String folderName, String visibility, String status) {
            this(folderName, visibility, status, null, null, null);
        }
    }

    /** 绑定视图：配置 ID + 展示信息 + 优先级（停用配置也原样展示） */
    public record BindingView(
            Long configId, String configName, String providerType, Integer priority, String configStatus) {}

    /**
     * 管理端目录视图：目录基础字段 + 调度策略 + 绑定集合 + 只读解析结果。
     * <p>resolved* 字段仅供列表展示，解析失败（无绑定/渠道停用等）一律给 null，
     * 不让展示层的解析异常影响目录管理本身。</p>
     */
    public record FolderView(
            Long id,
            Long parentId,
            String folderName,
            String folderPath,
            String visibility,
            String status,
            String ownerType,
            String ownerId,
            String storageStrategy,
            Long targetVersion,
            String uploadPolicy,
            List<BindingView> configs,
            Long resolvedConfigId,
            String resolvedProviderType,
            String resolvedTargetName,
            String createBy,
            LocalDateTime createTime,
            String updateBy,
            LocalDateTime updateTime) {}

    /** 授权行：主体 + 权限集合 + 可选限定配置（须为文件夹绑定子集，空=跟随文件夹绑定） */
    public record FolderPermRow(String subjectType, String subjectId, String permissions, List<BindingReq> configs) {

        /** 兼容旧调用点（不限定配置） */
        public FolderPermRow(String subjectType, String subjectId, String permissions) {
            this(subjectType, subjectId, permissions, null);
        }
    }

    public record FolderPermSaveReq(List<FolderPermRow> rows) {}

    /** 授权视图：授权行 + 限定的配置绑定 */
    public record PermView(
            Long id,
            String subjectType,
            String subjectId,
            String permissions,
            LocalDateTime expiresTime,
            List<BindingView> configs) {}

    /** 用户侧「我的文件夹」视图：文件夹基础信息 + 当前用户在该文件夹上的权限集合（ALL 或逗号分隔权限名） + 绑定配置 id 列表 + 只读解析结果 */
    public record FolderMineView(
            Long id,
            String folderName,
            String folderPath,
            String visibility,
            String status,
            String myPermissions,
            Long parentId,
            List<Long> bindingConfigIds,
            Long resolvedConfigId,
            String resolvedProviderType,
            String resolvedTargetName) {}

    // ==================== 上传与下载 ====================

    public record UploadTicketReq(Long folderId, String fileName, String contentType, long sizeBytes) {}

    /** 上传凭证视图（form 仅 UPYUN 表单直传非空：policy/authorization 随凭证下发） */
    public record TicketView(
            String uploadUrl, String method, String formField, String uploadId, long expiresAt, TicketFormView form) {}

    /** 表单直传附加字段（UPYUN）：随 upload-ticket 一并下发给浏览器，multipart POST 提交 */
    public record TicketFormView(String policy, String authorization) {}

    public record DownloadUrlView(String url, long expiresAt) {}

    /** 永久公开链接（不携带过期时间；仅 PUBLIC 可见文件且 Provider 支持时可签发） */
    public record PermanentUrlView(String url) {}

    /** R2/S3 直传完成后的浏览器回执：凭 uploadId 登记文件 */
    public record RegisterUploadReq(String uploadId) {}

    // ==================== Worker 回调与回执 ====================

    public record WorkerCallbackReq(
            String uploadId,
            String messageId,
            String telegramFileId,
            String fileUniqueId,
            long sizeBytes,
            String contentType,
            String fileName) {}

    /** 下载回源元数据（Worker 据此调用 Telegram getFile 并设置响应头） */
    public record OriginFileView(
            String publicId,
            long contentVersion,
            String status,
            String visibility,
            String contentType,
            long sizeBytes,
            String fileName,
            String telegramFileId) {}

    public record WorkerTaskView(long taskId, String chatId, String messageId, String publicId) {}

    public record WorkerAckReq(boolean success, String errorMessage) {}

    /** 文件管理查询；sort 只允许服务端白名单字段。 */
    public record FileQuery(Long folderId, String keyword, String category, String visibility, String sort) {}

    public record FileUpdateReq(String originalName, Long folderId) {}

    public record UserFolderReq(Long parentId, String folderName) {}

    public record FileBatchReq(List<String> publicIds, String action, Long folderId, String visibility) {}

    public record BatchFailure(String publicId, String message) {}

    public record BatchResult(List<String> succeeded, List<BatchFailure> failed) {}
}
