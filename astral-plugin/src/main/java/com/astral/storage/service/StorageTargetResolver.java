package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFolderConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.entity.StorageFolderPermissionEntity;
import com.astral.storage.entity.StoragePermissionConfigEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFolderConfigMapper;
import com.astral.storage.mapper.StorageFolderPermissionMapper;
import com.astral.storage.mapper.StoragePermissionConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 上传目标解析：文件夹绑定 + 授权绑定（多选 + 优先级分层 + 调度策略）。
 *
 * <p>解析顺序（用户上传）：</p>
 * <ol>
 *   <li>文件夹绑定（sys_storage_folder_config）为空 → STORAGE058，禁止上传——<b>无默认回落</b>；</li>
 *   <li>用户在该文件夹存在未撤销/未过期授权，且授权绑定了配置 → 用授权绑定
 *       （写入时保证是文件夹绑定的子集，这里再按文件夹当前可用集合防御性过滤）；</li>
 *   <li>否则用文件夹绑定全集。</li>
 * </ol>
 *
 * <p>调度策略取目录 {@code storage_strategy}：FAILOVER 按优先级层故障转移；
 * ROUND_ROBIN 在同层内由 {@link TargetSelectionCursor} 轮询（仅
 * {@link #resolveForUpload} 推进游标，只读解析不推进）。</p>
 *
 * <p><b>失败即关闭</b>：绑定缺失/渠道停用直接报错，不回落默认配置——
 * 一次配置错误不能被静默写进另一个渠道。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StorageTargetResolver {

    /** 路由快照上限（设计 §5.4：≤16KiB） */
    private static final int MAX_SNAPSHOT_CHARS = 16384;

    /** 解析来源：文件夹绑定 / 授权绑定 */
    public static final String SOURCE_FOLDER = "FOLDER";

    public static final String SOURCE_GRANT = "GRANT";

    private final StorageConfigMapper configMapper;
    private final StorageFolderConfigMapper folderConfigMapper;
    private final StorageFolderPermissionMapper permissionMapper;
    private final StoragePermissionConfigMapper permissionConfigMapper;
    private final TargetSelectionCursor cursor;
    private final ObjectMapper objectMapper;

    /** 单个候选：解析时看到的渠道状态快照 */
    public record Candidate(Long configId, Long configVersion, String providerType, String configName) {}

    /** 一个优先级层；同层内顺序即候选顺序（轮询时在层内旋转） */
    public record Tier(int priority, List<Candidate> candidates) {}

    /**
     * 解析结果。
     *
     * @param strategy        FAILOVER | ROUND_ROBIN
     * @param folderId        目录 ID
     * @param folderVersion   目录 target_version 快照
     * @param source          FOLDER | GRANT（本次候选来自文件夹绑定还是授权绑定）
     * @param tiers          候选层，按 priority 升序
     * @param routeSnapshotJson 可持久化的非秘密快照
     */
    public record Resolution(
            String strategy,
            Long folderId,
            Long folderVersion,
            String source,
            List<Tier> tiers,
            String routeSnapshotJson) {

        /** 按顺序展开的全部候选 */
        public List<Candidate> flatCandidates() {
            List<Candidate> flat = new ArrayList<>();
            for (Tier tier : tiers) {
                flat.addAll(tier.candidates());
            }
            return flat;
        }

        /** 首个候选 */
        public Candidate firstCandidate() {
            List<Candidate> flat = flatCandidates();
            if (flat.isEmpty()) {
                throw new BusinessException("STORAGE041");
            }
            return flat.getFirst();
        }
    }

    // ============================================================
    // 目录目标
    // ============================================================

    /**
     * 按文件夹绑定解析候选（不推进轮询游标）。
     *
     * <p>用于列表展示、管理端只读解析与无授权上下文的管理员路径。</p>
     */
    public Resolution resolveForFolder(StorageFolderEntity folder) {
        requireFolder(folder);
        List<StorageFolderConfigEntity> bindings = orderedFolderBindings(folder.getId());
        if (bindings.isEmpty()) {
            throw new BusinessException("STORAGE058", "目录未绑定存储配置，禁止上传: " + folder.getId());
        }
        Map<Long, Candidate> candidates = loadCandidates(bindings.stream()
                .map(StorageFolderConfigEntity::getStorageConfigId)
                .toList());
        Map<Integer, List<Candidate>> tiers = new LinkedHashMap<>();
        for (StorageFolderConfigEntity binding : bindings) {
            Candidate candidate = candidates.get(binding.getStorageConfigId());
            if (candidate != null) {
                tiers.computeIfAbsent(binding.getPriority(), k -> new ArrayList<>())
                        .add(candidate);
            }
        }
        return build(strategyOf(folder), folder, SOURCE_FOLDER, tiers, false);
    }

    /**
     * 为一次真实上传解析候选：授权绑定（子集）优先，ROUND_ROBIN 时推进轮询游标。
     *
     * @param folder 目录行
     * @param userId 上传者 ID（null 或目录所有者 → 直接用文件夹绑定）
     */
    public Resolution resolveForUpload(StorageFolderEntity folder, String userId) {
        Resolution folderResolution = resolveForFolder(folder);
        if (userId == null || isOwner(folder, userId)) {
            return rotateForIntent(folderResolution);
        }
        List<StorageFolderPermissionEntity> grants = activeUploadGrants(folder.getId(), userId);
        if (grants.isEmpty()) {
            return rotateForIntent(folderResolution);
        }
        List<StoragePermissionConfigEntity> grantBindings = new ArrayList<>();
        for (StorageFolderPermissionEntity grant : grants) {
            List<StoragePermissionConfigEntity> rows = orderedGrantBindings(grant.getId());
            if (rows.isEmpty()) {
                // 任一有效 UPLOAD 授权未限定配置 = 跟随文件夹绑定全集，与单行语义保持一致。
                return rotateForIntent(folderResolution);
            }
            grantBindings.addAll(rows);
        }
        // 防御性子集过滤：授权写入时已校验 ⊆ 文件夹绑定，这里按当前可用集合再过滤一遍
        Map<Long, Candidate> available = new LinkedHashMap<>();
        for (Candidate candidate : folderResolution.flatCandidates()) {
            available.put(candidate.configId(), candidate);
        }
        Map<Integer, List<Candidate>> tiers = new LinkedHashMap<>();
        for (StoragePermissionConfigEntity binding : grantBindings) {
            Candidate candidate = available.get(binding.getStorageConfigId());
            if (candidate != null) {
                tiers.computeIfAbsent(binding.getPriority(), k -> new ArrayList<>())
                        .add(candidate);
            }
        }
        if (tiers.isEmpty()) {
            // 授权是权限的上界：授权内配置全部失效时必须失败关闭，绝不能回落到文件夹全集，
            // 否则一条「只允许 A 渠道」的授权会因 A 停用而突然获得 B/C 渠道的上传能力。
            throw new BusinessException("STORAGE041", "授权范围内没有可用的存储配置");
        }
        return build(folderResolution.strategy(), folder, SOURCE_GRANT, tiers, true);
    }

    /**
     * 解析创建意图时落库的 v2 路由快照。候选顺序/priority/configVersion 只来自快照，
     * 不会实时 resolve 增补新候选；folderId/folderVersion 与当前操作不一致时失败关闭。
     */
    public Resolution parseSnapshot(String json, Long expectedFolderId, Long expectedFolderVersion) {
        if (json == null || json.isBlank()) {
            throw new BusinessException("STORAGE047", "上传意图缺少路由快照");
        }
        if (json.length() > MAX_SNAPSHOT_CHARS) {
            throw new BusinessException("STORAGE047", "路由快照超长");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null
                    || !root.path("v").isIntegralNumber()
                    || root.path("v").asInt() != 2) {
                throw invalidSnapshot();
            }
            if (!root.path("folderId").isIntegralNumber()
                    || !root.path("folderVersion").isIntegralNumber()) {
                throw invalidSnapshot();
            }
            long folderId = root.path("folderId").asLong();
            long folderVersion = root.path("folderVersion").asLong();
            if (!Objects.equals(expectedFolderId, folderId) || !Objects.equals(expectedFolderVersion, folderVersion)) {
                throw new BusinessException("STORAGE047", "路由快照与上传意图的目录版本不一致");
            }
            String strategy = text(root, "strategy").toUpperCase(java.util.Locale.ROOT);
            if (!StorageFolderEntity.STRATEGY_FAILOVER.equals(strategy)
                    && !StorageFolderEntity.STRATEGY_ROUND_ROBIN.equals(strategy)) {
                throw invalidSnapshot();
            }
            String source = text(root, "source").toUpperCase(java.util.Locale.ROOT);
            if (!SOURCE_FOLDER.equals(source) && !SOURCE_GRANT.equals(source)) {
                throw invalidSnapshot();
            }
            JsonNode tierNodes = root.path("tiers");
            if (!tierNodes.isArray() || tierNodes.isEmpty() || tierNodes.size() > 32) {
                throw invalidSnapshot();
            }
            List<Tier> tiers = new ArrayList<>();
            LinkedHashSet<Long> seenConfigIds = new LinkedHashSet<>();
            int candidateCount = 0;
            for (JsonNode tierNode : tierNodes) {
                if (!tierNode.isObject() || !tierNode.path("priority").isIntegralNumber()) {
                    throw invalidSnapshot();
                }
                JsonNode priorityNode = tierNode.path("priority");
                long rawPriority = priorityNode.asLong();
                // 与 sys_storage_folder_config.CHECK (priority BETWEEN 0 AND 10000)
                // 及 StorageFolderService.normalizePriority 的落库范围保持一致：0 是合法最小优先级，
                // 快照解析若拒绝 0 会让按默认/前端最小值创建的意图永远无法重放。
                if (rawPriority < 0 || rawPriority > 10000) {
                    throw invalidSnapshot();
                }
                int priority = (int) rawPriority;
                JsonNode candidateNodes = tierNode.path("candidates");
                if (!candidateNodes.isArray() || candidateNodes.isEmpty()) {
                    throw invalidSnapshot();
                }
                List<Candidate> candidates = new ArrayList<>();
                for (JsonNode node : candidateNodes) {
                    if (!node.isObject()
                            || !node.path("configId").isIntegralNumber()
                            || !node.path("configVersion").isIntegralNumber()) {
                        throw invalidSnapshot();
                    }
                    long configId = node.path("configId").asLong();
                    long configVersion = node.path("configVersion").asLong();
                    String providerType = text(node, "providerType").toUpperCase(java.util.Locale.ROOT);
                    if (configId <= 0 || configVersion < 0 || !isSelectable(providerType)) {
                        throw invalidSnapshot();
                    }
                    if (!seenConfigIds.add(configId) || ++candidateCount > 128) {
                        throw invalidSnapshot();
                    }
                    candidates.add(new Candidate(configId, configVersion, providerType, ""));
                }
                tiers.add(new Tier(priority, List.copyOf(candidates)));
            }
            return new Resolution(strategy, folderId, folderVersion, source, List.copyOf(tiers), json);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException("STORAGE047", "路由快照损坏");
        }
    }

    /**
     * 发送/故障转移前对单个快照候选做当前事实复核：目录版本、渠道启停/版本、操作者当前授权。
     * 复核不推进轮询游标，也不会把快照之外的候选加回来。
     */
    public Candidate requireCurrentCandidate(
            StorageFolderEntity folder,
            String userId,
            boolean folderAclExempt,
            Long expectedFolderId,
            Long expectedFolderVersion,
            Long configId,
            Long expectedConfigVersion) {
        requireFolder(folder);
        if (!Objects.equals(expectedFolderId, folder.getId())
                || !Objects.equals(expectedFolderVersion, versionOf(folder))) {
            throw new BusinessException("STORAGE047", "目录目标版本已变更");
        }
        if (!StorageFolderEntity.STATUS_ENABLED.equals(folder.getStatus())) {
            throw new BusinessException("STORAGE025", "目录已停用");
        }
        Resolution currentFolder = resolveForFolder(folder);
        Candidate current = null;
        for (Candidate candidate : currentFolder.flatCandidates()) {
            if (Objects.equals(candidate.configId(), configId)) {
                current = candidate;
                break;
            }
        }
        if (current == null) {
            throw new BusinessException("STORAGE041", "候选渠道已停用或不再属于目录绑定");
        }
        if (expectedConfigVersion != null && !expectedConfigVersion.equals(current.configVersion())) {
            throw new BusinessException("STORAGE047", "候选渠道配置版本已变更");
        }
        if (!folderAclExempt && userId != null && !isOwner(folder, userId)) {
            List<StorageFolderPermissionEntity> grants = activeUploadGrants(folder.getId(), userId);
            if (grants.isEmpty()) {
                throw new BusinessException("STORAGE013", "目录上传授权已撤销");
            }
            boolean unrestricted = false;
            LinkedHashSet<Long> allowedConfigIds = new LinkedHashSet<>();
            for (StorageFolderPermissionEntity grant : grants) {
                List<StoragePermissionConfigEntity> rows = orderedGrantBindings(grant.getId());
                if (rows.isEmpty()) {
                    unrestricted = true;
                    break;
                }
                for (StoragePermissionConfigEntity row : rows) {
                    allowedConfigIds.add(row.getStorageConfigId());
                }
            }
            if (!unrestricted && !allowedConfigIds.contains(configId)) {
                throw new BusinessException("STORAGE041", "候选渠道不在当前授权范围内");
            }
        }
        return current;
    }

    private static BusinessException invalidSnapshot() {
        return new BusinessException("STORAGE047", "路由快照格式无效");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalidSnapshot();
        }
        return value.asText();
    }

    // ============================================================
    // 组装
    // ============================================================

    private Resolution build(
            String strategy,
            StorageFolderEntity folder,
            String source,
            Map<Integer, List<Candidate>> tiers,
            boolean rotate) {
        if (tiers.isEmpty()) {
            throw new BusinessException("STORAGE041");
        }
        List<Tier> tierList = new ArrayList<>(tiers.size());
        for (Map.Entry<Integer, List<Candidate>> entry : tiers.entrySet()) {
            List<Candidate> candidates = List.copyOf(entry.getValue());
            if (rotate && StorageFolderEntity.STRATEGY_ROUND_ROBIN.equals(strategy) && candidates.size() > 1) {
                candidates = rotateTier(folder, entry.getKey(), candidates);
            }
            tierList.add(new Tier(entry.getKey(), candidates));
        }
        Resolution resolution =
                new Resolution(strategy, folder.getId(), versionOf(folder), source, List.copyOf(tierList), null);
        return withSnapshot(resolution);
    }

    private Resolution rotateForIntent(Resolution resolution) {
        if (!StorageFolderEntity.STRATEGY_ROUND_ROBIN.equals(resolution.strategy())) {
            return resolution;
        }
        List<Tier> rotated = new ArrayList<>(resolution.tiers().size());
        for (Tier tier : resolution.tiers()) {
            rotated.add(
                    tier.candidates().size() <= 1
                            ? tier
                            : new Tier(tier.priority(), rotateTier(resolution, tier.priority(), tier.candidates())));
        }
        return withSnapshot(new Resolution(
                resolution.strategy(),
                resolution.folderId(),
                resolution.folderVersion(),
                resolution.source(),
                List.copyOf(rotated),
                null));
    }

    private List<Candidate> rotateTier(StorageFolderEntity folder, int priority, List<Candidate> candidates) {
        int start = cursor.nextStart(folder.getId(), versionOf(folder), priority, candidates.size());
        List<Candidate> rotated = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            rotated.add(candidates.get((start + i) % candidates.size()));
        }
        return rotated;
    }

    private List<Candidate> rotateTier(Resolution resolution, int priority, List<Candidate> candidates) {
        int start = cursor.nextStart(resolution.folderId(), resolution.folderVersion(), priority, candidates.size());
        List<Candidate> rotated = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            rotated.add(candidates.get((start + i) % candidates.size()));
        }
        return rotated;
    }

    /** 两段式上传可选的渠道类型：已知类型 + CUSTOM_HTTP（CUSTOM_HTTP 仅两段式链路） */
    private static boolean isSelectable(String providerType) {
        return ProviderSupport.isKnown(providerType) || StorageConfigEntity.PROVIDER_CUSTOM_HTTP.equals(providerType);
    }

    /**
     * 加载候选集合：过滤不存在/停用/不可选的配置（记 warn），返回 configId → Candidate。
     */
    private Map<Long, Candidate> loadCandidates(List<Long> configIds) {
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (Long id : configIds) {
            if (id != null) {
                distinctIds.add(id);
            }
        }
        Map<Long, Candidate> result = new LinkedHashMap<>();
        if (distinctIds.isEmpty()) {
            return result;
        }
        for (StorageConfigEntity config : configMapper.selectByIds(distinctIds)) {
            if (!StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
                continue;
            }
            if (!isSelectable(config.getProviderType())) {
                log.warn(
                        "storage binding config {} provider {} unsupported, skipped",
                        config.getId(),
                        config.getProviderType());
                continue;
            }
            result.put(
                    config.getId(),
                    new Candidate(
                            config.getId(), config.getConfigVersion(), config.getProviderType(), config.getName()));
        }
        return result;
    }

    private List<StorageFolderConfigEntity> orderedFolderBindings(Long folderId) {
        return folderConfigMapper.selectList(new LambdaQueryWrapper<StorageFolderConfigEntity>()
                .eq(StorageFolderConfigEntity::getFolderId, folderId)
                .orderByAsc(StorageFolderConfigEntity::getPriority)
                .orderByAsc(StorageFolderConfigEntity::getId));
    }

    private List<StoragePermissionConfigEntity> orderedGrantBindings(Long permissionId) {
        return permissionConfigMapper.selectList(new LambdaQueryWrapper<StoragePermissionConfigEntity>()
                .eq(StoragePermissionConfigEntity::getPermissionId, permissionId)
                .orderByAsc(StoragePermissionConfigEntity::getPriority)
                .orderByAsc(StoragePermissionConfigEntity::getId));
    }

    /**
     * 取该用户全部有效且含 UPLOAD 的授权行（按 id 稳定排序）。
     *
     * <p>只取 LIMIT 1 的任意行会漏掉另一行授予的 UPLOAD/配置子集；过期行也可能被错误选中。
     * 多行授权的配置子集按并集处理，权限本身由 {@link StorageFolderService#hasPermission} 合并判断。</p>
     */
    private List<StorageFolderPermissionEntity> activeUploadGrants(Long folderId, String userId) {
        List<StorageFolderPermissionEntity> rows =
                permissionMapper.selectList(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                        .eq(StorageFolderPermissionEntity::getFolderId, folderId)
                        .eq(StorageFolderPermissionEntity::getSubjectType, StorageFolderPermissionEntity.SUBJECT_USER)
                        .eq(StorageFolderPermissionEntity::getSubjectId, userId)
                        .isNull(StorageFolderPermissionEntity::getRevokedTime)
                        .orderByAsc(StorageFolderPermissionEntity::getId));
        LocalDateTime now = LocalDateTime.now();
        List<StorageFolderPermissionEntity> active = new ArrayList<>();
        for (StorageFolderPermissionEntity row : rows) {
            if (row.getExpiresTime() != null && row.getExpiresTime().isBefore(now)) {
                continue;
            }
            if (hasUploadPermission(row.getPermissions())) {
                active.add(row);
            }
        }
        return active;
    }

    private static boolean hasUploadPermission(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (String permission : raw.split(",")) {
            if (StorageFolderPermissionEntity.PERM_UPLOAD.equalsIgnoreCase(permission.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwner(StorageFolderEntity folder, String userId) {
        return StorageFolderEntity.OWNER_USER.equals(folder.getOwnerType())
                && Objects.equals(folder.getOwnerId(), userId);
    }

    private static String strategyOf(StorageFolderEntity folder) {
        return folder.getStorageStrategy() == null
                ? StorageFolderEntity.STRATEGY_FAILOVER
                : folder.getStorageStrategy();
    }

    private static long versionOf(StorageFolderEntity folder) {
        return folder.getTargetVersion() == null ? 1L : folder.getTargetVersion();
    }

    private static void requireFolder(StorageFolderEntity folder) {
        if (folder == null) {
            throw new BusinessException("STORAGE058", "目录不存在");
        }
    }

    // ============================================================
    // 快照
    // ============================================================

    private Resolution withSnapshot(Resolution resolution) {
        String json = buildSnapshot(resolution);
        return new Resolution(
                resolution.strategy(),
                resolution.folderId(),
                resolution.folderVersion(),
                resolution.source(),
                resolution.tiers(),
                json);
    }

    /**
     * 生成可落库的路由快照：只含 ID/版本/类型/顺序，不含任何凭据、URL 参数或上游响应。
     */
    private String buildSnapshot(Resolution resolution) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("v", 2);
        snapshot.put("folderId", resolution.folderId());
        snapshot.put("folderVersion", resolution.folderVersion());
        snapshot.put("strategy", resolution.strategy());
        snapshot.put("source", resolution.source());
        List<Object> tiers = new ArrayList<>();
        for (Tier tier : resolution.tiers()) {
            Map<String, Object> tierNode = new LinkedHashMap<>();
            tierNode.put("priority", tier.priority());
            List<Object> candidates = new ArrayList<>();
            for (Candidate candidate : tier.candidates()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("configId", candidate.configId());
                node.put("configVersion", candidate.configVersion());
                node.put("providerType", candidate.providerType());
                candidates.add(node);
            }
            tierNode.put("candidates", candidates);
            tiers.add(tierNode);
        }
        snapshot.put("tiers", tiers);
        String json = objectMapper.writeValueAsString(snapshot);
        if (json.length() > MAX_SNAPSHOT_CHARS) {
            throw new BusinessException("STORAGE058", "路由快照超长");
        }
        return json;
    }
}
