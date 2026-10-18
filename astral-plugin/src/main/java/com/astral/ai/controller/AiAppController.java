package com.astral.ai.controller;

import com.astral.ai.common.AiCaller;
import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.dto.AiAppDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiConversationEntity;
import com.astral.ai.entity.AiMessageEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.service.AiAccessService;
import com.astral.ai.service.AiAgentService;
import com.astral.ai.service.AiChatOrchestrator;
import com.astral.ai.service.AiConversationService;
import com.astral.ai.service.AiEventService;
import com.astral.ai.service.AiFeedbackService;
import com.astral.ai.service.AiKnowledgeService;
import com.astral.ai.service.AiRequestService;
import com.astral.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App 端 AI 接口，{@code qt-uniappx} 与 {@code qt-pc} 共用。
 *
 * <p>两端只有 {@code clientType} 与本地适配器不同，会话、消息、事件、动作与错误结构完全一致。
 * 客户端不能提交 Provider、模型、Prompt 或工具列表——这些只能来自后台已发布的智能体版本。</p>
 */
@RestController
@RequestMapping("/api/v1/app/ai")
@RequiredArgsConstructor
public class AiAppController {

    private final AiAccessService accessService;
    private final AiAgentService agentService;
    private final AiConversationService conversationService;
    private final AiRequestService requestService;
    private final AiEventService eventService;
    private final AiFeedbackService feedbackService;
    private final AiKnowledgeService knowledgeService;
    private final AiChatOrchestrator orchestrator;

    // ==================== 智能体 ====================

    @GetMapping("/agents")
    public Result<List<AiAppDtos.AgentCard>> agents(HttpServletRequest request,
                                                    @RequestParam(required = false) String clientType) {
        Long userId = AiCaller.requireUserId(request);
        String client = resolveClient(clientType, request);
        List<AiAppDtos.AgentCard> cards = new ArrayList<>();
        for (AiAgentEntity agent : agentService.list()) {
            if (agent.getPublishedVersionId() == null
                    || AiConstants.AgentStatus.DISABLED.equals(agent.getStatus())) {
                continue;
            }
            if (!accessService.supportsClient(agent, client)) {
                continue;
            }
            if (!accessService.permitted(agent, client, userId)) {
                continue;
            }
            cards.add(new AiAppDtos.AgentCard(agent.getAgentCode(), agent.getName(), agent.getAgentType(),
                    agent.getDescription(), agent.getIcon(), agent.getEntryConfig(),
                    capabilitiesOf(agent, client)));
        }
        return Result.success(cards);
    }

    @GetMapping("/capabilities")
    public Result<AiAppDtos.Capabilities> capabilities(@RequestParam String agentCode,
                                                       HttpServletRequest request,
                                                       @RequestParam(required = false) String clientType) {
        Long userId = AiCaller.requireUserId(request);
        String client = resolveClient(clientType, request);
        AiAgentEntity agent = accessService.requireForClient(agentCode, client, userId);
        return Result.success(capabilitiesOf(agent, client));
    }

    // ==================== 会话 ====================

    @GetMapping("/conversations")
    public Result<List<AiAppDtos.ConversationVo>> conversations(@RequestParam String agentCode,
                                                                HttpServletRequest request,
                                                                @RequestParam(required = false) String clientType) {
        Long userId = AiCaller.requireUserId(request);
        String client = resolveClient(clientType, request);
        AiAgentEntity agent = accessService.requireForClient(agentCode, client, userId);
        List<AiAppDtos.ConversationVo> list = new ArrayList<>();
        for (AiConversationEntity entity : conversationService.list(userId, agent.getId(), client)) {
            list.add(toVo(entity, agent));
        }
        return Result.success(list);
    }

    @PostMapping("/conversations")
    public Result<AiAppDtos.ConversationVo> createConversation(
            @Valid @RequestBody AiAppDtos.ConversationCreateReq req,
            HttpServletRequest request,
            @RequestParam(required = false) String clientType) {
        Long userId = AiCaller.requireUserId(request);
        String client = resolveClient(clientType, request);
        AiAgentEntity agent = accessService.requireForClient(req.agentCode(), client, userId);
        return Result.success("创建成功",
                toVo(conversationService.create(userId, agent, client, req.title()), agent));
    }

