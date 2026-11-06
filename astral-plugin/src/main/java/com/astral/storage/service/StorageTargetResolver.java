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
        StorageFolderPermissionEntity grant = activeGrant(folder.getId(), userId);
        if (grant == null) {
            return rotateForIntent(folderResolution);
        }
        List<StoragePermissionConfigEntity> grantBindings = orderedGrantBindings(grant.getId());
        if (grantBindings.isEmpty()) {
            return rotateForIntent(folderResolution);
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
            // 授权绑定的配置已全部失效 → 回落文件夹绑定全集，不让授权行把上传彻底堵死
            return rotateForIntent(folderResolution);
        }
        return build(folderResolution.strategy(), folder, SOURCE_GRANT, tiers, true);
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
        Resolution resolution = new Resolution(
                strategy, folder.getId(), folder.getTargetVersion(), source, List.copyOf(tierList), null);
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

    private StorageFolderPermissionEntity activeGrant(Long folderId, String userId) {
        StorageFolderPermissionEntity grant =
                permissionMapper.selectOne(new LambdaQueryWrapper<StorageFolderPermissionEntity>()
                        .eq(StorageFolderPermissionEntity::getFolderId, folderId)
                        .eq(StorageFolderPermissionEntity::getSubjectType, StorageFolderPermissionEntity.SUBJECT_USER)
                        .eq(StorageFolderPermissionEntity::getSubjectId, userId)
                        .isNull(StorageFolderPermissionEntity::getRevokedTime)
                        .last("LIMIT 1"));
        if (grant == null) {
            return null;
        }
        if (grant.getExpiresTime() != null && grant.getExpiresTime().isBefore(LocalDateTime.now())) {
            return null;
        }
        return grant;
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
