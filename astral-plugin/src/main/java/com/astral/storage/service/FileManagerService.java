package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 文件管理只调整逻辑目录与名称，保留 Provider 定位信息及既有链接。 */
@Service
@RequiredArgsConstructor
public class FileManagerService {
    private final StorageFileMapper fileMapper;
    private final StorageFileService fileService;
    private final StorageFolderService folderService;
    private final StorageAuditService auditService;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public Page<StorageFileEntity> page(String userId, boolean admin, long current, long size, StorageDtos.FileQuery query) {
        requireLogin(userId);
        var wrapper = new LambdaQueryWrapper<StorageFileEntity>()
                .ne(StorageFileEntity::getStatus, StorageFileEntity.STATUS_DELETED);
        if (query.folderId() != null) {
            if (!admin) folderService.requirePermission(userId, query.folderId(), "READ");
            wrapper.eq(StorageFileEntity::getFolderId, query.folderId());
        } else if (!admin) {
            List<Long> ids = folderService.listForUser(userId).stream()
                    .filter(f -> readable(f.myPermissions())).map(StorageDtos.FolderMineView::id).toList();
            if (ids.isEmpty()) return new Page<StorageFileEntity>(Math.max(1, current), Math.clamp(size, 1, 100), 0);
            wrapper.in(StorageFileEntity::getFolderId, ids);
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            if (query.keyword().length() > 255) throw new BusinessException("COMMON002", "搜索内容不能超过 255 字符");
            wrapper.like(StorageFileEntity::getOriginalName, query.keyword().trim());
        }
        if (query.visibility() != null && !query.visibility().isBlank()) {
            if (!Set.of("PRIVATE", "PUBLIC").contains(query.visibility())) throw new BusinessException("COMMON002", "非法的可见性值");
            wrapper.eq(StorageFileEntity::getVisibility, query.visibility());
        }
        String category = query.category();
        if (category != null && !category.isBlank()) {
            switch (category) {
                case "image", "audio", "video" -> wrapper.likeRight(StorageFileEntity::getContentType, category + "/");
                case "document" -> wrapper.and(w -> w.likeRight(StorageFileEntity::getContentType, "text/")
                        .or().eq(StorageFileEntity::getContentType, "application/pdf")
                        .or().like(StorageFileEntity::getContentType, "officedocument")
                        .or().like(StorageFileEntity::getContentType, "msword")
                        .or().like(StorageFileEntity::getContentType, "ms-excel")
                        .or().like(StorageFileEntity::getContentType, "ms-powerpoint")
                        .or().like(StorageFileEntity::getContentType, "opendocument"));
                case "archive" -> wrapper.and(w -> w.like(StorageFileEntity::getContentType, "zip")
                        .or().like(StorageFileEntity::getContentType, "compressed")
                        .or().like(StorageFileEntity::getContentType, "rar")
                        .or().eq(StorageFileEntity::getContentType, "application/x-tar"));
                default -> throw new BusinessException("COMMON002", "未知文件类型");
            }
        }
        String sort = query.sort() == null ? "newest" : query.sort();
        switch (sort) {
            case "name" -> wrapper.orderByAsc(StorageFileEntity::getOriginalName);
            case "size" -> wrapper.orderByDesc(StorageFileEntity::getSizeBytes);
            case "oldest" -> wrapper.orderByAsc(StorageFileEntity::getCreateTime);
            case "newest", "" -> wrapper.orderByDesc(StorageFileEntity::getCreateTime);
            default -> throw new BusinessException("COMMON002", "未知排序方式");
        }
        wrapper.orderByDesc(StorageFileEntity::getId);
        Page<StorageFileEntity> page = fileMapper.selectPage(new Page<>(Math.max(1, current), Math.clamp(size, 1, 100)), wrapper);
        page.getRecords().forEach(f -> f.setProviderLocatorJson(null));
        return page;
    }

