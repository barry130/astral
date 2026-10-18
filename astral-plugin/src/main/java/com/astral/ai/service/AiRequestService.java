package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.mapper.AiRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 请求生命周期服务。
 *
 * <p>一次请求就是一次完整的模型调用链路记录。请求创建时把**当次生效的全部配置**
 * 快照进 {@code ai_request}（设计文档 §4.8）：提供方、模型、Prompt 版本、工具策略、
 * 上下文策略、响应 Schema、安全策略、额度策略、知识库策略。这样即使智能体随后
 * 发布了新版本，历史请求仍能完整复现与审计。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRequestService {

    /** 请求列表默认页大小 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 请求列表页大小上限 */
    public static final int MAX_PAGE_SIZE = 200;

    private final AiRequestMapper requestMapper;

    // ==================== 创建 ====================

    /**
     * 创建一次请求并落快照。
     *
     * @param conversationId 会话 ID
     * @param agent          智能体
     * @param version        绑定的版本
     * @param userId         用户
     * @param clientType     客户端类型
     * @param idempotencyKey 幂等键（可空）
     * @param inputSnapshot  输入快照 JSON
     * @param contextSnapshot 上下文快照 JSON
     * @return 已落库的请求
     */
    @Transactional(rollbackFor = Exception.class)
    public AiRequestEntity create(Long conversationId, AiAgentEntity agent, AiAgentVersionEntity version,
                                  Long userId, String clientType, String idempotencyKey,
                                  String inputSnapshot, String contextSnapshot) {
        AiRequestEntity entity = new AiRequestEntity();
        entity.setConversationId(conversationId);
        entity.setAgentId(agent.getId());
        entity.setAgentVersionId(version.getId());
        entity.setUserId(userId);
        entity.setClientType(clientType);
        entity.setStatus(AiConstants.RequestStatus.QUEUED);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setProviderId(null);
        entity.setModelId(null);
        entity.setPromptVersionId(version.getSystemPromptVersionId());
        entity.setInputSnapshot(inputSnapshot);
        entity.setContextSnapshot(contextSnapshot);
        entity.setConfigurationSnapshot(configurationSnapshot(version));
        entity.setKnowledgeSnapshot(knowledgeSnapshot(version));
        entity.setToolPolicySnapshot(emptyToNull(version.getToolPolicyJson()));
        entity.setContextPolicySnapshot(emptyToNull(version.getContextPolicyJson()));
        entity.setResponseSchemaSnapshot(emptyToNull(version.getResponseSchemaJson()));
        entity.setSafetyPolicySnapshot(emptyToNull(version.getSafetyPolicyJson()));
        entity.setQuotaPolicySnapshot(emptyToNull(version.getQuotaOverrideJson()));
        requestMapper.insert(entity);
        return entity;
    }

    /** 幂等查找：同一用户 + 智能体 + 幂等键只允许一次请求 */
    public AiRequestEntity findByIdempotency(Long userId, Long agentId, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        List<AiRequestEntity> rows = requestMapper.selectList(new LambdaQueryWrapper<AiRequestEntity>()
                .eq(AiRequestEntity::getUserId, userId)
                .eq(AiRequestEntity::getAgentId, agentId)
                .eq(AiRequestEntity::getIdempotencyKey, key.trim())
                .orderByDesc(AiRequestEntity::getId)
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 查询 ====================

    public AiRequestEntity find(Long id) {
        return id == null ? null : requestMapper.selectById(id);
    }

    /** 取请求并校验归属；不存在或不属于该用户抛 AI014 */
    public AiRequestEntity requireOwned(Long id, Long userId) {
        AiRequestEntity entity = find(id);
        if (entity == null || (userId != null && !userId.equals(entity.getUserId()))) {
            throw new AiException("AI014");
        }
        return entity;
    }

    public AiRequestEntity require(Long id) {
        AiRequestEntity entity = find(id);
        if (entity == null) {
            throw new AiException("AI014");
        }
        return entity;
    }

    /** 管理端分页查询 */
    public List<AiRequestEntity> search(String status, Long agentId, Long userId, String clientType,
                                        LocalDateTime from, LocalDateTime to, int pageNum, int pageSize) {
        int size = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        int num = Math.max(1, pageNum);
        return requestMapper.selectList(new LambdaQueryWrapper<AiRequestEntity>()
                .eq(status != null && !status.isBlank(), AiRequestEntity::getStatus, status)
                .eq(agentId != null, AiRequestEntity::getAgentId, agentId)
                .eq(userId != null, AiRequestEntity::getUserId, userId)
                .eq(clientType != null && !clientType.isBlank(), AiRequestEntity::getClientType, clientType)
                .ge(from != null, AiRequestEntity::getCreateTime, from)
                .le(to != null, AiRequestEntity::getCreateTime, to)
                .orderByDesc(AiRequestEntity::getId)
                .last("limit " + size + " offset " + ((long) (num - 1) * size)));
    }

    /** 统计满足条件的请求数 */
    public long count(String status, Long agentId, Long userId, String clientType,
                      LocalDateTime from, LocalDateTime to) {
        Long total = requestMapper.selectCount(new LambdaQueryWrapper<AiRequestEntity>()
                .eq(status != null && !status.isBlank(), AiRequestEntity::getStatus, status)
                .eq(agentId != null, AiRequestEntity::getAgentId, agentId)
                .eq(userId != null, AiRequestEntity::getUserId, userId)
                .eq(clientType != null && !clientType.isBlank(), AiRequestEntity::getClientType, clientType)
                .ge(from != null, AiRequestEntity::getCreateTime, from)
                .le(to != null, AiRequestEntity::getCreateTime, to));
        return total == null ? 0L : total;
    }

    /** 请求详情所需的全部配置快照 */
    public String configurationSnapshot(AiAgentVersionEntity version) {
        var node = AiJson.mapper().createObjectNode();
        node.put("agentVersionId", version.getId());
        node.put("agentVersionNo", version.getVersionNo());
        node.put("providerRouteId", version.getProviderRouteId());
        node.put("promptVersionId", version.getSystemPromptVersionId());
        node.put("responseSchemaJson", emptyToNull(version.getResponseSchemaJson()));
        node.put("modelParametersJson", emptyToNull(version.getModelParametersJson()));
        node.put("safetyPolicyJson", emptyToNull(version.getSafetyPolicyJson()));
        node.put("quotaOverrideJson", emptyToNull(version.getQuotaOverrideJson()));
        node.put("clientTypesSnapshot", emptyToNull(version.getClientTypesSnapshot()));
        node.put("entryConfigSnapshot", emptyToNull(version.getEntryConfigSnapshot()));
        return AiJson.write(node);
    }

    /** 知识库策略快照（含绑定的知识源 ID 列表） */
    public String knowledgeSnapshot(AiAgentVersionEntity version) {
        var node = AiJson.mapper().createObjectNode();
        node.put("knowledgePolicyJson", emptyToNull(version.getKnowledgePolicyJson()));
        return AiJson.write(node);
    }

    // ==================== 状态流转 ====================

    /** 置为运行中并记录开始时间与最终选定的提供方/模型 */
    public void markRunning(Long requestId, Long providerId, Long modelId) {
        AiRequestEntity entity = require(requestId);
        entity.setStatus(AiConstants.RequestStatus.RUNNING);
        entity.setProviderId(providerId);
        entity.setModelId(modelId);
        if (entity.getStartedAt() == null) {
            entity.setStartedAt(LocalDateTime.now());
        }
        requestMapper.updateById(entity);
    }

    /** 置为等待工具/等待确认 */
    public void markWaiting(Long requestId, boolean approval) {
        AiRequestEntity entity = require(requestId);
        entity.setStatus(approval ? AiConstants.RequestStatus.WAITING_APPROVAL
                : AiConstants.RequestStatus.WAITING_TOOL);
        requestMapper.updateById(entity);
    }

    /** 记录首个 token 到达时间（用于计算首字延迟） */
    public void markFirstToken(Long requestId) {
        AiRequestEntity entity = find(requestId);
        if (entity == null || entity.getFirstTokenAt() != null) {
            return;
        }
        entity.setFirstTokenAt(LocalDateTime.now());
        requestMapper.updateById(entity);
    }

    /** 成功结束 */
    public void markSucceeded(Long requestId, int tokenIn, int tokenOut) {
        AiRequestEntity entity = require(requestId);
        entity.setStatus(AiConstants.RequestStatus.SUCCEEDED);
        entity.setTokenIn(tokenIn);
        entity.setTokenOut(tokenOut);
        entity.setFinishedAt(LocalDateTime.now());
        entity.setErrorCode(null);
        entity.setErrorSummary(null);
        requestMapper.updateById(entity);
    }

    /** 失败结束 */
    public void markFailed(Long requestId, String errorCode, String errorSummary, int tokenIn, int tokenOut) {
        AiRequestEntity entity = find(requestId);
        if (entity == null) {
            return;
        }
        if (AiConstants.RequestStatus.CANCELLED.equals(entity.getStatus())) {
            // 已取消的请求不再被失败覆盖，保证取消语义
            return;
        }
        entity.setStatus(AiConstants.RequestStatus.FAILED);
        entity.setErrorCode(errorCode);
        entity.setErrorSummary(AiJson.truncate(errorSummary, 2000));
        entity.setTokenIn(tokenIn);
        entity.setTokenOut(tokenOut);
        entity.setFinishedAt(LocalDateTime.now());
        requestMapper.updateById(entity);
    }

    /** 取消（仅非终态可取消） */
    @Transactional(rollbackFor = Exception.class)
    public AiRequestEntity cancel(Long requestId, Long userId, String reason, boolean admin) {
        AiRequestEntity entity = admin ? require(requestId) : requireOwned(requestId, userId);
        if (AiConstants.RequestStatus.isTerminal(entity.getStatus())) {
            // 已结束：不报错，直接返回当前状态，保证客户端重试幂等
            return entity;
        }
        entity.setStatus(AiConstants.RequestStatus.CANCELLED);
        entity.setCancelReason(AiJson.truncate(reason, 500));
        entity.setFinishedAt(LocalDateTime.now());
        requestMapper.updateById(entity);
        return entity;
    }

    /** 是否为可取消状态 */
    public boolean cancellable(AiRequestEntity entity) {
        return entity != null && !AiConstants.RequestStatus.isTerminal(entity.getStatus());
    }

    /** 统计指定时间之后的请求数 */
    public long countSince(LocalDateTime since, String status) {
        Long total = requestMapper.selectCount(new LambdaQueryWrapper<AiRequestEntity>()
                .ge(since != null, AiRequestEntity::getCreateTime, since)
                .eq(status != null && !status.isBlank(), AiRequestEntity::getStatus, status));
        return total == null ? 0L : total;
    }

    /** 正在运行（未终态）的请求数 */
    public long countRunning() {
        Long total = requestMapper.selectCount(new LambdaQueryWrapper<AiRequestEntity>()
                .in(AiRequestEntity::getStatus, AiConstants.RequestStatus.RUNNING,
                        AiConstants.RequestStatus.QUEUED,
                        AiConstants.RequestStatus.WAITING_TOOL,
                        AiConstants.RequestStatus.WAITING_APPROVAL));
        return total == null ? 0L : total;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
