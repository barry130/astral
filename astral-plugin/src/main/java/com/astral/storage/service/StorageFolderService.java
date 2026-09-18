package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.entity.StorageFolderPermissionEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageFolderMapper;
import com.astral.storage.mapper.StorageFolderPermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 存储文件夹与授权服务
 * <p>文件夹是组织与授权边界；权限集合保存于 sys_storage_folder_permission，
 * Scope 别名（storage:file:upload 等）在校验时映射为 READ/UPLOAD/UPDATE/DELETE/MANAGE。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageFolderService {

    private static final String TABLE = "sys_storage_folder";
    private static final String PERM_TABLE = "sys_storage_folder_permission";

    private final StorageFolderMapper folderMapper;
    private final StorageFolderPermissionMapper permMapper;
    private final StorageConfigMapper configMapper;
    private final StorageFileMapper fileMapper;
    private final StorageAuditService auditService;

    public List<StorageFolderEntity> listAll() {
        return folderMapper.selectList(new LambdaQueryWrapper<StorageFolderEntity>()
                .orderByAsc(StorageFolderEntity::getFolderPath));
    }

    /**
     * 管理员视角：全部文件夹均视为拥有全部权限（与后台「文件存储」管理页的可见范围一致）。
     */
    public List<StorageDtos.FolderMineView> listAllAsMine() {
        return listAll().stream()
                .map(f -> new StorageDtos.FolderMineView(f.getId(), f.getFolderName(), f.getFolderPath(),
                        f.getVisibility(), f.getStatus(), f.getStorageConfigId(), "ALL"))
                .collect(Collectors.toList());
    }

    /**
     * 用户侧「我的文件夹」：本人所有（ownerType=USER）+ 授权给我的（存在未撤销、未过期的 USER 授权行）。
     * myPermissions 为 ALL（所有者）或授权行的规范化权限集合，供前端控制上传/删除等入口显隐。
     */
    public List<StorageDtos.FolderMineView> listForUser(String userId) {
        if (userId == null) {
            return List.of();
        }
        Map<Long, String> permsByFolderId = new LinkedHashMap<>();
        // 1. 本人所有的文件夹：全部权限
        folderMapper.selectList(new LambdaQueryWrapper<StorageFolderEntity>()
                        .eq(StorageFolderEntity::getOwnerType, StorageFolderEntity.OWNER_USER)
                        .eq(StorageFolderEntity::getOwnerId, userId))
                .forEach(f -> permsByFolderId.put(f.getId(), "ALL"));
        // 2. 授权给我的文件夹：取授权行权限集合（未撤销、未过期）
        for (StorageFolderPermissionEntity row : permMapper.selectList(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                .eq(StorageFolderPermissionEntity::getSubjectType, StorageFolderPermissionEntity.SUBJECT_USER)
                .eq(StorageFolderPermissionEntity::getSubjectId, userId)
                .isNull(StorageFolderPermissionEntity::getRevokedTime))) {
            if (row.getExpiresTime() != null && row.getExpiresTime().isBefore(LocalDateTime.now())) {
                continue;
            }
            permsByFolderId.putIfAbsent(row.getFolderId(), normalizePermissions(row.getPermissions()));
        }
        if (permsByFolderId.isEmpty()) {
            return List.of();
        }
        Map<Long, StorageFolderEntity> folders = folderMapper.selectList(new LambdaQueryWrapper<StorageFolderEntity>()
                        .in(StorageFolderEntity::getId, permsByFolderId.keySet())
                        .orderByAsc(StorageFolderEntity::getFolderPath))
                .stream()
                .collect(Collectors.toMap(StorageFolderEntity::getId, f -> f));
        return permsByFolderId.entrySet().stream()
                .filter(e -> folders.containsKey(e.getKey()))
                .map(e -> {
                    StorageFolderEntity f = folders.get(e.getKey());
                    return new StorageDtos.FolderMineView(f.getId(), f.getFolderName(), f.getFolderPath(),
                            f.getVisibility(), f.getStatus(), f.getStorageConfigId(), e.getValue());
                })
                .collect(Collectors.toList());
    }

    public StorageFolderEntity getById(Long id) {
        StorageFolderEntity folder = folderMapper.selectById(id);
        if (folder == null) {
            throw new BusinessException("STORAGE025");
        }
        return folder;
    }

    @Transactional(rollbackFor = Exception.class)
    public StorageFolderEntity create(StorageDtos.FolderCreateReq req, String operator) {
        if (req.folderName() == null || req.folderName().isBlank() || req.folderName().length() > 128) {
            throw new BusinessException("COMMON002", "文件夹名称不能为空且不超过 128 字符");
        }
        if (req.folderName().contains("/") || req.folderName().contains("\\")) {
            throw new BusinessException("COMMON002", "文件夹名称不能包含路径分隔符");
        }
        String visibility = StorageFolderEntity.VISIBILITY_PRIVATE.equals(req.visibility())
                || StorageFolderEntity.VISIBILITY_PUBLIC.equals(req.visibility())
                ? req.visibility() : StorageFolderEntity.VISIBILITY_PRIVATE;

        // 解析使用的存储配置：显式指定优先，否则取默认配置
        Long configId = req.configId();
        if (configId == null) {
            configId = requireDefaultConfig().getId();
        } else {
            StorageConfigEntity config = configMapper.selectById(configId);
            if (config == null || !StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
                throw new BusinessException("STORAGE002");
            }
        }

        Long parentId = req.parentId();
        String parentPath = "";
        if (parentId != null) {
            StorageFolderEntity parent = getById(parentId);
            if (!StorageFolderEntity.STATUS_ENABLED.equals(parent.getStatus())) {
                throw new BusinessException("STORAGE025");
            }
            parentPath = parent.getFolderPath();
        }
        Long dup = folderMapper.selectCount(new LambdaQueryWrapper<StorageFolderEntity>()
                .eq(StorageFolderEntity::getParentId, parentId)
                .eq(StorageFolderEntity::getFolderName, req.folderName().trim()));
        if (dup != null && dup > 0) {
            throw new BusinessException("STORAGE028");
        }

        StorageFolderEntity folder = new StorageFolderEntity();
        folder.setParentId(parentId);
        folder.setFolderName(req.folderName().trim());
        folder.setFolderPath(parentPath.isEmpty() ? "/" + req.folderName().trim() : parentPath + "/" + req.folderName().trim());
        folder.setStorageConfigId(configId);
        folder.setOwnerType(StorageFolderEntity.OWNER_USER);
        folder.setOwnerId(operator);
        folder.setVisibility(visibility);
        folder.setStatus(StorageFolderEntity.STATUS_ENABLED);
        folder.setCreateBy(operator);
        folder.setUpdateBy(operator);
        folderMapper.insert(folder);
        auditService.record("FOLDER_CREATE", "USER", operator, "FOLDER", String.valueOf(folder.getId()),
                folder.getFolderPath(), "OK");
        return folder;
    }

    @Transactional(rollbackFor = Exception.class)
    public StorageFolderEntity update(Long id, StorageDtos.FolderUpdateReq req, String operator) {
        StorageFolderEntity folder = getById(id);
        if (req.folderName() != null && !req.folderName().isBlank()) {
            if (req.folderName().contains("/") || req.folderName().contains("\\")) {
                throw new BusinessException("COMMON002", "文件夹名称不能包含路径分隔符");
            }
            folder.setFolderName(req.folderName().trim());
            // 重建自身展示路径（子级路径不级联更新，MVP 限制：重命名深层文件夹后子级路径展示保持原值）
            String oldName = folder.getFolderPath().substring(folder.getFolderPath().lastIndexOf('/') + 1);
            folder.setFolderPath(folder.getFolderPath().substring(0,
                    folder.getFolderPath().length() - oldName.length()) + req.folderName().trim());
        }
        if (req.visibility() != null && !req.visibility().isBlank()) {
            if (!StorageFolderEntity.VISIBILITY_PRIVATE.equals(req.visibility())
                    && !StorageFolderEntity.VISIBILITY_PUBLIC.equals(req.visibility())) {
                throw new BusinessException("COMMON002", "非法的可见性值");
            }
            folder.setVisibility(req.visibility());
        }
        if (req.status() != null && !req.status().isBlank()) {
            if (!StorageFolderEntity.STATUS_ENABLED.equals(req.status())
                    && !StorageFolderEntity.STATUS_DISABLED.equals(req.status())) {
                throw new BusinessException("COMMON002", "非法的状态值");
            }
            folder.setStatus(req.status());
        }
        if (req.configId() != null) {
            StorageConfigEntity config = configMapper.selectById(req.configId());
            if (config == null) {
                throw new BusinessException("STORAGE002");
            }
            folder.setStorageConfigId(req.configId());
        }
        folder.setUpdateBy(operator);
        folderMapper.updateById(folder);
        auditService.record("FOLDER_UPDATE", "USER", operator, "FOLDER", String.valueOf(id), null, "OK");
        return folder;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String operator) {
        getById(id);
        Long children = folderMapper.selectCount(new LambdaQueryWrapper<StorageFolderEntity>()
                .eq(StorageFolderEntity::getParentId, id));
        if (children != null && children > 0) {
            throw new BusinessException("COMMON002", "存在子文件夹，无法删除");
        }
        Long files = fileMapper.selectCount(new LambdaQueryWrapper<StorageFileEntity>()
                .eq(StorageFileEntity::getFolderId, id)
                .ne(StorageFileEntity::getStatus, StorageFileEntity.STATUS_DELETED));
        if (files != null && files > 0) {
            throw new BusinessException("COMMON002", "文件夹内仍有 " + files + " 个文件，无法删除");
        }
        permMapper.delete(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                .eq(StorageFolderPermissionEntity::getFolderId, id));
        folderMapper.deleteById(id);
        auditService.record("FOLDER_DELETE", "USER", operator, "FOLDER", String.valueOf(id), null, "OK");
    }

    // ==================== 授权管理 ====================

    public List<StorageFolderPermissionEntity> listPermissions(Long folderId) {
        getById(folderId);
        return permMapper.selectList(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                .eq(StorageFolderPermissionEntity::getFolderId, folderId)
                .orderByAsc(StorageFolderPermissionEntity::getSubjectType)
                .orderByAsc(StorageFolderPermissionEntity::getSubjectId));
    }

    /** 全量替换文件夹授权（管理端每次提交完整列表） */
    @Transactional(rollbackFor = Exception.class)
    public void savePermissions(Long folderId, List<StorageDtos.FolderPermRow> rows, String operator) {
        getById(folderId);
        permMapper.delete(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                .eq(StorageFolderPermissionEntity::getFolderId, folderId));
        if (rows == null) {
            return;
        }
        for (StorageDtos.FolderPermRow row : rows) {
            String subjectType = row.subjectType() == null ? "" : row.subjectType().trim();
            String subjectId = row.subjectId() == null ? "" : row.subjectId().trim();
            String permissions = normalizePermissions(row.permissions());
            if (subjectId.isEmpty() || permissions.isEmpty()) {
                continue;
            }
            if (!StorageFolderPermissionEntity.SUBJECT_USER.equals(subjectType)
                    && !StorageFolderPermissionEntity.SUBJECT_PLUGIN.equals(subjectType)) {
                throw new BusinessException("COMMON002", "非法的授权主体类型: " + subjectType);
            }
            StorageFolderPermissionEntity perm = new StorageFolderPermissionEntity();
            perm.setFolderId(folderId);
            perm.setSubjectType(subjectType);
            perm.setSubjectId(subjectId);
            perm.setPermissions(permissions);
            perm.setCreateBy(operator);
            permMapper.insert(perm);
        }
        auditService.record("FOLDER_GRANT", "USER", operator, "FOLDER", String.valueOf(folderId),
                rows == null ? "清空授权" : rows.size() + " 条授权", "OK");
    }

    // ==================== 权限判断 ====================

    /**
     * 判断用户对文件夹是否拥有指定权限。
     * 所有者拥有全部权限；否则查授权记录（未撤销、未过期、权限集合包含目标权限）。
     */
    public boolean hasPermission(String userId, StorageFolderEntity folder, String perm) {
        if (userId == null) {
            return false;
        }
        if (StorageFolderEntity.OWNER_USER.equals(folder.getOwnerType()) && userId.equals(folder.getOwnerId())) {
            return true;
        }
        StorageFolderPermissionEntity permRow = permMapper.selectOne(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                .eq(StorageFolderPermissionEntity::getFolderId, folder.getId())
                .eq(StorageFolderPermissionEntity::getSubjectType, StorageFolderPermissionEntity.SUBJECT_USER)
                .eq(StorageFolderPermissionEntity::getSubjectId, userId)
                .isNull(StorageFolderPermissionEntity::getRevokedTime)
                .last("LIMIT 1"));
        if (permRow == null) {
            return false;
        }
        if (permRow.getExpiresTime() != null && permRow.getExpiresTime().isBefore(LocalDateTime.now())) {
            return false;
        }
        return normalizedList(permRow.getPermissions()).contains(perm);
    }

    public void requirePermission(String userId, Long folderId, String perm) {
        StorageFolderEntity folder = getById(folderId);
        if (!hasPermission(userId, folder, perm)) {
            throw new BusinessException("STORAGE013");
        }
    }

    private String normalizePermissions(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String p : raw.split(",")) {
            String perm = p.trim().toUpperCase();
            if (perm.isEmpty()) {
                continue;
            }
            boolean known = StorageFolderPermissionEntity.PERM_READ.equals(perm)
                    || StorageFolderPermissionEntity.PERM_UPLOAD.equals(perm)
                    || StorageFolderPermissionEntity.PERM_UPDATE.equals(perm)
                    || StorageFolderPermissionEntity.PERM_DELETE.equals(perm)
                    || StorageFolderPermissionEntity.PERM_MANAGE.equals(perm);
            if (!known) {
                throw new BusinessException("COMMON002", "未知权限: " + perm);
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(perm);
        }
        return sb.toString();
    }

    private List<String> normalizedList(String raw) {
        return List.of(normalizePermissions(raw).split(","));
    }

    private StorageConfigEntity requireDefaultConfig() {
        StorageConfigEntity config = configMapper.selectOne(new LambdaQueryWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getIsDefault, 1)
                .eq(StorageConfigEntity::getStatus, StorageConfigEntity.STATUS_ENABLED)
                .last("LIMIT 1"));
        if (config == null) {
            throw new BusinessException("STORAGE004");
        }
        return config;
    }
}