    @Transactional(rollbackFor = Exception.class)
    public StorageFileEntity update(String publicId, StorageDtos.FileUpdateReq req, String userId, boolean admin) {
        requireLogin(userId);
        fileMapper.lockUploadKey("storage-file:" + publicId);
        StorageFileEntity file = fileService.getByPublicId(publicId);
        if (!StorageFileEntity.STATUS_AVAILABLE.equals(file.getStatus())) throw new BusinessException("STORAGE016");
        if (!admin) folderService.requirePermission(userId, file.getFolderId(), "UPDATE");
        String name = req.originalName();
        if (name != null && (name.isBlank() || name.trim().length() > 255 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl) || Set.of(".", "..").contains(name.trim()))) {
            throw new BusinessException("COMMON002", "文件名须为 1–255 字符，不能含路径分隔符、控制字符或仅为点号");
        }
        if (name == null && req.folderId() == null) throw new BusinessException("COMMON002", "未指定修改内容");
        if (req.folderId() != null && !Objects.equals(req.folderId(), file.getFolderId())) {
            // 与文件夹删除串行，避免移动到已删除的目标目录。
            fileMapper.lockUploadKey("storage-folder-tree");
            StorageFolderEntity target = folderService.getById(req.folderId());
            if (!admin) folderService.requirePermission(userId, target.getId(), "UPLOAD");
            if (!StorageFolderEntity.STATUS_ENABLED.equals(target.getStatus())) throw new BusinessException("STORAGE025");
            if (!Objects.equals(target.getStorageConfigId(), file.getStorageConfigId())) {
                throw new BusinessException("COMMON002", "仅支持移动到相同存储配置的文件夹，跨存储请重新上传");
            }
            file.setFolderId(target.getId());
        }
        if (name != null) file.setOriginalName(name.trim());
        // 白名单更新，避免并发可见性/删除操作被旧实体覆盖；不改对象键、版本、签名路径。
        int changed = fileMapper.update(null, new LambdaUpdateWrapper<StorageFileEntity>()
                .eq(StorageFileEntity::getId, file.getId()).eq(StorageFileEntity::getStatus, StorageFileEntity.STATUS_AVAILABLE)
                .set(StorageFileEntity::getOriginalName, file.getOriginalName())
                .set(StorageFileEntity::getFolderId, file.getFolderId()).set(StorageFileEntity::getUpdateTime, LocalDateTime.now()));
        if (changed != 1) throw new BusinessException("STORAGE016");
        auditService.record("FILE_UPDATE", "USER", userId, "FILE", publicId,
                "name=" + file.getOriginalName() + ",folderId=" + file.getFolderId(), "OK");
        file.setProviderLocatorJson(null);
        return file;
    }

    @Transactional(rollbackFor = Exception.class)
    public StorageFolderEntity createFolder(StorageDtos.UserFolderReq req, String userId, boolean admin) {
        requireLogin(userId);
        StorageFolderEntity parent = req.parentId() == null ? null : folderService.getById(req.parentId());
        if (parent != null && !admin) folderService.requirePermission(userId, parent.getId(), "MANAGE");
        java.util.Map<String, Object> policy = null;
        if (parent != null && parent.getUploadPolicy() != null) {
            policy = objectMapper.readValue(parent.getUploadPolicy(), new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        }
        return folderService.create(new StorageDtos.FolderCreateReq(req.parentId(), req.folderName(),
                parent == null ? null : parent.getStorageConfigId(), parent == null ? "PRIVATE" : parent.getVisibility(), policy), userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public StorageFolderEntity renameFolder(Long id, StorageDtos.UserFolderReq req, String userId, boolean admin) {
        requireLogin(userId);
        if (!admin) folderService.requirePermission(userId, id, "MANAGE");
        // 目录的可见性、存储与授权策略仍由后台维护，用户端仅允许重命名。
        return folderService.update(id, new StorageDtos.FolderUpdateReq(req.folderName(), null, null, null, null), userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFolder(Long id, String userId, boolean admin) {
        requireLogin(userId);
        if (!admin) folderService.requirePermission(userId, id, "MANAGE");
        folderService.delete(id, userId);
    }

    private boolean readable(String permissions) {
        return "ALL".equals(permissions) || List.of(permissions.split(",")).contains("READ");
    }

    private void requireLogin(String userId) {
        if (userId == null || userId.isBlank()) throw new BusinessException("STORAGE013");
    }
}
