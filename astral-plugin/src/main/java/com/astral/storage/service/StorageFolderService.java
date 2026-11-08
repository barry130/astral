package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.entity.StorageFolderPermissionEntity;
import com.astral.storage.entity.StoragePermissionConfigEntity;
import com.astral.storage.entity.StorageUploadOperationEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageFolderConfigMapper;
import com.astral.storage.mapper.StorageFolderMapper;
import com.astral.storage.mapper.StorageFolderPermissionMapper;
import com.astral.storage.mapper.StoragePermissionConfigMapper;
import com.astral.storage.mapper.StorageUploadOperationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 存储文件夹与授权服务
 * <p>文件夹是组织与授权边界；权限集合保存于 sys_storage_folder_permission，
 * Scope 别名（storage:file:upload 等）在校验时映射为 READ/UPLOAD/UPDATE/DELETE/MANAGE。</p>
 *
 * <p>存储绑定：文件夹直接绑定多个存储配置（sys_storage_folder_config，多选 + 优先级，
 * 调度策略见 sys_storage_folder.storage_strategy）；授权行可限定使用其中的子集
 * （sys_storage_permission_config）。<b>绑定为空 = 该文件夹禁止上传，无默认回落。</b></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageFolderService {

    private static final String TABLE = "sys_storage_folder";
    private static final String PERM_TABLE = "sys_storage_folder_permission";

    /** 单文件夹/单授权的绑定数量上限 */
    private static final int MAX_BINDINGS = 16;
    /** 绑定优先级默认值与上下限 */
    private static final int DEFAULT_PRIORITY = 100;

    private final StorageFolderMapper folderMapper;
    private final StorageFolderConfigMapper folderConfigMapper;
    private final StorageFolderPermissionMapper permMapper;
    private final StoragePermissionConfigMapper permissionConfigMapper;
    private final StorageConfigMapper configMapper;
    private final StorageFileMapper fileMapper;
    private final StorageAuditService auditService;
    private final UploadPolicyService policyService;
    private final StorageTargetResolver targetResolver;
    private final StorageUploadOperationMapper operationMapper;
    private final LegacyUploadReceiptService receiptService;

    /** 目录安全锁：授权、绑定与可见性变更都走这把锁（§8.4 锁顺序：树锁 → 目录安全锁 → ...） */
    static String securityLockKey(Long folderId) {
        return "storage-folder-security:" + folderId;
    }

    /** 绑定落库行（优先级已归一） */
    private record Binding(Long storageConfigId, int priority) {}

    /** 只读解析结果（展示用，失败即 null） */
    private record ResolvedTarget(Long configId, String providerType, String targetName) {}

    public List<StorageFolderEntity> listAll() {
        return folderMapper.selectList(
                new LambdaQueryWrapper<StorageFolderEntity>().orderByAsc(StorageFolderEntity::getFolderPath));
    }

    /** 管理端目录列表视图（含绑定集合与只读解析结果；解析失败字段为 null） */
    public List<StorageDtos.FolderView> listAllViews() {
        return listAll().stream().map(this::toView).collect(Collectors.toList());
    }

    /**
     * 目录视图装配：基础字段 + 绑定集合 + 只读解析结果。
     * <p>解析是展示用途，任何解析异常都在此吞掉并回落 null，避免一个坏掉的绑定把整个目录列表打挂。</p>
     */
    public StorageDtos.FolderView toView(StorageFolderEntity folder) {
        ResolvedTarget resolved = resolvedTarget(folder);
        List<StorageDtos.BindingView> configs = bindingViews(folder.getId());
        return new StorageDtos.FolderView(
                folder.getId(),
                folder.getParentId(),
                folder.getFolderName(),
                folder.getFolderPath(),
                folder.getVisibility(),
                folder.getStatus(),
                folder.getOwnerType(),
                folder.getOwnerId(),
                folder.getStorageStrategy(),
                folder.getTargetVersion(),
                folder.getUploadPolicy(),
                configs,
                resolved.configId(),
                resolved.providerType(),
                resolved.targetName(),
                folder.getCreateBy(),
                folder.getCreateTime(),
                folder.getUpdateBy(),
                folder.getUpdateTime());
    }

    /** 绑定视图（含配置名/类型/状态，停用的配置也原样展示，便于管理端发现问题） */
    private List<StorageDtos.BindingView> bindingViews(Long folderId) {
        List<StorageFolderConfigEntity> bindings = orderedBindings(folderId);
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<Long, StorageConfigEntity> configs = new LinkedHashMap<>();
        for (StorageConfigEntity config : configMapper.selectByIds(bindings.stream()
                .map(StorageFolderConfigEntity::getStorageConfigId)
                .toList())) {
            configs.put(config.getId(), config);
        }
        List<StorageDtos.BindingView> views = new ArrayList<>(bindings.size());
        for (StorageFolderConfigEntity binding : bindings) {
            StorageConfigEntity config = configs.get(binding.getStorageConfigId());
            views.add(new StorageDtos.BindingView(
                    binding.getStorageConfigId(),
                    config == null ? null : config.getName(),
                    config == null ? null : config.getProviderType(),
                    binding.getPriority(),
                    config == null ? null : config.getStatus()));
        }
        return views;
    }

    /**
     * 管理员视角：全部文件夹均视为拥有全部权限（与后台「文件存储」管理页的可见范围一致）。
     */
    public List<StorageDtos.FolderMineView> listAllAsMine() {
        return listAll().stream().map(f -> mineViewOf(f, "ALL")).collect(Collectors.toList());
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
        folderMapper
                .selectList(new LambdaQueryWrapper<StorageFolderEntity>()
                        .eq(StorageFolderEntity::getOwnerType, StorageFolderEntity.OWNER_USER)
                        .eq(StorageFolderEntity::getOwnerId, userId))
                .forEach(f -> permsByFolderId.put(f.getId(), "ALL"));
        // 2. 授权给我的文件夹：取授权行权限集合（未撤销、未过期）
        for (StorageFolderPermissionEntity row :
                permMapper.selectList(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                        .eq(StorageFolderPermissionEntity::getSubjectType, StorageFolderPermissionEntity.SUBJECT_USER)
                        .eq(StorageFolderPermissionEntity::getSubjectId, userId)
                        .isNull(StorageFolderPermissionEntity::getRevokedTime))) {
            if (row.getExpiresTime() != null && row.getExpiresTime().isBefore(LocalDateTime.now())) {
                continue;
            }
            // 历史数据/重复提交可能同一用户在同一目录存在多行授权；权限必须取并集，
            // 只取首行（putIfAbsent）会让「另起一行补授 UPLOAD」在列表上显示为不可上传，
            // 且与实际 hasPermission 判断不一致。所有者已标 ALL，保持最高优先级。
            String current = permsByFolderId.get(row.getFolderId());
            String merged = normalizePermissions(row.getPermissions());
            if (current == null) {
                permsByFolderId.put(row.getFolderId(), merged);
            } else if (!"ALL".equals(current)) {
                permsByFolderId.put(row.getFolderId(), mergePermissions(current, merged));
            }
        }
        if (permsByFolderId.isEmpty()) {
            return List.of();
        }
        Map<Long, StorageFolderEntity> folders = folderMapper
                .selectList(new LambdaQueryWrapper<StorageFolderEntity>()
                        .in(StorageFolderEntity::getId, permsByFolderId.keySet())
                        .orderByAsc(StorageFolderEntity::getFolderPath))
                .stream()
                .collect(Collectors.toMap(StorageFolderEntity::getId, f -> f));
        return permsByFolderId.entrySet().stream()
                .filter(e -> folders.containsKey(e.getKey()))
                .map(e -> mineViewOf(folders.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
    }

    /** 「我的文件夹」视图装配：基础字段 + 权限集合 + 绑定配置 id 列表 + 只读解析结果（复用 toView 的解析与容错） */
    private StorageDtos.FolderMineView mineViewOf(StorageFolderEntity folder, String myPermissions) {
        StorageDtos.FolderView view = toView(folder);
        List<Long> bindingConfigIds = orderedBindings(folder.getId()).stream()
                .map(StorageFolderConfigEntity::getStorageConfigId)
                .collect(Collectors.toList());
        return new StorageDtos.FolderMineView(
                view.id(),
                view.folderName(),
                view.folderPath(),
                view.visibility(),
                view.status(),
                myPermissions,
                view.parentId(),
                bindingConfigIds,
                view.resolvedConfigId(),
                view.resolvedProviderType(),
                view.resolvedTargetName());
    }

    public StorageFolderEntity getById(Long id) {
        StorageFolderEntity folder = folderMapper.selectById(id);
        if (folder == null) {
            throw new BusinessException("STORAGE025");
        }
        return folder;
    }

    /** 文件夹的绑定行（priority 升序），供子目录继承/展示使用 */
    public List<StorageFolderConfigEntity> orderedBindings(Long folderId) {
        return folderConfigMapper.selectList(new LambdaQueryWrapper<StorageFolderConfigEntity>()
                .eq(StorageFolderConfigEntity::getFolderId, folderId)
                .orderByAsc(StorageFolderConfigEntity::getPriority)
                .orderByAsc(StorageFolderConfigEntity::getId));
    }

    /** 平台默认启用配置 ID（用户根级目录创建时回落绑定；无默认返回 null → 目录无绑定，上传时报错） */
    public Long defaultConfigIdOrNull() {
        StorageConfigEntity config = configMapper.selectOne(new LambdaQueryWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getIsDefault, 1)
                .eq(StorageConfigEntity::getStatus, StorageConfigEntity.STATUS_ENABLED)
                .last("LIMIT 1"));
        return config == null ? null : config.getId();
    }

    // ==================== 文件夹 CRUD ====================

    @Transactional(rollbackFor = Exception.class)
    public StorageFolderEntity create(StorageDtos.FolderCreateReq req, String operator) {
        fileMapper.lockUploadKey("storage-folder-tree");
        validateFolderName(req.folderName());
        if (req.folderName() == null
                || req.folderName().isBlank()
                || req.folderName().length() > 128) {
            throw new BusinessException("COMMON002", "文件夹名称不能为空且不超过 128 字符");
        }
        if (req.folderName().contains("/") || req.folderName().contains("\\")) {
            throw new BusinessException("COMMON002", "文件夹名称不能包含路径分隔符");
        }
        String visibility = StorageFolderEntity.VISIBILITY_PRIVATE.equals(req.visibility())
                        || StorageFolderEntity.VISIBILITY_PUBLIC.equals(req.visibility())
                ? req.visibility()
                : StorageFolderEntity.VISIBILITY_PRIVATE;

        List<Binding> bindings = normalizeBindings(req.configs());
        String strategy = normalizeStrategy(req.storageStrategy());

        Long parentId = req.parentId();
        String parentPath = "";
        if (parentId != null) {
            StorageFolderEntity parent = getById(parentId);
            if (!StorageFolderEntity.STATUS_ENABLED.equals(parent.getStatus())) {
                throw new BusinessException("STORAGE025");
            }
            parentPath = parent.getFolderPath();
        }
        Long dup = folderMapper.selectCount(siblings(parentId)
                .eq(StorageFolderEntity::getFolderName, req.folderName().trim()));
        if (dup != null && dup > 0) {
            throw new BusinessException("STORAGE028");
        }

        StorageFolderEntity folder = new StorageFolderEntity();
        folder.setParentId(parentId);
        folder.setFolderName(req.folderName().trim());
        folder.setFolderPath(
                parentPath.isEmpty()
                        ? "/" + req.folderName().trim()
                        : parentPath + "/" + req.folderName().trim());
        validateFolderPath(folder.getFolderPath());
        folder.setStorageStrategy(strategy);
        folder.setTargetVersion(1L);
        folder.setOwnerType(StorageFolderEntity.OWNER_USER);
        folder.setOwnerId(operator);
        folder.setVisibility(visibility);
        folder.setStatus(StorageFolderEntity.STATUS_ENABLED);
        // 上传策略（UPDATE_DESIGN.md §3.3）：结构化对象归一化后落 JSON；null/空 = 不配置
        folder.setUploadPolicy(policyService.normalize(req.policy()));
        folder.setCreateBy(operator);
        folder.setUpdateBy(operator);
        folderMapper.insert(folder);
        insertBindings(folder.getId(), bindings);
        auditService.record(
                "FOLDER_CREATE",
                "USER",
                operator,
                "FOLDER",
                String.valueOf(folder.getId()),
                folder.getFolderPath(),
                "OK");
        return folder;
    }

    /**
     * 更新目录：策略/绑定任一显式携带即重设（合并后在锁内校验并落库）。
     */
    @Transactional(rollbackFor = Exception.class)
    public StorageFolderEntity update(Long id, StorageDtos.FolderUpdateReq req, String operator) {
        fileMapper.lockUploadKey("storage-folder-tree");
        // 锁顺序：树锁 → 目录安全锁（§8.4）；授权/绑定/可见性/策略变更都在这把锁内，
        // 上传侧 SENDING 复检读同一把锁，保证「读到的目录能力」与「写入的目标版本」原子一致。
        fileMapper.lockUploadKey(securityLockKey(id));
        StorageFolderEntity folder = getById(id);
        // 存量行策略列 NOT NULL，防御性兜底（测试构造实体可能为 null）
        if (folder.getStorageStrategy() == null) {
            folder.setStorageStrategy(StorageFolderEntity.STRATEGY_FAILOVER);
        }
        String signatureBefore = targetSignature(folder, orderedBindings(id));
        if (req.folderName() != null) {
            validateFolderName(req.folderName());
            Long duplicate = folderMapper.selectCount(siblings(folder.getParentId())
                    .ne(StorageFolderEntity::getId, id)
                    .eq(StorageFolderEntity::getFolderName, req.folderName().trim()));
            if (duplicate != null && duplicate > 0) {
                throw new BusinessException("STORAGE028");
            }
            String parentPath = folder.getParentId() == null
                    ? ""
                    : getById(folder.getParentId()).getFolderPath();
            folder.setFolderName(req.folderName().trim());
            folder.setFolderPath(parentPath + "/" + folder.getFolderName());
            validateFolderPath(folder.getFolderPath());
            syncDescendantPaths(folder, operator, new java.util.HashSet<>());
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
        // 绑定/策略：显式携带即重设（null = 保持不变）
        if (req.storageStrategy() != null && !req.storageStrategy().isBlank()) {
            folder.setStorageStrategy(normalizeStrategy(req.storageStrategy()));
        }
        List<Binding> newBindings = null;
        if (req.configs() != null) {
            newBindings = normalizeBindings(req.configs());
        }
        // 上传策略（UPDATE_DESIGN.md §3.3）：仅当请求显式携带 policy 字段时才改动（null = 保持不变）
        if (req.policy() != null) {
            folder.setUploadPolicy(policyService.normalize(req.policy()));
        }
        // 版本语义：任何影响目标解析的字段变化（策略/绑定/状态/可见性/uploadPolicy）
        // 都必须 targetVersion+1；只改名字/路径不涨版本。
        if (newBindings != null) {
            replaceBindings(id, newBindings);
        }
        if (!signatureBefore.equals(targetSignature(folder, orderedBindings(id)))) {
            folder.setTargetVersion(nextTargetVersion(folder.getTargetVersion()));
        }
        folder.setUpdateBy(operator);
        // 实体经 getById 加载且策略/版本列已显式兜底非空（见上），updateById 的 NOT_NULL
        // 策略会覆盖全部变化列；不要再叠加 LambdaUpdateWrapper 显式 set 同列——
        // MP 会把实体 SET 与 wrapper SET 拼接，同列两次赋值被 PostgreSQL 拒绝
        // （ERROR: multiple assignments to same column "storage_strategy"）。
        folderMapper.updateById(folder);
        auditService.record("FOLDER_UPDATE", "USER", operator, "FOLDER", String.valueOf(id), null, "OK");
        return folder;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String operator) {
        fileMapper.lockUploadKey("storage-folder-tree");
        // 与 update/savePermissions 使用同一把目录安全锁（锁顺序：树锁 → 安全锁），
        // 避免删除与授权全量替换交错产生「目录已删、授权行仍被重新插入」的孤儿授权。
        fileMapper.lockUploadKey(securityLockKey(id));
        getById(id);
        Long pending = operationMapper.selectCount(new LambdaQueryWrapper<StorageUploadOperationEntity>()
                .eq(StorageUploadOperationEntity::getFolderId, id)
                .and(w -> w.in(
                                StorageUploadOperationEntity::getState,
                                StorageUploadOperationEntity.STATE_RECEIVING,
                                StorageUploadOperationEntity.STATE_SENDING,
                                StorageUploadOperationEntity.STATE_REMOTE_SUCCEEDED,
                                StorageUploadOperationEntity.STATE_UNKNOWN_OUTCOME)
                        .or(created -> created.eq(
                                        StorageUploadOperationEntity::getState,
                                        StorageUploadOperationEntity.STATE_CREATED)
                                .gt(StorageUploadOperationEntity::getIntentExpiresAt, LocalDateTime.now()))));
        if ((pending != null && pending > 0) || receiptService.hasPending(id)) {
            throw new BusinessException("COMMON002", "文件夹仍有上传或待核对的远端结果，无法删除");
        }
        Long children = folderMapper.selectCount(
                new LambdaQueryWrapper<StorageFolderEntity>().eq(StorageFolderEntity::getParentId, id));
        if (children != null && children > 0) {
            throw new BusinessException("COMMON002", "存在子文件夹，无法删除");
        }
        Long files = fileMapper.selectCount(new LambdaQueryWrapper<StorageFileEntity>()
                .eq(StorageFileEntity::getFolderId, id)
                .ne(StorageFileEntity::getStatus, StorageFileEntity.STATUS_DELETED));
        if (files != null && files > 0) {
            throw new BusinessException("COMMON002", "文件夹内仍有 " + files + " 个文件，无法删除");
        }
        deletePermissionBindingsOfFolder(id);
        permMapper.delete(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                .eq(StorageFolderPermissionEntity::getFolderId, id));
        folderConfigMapper.delete(
                new LambdaQueryWrapper<StorageFolderConfigEntity>().eq(StorageFolderConfigEntity::getFolderId, id));
        folderMapper.deleteById(id);
        auditService.record("FOLDER_DELETE", "USER", operator, "FOLDER", String.valueOf(id), null, "OK");
    }

    // ==================== 授权管理 ====================

    /** 授权视图：授权行 + 该授权限定的配置绑定（子集，空 = 跟随文件夹绑定） */
    public List<StorageDtos.PermView> listPermissions(Long folderId) {
        getById(folderId);
        List<StorageFolderPermissionEntity> rows =
                permMapper.selectList(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                        .eq(StorageFolderPermissionEntity::getFolderId, folderId)
                        .orderByAsc(StorageFolderPermissionEntity::getSubjectType)
                        .orderByAsc(StorageFolderPermissionEntity::getSubjectId));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<StoragePermissionConfigEntity> grantBindings = permissionConfigMapper.selectList(new LambdaQueryWrapper<
                        StoragePermissionConfigEntity>()
                .in(
                        StoragePermissionConfigEntity::getPermissionId,
                        rows.stream().map(StorageFolderPermissionEntity::getId).toList())
                .orderByAsc(StoragePermissionConfigEntity::getPriority)
                .orderByAsc(StoragePermissionConfigEntity::getId));
        Map<Long, List<StoragePermissionConfigEntity>> byPerm = new LinkedHashMap<>();
        for (StoragePermissionConfigEntity binding : grantBindings) {
            byPerm.computeIfAbsent(binding.getPermissionId(), k -> new ArrayList<>())
                    .add(binding);
        }
        Map<Long, StorageConfigEntity> configs = new LinkedHashMap<>();
        java.util.Set<Long> configIds = new LinkedHashSet<>();
        for (StoragePermissionConfigEntity binding : grantBindings) {
            if (binding.getStorageConfigId() != null) {
                configIds.add(binding.getStorageConfigId());
            }
        }
        if (!configIds.isEmpty()) {
            for (StorageConfigEntity config : configMapper.selectByIds(configIds)) {
                configs.put(config.getId(), config);
            }
        }
        return rows.stream()
                .map(row -> {
                    List<StoragePermissionConfigEntity> bindings = byPerm.getOrDefault(row.getId(), List.of());
                    List<StorageDtos.BindingView> views = new ArrayList<>(bindings.size());
                    for (StoragePermissionConfigEntity binding : bindings) {
                        StorageConfigEntity config = configs.get(binding.getStorageConfigId());
                        views.add(new StorageDtos.BindingView(
                                binding.getStorageConfigId(),
                                config == null ? null : config.getName(),
                                config == null ? null : config.getProviderType(),
                                binding.getPriority(),
                                config == null ? null : config.getStatus()));
                    }
                    return new StorageDtos.PermView(
                            row.getId(),
                            row.getSubjectType(),
                            row.getSubjectId(),
                            row.getPermissions(),
                            row.getExpiresTime(),
                            views);
                })
                .collect(Collectors.toList());
    }

    /**
     * 全量替换文件夹授权（管理端每次提交完整列表）。
     * <p>每行可选携带 configs 限定该被授权人上传使用的配置，必须是文件夹绑定集合的子集；
     * 空/缺省 = 跟随文件夹绑定。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void savePermissions(Long folderId, List<StorageDtos.FolderPermRow> rows, String operator) {
        fileMapper.lockUploadKey(securityLockKey(folderId));
        StorageFolderEntity folder = getById(folderId);
        deletePermissionBindingsOfFolder(folderId);
        permMapper.delete(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                .eq(StorageFolderPermissionEntity::getFolderId, folderId));
        if (rows == null) {
            // 清空授权同样改变「谁能上传」，必须涨目标版本（否则上传侧可能沿用旧能力快照）
            bumpTargetVersion(folderId, folder, operator);
            return;
        }
        SetOfLong folderConfigIds = new SetOfLong(orderedBindings(folderId).stream()
                .map(StorageFolderConfigEntity::getStorageConfigId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        java.util.Set<String> seenSubjects = new java.util.HashSet<>();
        for (StorageDtos.FolderPermRow row : rows) {
            String subjectType =
                    row.subjectType() == null ? "" : row.subjectType().trim().toUpperCase(Locale.ROOT);
            String subjectId = row.subjectId() == null ? "" : row.subjectId().trim();
            String permissions = normalizePermissions(row.permissions());
            if (subjectId.isEmpty() || permissions.isEmpty()) {
                continue;
            }
            // 当前权限消费链只接受 USER：PLUGIN 行会被 hasPermission 静默忽略，
            // 若允许保存会造成“授权成功但永远不生效”的假象，故在写入边界显式拒绝。
            if (!StorageFolderPermissionEntity.SUBJECT_USER.equals(subjectType)) {
                throw new BusinessException("COMMON002", "暂不支持该授权主体类型: " + subjectType);
            }
            // 同一主体在一个目录只能有一行：多行会让权限与授权配置子集产生歧义
            // （历史上正是重复行导致首行覆盖/任意行判权）。重复提交直接拒绝，由管理端合并后重试。
            if (!seenSubjects.add(subjectType + ":" + subjectId)) {
                throw new BusinessException("COMMON002", "同一授权主体重复提交: " + subjectType + ":" + subjectId);
            }
            StorageFolderPermissionEntity perm = new StorageFolderPermissionEntity();
            perm.setFolderId(folderId);
            perm.setSubjectType(subjectType);
            perm.setSubjectId(subjectId);
            perm.setPermissions(permissions);
            perm.setCreateBy(operator);
            permMapper.insert(perm);
            List<Binding> grantBindings = normalizeBindings(row.configs());
            requireSubset(grantBindings, folderConfigIds);
            insertGrantBindings(perm.getId(), grantBindings);
        }
        auditService.record(
                "FOLDER_GRANT", "USER", operator, "FOLDER", String.valueOf(folderId), rows.size() + " 条授权", "OK");
        bumpTargetVersion(folderId, folder, operator);
    }

    /** 授权绑定必须是文件夹绑定的子集（STORAGE058） */
    private void requireSubset(List<Binding> grantBindings, SetOfLong folderConfigIds) {
        for (Binding binding : grantBindings) {
            if (!folderConfigIds.contains(binding.storageConfigId())) {
                throw new BusinessException(
                        "STORAGE058", "授权绑定的配置必须是文件夹绑定配置的子集: configId=" + binding.storageConfigId());
            }
        }
    }

    /** 删除文件夹全部授权行关联的配置绑定 */
    private void deletePermissionBindingsOfFolder(Long folderId) {
        List<StorageFolderPermissionEntity> rows =
                permMapper.selectList(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                        .eq(StorageFolderPermissionEntity::getFolderId, folderId));
        if (rows.isEmpty()) {
            return;
        }
        permissionConfigMapper.delete(new LambdaQueryWrapper<StoragePermissionConfigEntity>()
                .in(
                        StoragePermissionConfigEntity::getPermissionId,
                        rows.stream().map(StorageFolderPermissionEntity::getId).toList()));
    }

    // ==================== 绑定读写 ====================

    /** 归一化绑定列表：去重、优先级夹取；配置必须存在且启用（STORAGE002/COMMON002） */
    private List<Binding> normalizeBindings(List<StorageDtos.BindingReq> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        if (requests.size() > MAX_BINDINGS) {
            throw new BusinessException("COMMON002", "单个文件夹/授权最多绑定 " + MAX_BINDINGS + " 个配置");
        }
        Map<Long, Integer> byConfig = new LinkedHashMap<>();
        for (StorageDtos.BindingReq request : requests) {
            if (request == null || request.storageConfigId() == null) {
                throw new BusinessException("COMMON002", "绑定项缺少 storageConfigId");
            }
            Integer previous = byConfig.putIfAbsent(request.storageConfigId(), normalizePriority(request.priority()));
            if (previous != null) {
                throw new BusinessException("COMMON002", "配置重复绑定: " + request.storageConfigId());
            }
        }
        List<StorageConfigEntity> configs = configMapper.selectByIds(byConfig.keySet());
        if (configs.size() != byConfig.size()) {
            throw new BusinessException("STORAGE002", "存在无效的存储配置");
        }
        for (StorageConfigEntity config : configs) {
            if (!StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
                throw new BusinessException("STORAGE002", "存储配置已停用: " + config.getId());
            }
        }
        return byConfig.entrySet().stream()
                .map(e -> new Binding(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparingInt(Binding::priority).thenComparing(Binding::storageConfigId))
                .collect(Collectors.toList());
    }

    private static int normalizePriority(Integer priority) {
        return priority == null ? DEFAULT_PRIORITY : Math.clamp(priority, 0, 10000);
    }

    private String normalizeStrategy(String value) {
        if (value == null || value.isBlank()) {
            return StorageFolderEntity.STRATEGY_FAILOVER;
        }
        String strategy = value.trim().toUpperCase(Locale.ROOT);
        if (!StorageFolderEntity.STRATEGY_FAILOVER.equals(strategy)
                && !StorageFolderEntity.STRATEGY_ROUND_ROBIN.equals(strategy)) {
            throw new BusinessException("STORAGE058", "非法的调度策略: " + value);
        }
        return strategy;
    }

    private void insertBindings(Long folderId, List<Binding> bindings) {
        for (Binding binding : bindings) {
            StorageFolderConfigEntity row = new StorageFolderConfigEntity();
            row.setFolderId(folderId);
            row.setStorageConfigId(binding.storageConfigId());
            row.setPriority(binding.priority());
            folderConfigMapper.insert(row);
        }
    }

    private void insertGrantBindings(Long permissionId, List<Binding> bindings) {
        for (Binding binding : bindings) {
            StoragePermissionConfigEntity row = new StoragePermissionConfigEntity();
            row.setPermissionId(permissionId);
            row.setStorageConfigId(binding.storageConfigId());
            row.setPriority(binding.priority());
            permissionConfigMapper.insert(row);
        }
    }

    /** 全量替换文件夹绑定（在目录安全锁内调用） */
    private void replaceBindings(Long folderId, List<Binding> bindings) {
        folderConfigMapper.delete(new LambdaQueryWrapper<StorageFolderConfigEntity>()
                .eq(StorageFolderConfigEntity::getFolderId, folderId));
        insertBindings(folderId, bindings);
    }

    // ==================== 权限判断 ====================

    /**
     * 判断用户对文件夹是否拥有指定权限。
     * 所有者拥有全部权限；否则查授权记录（未撤销、未过期、权限集合包含目标权限）。
     */
    public boolean hasPermission(String userId, StorageFolderEntity folder, String perm) {
        if (userId == null || folder == null) {
            return false;
        }
        if (StorageFolderEntity.OWNER_USER.equals(folder.getOwnerType()) && userId.equals(folder.getOwnerId())) {
            return true;
        }
        // 同一用户在同一目录可能存在多行授权（历史重复提交）。权限按所有未撤销、未过期行的并集判断；
        // 只取 LIMIT 1 的任意一行会造成「明明有 UPLOAD 授权却被拒绝」或未来某行被误当唯一授权。
        List<StorageFolderPermissionEntity> permRows =
                permMapper.selectList(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                        .eq(StorageFolderPermissionEntity::getFolderId, folder.getId())
                        .eq(StorageFolderPermissionEntity::getSubjectType, StorageFolderPermissionEntity.SUBJECT_USER)
                        .eq(StorageFolderPermissionEntity::getSubjectId, userId)
                        .isNull(StorageFolderPermissionEntity::getRevokedTime));
        LocalDateTime now = LocalDateTime.now();
        for (StorageFolderPermissionEntity permRow : permRows) {
            if (permRow.getExpiresTime() != null && permRow.getExpiresTime().isBefore(now)) {
                continue;
            }
            if (normalizedList(permRow.getPermissions()).contains(perm)) {
                return true;
            }
        }
        return false;
    }

    public void requirePermission(String userId, Long folderId, String perm) {
        StorageFolderEntity folder = getById(folderId);
        if (folder == null) {
            throw new BusinessException("STORAGE016");
        }
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
            String perm = p.trim().toUpperCase(Locale.ROOT);
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

    /** 合并多行授权的权限并集，保留 READ,UPLOAD,UPDATE,DELETE,MANAGE 的稳定顺序 */
    private String mergePermissions(String left, String right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String perm : normalizedList(left)) {
            if (!perm.isEmpty()) {
                merged.add(perm);
            }
        }
        for (String perm : normalizedList(right)) {
            if (!perm.isEmpty()) {
                merged.add(perm);
            }
        }
        return String.join(",", merged);
    }

    // ==================== 版本与解析 ====================

    /** 影响目标解析的字段快照（用于判断是否需要 targetVersion+1） */
    private String targetSignature(StorageFolderEntity folder, List<StorageFolderConfigEntity> bindings) {
        String bindingSignature = bindings.stream()
                .map(b -> b.getStorageConfigId() + ":" + b.getPriority())
                .collect(Collectors.joining(","));
        return String.join(
                "|",
                String.valueOf(folder.getStorageStrategy()),
                bindingSignature,
                String.valueOf(folder.getStatus()),
                String.valueOf(folder.getVisibility()),
                String.valueOf(folder.getUploadPolicy()));
    }

    private long nextTargetVersion(Long current) {
        return current == null ? 1L : current + 1L;
    }

    /** 只写 target_version（绑定行由 insert/replace 独立维护） */
    private void bumpTargetVersion(Long folderId, StorageFolderEntity folder, String operator) {
        StorageFolderEntity bump = new StorageFolderEntity();
        bump.setId(folderId);
        bump.setTargetVersion(nextTargetVersion(folder.getTargetVersion()));
        bump.setUpdateBy(operator);
        folderMapper.updateById(bump);
    }

    /**
     * 只读目标解析（列表展示用）：解析失败一律返回全 null 的三元组，不影响目录管理本身。
     */
    private ResolvedTarget resolvedTarget(StorageFolderEntity folder) {
        try {
            StorageTargetResolver.Candidate candidate =
                    targetResolver.resolveForFolder(folder).firstCandidate();
            return new ResolvedTarget(candidate.configId(), candidate.providerType(), candidate.configName());
        } catch (RuntimeException ex) {
            log.debug("目录目标解析失败（仅展示用途）: folderId={}, reason={}", folder.getId(), ex.getMessage());
            return new ResolvedTarget(null, null, null);
        }
    }

    // ==================== 基础校验 ====================

    private LambdaQueryWrapper<StorageFolderEntity> siblings(Long parentId) {
        return new LambdaQueryWrapper<StorageFolderEntity>()
                .isNull(parentId == null, StorageFolderEntity::getParentId)
                .eq(parentId != null, StorageFolderEntity::getParentId, parentId);
    }

    private void validateFolderName(String name) {
        if (name == null
                || name.isBlank()
                || name.trim().length() > 128
                || name.contains("/")
                || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl)
                || ".".equals(name.trim())
                || "..".equals(name.trim())) {
            throw new BusinessException("COMMON002", "文件夹名称须为 1–128 字符，不能包含路径分隔符、控制字符或仅为点号");
        }
    }

    private void validateFolderPath(String path) {
        if (path.length() > 512) {
            throw new BusinessException("COMMON002", "文件夹完整路径不能超过 512 字符");
        }
    }

    private void syncDescendantPaths(StorageFolderEntity parent, String operator, java.util.Set<Long> visited) {
        if (!visited.add(parent.getId())) {
            throw new BusinessException("COMMON002", "文件夹结构存在循环");
        }
        for (StorageFolderEntity child : folderMapper.selectList(siblings(parent.getId()))) {
            child.setFolderPath(parent.getFolderPath() + "/" + child.getFolderName());
            validateFolderPath(child.getFolderPath());
            child.setUpdateBy(operator);
            folderMapper.updateById(child);
            syncDescendantPaths(child, operator, visited);
        }
    }

    /** Long 集合小工具（避免 null 元素） */
    private static final class SetOfLong {
        private final java.util.Set<Long> values;

        SetOfLong(Collection<Long> values) {
            this.values = new LinkedHashSet<>();
            for (Long value : values) {
                if (value != null) {
                    this.values.add(value);
                }
            }
        }

        boolean contains(Long value) {
            return value != null && values.contains(value);
        }
    }
}
