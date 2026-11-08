package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentKnowledgeEntity;
import com.astral.ai.entity.AiAgentToolEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.mapper.AiAgentKnowledgeMapper;
import com.astral.ai.mapper.AiAgentMapper;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiAgentVersionMapper;
import com.astral.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 智能体与智能体版本服务。
 *
 * <p>三层结构：{@code ai_agent}（身份与入口） → {@code ai_agent_version}（不可变行为快照）
 * → 运行时绑定。发布后的版本<b>不再允许修改</b>，需要变更只能新建草稿版本或回滚；
 * 这条约束保证任何一次历史请求都能被完整复现与审计（设计文档 §4.3）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentService {

    private final AiAgentMapper agentMapper;
    private final AiAgentVersionMapper versionMapper;
    private final AiAgentToolMapper agentToolMapper;
    private final AiAgentKnowledgeMapper agentKnowledgeMapper;

    // ==================== 查询 ====================

    /** 全部智能体（不含已删除） */
    public List<AiAgentEntity> list() {
        return agentMapper.selectList(new LambdaQueryWrapper<AiAgentEntity>()
                .isNull(AiAgentEntity::getDeletedAt)
                .orderByAsc(AiAgentEntity::getSort)
                .orderByAsc(AiAgentEntity::getId));
    }

    /** 按编码取（含未发布） */
    public AiAgentEntity findByCode(String agentCode) {
        if (agentCode == null || agentCode.isBlank()) {
            return null;
        }
        return agentMapper.selectOne(new LambdaQueryWrapper<AiAgentEntity>()
                .eq(AiAgentEntity::getAgentCode, agentCode.trim())
                .isNull(AiAgentEntity::getDeletedAt)
                .last("limit 1"));
    }

    /** 按主键取 */
    public AiAgentEntity findById(Long id) {
        if (id == null) {
            return null;
        }
        AiAgentEntity entity = agentMapper.selectById(id);
        return entity == null || entity.getDeletedAt() != null ? null : entity;
    }

    /** 取智能体，不存在抛错 */
    public AiAgentEntity require(Long id) {
        AiAgentEntity entity = findById(id);
        if (entity == null) {
            throw new BusinessException("AI002", "智能体不存在: " + id);
        }
        return entity;
    }

    /** 取可用智能体：必须存在且已发布 */
    public AiAgentEntity requireAvailable(String agentCode) {
        AiAgentEntity entity = findByCode(agentCode);
        if (entity == null) {
            throw new BusinessException("AI002", agentCode);
        }
        if (AiConstants.AgentStatus.DISABLED.equals(entity.getStatus())) {
            throw new BusinessException("AI002", agentCode);
        }
        if (entity.getPublishedVersionId() == null) {
            throw new BusinessException("AI002", agentCode);
        }
        return entity;
    }

    /** 取版本 */
    public AiAgentVersionEntity requireVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException("AI007", "智能体未发布可用版本");
        }
        AiAgentVersionEntity version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("AI007", "智能体版本不存在: " + versionId);
        }
        return version;
    }

    /** 某智能体的全部版本（新到旧） */
    public List<AiAgentVersionEntity> listVersions(Long agentId) {
        return versionMapper.selectList(new LambdaQueryWrapper<AiAgentVersionEntity>()
                .eq(AiAgentVersionEntity::getAgentId, agentId)
                .orderByDesc(AiAgentVersionEntity::getVersionNo));
    }

    /** 批量取版本号（列表展示用，避免 N+1） */
    public Map<Long, AiAgentVersionEntity> versionsByIds(List<Long> versionIds) {
        List<Long> ids = versionIds == null
                ? List.of()
                : versionIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return versionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(AiAgentVersionEntity::getId, Function.identity(), (a, b) -> a));
    }

    // ==================== 智能体维护 ====================

    /** 新增智能体（初始为草稿） */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentEntity create(
            String agentCode,
            String name,
            String agentType,
            String description,
            String icon,
            List<String> clientTypes,
            String entryConfig,
            String permissionCode,
            String clientPermissionCodes,
            Long quotaPolicyId,
            Boolean allowUserContext,
            Boolean allowFileContext,
            Boolean conversationEnabled,
            Boolean feedbackEnabled,
            Integer sort,
            Long operatorId) {
        if (findByCode(agentCode) != null) {
            throw new BusinessException("AI007", "智能体编码已存在: " + agentCode);
        }
        if (!AiConstants.AgentType.all().contains(agentType)) {
            throw new BusinessException("AI007", "智能体类型非法: " + agentType);
        }
        AiAgentEntity entity = new AiAgentEntity();
        entity.setAgentCode(agentCode);
        entity.setName(name);
        entity.setAgentType(agentType);
        entity.setDescription(description);
        entity.setIcon(icon);
        entity.setStatus(AiConstants.AgentStatus.DRAFT);
        entity.setClientTypes(AiJson.joinCsv(normalizeClients(clientTypes)));
        entity.setEntryConfig(entryConfig);
        entity.setPermissionCode(permissionCode);
        entity.setClientPermissionCodes(clientPermissionCodes);
        entity.setQuotaPolicyId(quotaPolicyId);
        entity.setAllowUserContext(allowUserContext == null || allowUserContext);
        entity.setAllowFileContext(allowFileContext != null && allowFileContext);
        entity.setConversationEnabled(conversationEnabled == null || conversationEnabled);
        entity.setFeedbackEnabled(feedbackEnabled == null || feedbackEnabled);
        entity.setSort(sort == null ? 100 : sort);
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        agentMapper.insert(entity);
        return entity;
    }

    /** 更新智能体身份信息（不改已发布版本内容） */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentEntity update(
            Long id,
            String name,
            String agentType,
            String description,
            String icon,
            List<String> clientTypes,
            String entryConfig,
            String permissionCode,
            String clientPermissionCodes,
            Long quotaPolicyId,
            Boolean allowUserContext,
            Boolean allowFileContext,
            Boolean conversationEnabled,
            Boolean feedbackEnabled,
            Integer sort,
            Long operatorId) {
        lockAgent(id);
        var patch = new LambdaUpdateWrapper<AiAgentEntity>().eq(AiAgentEntity::getId, id);
        if (name != null && !name.isBlank()) {
            patch.set(AiAgentEntity::getName, name.trim());
        }
        if (agentType != null && !agentType.isBlank()) {
            if (!AiConstants.AgentType.all().contains(agentType)) {
                throw new BusinessException("AI007", "智能体类型非法: " + agentType);
            }
            patch.set(AiAgentEntity::getAgentType, agentType);
        }
        if (description != null) {
            patch.set(AiAgentEntity::getDescription, description);
        }
        if (icon != null) {
            patch.set(AiAgentEntity::getIcon, icon);
        }
        if (clientTypes != null) {
            List<String> clients = normalizeClients(clientTypes);
            if (clients.isEmpty()) {
                throw new BusinessException("AI007", "至少需要选择一个可用客户端");
            }
            patch.set(AiAgentEntity::getClientTypes, AiJson.joinCsv(clients));
        }
        if (entryConfig != null) {
            patch.set(AiAgentEntity::getEntryConfig, entryConfig);
        }
        if (permissionCode != null) {
            patch.set(AiAgentEntity::getPermissionCode, permissionCode.isBlank() ? null : permissionCode.trim());
        }
        if (clientPermissionCodes != null) {
            patch.set(
                    AiAgentEntity::getClientPermissionCodes,
                    clientPermissionCodes.isBlank() ? null : clientPermissionCodes);
        }
        if (quotaPolicyId != null) {
            patch.set(AiAgentEntity::getQuotaPolicyId, quotaPolicyId);
        }
        if (allowUserContext != null) {
            patch.set(AiAgentEntity::getAllowUserContext, allowUserContext);
        }
        if (allowFileContext != null) {
            patch.set(AiAgentEntity::getAllowFileContext, allowFileContext);
        }
        if (conversationEnabled != null) {
            patch.set(AiAgentEntity::getConversationEnabled, conversationEnabled);
        }
        if (feedbackEnabled != null) {
            patch.set(AiAgentEntity::getFeedbackEnabled, feedbackEnabled);
        }
        if (sort != null) {
            patch.set(AiAgentEntity::getSort, sort);
        }
        patch.set(AiAgentEntity::getUpdatedBy, operatorId);
        patch.set(AiAgentEntity::getUpdateTime, LocalDateTime.now());
        agentMapper.update(null, patch);
        return require(id);
    }

    /** 下线智能体（不删除历史数据） */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentEntity disable(Long id, Long operatorId) {
        AiAgentEntity entity = lockAgent(id);
        agentMapper.update(
                null,
                new LambdaUpdateWrapper<AiAgentEntity>()
                        .eq(AiAgentEntity::getId, id)
                        .set(AiAgentEntity::getStatus, AiConstants.AgentStatus.DISABLED)
                        .set(AiAgentEntity::getUpdatedBy, operatorId)
                        .set(AiAgentEntity::getUpdateTime, LocalDateTime.now()));
        log.info("[AI] 智能体已下线 code={} operator={}", entity.getAgentCode(), operatorId);
        return require(id);
    }

    /** 重新上线（需已存在发布版本） */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentEntity enable(Long id, Long operatorId) {
        AiAgentEntity entity = lockAgent(id);
        if (entity.getPublishedVersionId() == null) {
            throw new BusinessException("AI007", "该智能体还没有已发布版本，无法上线");
        }
        agentMapper.update(
                null,
                new LambdaUpdateWrapper<AiAgentEntity>()
                        .eq(AiAgentEntity::getId, id)
                        .set(AiAgentEntity::getStatus, AiConstants.AgentStatus.PUBLISHED)
                        .set(AiAgentEntity::getUpdatedBy, operatorId)
                        .set(AiAgentEntity::getUpdateTime, LocalDateTime.now()));
        return require(id);
    }

    /** 逻辑删除 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        lockAgent(id);
        agentMapper.update(
                null,
                new LambdaUpdateWrapper<AiAgentEntity>()
                        .eq(AiAgentEntity::getId, id)
                        .set(AiAgentEntity::getDeletedAt, LocalDateTime.now())
                        .set(AiAgentEntity::getStatus, AiConstants.AgentStatus.DISABLED)
                        .set(AiAgentEntity::getUpdatedBy, operatorId)
                        .set(AiAgentEntity::getUpdateTime, LocalDateTime.now()));
    }

    // ==================== 版本维护 ====================

    /** 新建草稿版本（版本号自增） */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentVersionEntity createDraftVersion(
            Long agentId,
            String versionName,
            Long providerRouteId,
            Long systemPromptVersionId,
            String contextPolicyJson,
            String responseSchemaJson,
            String toolPolicyJson,
            String knowledgePolicyJson,
            String modelParametersJson,
            String safetyPolicyJson,
            String quotaOverrideJson,
            Long operatorId) {
        AiAgentEntity agent = lockAgent(agentId);
        lockRoute(providerRouteId);
        List<AiAgentVersionEntity> versions = listVersions(agentId);
        int nextNo = versions.isEmpty() ? 1 : versions.get(0).getVersionNo() + 1;
        AiAgentVersionEntity version = new AiAgentVersionEntity();
        version.setAgentId(agentId);
        version.setVersionNo(nextNo);
        version.setVersionName(versionName == null || versionName.isBlank() ? "v" + nextNo : versionName.trim());
        version.setStatus(AiConstants.VersionStatus.DRAFT);
        version.setProviderRouteId(providerRouteId);
        version.setSystemPromptVersionId(systemPromptVersionId);
        version.setClientTypesSnapshot(agent.getClientTypes());
        version.setEntryConfigSnapshot(agent.getEntryConfig());
        version.setContextPolicyJson(contextPolicyJson);
        version.setResponseSchemaJson(responseSchemaJson);
        version.setToolPolicyJson(toolPolicyJson);
        version.setKnowledgePolicyJson(knowledgePolicyJson);
        version.setModelParametersJson(modelParametersJson);
        version.setSafetyPolicyJson(safetyPolicyJson);
        version.setQuotaOverrideJson(quotaOverrideJson);
        version.setCreatedBy(operatorId);
        version.setUpdatedBy(operatorId);
        versionMapper.insert(version);
        // 绑定挂在版本上：新建草稿必须从最新版本带上工具/知识绑定，否则发布会悄悄丢能力
        if (!versions.isEmpty()) {
            copyRuntimeBindingsFrom(versions.get(0).getId(), version.getId());
        }
        return version;
    }

    /** 更新草稿版本（已发布版本禁止修改） */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentVersionEntity updateDraftVersion(
            Long versionId,
            String versionName,
            Long providerRouteId,
            Long systemPromptVersionId,
            String contextPolicyJson,
            String responseSchemaJson,
            String toolPolicyJson,
            String knowledgePolicyJson,
            String modelParametersJson,
            String safetyPolicyJson,
            String quotaOverrideJson,
            Long operatorId) {
        AiAgentVersionEntity version = lockVersionForPublication(versionId);
        requireDraft(version);
        var patch = new LambdaUpdateWrapper<AiAgentVersionEntity>()
                .eq(AiAgentVersionEntity::getId, versionId)
                .eq(AiAgentVersionEntity::getStatus, AiConstants.VersionStatus.DRAFT);
        if (versionName != null && !versionName.isBlank()) {
            patch.set(AiAgentVersionEntity::getVersionName, versionName.trim());
        }
        if (providerRouteId != null) {
            lockRoute(providerRouteId);
            patch.set(AiAgentVersionEntity::getProviderRouteId, providerRouteId);
        }
        if (systemPromptVersionId != null) {
            patch.set(AiAgentVersionEntity::getSystemPromptVersionId, systemPromptVersionId);
        }
        if (contextPolicyJson != null) {
            patch.set(AiAgentVersionEntity::getContextPolicyJson, contextPolicyJson);
        }
        if (responseSchemaJson != null) {
            patch.set(AiAgentVersionEntity::getResponseSchemaJson, responseSchemaJson);
        }
        if (toolPolicyJson != null) {
            patch.set(AiAgentVersionEntity::getToolPolicyJson, toolPolicyJson);
        }
        if (knowledgePolicyJson != null) {
            patch.set(AiAgentVersionEntity::getKnowledgePolicyJson, knowledgePolicyJson);
        }
        if (modelParametersJson != null) {
            patch.set(AiAgentVersionEntity::getModelParametersJson, modelParametersJson);
        }
        if (safetyPolicyJson != null) {
            patch.set(AiAgentVersionEntity::getSafetyPolicyJson, safetyPolicyJson);
        }
        if (quotaOverrideJson != null) {
            patch.set(AiAgentVersionEntity::getQuotaOverrideJson, quotaOverrideJson);
        }
        patch.set(AiAgentVersionEntity::getUpdatedBy, operatorId)
                .set(AiAgentVersionEntity::getUpdateTime, LocalDateTime.now());
        if (versionMapper.update(null, patch) != 1) throw new BusinessException("AI007", "草稿已变化，请刷新后重试");
        return requireVersion(versionId);
    }

    /** Lock ordering for edits/publication is agent → version → route. Caller transaction retains the locks. */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentVersionEntity lockVersionForPublication(Long versionId) {
        AiAgentVersionEntity observed = requireVersion(versionId);
        lockAgent(observed.getAgentId());
        AiAgentVersionEntity current = versionMapper.lock(versionId);
        if (current == null) throw new BusinessException("AI007", "智能体版本不存在");
        return current;
    }

    private AiAgentEntity lockAgent(Long id) {
        AiAgentEntity agent = agentMapper.lock(id);
        if (agent == null) throw new BusinessException("AI002", "智能体不存在");
        return agent;
    }

    /** 版本必须是草稿，否则拒绝（版本不可变约束的唯一出口） */
    public void requireDraft(AiAgentVersionEntity version) {
        if (!AiConstants.VersionStatus.DRAFT.equals(version.getStatus())) {
            throw new BusinessException("AI007", "版本 " + version.getVersionNo() + " 已发布，不可修改；请新建草稿版本");
        }
    }

    /** 发布版本：归档旧的已发布版本并切换智能体指针 */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentVersionEntity publish(Long versionId, Long operatorId) {
        AiAgentVersionEntity version = lockVersionForPublication(versionId);
        requireDraft(version);
        AiAgentEntity agent = require(version.getAgentId());

        List<AiAgentVersionEntity> published = versionMapper.selectList(new LambdaQueryWrapper<AiAgentVersionEntity>()
                .eq(AiAgentVersionEntity::getAgentId, agent.getId())
                .eq(AiAgentVersionEntity::getStatus, AiConstants.VersionStatus.PUBLISHED));
        for (AiAgentVersionEntity old : published) {
            if (!old.getId().equals(version.getId())) {
                versionMapper.update(
                        null,
                        new LambdaUpdateWrapper<AiAgentVersionEntity>()
                                .eq(AiAgentVersionEntity::getId, old.getId())
                                .set(AiAgentVersionEntity::getStatus, AiConstants.VersionStatus.ARCHIVED)
                                .set(AiAgentVersionEntity::getUpdateTime, LocalDateTime.now()));
            }
        }

        versionMapper.update(
                null,
                new LambdaUpdateWrapper<AiAgentVersionEntity>()
                        .eq(AiAgentVersionEntity::getId, versionId)
                        .set(AiAgentVersionEntity::getStatus, AiConstants.VersionStatus.PUBLISHED)
                        .set(AiAgentVersionEntity::getPublishedBy, operatorId)
                        .set(AiAgentVersionEntity::getPublishedAt, LocalDateTime.now())
                        .set(AiAgentVersionEntity::getClientTypesSnapshot, agent.getClientTypes())
                        .set(AiAgentVersionEntity::getEntryConfigSnapshot, agent.getEntryConfig())
                        .set(AiAgentVersionEntity::getUpdatedBy, operatorId)
                        .set(AiAgentVersionEntity::getUpdateTime, LocalDateTime.now()));
        agentMapper.update(
                null,
                new LambdaUpdateWrapper<AiAgentEntity>()
                        .eq(AiAgentEntity::getId, agent.getId())
                        .set(AiAgentEntity::getPublishedVersionId, versionId)
                        .set(AiAgentEntity::getStatus, AiConstants.AgentStatus.PUBLISHED)
                        .set(AiAgentEntity::getUpdatedBy, operatorId)
                        .set(AiAgentEntity::getUpdateTime, LocalDateTime.now()));
        log.info("[AI] 智能体发布 code={} version={} operator={}", agent.getAgentCode(), version.getVersionNo(), operatorId);
        return requireVersion(versionId);
    }

    /**
     * 回滚：以历史发布版本为模板生成一个新的草稿版本并立即发布。
     *
     * <p>不直接改旧版本状态，避免审计链断裂——历史永远是历史。</p>
     *
     * @param agentId          智能体
     * @param targetVersionId  目标历史版本
     * @param operatorId       操作人
     * @return 新的已发布版本
     */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentVersionEntity rollback(Long agentId, Long targetVersionId, Long operatorId) {
        AiAgentEntity agent = lockAgent(agentId);
        AiAgentVersionEntity target = requireVersion(targetVersionId);
        if (!agent.getId().equals(target.getAgentId())) {
            throw new BusinessException("AI007", "目标版本不属于该智能体");
        }
        lockRoute(target.getProviderRouteId());
        List<AiAgentVersionEntity> versions = listVersions(agentId);
        int nextNo = versions.isEmpty() ? 1 : versions.get(0).getVersionNo() + 1;
        AiAgentVersionEntity version = new AiAgentVersionEntity();
        version.setAgentId(agentId);
        version.setVersionNo(nextNo);
        version.setVersionName("回滚自 v" + target.getVersionNo());
        version.setStatus(AiConstants.VersionStatus.DRAFT);
        version.setProviderRouteId(target.getProviderRouteId());
        version.setSystemPromptVersionId(target.getSystemPromptVersionId());
        version.setClientTypesSnapshot(agent.getClientTypes());
        version.setEntryConfigSnapshot(agent.getEntryConfig());
        version.setContextPolicyJson(target.getContextPolicyJson());
        version.setResponseSchemaJson(target.getResponseSchemaJson());
        version.setToolPolicyJson(target.getToolPolicyJson());
        version.setKnowledgePolicyJson(target.getKnowledgePolicyJson());
        version.setModelParametersJson(target.getModelParametersJson());
        version.setSafetyPolicyJson(target.getSafetyPolicyJson());
        version.setQuotaOverrideJson(target.getQuotaOverrideJson());
        version.setRollbackFromVersionId(target.getId());
        version.setCreatedBy(operatorId);
        version.setUpdatedBy(operatorId);
        versionMapper.insert(version);
        copyRuntimeBindingsFrom(target.getId(), version.getId());
        return publish(version.getId(), operatorId);
    }

    private void lockRoute(Long routeId) {
        if (routeId != null && versionMapper.lockRoute(routeId) == null) {
            throw new BusinessException("AI007", "模型路由不存在");
        }
    }

    /**
     * 从指定版本复制运行时绑定（工具/知识源）到新版本，草稿创建与回滚共用。
     *
     * <p>绑定挂在版本上（{@code ai_agent_tool}/{@code ai_agent_knowledge} 按 versionId 存），
     * 而新建草稿/回滚只复制版本字段——否则每次发新版本都会悄悄丢掉全部工具与知识源，
     * 线上已踩坑（管理助手 v2 发布后 0 工具绑定）。以来源版本为模板，管理员在新草稿里
     * 仍可自由增删，这里只保证「不丢」。</p>
     *
     * <p>直接用 Mapper 而不是 AiToolService/AiKnowledgeService：后者经工具 Provider
     * 与 AiOverviewService 反向依赖本服务，构造器注入会成环。</p>
     */
    private void copyRuntimeBindingsFrom(Long sourceVersionId, Long targetVersionId) {
        List<AiAgentToolEntity> toolBindings = agentToolMapper.selectList(new LambdaQueryWrapper<AiAgentToolEntity>()
                .eq(AiAgentToolEntity::getAgentVersionId, sourceVersionId)
                .orderByAsc(AiAgentToolEntity::getSort));
        int toolCount = 0;
        for (AiAgentToolEntity src : toolBindings) {
            AiAgentToolEntity copy = new AiAgentToolEntity();
            copy.setAgentVersionId(targetVersionId);
            copy.setToolId(src.getToolId());
            copy.setEnabled(Boolean.TRUE.equals(src.getEnabled()));
            copy.setSort(src.getSort());
            copy.setRiskOverride(src.getRiskOverride());
            copy.setApprovalPolicy(src.getApprovalPolicy());
            copy.setArgumentsSchemaOverride(src.getArgumentsSchemaOverride());
            copy.setResultSchemaOverride(src.getResultSchemaOverride());
            agentToolMapper.insert(copy);
            toolCount++;
        }
        List<AiAgentKnowledgeEntity> knowledgeBindings =
                agentKnowledgeMapper.selectList(new LambdaQueryWrapper<AiAgentKnowledgeEntity>()
                        .eq(AiAgentKnowledgeEntity::getAgentVersionId, sourceVersionId)
                        .orderByAsc(AiAgentKnowledgeEntity::getPriority));
        int knowledgeCount = 0;
        for (AiAgentKnowledgeEntity src : knowledgeBindings) {
            AiAgentKnowledgeEntity copy = new AiAgentKnowledgeEntity();
            copy.setAgentVersionId(targetVersionId);
            copy.setKnowledgeSourceId(src.getKnowledgeSourceId());
            copy.setEnabled(Boolean.TRUE.equals(src.getEnabled()));
            copy.setPriority(src.getPriority());
            copy.setRetrievalTopK(src.getRetrievalTopK());
            copy.setMinScore(src.getMinScore());
            copy.setCitationPolicy(src.getCitationPolicy());
            copy.setFilterPolicyJson(src.getFilterPolicyJson());
            agentKnowledgeMapper.insert(copy);
            knowledgeCount++;
        }
        if (toolCount > 0 || knowledgeCount > 0) {
            log.info(
                    "[AI] 复制运行时绑定 version {} -> {}（工具 {} 个，知识源 {} 个）",
                    sourceVersionId,
                    targetVersionId,
                    toolCount,
                    knowledgeCount);
        }
    }

    /** 客户端列表规范化（去重、大写、过滤非法值） */
    public List<String> normalizeClients(List<String> clientTypes) {
        List<String> result = new ArrayList<>();
        if (clientTypes == null) {
            return result;
        }
        for (String raw : clientTypes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (AiConstants.ClientType.all().contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }
}