    @GetMapping("/conversations/{id}")
    public Result<AiAppDtos.ConversationVo> conversation(@PathVariable Long id, HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        AiConversationEntity entity = conversationService.requireOwned(id, userId);
        return Result.success(toVo(entity, agentService.findById(entity.getAgentId())));
    }

    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable Long id, HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        conversationService.delete(id, userId);
        return Result.success("已删除", null);
    }

    // ==================== 消息 ====================

    @GetMapping("/conversations/{id}/messages")
    public Result<AiAppDtos.MessagePage> messages(@PathVariable Long id,
                                                  HttpServletRequest request,
                                                  @RequestParam(required = false) Long beforeId,
                                                  @RequestParam(required = false) Integer limit) {
        Long userId = AiCaller.requireUserId(request);
        conversationService.requireOwned(id, userId);
        int size = limit == null ? AiConversationService.DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(AiConversationService.MAX_PAGE_SIZE, limit));
        // 多取一条用于判断 hasMore
        List<AiMessageEntity> rows = conversationService.messages(id, beforeId, size + 1);
        boolean hasMore = rows.size() > size;
        List<AiMessageEntity> page = hasMore ? rows.subList(0, size) : rows;
        List<AiAppDtos.MessageVo> items = new ArrayList<>();
        for (AiMessageEntity row : page) {
            items.add(toVo(row));
        }
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getId();
        return Result.success(new AiAppDtos.MessagePage(items, nextCursor, hasMore));
    }

    @PostMapping("/conversations/{id}/messages")
    public Result<AiAppDtos.SendMessageResp> send(@PathVariable Long id,
                                                  @Valid @RequestBody AiAppDtos.SendMessageReq req,
                                                  HttpServletRequest request,
                                                  @RequestParam(required = false) String clientType) {
        Long userId = AiCaller.requireUserId(request);
        String client = resolveClient(clientType, request);
        AiConversationEntity conversation = conversationService.requireOwned(id, userId);
        AiAgentEntity agent = agentService.require(conversation.getAgentId());
        if (!accessService.supportsClient(agent, client)) {
            throw new AiException("AI003", agent.getName());
        }
        if (!accessService.permitted(agent, client, userId)) {
            throw new AiException("AI004", agent.getName());
        }
        AiAgentVersionEntity version = accessService.resolveVersion(agent, conversation.getAgentVersionId());
        AiChatOrchestrator.Start started = orchestrator.start(agent, version, conversation, userId, client,
                req.content(), req.context(), req.idempotencyKey());
        return Result.success(new AiAppDtos.SendMessageResp(started.requestId(), started.userMessageId(),
                started.assistantMessageId(), started.status(), started.stream()));
    }

    // ==================== 事件与取消 ====================

    @GetMapping("/requests/{id}/events")
    public Result<AiAppDtos.EventBatch> events(@PathVariable Long id,
                                               HttpServletRequest request,
                                               @RequestParam(required = false) Long after,
                                               @RequestParam(required = false) Integer limit) {
        Long userId = AiCaller.requireUserId(request);
        AiRequestEntity entity = requestService.requireOwned(id, userId);
        List<AiAppDtos.EventVo> events = new ArrayList<>();
        for (var row : eventService.read(id, after, limit)) {
            events.add(new AiAppDtos.EventVo(id, row.getSequenceNo(), row.getEventType(),
                    toMap(row.getDataJson()), row.getCreateTime()));
        }
        long next = after == null ? 0L : after;
        if (!events.isEmpty()) {
            next = events.get(events.size() - 1).sequence();
        }
        return Result.success(new AiAppDtos.EventBatch(id, entity.getStatus(), events, next,
                AiConstants.RequestStatus.isTerminal(entity.getStatus())));
    }

    @PostMapping("/requests/{id}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable Long id,
                                              @RequestBody(required = false) AiAppDtos.CancelReq req,
                                              HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        String reason = req == null ? null : req.reason();
        AiRequestEntity entity = requestService.cancel(id, userId, reason, false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", id);
        data.put("status", entity.getStatus());
        return Result.success("已取消", data);
    }

    // ==================== 反馈与动作 ====================

    @PostMapping("/messages/{id}/feedback")
    public Result<Map<String, Object>> feedback(@PathVariable Long id,
                                                @Valid @RequestBody AiAppDtos.FeedbackReq req,
                                                HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        AiMessageEntity message = feedbackService.requireMessage(id, userId);
        feedbackService.submit(userId, message, req.feedbackType(), req.reasonCode(), req.comment());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", id);
        data.put("feedbackType", req.feedbackType() == null ? null : req.feedbackType().toUpperCase());
        return Result.success("已记录", data);
    }

    /**
     * 动作回执。
     *
     * <p>动作由服务端在助手消息里签发，客户端执行后回传结果；服务端只校验类型白名单与归属，
     * 不接受模型原始文本，也不会据此执行任何副作用操作（播放发生在客户端）。</p>
     */
    @PostMapping("/actions/execute")
    public Result<Map<String, Object>> executeAction(@Valid @RequestBody AiAppDtos.ActionExecuteReq req,
                                                     HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        String type = req.actionType() == null ? "" : req.actionType().trim().toUpperCase();
        if (!AiConstants.ActionType.all().contains(type)) {
            throw new AiException("AI008", type);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionId", req.actionId());
        data.put("actionType", type);
        data.put("accepted", Boolean.TRUE.equals(req.success()));
        data.put("userId", userId);
        return Result.success("已受理", data);
    }

    // ==================== 内部 ====================

    private String resolveClient(String clientType, HttpServletRequest request) {
        String client = clientType;
        if (client == null || client.isBlank()) {
            client = request.getHeader("X-Ai-Client");
        }
        if (client == null || client.isBlank()) {
            client = AiConstants.ClientType.QT_UNIAPPX;
        }
        String normalized = client.trim().toUpperCase(java.util.Locale.ROOT);
        if (!AiConstants.ClientType.all().contains(normalized)) {
            throw new AiException("AI003", client);
        }
        return normalized;
    }

    private AiAppDtos.Capabilities capabilitiesOf(AiAgentEntity agent, String client) {
        boolean citationRequired = agent.getPublishedVersionId() != null
                && knowledgeService.hasReadyKnowledge(agent.getPublishedVersionId());
        return new AiAppDtos.Capabilities(
                Boolean.TRUE.equals(agent.getConversationEnabled()),
                Boolean.TRUE.equals(agent.getFeedbackEnabled()),
                Boolean.TRUE.equals(agent.getAllowFileContext()),
                Boolean.TRUE.equals(agent.getAllowUserContext()),
                citationRequired,
                AiConstants.AgentType.MUSIC.equals(agent.getAgentType()),
                client);
    }

    private AiAppDtos.ConversationVo toVo(AiConversationEntity entity, AiAgentEntity agent) {
        return new AiAppDtos.ConversationVo(entity.getId(),
                agent == null ? null : agent.getAgentCode(),
                agent == null ? null : agent.getName(),
                entity.getTitle(), entity.getStatus(), entity.getMessageCount(), entity.getPinned(),
                entity.getLastMessageTime(), entity.getCreateTime());
    }

    private AiAppDtos.MessageVo toVo(AiMessageEntity entity) {
        List<AiAppDtos.Block> blocks = new ArrayList<>();
        var blocksNode = AiJson.readArray(entity.getBlocksJson());
        if (blocksNode != null) {
            for (var node : blocksNode) {
                blocks.add(new AiAppDtos.Block(AiJson.text(node, "type", "text"),
                        AiJson.text(node, "title", null),
                        toMap(AiJson.write(node.get("data")))));
            }
        }
        List<AiAppDtos.Citation> citations = new ArrayList<>();
        var citationsNode = AiJson.readArray(entity.getCitationsJson());
        if (citationsNode != null) {
            for (var node : citationsNode) {
                citations.add(new AiAppDtos.Citation(
                        longOrNull(node, "sourceId"),
                        AiJson.text(node, "sourceType", null),
                        AiJson.text(node, "title", null),
                        AiJson.text(node, "path", null),
                        AiJson.text(node, "section", null),
                        AiJson.text(node, "version", null),
                        AiJson.text(node, "matchedText", null)));
            }
        }
        return new AiAppDtos.MessageVo(entity.getId(), entity.getConversationId(), entity.getRequestId(),
                entity.getRole(), entity.getContent(), entity.getContentType(), entity.getStatus(),
                blocks, citations, entity.getModelName(), entity.getCreateTime());
    }

    private Map<String, Object> toMap(String json) {
        var node = AiJson.read(json);
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        var names = node.propertyNames();
        for (String name : names) {
            map.put(name, AiJson.mapper().convertValue(node.get(name), Object.class));
        }
        return map;
    }

    private Long longOrNull(tools.jackson.databind.JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.valueOf(value.asString().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
