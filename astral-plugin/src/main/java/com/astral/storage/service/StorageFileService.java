package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.entity.StorageTaskEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageTaskMapper;
import com.astral.storage.security.StorageUrlSigner;
import com.astral.storage.security.UploadTicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 存储文件服务
 * <p>
 * TELEGRAM：Worker 直连架构——上传回调登记 Telegram 定位元数据，下载 URL 由 Astral 签名、
 * Worker 校验并回源 Telegram；删除通过任务表由 Worker 拉取执行 deleteMessage 后确认。
 * 对象存储系（R2/S3/七牛/COS/OSS/UPYUN）：浏览器直传后回执登记（服务端 HEAD 确认），
 * 下载走各 Provider 预签名 URL，删除由本服务同步调 Provider API。
 * Astral 全程不接触文件正文。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageFileService {

    private static final String TABLE = "sys_storage_file";
    private static final String TASK_TABLE = "sys_storage_task";
    private static final int MAX_DELETE_RETRY = 5;

    private final StorageFileMapper fileMapper;
    private final StorageTaskMapper taskMapper;
    private final StorageConfigMapper configMapper;
    private final StorageFolderService folderService;
    private final StorageAuditService auditService;
    private final UploadTicketService ticketService;
    private final StorageUrlSigner urlSigner;
    private final S3ObjectService s3ObjectService;
    private final CosApiService cosApiService;
    private final OssApiService ossApiService;
    private final UpyunApiService upyunApiService;
    private final StorageProperties properties;
    private final ObjectMapper objectMapper;

    // ==================== 查询 ====================

    /** 任务分页（管理端：远端删除补偿等） */
    public Page<StorageTaskEntity> pageTasks(long current, long size) {
        return taskMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<StorageTaskEntity>().orderByDesc(StorageTaskEntity::getCreateTime));
    }

    public Page<StorageFileEntity> pageForAdmin(long current, long size, Long folderId, String keyword) {
        LambdaQueryWrapper<StorageFileEntity> wrapper = new LambdaQueryWrapper<>();
        if (folderId != null) {
            wrapper.eq(StorageFileEntity::getFolderId, folderId);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(StorageFileEntity::getOriginalName, keyword.trim());
        }
        wrapper.orderByDesc(StorageFileEntity::getCreateTime);
        return fileMapper.selectPage(new Page<>(current, size), wrapper);
    }

    public Page<StorageFileEntity> pageFolderFiles(Long folderId, String userId, long current, long size) {
        folderService.requirePermission(userId, folderId, "READ");
        return fileMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<StorageFileEntity>()
                        .eq(StorageFileEntity::getFolderId, folderId)
                        .ne(StorageFileEntity::getStatus, StorageFileEntity.STATUS_DELETED)
                        .orderByDesc(StorageFileEntity::getCreateTime));
    }

    public StorageFileEntity getByPublicId(String publicId) {
        StorageFileEntity file = fileMapper.selectOne(new LambdaQueryWrapper<StorageFileEntity>()
                .eq(StorageFileEntity::getPublicId, publicId)
                .last("LIMIT 1"));
        if (file == null) {
            throw new BusinessException("STORAGE016");
        }
        return file;
    }

    // ==================== 上传回调登记 ====================

    /**
     * Worker 上传回调：校验凭证上下文后登记文件元数据（幂等，upload_id 唯一）。
     * 文件正文已在 Worker 中流转，Astral 只保存 Telegram 定位信息。
     */
    @Transactional(rollbackFor = Exception.class)
    public StorageFileEntity registerFromCallback(StorageDtos.WorkerCallbackReq req) {
        if (req.uploadId() == null || req.uploadId().isBlank()) {
            throw new BusinessException("STORAGE008");
        }
        // 幂等：同一 uploadId 重复回调返回已有记录
        StorageFileEntity existing = fileMapper.selectOne(new LambdaQueryWrapper<StorageFileEntity>()
                .eq(StorageFileEntity::getUploadId, req.uploadId())
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        JsonNode ticket = ticketService.validateCallback(
                req.uploadId(), req.sizeBytes(), req.contentType());
        if (req.telegramFileId() == null || req.telegramFileId().isBlank()
                || req.messageId() == null || req.messageId().isBlank()) {
            throw new BusinessException("STORAGE011", "缺少 Telegram 定位信息");
        }

        long folderId = ticketService.folderIdOf(ticket);
        StorageFolderEntity folder = folderService.getById(folderId);
        String publicId = UUID.randomUUID().toString().replace("-", "");
        StorageFileEntity file = new StorageFileEntity();
        file.setPublicId(publicId);
        file.setUploadId(req.uploadId());
        file.setFolderId(folderId);
        file.setStorageConfigId(ticket.path("configId").asLong());
        file.setProviderType("TELEGRAM");
        file.setProviderLocatorJson(buildLocator(ticket.path("chatId").asText(), req, publicId));
        file.setOriginalName(sanitizeName(req.fileName()));
        file.setContentType(normalizeMime(req.contentType()));
        file.setSizeBytes(req.sizeBytes());
        file.setContentVersion(1L);
        file.setVisibility(folder.getVisibility());
        file.setStatus(StorageFileEntity.STATUS_AVAILABLE);
        file.setUploaderType("WORKER");
        file.setUploaderId(ticket.path("uploaderId").asText(null));
        fileMapper.insert(file);
        auditService.record("FILE_UPLOAD", "WORKER", req.uploadId(), "FILE", file.getPublicId(),
                file.getOriginalName() + " " + req.sizeBytes() + "B", "OK");
        log.info("[Storage] 上传登记完成: publicId={}, folderId={}, size={}",
                file.getPublicId(), folderId, req.sizeBytes());
        return file;
    }

    /**
     * 浏览器直传对象存储完成后的回执登记：校验凭证上下文与操作者、HEAD 对象确认真实存在，
     * 然后以签发凭证时生成的 publicId/对象键落库（幂等，upload_id 唯一）。
     */
    @Transactional(rollbackFor = Exception.class)
    public StorageFileEntity registerFromBrowser(String uploadId, String userId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new BusinessException("STORAGE008");
        }
        // 幂等：同一 uploadId 重复回执返回已有记录
        StorageFileEntity existing = fileMapper.selectOne(new LambdaQueryWrapper<StorageFileEntity>()
                .eq(StorageFileEntity::getUploadId, uploadId)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        JsonNode ticket = ticketService.getPayload(uploadId);
        String ticketUploader = ticket.path("uploaderId").asText(null);
        if (userId == null || !userId.equals(ticketUploader)) {
            throw new BusinessException("STORAGE013");
        }
        String providerType = ticket.path("providerType").asText("");
        if (!ProviderSupport.isRegisterFamily(providerType)) {
            throw new BusinessException("STORAGE019", "TELEGRAM 上传由 Worker 回调登记，无需浏览器回执");
        }
        long folderId = ticketService.folderIdOf(ticket);
        StorageFolderEntity folder = folderService.getById(folderId);
        StorageConfigEntity config = configMapper.selectById(ticket.path("configId").asLong());
        if (config == null || !StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
            throw new BusinessException("STORAGE002");
        }
        String objectKey = ticket.path("objectKey").asText(null);
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException("STORAGE009");
        }
        long sizeBytes = headObjectByProvider(providerType, config, objectKey);
        if (sizeBytes < 0) {
            throw new BusinessException("STORAGE009");
        }
        long maxSize = ticket.path("maxSize").asLong(properties.getMaxFileSizeBytes());
        if (sizeBytes > maxSize) {
            throw new BusinessException("STORAGE006");
        }

        String publicId = ticket.path("publicId").asText(null);
        if (publicId == null || publicId.isBlank()) {
            publicId = UUID.randomUUID().toString().replace("-", "");
        }
        StorageFileEntity file = new StorageFileEntity();
        file.setPublicId(publicId);
        file.setUploadId(uploadId);
        file.setFolderId(folderId);
        file.setStorageConfigId(config.getId());
        file.setProviderType(providerType);
        file.setProviderLocatorJson(buildObjectLocator(providerType, config, objectKey, publicId));
        file.setOriginalName(sanitizeName(ticket.path("fileName").asText("")));
        file.setContentType(normalizeMime(ticket.path("mime").asText("application/octet-stream")));
        file.setSizeBytes(sizeBytes);
        file.setContentVersion(1L);
        file.setVisibility(folder.getVisibility());
        file.setStatus(StorageFileEntity.STATUS_AVAILABLE);
        file.setUploaderType("BROWSER");
        file.setUploaderId(ticketUploader);
        fileMapper.insert(file);
        auditService.record("FILE_UPLOAD", "BROWSER", uploadId, "FILE", file.getPublicId(),
                file.getOriginalName() + " " + sizeBytes + "B provider=" + providerType, "OK");
        log.info("[Storage] 直传登记完成: publicId={}, provider={}, folderId={}, size={}",
                file.getPublicId(), providerType, folderId, sizeBytes);
        return file;
    }

    // ==================== 下载 ====================

    public StorageDtos.DownloadUrlView issueDownloadUrl(String publicId, String userId) {
        StorageFileEntity file = getByPublicId(publicId);
        if (StorageFileEntity.STATUS_DELETED.equals(file.getStatus())) {
            throw new BusinessException("STORAGE021");
        }
        if (!StorageFileEntity.STATUS_AVAILABLE.equals(file.getStatus())) {
            throw new BusinessException("STORAGE016");
        }
        // 上传者本人或持文件夹 READ 权限
        boolean uploader = userId != null && userId.equals(file.getUploaderId());
        if (!uploader) {
            folderService.requirePermission(userId, file.getFolderId(), "READ");
        }
        StorageConfigEntity config = configMapper.selectById(file.getStorageConfigId());
        if (config == null || !StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
            throw new BusinessException("STORAGE002");
        }
        String providerType = config.getProviderType() == null
                ? StorageConfigEntity.PROVIDER_TELEGRAM : config.getProviderType();
        StorageDtos.DownloadUrlView view;
        if (ProviderSupport.isS3Family(providerType)) {
            S3ObjectService.PresignedUrl presigned = s3ObjectService.presignGet(
                    config, locatorKey(file), properties.getDownloadUrlTtlSeconds());
            view = new StorageDtos.DownloadUrlView(presigned.url(), presigned.expiresAtEpochSeconds());
        } else if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
            CosApiService.PresignedUrl presigned = cosApiService.presignGet(
                    config, locatorKey(file), properties.getDownloadUrlTtlSeconds());
            view = new StorageDtos.DownloadUrlView(presigned.url(), presigned.expiresAtEpochSeconds());
        } else if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
            OssApiService.PresignedUrl presigned = ossApiService.presignGet(
                    config, locatorKey(file), properties.getDownloadUrlTtlSeconds());
            view = new StorageDtos.DownloadUrlView(presigned.url(), presigned.expiresAtEpochSeconds());
        } else if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
            view = upyunDownloadUrl(config, locatorKey(file));
        } else {
            StorageUrlSigner.SignedUrl signed = urlSigner.issue(config, file);
            view = new StorageDtos.DownloadUrlView(signed.url(), signed.expiresAtEpochSeconds());
        }
        auditService.record("URL_ISSUE", "USER", userId, "FILE", publicId,
                "visibility=" + file.getVisibility(), "OK");
        return view;
    }

    /**
     * 永久公开链接（不携带过期签名）：
     * TELEGRAM → Worker /p/{publicId}/{contentVersion}（仅 PUBLIC 可访问，边缘长缓存）；
     * 对象存储系 → provider_options.publicBaseUrl + 对象键。
     * 仅 PUBLIC 可见文件可签发；私有点击会得到明确报错。
     */
    public StorageDtos.PermanentUrlView issuePermanentUrl(String publicId, String userId) {
        StorageFileEntity file = getByPublicId(publicId);
        if (StorageFileEntity.STATUS_DELETED.equals(file.getStatus())) {
            throw new BusinessException("STORAGE021");
        }
        if (!StorageFileEntity.STATUS_AVAILABLE.equals(file.getStatus())) {
            throw new BusinessException("STORAGE016");
        }
        boolean uploader = userId != null && userId.equals(file.getUploaderId());
        if (!uploader) {
            folderService.requirePermission(userId, file.getFolderId(), "READ");
        }
        if (!StorageFileEntity.VISIBILITY_PUBLIC.equals(file.getVisibility())) {
            throw new BusinessException("STORAGE019", "永久链接仅对公开文件开放，请先将文件设为公开");
        }
        StorageConfigEntity config = configMapper.selectById(file.getStorageConfigId());
        if (config == null || !StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
            throw new BusinessException("STORAGE002");
        }
        String url;
        String providerType = config.getProviderType() == null
                ? StorageConfigEntity.PROVIDER_TELEGRAM : config.getProviderType();
        if (StorageConfigEntity.PROVIDER_TELEGRAM.equals(providerType)) {
            if (config.getWorkerBaseUrl() == null || config.getWorkerBaseUrl().isBlank()) {
                throw new BusinessException("STORAGE019", "该配置未填写 Worker 地址");
            }
            String base = config.getWorkerBaseUrl().trim();
            url = stripTrailingSlash(base) + "/p/" + file.getPublicId() + "/" + file.getContentVersion();
        } else if (ProviderSupport.isPermanentUrlFamily(providerType)) {
            url = publicObjectUrl(providerType, config, locatorKey(file));
        } else {
            throw new BusinessException("STORAGE019", providerType);
        }
        auditService.record("URL_PERMANENT_ISSUE", "USER", userId, "FILE", publicId, null, "OK");
        return new StorageDtos.PermanentUrlView(url);
    }

    // ==================== 可见性与删除 ====================

    @Transactional(rollbackFor = Exception.class)
    public StorageFileEntity changeVisibility(String publicId, String userId, String visibility) {
        StorageFileEntity file = getByPublicId(publicId);
        boolean uploader = userId != null && userId.equals(file.getUploaderId());
        if (!uploader) {
            folderService.requirePermission(userId, file.getFolderId(), "UPDATE");
        }
        if (!StorageFileEntity.VISIBILITY_PRIVATE.equals(visibility)
                && !StorageFileEntity.VISIBILITY_PUBLIC.equals(visibility)) {
            throw new BusinessException("COMMON002", "非法的可见性值");
        }
        if (visibility.equals(file.getVisibility())) {
            return file;
        }
        file.setVisibility(visibility);
        // 内容版本递增：Worker 缓存键随版本变化，旧缓存自然过期（撤销窗口 = URL TTL）
        file.setContentVersion(file.getContentVersion() + 1);
        file.setUpdateTime(LocalDateTime.now());
        fileMapper.updateById(file);
        auditService.record("FILE_VISIBILITY", "USER", userId, "FILE", publicId, visibility, "OK");
        return file;
    }

    /**
     * 删除：对象存储系（R2/S3/七牛/COS/OSS/UPYUN）由本服务直接删除对象（同步，失败即回滚并报错）；
     * TELEGRAM 走任务表由 Worker 拉取执行 deleteMessage 后确认。
     */
    @Transactional(rollbackFor = Exception.class)
    public void requestDelete(String publicId, String userId) {
        StorageFileEntity file = getByPublicId(publicId);
        if (StorageFileEntity.STATUS_DELETED.equals(file.getStatus())) {
            throw new BusinessException("STORAGE021");
        }
        if (StorageFileEntity.STATUS_DELETING.equals(file.getStatus())) {
            return;
        }
        boolean uploader = userId != null && userId.equals(file.getUploaderId());
        if (!uploader) {
            folderService.requirePermission(userId, file.getFolderId(), "DELETE");
        }
        String providerType = providerTypeOf(file);
        if (ProviderSupport.isServerManagedFamily(providerType)) {
            StorageConfigEntity config = configMapper.selectById(file.getStorageConfigId());
            if (config == null || !StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
                throw new BusinessException("STORAGE002");
            }
            deleteObjectByProvider(providerType, config, locatorKey(file));
            file.setStatus(StorageFileEntity.STATUS_DELETED);
            file.setDeletedTime(LocalDateTime.now());
            file.setUpdateTime(LocalDateTime.now());
            fileMapper.updateById(file);
            auditService.record("FILE_DELETE", "USER", userId, "FILE", publicId,
                    "provider=" + providerType, "OK");
            return;
        }
        file.setStatus(StorageFileEntity.STATUS_DELETING);
        file.setUpdateTime(LocalDateTime.now());
        fileMapper.updateById(file);

        StorageTaskEntity task = new StorageTaskEntity();
        task.setTaskType(StorageTaskEntity.TYPE_DELETE_REMOTE);
        task.setFileId(file.getId());
        task.setPayloadJson(file.getProviderLocatorJson() == null ? "{}" : file.getProviderLocatorJson());
        task.setRetryCount(0);
        task.setStatus(StorageTaskEntity.STATUS_PENDING);
        taskMapper.insert(task);
        auditService.record("FILE_DELETE", "USER", userId, "FILE", publicId, "task=" + task.getId(), "OK");
    }

    // ==================== Worker 删除任务协同 ====================

    /** 拉取待执行的远端删除任务并标记 RUNNING（Worker 定时拉取） */
    @Transactional(rollbackFor = Exception.class)
    public List<StorageDtos.WorkerTaskView> pullDeleteTasks(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<StorageTaskEntity> tasks = taskMapper.selectList(new LambdaQueryWrapper<StorageTaskEntity>()
                .eq(StorageTaskEntity::getTaskType, StorageTaskEntity.TYPE_DELETE_REMOTE)
                .eq(StorageTaskEntity::getStatus, StorageTaskEntity.STATUS_PENDING)
                .and(w -> w.isNull(StorageTaskEntity::getNextRetryTime)
                        .or().le(StorageTaskEntity::getNextRetryTime, now))
                .last("LIMIT " + Math.max(1, Math.min(limit, 50))));
        return tasks.stream().map(task -> {
            task.setStatus(StorageTaskEntity.STATUS_RUNNING);
            task.setUpdateTime(now);
            taskMapper.updateById(task);
            return toTaskView(task);
        }).toList();
    }

    /** Worker 删除确认：成功归档；失败按指数退避重试，超过上限转 DEAD */
    @Transactional(rollbackFor = Exception.class)
    public void ackDeleteTask(long taskId, boolean success, String errorMessage) {
        StorageTaskEntity task = taskMapper.selectById(taskId);
        if (task == null || StorageTaskEntity.STATUS_SUCCESS.equals(task.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        task.setUpdateTime(now);
        if (success) {
            task.setStatus(StorageTaskEntity.STATUS_SUCCESS);
            taskMapper.updateById(task);
            if (task.getFileId() != null) {
                StorageFileEntity file = fileMapper.selectById(task.getFileId());
                if (file != null) {
                    file.setStatus(StorageFileEntity.STATUS_DELETED);
                    file.setDeletedTime(now);
                    file.setUpdateTime(now);
                    fileMapper.updateById(file);
                }
            }
            auditService.record("FILE_DELETE_ACK", "WORKER", String.valueOf(taskId), "FILE",
                    task.getFileId() == null ? null : String.valueOf(task.getFileId()), null, "OK");
            return;
        }
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        task.setRetryCount(retry);
        task.setErrorMessage(errorMessage == null ? "unknown" : errorMessage.substring(0, Math.min(errorMessage.length(), 500)));
        if (retry >= MAX_DELETE_RETRY) {
            task.setStatus(StorageTaskEntity.STATUS_DEAD);
            taskMapper.updateById(task);
            if (task.getFileId() != null) {
                StorageFileEntity file = fileMapper.selectById(task.getFileId());
                if (file != null) {
                    // 远端消息可能仍存在，保留 locator 供管理员人工处置
                    file.setStatus(StorageFileEntity.STATUS_DELETE_FAILED);
                    file.setUpdateTime(now);
                    fileMapper.updateById(file);
                }
            }
            auditService.record("FILE_DELETE_DEAD", "WORKER", String.valueOf(taskId), "FILE", null,
                    task.getErrorMessage(), "ERROR");
            return;
        }
        // 指数退避：2^n 分钟
        task.setStatus(StorageTaskEntity.STATUS_PENDING);
        task.setNextRetryTime(now.plusMinutes((long) Math.pow(2, retry)));
        taskMapper.updateById(task);
    }

    // ==================== 内部工具 ====================

    private String providerTypeOf(StorageFileEntity file) {
        return file.getProviderType() == null ? StorageConfigEntity.PROVIDER_TELEGRAM : file.getProviderType();
    }

    /** 按 Provider HEAD 对象（回执登记校验用）：返回 Content-Length / x-upyun-file-size，缺失 -1 */
    private long headObjectByProvider(String providerType, StorageConfigEntity config, String key) {
        if (ProviderSupport.isS3Family(providerType)) {
            return s3ObjectService.headObject(config, key);
        }
        if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
            return cosApiService.headObject(config, key);
        }
        if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
            return ossApiService.headObject(config, key);
        }
        if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
            return upyunApiService.headObject(config, key);
        }
        return -1;
    }

    /** 按 Provider DELETE 对象 */
    private void deleteObjectByProvider(String providerType, StorageConfigEntity config, String key) {
        if (ProviderSupport.isS3Family(providerType)) {
            s3ObjectService.deleteObject(config, key);
        } else if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
            cosApiService.deleteObject(config, key);
        } else if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
            ossApiService.deleteObject(config, key);
        } else if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
            upyunApiService.deleteObject(config, key);
        }
    }

    /** 又拍云下载 URL（CDN token 防盗链；tokenKey 未配置时为裸公开 URL） */
    private StorageDtos.DownloadUrlView upyunDownloadUrl(StorageConfigEntity config, String key) {
        UpyunApiService.DownloadUrl url = upyunApiService.downloadUrl(
                config, key, properties.getDownloadUrlTtlSeconds());
        return new StorageDtos.DownloadUrlView(url.url(), url.expiresAtEpochSeconds());
    }

    /** 对象存储系公开 URL：publicBaseUrl + 编码后的对象键（永久链接用） */
    private String publicObjectUrl(String providerType, StorageConfigEntity config, String key) {
        String publicBaseUrl = null;
        try {
            if (ProviderSupport.isS3Family(providerType)) {
                publicBaseUrl = s3ObjectService.optionsOf(config).publicBaseUrl();
            } else if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
                publicBaseUrl = cosApiService.optionsOf(config).publicBaseUrl();
            } else if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
                publicBaseUrl = ossApiService.optionsOf(config).publicBaseUrl();
            } else if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
                publicBaseUrl = upyunApiService.optionsOf(config).publicBaseUrl();
            }
        } catch (Exception ignored) {
            // options 解析失败走统一报错
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new BusinessException("STORAGE019", "该配置未设置公开访问域名 publicBaseUrl");
        }
        return stripTrailingSlash(publicBaseUrl) + "/" + S3ObjectService.encodeKeyPath(key);
    }

    /** 从 locator JSON 取对象键（R2/S3），缺失视为记录损坏 */
    private String locatorKey(StorageFileEntity file) {
        try {
            JsonNode locator = objectMapper.readTree(file.getProviderLocatorJson());
            String key = locator.path("key").asText(null);
            if (key == null || key.isBlank()) {
                throw new BusinessException("STORAGE016");
            }
            return key;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("STORAGE016");
        }
    }

    /** 对象存储系 locator：记录桶/端点（TELEGRAM 之外的家族通用） */
    private String buildObjectLocator(String providerType, StorageConfigEntity config,
                                      String objectKey, String publicId) {
        ObjectNode node = objectMapper.createObjectNode();
        try {
            if (ProviderSupport.isS3Family(providerType)) {
                S3ObjectService.S3Options options = s3ObjectService.optionsOf(config);
                node.put("bucket", options.bucket());
                node.put("endpoint", options.endpoint());
            } else if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
                CosApiService.CosOptions options = cosApiService.optionsOf(config);
                node.put("bucket", options.bucket());
                node.put("region", options.region());
            } else if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
                OssApiService.OssOptions options = ossApiService.optionsOf(config);
                node.put("bucket", options.bucket());
                node.put("endpoint", options.endpoint());
            } else if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
                UpyunApiService.UpyunOptions options = upyunApiService.optionsOf(config);
                node.put("bucket", options.bucket());
                node.put("endpoint", options.endpoint());
            }
        } catch (Exception e) {
            node.put("bucket", "unknown");
        }
        node.put("key", objectKey);
        node.put("publicId", publicId);
        return node.toString();
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private StorageDtos.WorkerTaskView toTaskView(StorageTaskEntity task) {
        try {
            JsonNode locator = objectMapper.readTree(task.getPayloadJson());
            return new StorageDtos.WorkerTaskView(task.getId(),
                    locator.path("chatId").asText(null),
                    locator.path("messageId").asText(null),
                    locator.path("publicId").asText(null));
        } catch (Exception e) {
            log.warn("[Storage] 删除任务 payload 解析失败: taskId={}", task.getId());
            return new StorageDtos.WorkerTaskView(task.getId(), null, null, null);
        }
    }

    private String buildLocator(String chatId, StorageDtos.WorkerCallbackReq req, String publicId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("chatId", chatId);
        node.put("messageId", req.messageId());
        node.put("fileId", req.telegramFileId());
        node.put("fileUniqueId", req.fileUniqueId());
        node.put("publicId", publicId);
        return node.toString();
    }

    private String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        return cleaned.length() > 255 ? cleaned.substring(cleaned.length() - 255) : cleaned;
    }

    private String normalizeMime(String mime) {
        String m = mime == null ? "application/octet-stream" : mime.trim().toLowerCase();
        int semicolon = m.indexOf(';');
        return semicolon > 0 ? m.substring(0, semicolon).trim() : m;
    }
}
