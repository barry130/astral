package com.astral.ai.controller;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiConversationEntity;
import com.astral.ai.entity.AiMessageEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.entity.AiRequestEventEntity;
import com.astral.ai.entity.AiToolCallEntity;
import com.astral.ai.mapper.AiConversationMapper;
import com.astral.ai.mapper.AiMessageMapper;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.service.AiAgentService;
import com.astral.ai.service.AiEventService;
import com.astral.ai.service.AiRequestService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.common.util.PageQuery;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 后台：请求记录、会话与事件审计。
 *
 * <p>这里刻意把「看得到请求」与「看得到正文」拆成两个权限：{@code admin:ai:request:view}
 * 只能看到状态、Token、耗时等元数据；只有再叠加 {@code admin:ai:conversation:content:view}
 * 才能读到用户输入与模型输出。设计文档 §9.3 要求如此，避免运维排障顺手读走业务内容。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiresPermission(value = "admin:ai:request:view", name = "AI 请求查看", description = "查看 AI 请求记录")
@RequiredArgsConstructor
public class AiAdminRequestController {

    private final AiRequestService requestService;
    private final AiAgentService agentService;
    private final AiEventService eventService;
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiToolCallMapper toolCallMapper;

    // ==================== 请求列表 ====================

    @GetMapping("/requests")
    public Result<Map<String, Object>> requests(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) Long agentId,
                                                @RequestParam(required = false) Long userId,
                                                @RequestParam(required = false) String clientType,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @RequestParam(required = false) Long pageNum,
                                                @RequestParam(required = false) Long pageSize) {
        int num = (int) PageQuery.pageNum(pageNum == null ? 1 : pageNum);
        int size = (int) PageQuery.pageSize(pageSize == null ? PageQuery.DEFAULT_PAGE_SIZE : pageSize);
        String normalizedStatus = normalizeStatus(status);
        String normalizedClient = normalizeClient(clientType);
        LocalDateTime start = parseStart(from);
        LocalDateTime end = parseEnd(to);

        List<AiRequestEntity> rows = requestService.search(normalizedStatus, agentId, userId,
                normalizedClient, start, end, num, size);
        long total = requestService.count(normalizedStatus, agentId, userId, normalizedClient, start, end);

        Map<Long, AiAgentEntity> agents = new HashMap<>();
        List<AiAdminDtos.RequestVo> items = new ArrayList<>();
        for (AiRequestEntity row : rows) {
            AiAgentEntity agent = agents.computeIfAbsent(row.getAgentId(), id -> {
                try {
                    return agentService.require(id);
                } catch (RuntimeException e) {
                    return null;
                }
            });
            items.add(toVo(row, agent));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", total);
        data.put("pageNum", num);
        data.put("pageSize", size);
        return Result.success(data);
    }

    // ==================== 请求详情 ====================

    @GetMapping("/requests/{id}")
    public Result<AiAdminDtos.RequestDetailVo> request(@PathVariable Long id,
                                                       @RequestParam(required = false) Boolean content) {
        AiRequestEntity entity = requestService.require(id);
        AiAgentEntity agent = null;
        try {
            agent = agentService.require(entity.getAgentId());
        } catch (RuntimeException ignored) {
            agent = null;
        }
        List<AiAdminDtos.ToolCallVo> toolCalls = new ArrayList<>();
        for (AiToolCallEntity call : toolCallMapper.selectList(new LambdaQueryWrapper<AiToolCallEntity>()
                .eq(AiToolCallEntity::getRequestId, id)
                .orderByAsc(AiToolCallEntity::getId))) {
            toolCalls.add(new AiAdminDtos.ToolCallVo(call.getId(), call.getRequestId(), call.getToolCode(),
                    call.getToolName(), call.getRiskLevel(), call.getStatus(), call.getArgumentsJson(),
                    call.getResultJson(), call.getErrorMessage(), call.getApprovalStatus(),
                    call.getApprovedBy(), call.getApprovedAt(), call.getStartedAt(), call.getFinishedAt()));
        }
        List<AiAdminDtos.EventVo> events = new ArrayList<>();
        for (AiRequestEventEntity row : eventService.readAll(id, AiEventService.MAX_BATCH)) {
            events.add(new AiAdminDtos.EventVo(row.getSequenceNo(), row.getEventType(),
                    row.getDataJson(), row.getCreateTime()));
        }
        List<AiAdminDtos.MessageVo> messages = new ArrayList<>();
        for (AiMessageEntity row : messageMapper.selectList(new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getRequestId, id)
                .orderByAsc(AiMessageEntity::getId))) {
            messages.add(new AiAdminDtos.MessageVo(row.getId(), row.getConversationId(), row.getRequestId(),
                    row.getRole(), row.getContent(), row.getContentType(), row.getStatus(),
                    row.getBlocksJson(), row.getCitationsJson(), row.getModelName(),
                    row.getInputTokens(), row.getOutputTokens(), row.getCreateTime()));
        }
        return Result.success(new AiAdminDtos.RequestDetailVo(toVo(entity, agent), entity.getInputSnapshot(),
                entity.getContextSnapshot(), entity.getConfigurationSnapshot(), entity.getKnowledgeSnapshot(),
                entity.getToolPolicySnapshot(), entity.getSafetyPolicySnapshot(), toolCalls, events, messages));
    }

    // ==================== 会话审计 ====================

    /**
     * 会话列表。
     *
     * <p>{@code admin:ai:conversation:view} 只给到标题、消息数、最后活跃时间；
     * 正文需要额外的 {@code admin:ai:conversation:content:view}，在下面的消息接口上校验。</p>
     *
     * <p>路径刻意用 {@code /conversation-audits} 而不是 {@code /conversations}：
     * 后者属于「使用侧」接口（{@code AiAdminChatController}，按当前登录人自己的会话返回），
     * 两者权限模型完全不同，同路径会导致 Spring 启动时映射冲突。</p>
     */
    @GetMapping("/conversation-audits")
    @RequiresPermission(value = "admin:ai:conversation:view", name = "AI 会话查看", description = "查看 AI 会话列表")
    public Result<Map<String, Object>> conversations(@RequestParam(required = false) Long agentId,
                                                     @RequestParam(required = false) Long userId,
                                                     @RequestParam(required = false) String clientType,
                                                     @RequestParam(required = false) Long pageNum,
                                                     @RequestParam(required = false) Long pageSize) {
        int num = (int) PageQuery.pageNum(pageNum == null ? 1 : pageNum);
        int size = (int) PageQuery.pageSize(pageSize == null ? PageQuery.DEFAULT_PAGE_SIZE : pageSize);
        LambdaQueryWrapper<AiConversationEntity> wrapper = new LambdaQueryWrapper<AiConversationEntity>()
                .isNull(AiConversationEntity::getDeletedAt)
                .eq(agentId != null, AiConversationEntity::getAgentId, agentId)
                .eq(userId != null, AiConversationEntity::getUserId, userId)
                .eq(clientType != null && !clientType.isBlank(), AiConversationEntity::getClientType,
                        normalizeClient(clientType))
                .orderByDesc(AiConversationEntity::getId)
                .last("limit " + size + " offset " + ((num - 1) * size));
        List<AiConversationEntity> rows = conversationMapper.selectList(wrapper);

        Map<Long, AiAgentEntity> agents = new HashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (AiConversationEntity row : rows) {
            AiAgentEntity agent = agents.computeIfAbsent(row.getAgentId(), id -> {
                try {
                    return agentService.require(id);
                } catch (RuntimeException e) {
                    return null;
                }
            });
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("agentId", row.getAgentId());
            item.put("agentCode", agent == null ? null : agent.getAgentCode());
            item.put("agentName", agent == null ? null : agent.getName());
            item.put("userId", row.getUserId());
            item.put("clientType", row.getClientType());
            item.put("title", row.getTitle());
            item.put("status", row.getStatus());
            item.put("messageCount", row.getMessageCount());
            item.put("pinned", row.getPinned());
            item.put("lastMessageTime", row.getLastMessageTime());
            item.put("createTime", row.getCreateTime());
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("pageNum", num);
        data.put("pageSize", size);
        return Result.success(data);
    }

    @GetMapping("/conversation-audits/{id}/messages")
    @RequiresPermission(value = "admin:ai:conversation:content:view", name = "AI 会话正文查看",
            description = "查看 AI 会话完整内容（敏感操作，需审计）")
    public Result<List<AiAdminDtos.MessageVo>> conversationMessages(@PathVariable Long id,
                                                                   @RequestParam(required = false) Long beforeId,
                                                                   @RequestParam(required = false) Integer limit) {
        AiConversationEntity conversation = conversationMapper.selectById(id);
        if (conversation == null) {
            throw new AiException("AI015", id);
        }
        int size = limit == null ? 30 : Math.max(1, Math.min(200, limit));
        LambdaQueryWrapper<AiMessageEntity> wrapper = new LambdaQueryWrapper<AiMessageEntity>()
                .eq(AiMessageEntity::getConversationId, id)
                .lt(beforeId != null, AiMessageEntity::getId, beforeId)
                .orderByDesc(AiMessageEntity::getId)
                .last("limit " + size);
        List<AiMessageEntity> rows = messageMapper.selectList(wrapper);
        List<AiAdminDtos.MessageVo> items = new ArrayList<>();
        // 倒序取出后正序返回，前端渲染更省事
        for (int i = rows.size() - 1; i >= 0; i--) {
            AiMessageEntity row = rows.get(i);
            items.add(new AiAdminDtos.MessageVo(row.getId(), row.getConversationId(), row.getRequestId(),
                    row.getRole(), row.getContent(), row.getContentType(), row.getStatus(),
                    row.getBlocksJson(), row.getCitationsJson(), row.getModelName(),
                    row.getInputTokens(), row.getOutputTokens(), row.getCreateTime()));
        }
        return Result.success(items);
    }

    // ==================== 内部 ====================

    private AiAdminDtos.RequestVo toVo(AiRequestEntity entity, AiAgentEntity agent) {
        Integer versionNo = null;
        if (entity.getAgentVersionId() != null) {
            try {
                AiAgentVersionEntity version = agentService.requireVersion(entity.getAgentVersionId());
                versionNo = version.getVersionNo();
            } catch (RuntimeException ignored) {
                versionNo = null;
            }
        }
        return new AiAdminDtos.RequestVo(entity.getId(), entity.getConversationId(), entity.getAgentId(),
                agent == null ? null : agent.getName(), entity.getAgentVersionId(), versionNo,
                entity.getUserId(), entity.getClientType(), entity.getStatus(), entity.getErrorCode(),
                entity.getErrorSummary(), entity.getTokenIn(), entity.getTokenOut(), durationMs(entity),
                entity.getStartedAt(), entity.getFinishedAt(), entity.getCreateTime());
    }

    private Long durationMs(AiRequestEntity entity) {
        if (entity.getStartedAt() == null) {
            return null;
        }
        LocalDateTime end = entity.getFinishedAt() == null ? LocalDateTime.now() : entity.getFinishedAt();
        return java.time.Duration.between(entity.getStartedAt(), end).toMillis();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        return AiConstants.RequestStatus.all().contains(value) ? value : null;
    }

    private String normalizeClient(String clientType) {
        if (clientType == null || clientType.isBlank()) {
            return null;
        }
        String value = clientType.trim().toUpperCase();
        return AiConstants.ClientType.all().contains(value) ? value : null;
    }

    private static LocalDateTime parseStart(String value) {
        LocalDate date = parseDate(value);
        return date == null ? null : date.atStartOfDay();
    }

    private static LocalDateTime parseEnd(String value) {
        LocalDate date = parseDate(value);
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
