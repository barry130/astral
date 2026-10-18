package com.astral.ai.controller;

import com.astral.ai.AiPlugin;
import com.astral.ai.common.AiCaller;
import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAppDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiConversationEntity;
import com.astral.ai.entity.AiMessageEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.entity.AiRequestEventEntity;
import com.astral.ai.service.AiAccessService;
import com.astral.ai.service.AiAgentService;
import com.astral.ai.service.AiChatOrchestrator;
import com.astral.ai.service.AiConversationService;
import com.astral.ai.service.AiEventService;
import com.astral.ai.service.AiFeedbackService;
import com.astral.ai.service.AiKnowledgeService;
import com.astral.ai.service.AiRequestService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理端 AI 使用接口：管理助手（{@code admin_assistant}）与独立答疑（{@code admin_qa}）。
 *
 * <p>两个助手共用同一套会话/消息/事件/动作协议，区别只来自后台为各自配置的智能体版本
 * （提示词、模型、工具、知识源、额度）。因此这里<b>不按 agentCode 分支写逻辑</b>，
 * 只固定 {@code clientType = ASTRAL_FRONT}，其余全部由 {@link AiAccessService} 判定。</p>
 *
 * <p>权限上先走智能体自身的 {@code permission_code}（AI004），再叠加控制器上的
 * {@code admin:ai:assistant:use} / {@code admin:ai:qa:use}，两道都不能省。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai")
@ConditionalOnProperty(name = "astral.plugins.ai.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AiAdminChatController {

    /** SSE 轮询间隔 */
    private static final long SSE_POLL_MILLIS = 300L;

    /** SSE 单连接最长存活时间（毫秒） */
    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    private final AiAccessService accessService;
    private final AiAgentService agentService;
    private final AiConversationService conversationService;
    private final AiRequestService requestService;
    private final AiEventService eventService;
    private final AiFeedbackService feedbackService;
    private final AiKnowledgeService knowledgeService;
    private final AiChatOrchestrator orchestrator;
    private final AiProperties properties;

    /** SSE 推送线程池：与编排线程池隔离，避免长连接占用模型调用线程 */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "ai-admin-sse");
        thread.setDaemon(true);
        return thread;
    });

    // ==================== 智能体 ====================

    /** 管理端可用智能体列表（管理助手与答疑都在这里，一次拉全） */
    @GetMapping("/assistants")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "查看可用智能体")
    public Result<List<AiAppDtos.AgentCard>> assistants(HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        List<AiAppDtos.AgentCard> cards = new ArrayList<>();
        for (AiAgentEntity agent : agentService.list()) {
            if (agent.getPublishedVersionId() == null
                    || AiConstants.AgentStatus.DISABLED.equals(agent.getStatus())) {
                continue;
            }
            if (!accessService.supportsClient(agent, AiConstants.ClientType.ASTRAL_FRONT)) {
                continue;
            }
            if (!accessService.permitted(agent, AiConstants.ClientType.ASTRAL_FRONT, userId)) {
                continue;
            }
            cards.add(new AiAppDtos.AgentCard(agent.getAgentCode(), agent.getName(), agent.getAgentType(),
                    agent.getDescription(), agent.getIcon(), agent.getEntryConfig(),
                    capabilitiesOf(agent)));
        }
        return Result.success(cards);
    }

    @GetMapping("/capabilities")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "查看智能体能力")
    public Result<AiAppDtos.Capabilities> capabilities(@RequestParam String agentCode,
                                                       HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        AiAgentEntity agent = accessService.requireForClient(agentCode,
                AiConstants.ClientType.ASTRAL_FRONT, userId);
        requireUsePermission(agent);
        return Result.success(capabilitiesOf(agent));
    }

    // ==================== 会话 ====================

    @GetMapping("/conversations")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "查看会话列表")
    public Result<List<AiAppDtos.ConversationVo>> conversations(@RequestParam String agentCode,
                                                                HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        AiAgentEntity agent = accessService.requireForClient(agentCode,
                AiConstants.ClientType.ASTRAL_FRONT, userId);
        requireUsePermission(agent);
        List<AiAppDtos.ConversationVo> list = new ArrayList<>();
        for (AiConversationEntity entity
                : conversationService.list(userId, agent.getId(), AiConstants.ClientType.ASTRAL_FRONT)) {
            list.add(toVo(entity, agent));
        }
        return Result.success(list);
    }

    @PostMapping("/conversations")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "新建会话")
    public Result<AiAppDtos.ConversationVo> createConversation(
            @Valid @RequestBody AiAppDtos.ConversationCreateReq req,
            HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        AiAgentEntity agent = accessService.requireForClient(req.agentCode(),
                AiConstants.ClientType.ASTRAL_FRONT, userId);
        requireUsePermission(agent);
        return Result.success("创建成功", toVo(conversationService.create(userId, agent,
                AiConstants.ClientType.ASTRAL_FRONT, req.title()), agent));
    }

    @GetMapping("/conversations/{id}")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "查看会话详情")
    public Result<AiAppDtos.ConversationVo> conversation(@PathVariable Long id, HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        AiConversationEntity entity = conversationService.requireOwned(id, userId);
        return Result.success(toVo(entity, agentService.findById(entity.getAgentId())));
    }

    @PutMapping("/conversations/{id}")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "重命名会话")
    public Result<AiAppDtos.ConversationVo> renameConversation(@PathVariable Long id,
                                                               @Valid @RequestBody AiAppDtos.ConversationRenameReq req,
                                                               HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        AiConversationEntity entity = conversationService.rename(id, userId, req.title());
        return Result.success("已重命名", toVo(entity, agentService.findById(entity.getAgentId())));
    }

    @DeleteMapping("/conversations/{id}")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "删除会话")
    public Result<Void> deleteConversation(@PathVariable Long id, HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        conversationService.delete(id, userId);
        return Result.success("已删除", null);
    }

    // ==================== 消息 ====================

    @GetMapping("/conversations/{id}/messages")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "查看会话消息")
    public Result<AiAppDtos.MessagePage> messages(@PathVariable Long id,
                                                  HttpServletRequest request,
                                                  @RequestParam(required = false) Long beforeId,
                                                  @RequestParam(required = false) Integer limit) {
        Long userId = AiCaller.requireUserId(request);
        conversationService.requireOwned(id, userId);
        int size = limit == null ? AiConversationService.DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(AiConversationService.MAX_PAGE_SIZE, limit));
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
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "发送消息")
    public Result<AiAppDtos.SendMessageResp> send(@PathVariable Long id,
                                                  @Valid @RequestBody AiAppDtos.SendMessageReq req,
                                                  HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        AiConversationEntity conversation = conversationService.requireOwned(id, userId);
        AiAgentEntity agent = agentService.require(conversation.getAgentId());
        if (!accessService.supportsClient(agent, AiConstants.ClientType.ASTRAL_FRONT)) {
            throw new AiException("AI003", agent.getName());
        }
        if (!accessService.permitted(agent, AiConstants.ClientType.ASTRAL_FRONT, userId)) {
            throw new AiException("AI004", agent.getName());
        }
        requireUsePermission(agent);
        AiAgentVersionEntity version = accessService.resolveVersion(agent, conversation.getAgentVersionId());
        AiChatOrchestrator.Start started = orchestrator.start(agent, version, conversation, userId,
                AiConstants.ClientType.ASTRAL_FRONT, req.content(), req.context(), req.idempotencyKey());
        return Result.success(new AiAppDtos.SendMessageResp(started.requestId(), started.userMessageId(),
                started.assistantMessageId(), started.status(), started.stream()));
    }

    // ==================== 事件 ====================

    /** 轮询式事件拉取，与 App 端同构（SSE 不可用时前端可退化为轮询） */
    @GetMapping("/requests/{id}/events")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "拉取请求事件")
    public Result<AiAppDtos.EventBatch> events(@PathVariable Long id,
                                               HttpServletRequest request,
                                               @RequestParam(required = false) Long after,
                                               @RequestParam(required = false) Integer limit) {
        Long userId = AiCaller.requireUserId(request);
        AiRequestEntity entity = requestService.requireOwned(id, userId);
        return Result.success(batch(entity, after, limit));
    }

    /**
     * Server-Sent Events 推送。
     *
     * <p>实现方式是服务端按 sequence 增量读库后下发，而不是把模型连接直接透传：
     * 这样断线重连只要带上 {@code Last-Event-ID} 就能续传，多端也能同时观看同一请求。</p>
     */
    @GetMapping(value = "/requests/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "订阅请求流")
    public SseEmitter stream(@PathVariable Long id,
                             HttpServletRequest request,
                             @RequestParam(required = false) Long after,
                             @org.springframework.web.bind.annotation.RequestHeader(
                                     value = "Last-Event-ID", required = false) String lastEventId) {
        Long userId = AiCaller.requireUserId(request);
        AiRequestEntity entity = requestService.requireOwned(id, userId);
        long start = after != null ? after : parseLong(lastEventId, 0L);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));
        sseExecutor.submit(() -> pump(id, start, emitter, closed));
        return emitter;
    }

    @PostMapping("/requests/{id}/cancel")
    @RequiresPermission(value = "admin:ai:request:cancel", name = "AI 请求取消", description = "取消进行中的请求")
    public Result<Map<String, Object>> cancel(@PathVariable Long id,
                                              @RequestBody(required = false) AiAppDtos.CancelReq req,
                                              HttpServletRequest request) {
        Long userId = AiCaller.requireUserId(request);
        String reason = req == null ? null : req.reason();
        AiRequestEntity entity = requestService.cancel(id, userId, reason, true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", id);
        data.put("status", entity.getStatus());
        return Result.success("已取消", data);
    }

    // ==================== 反馈与动作 ====================

    @PostMapping("/messages/{id}/feedback")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "提交回答反馈")
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

    /** 动作回执：管理端只接受"打开页面/填充筛选"这类无副作用动作 */
    @PostMapping("/actions/execute")
    @RequiresPermission(value = "admin:ai:config:view", name = "AI 助手使用", description = "执行助手动作")
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

    private void pump(Long requestId, long start, SseEmitter emitter, AtomicBoolean closed) {
        long cursor = start;
        long deadline = System.currentTimeMillis() + SSE_TIMEOUT_MILLIS - 2_000L;
        try {
            while (!closed.get() && System.currentTimeMillis() < deadline) {
                int limit = Math.max(1, Math.min(AiEventService.MAX_BATCH, properties.getEventBatchSize()));
                List<AiRequestEventEntity> rows = eventService.read(requestId, cursor, limit);
                for (AiRequestEventEntity row : rows) {
                    if (closed.get()) {
                        return;
                    }
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(row.getSequenceNo()))
                            .name(row.getEventType())
                            .data(row.getDataJson() == null ? "{}" : row.getDataJson(),
                                    MediaType.APPLICATION_JSON));
                    cursor = row.getSequenceNo();
                }
                AiRequestEntity entity = requestService.find(requestId);
                if (entity == null || AiConstants.RequestStatus.isTerminal(entity.getStatus())) {
                    if (rows.isEmpty()) {
                        emitter.send(SseEmitter.event().name("stream.end")
                                .data("{\"status\":\"" + (entity == null ? "UNKNOWN" : entity.getStatus()) + "\"}",
                                        MediaType.APPLICATION_JSON));
                        emitter.complete();
                        return;
                    }
                    continue;
                }
                Thread.sleep(SSE_POLL_MILLIS);
            }
            emitter.complete();
        } catch (IOException e) {
            log.debug("[AI] SSE 客户端断开 requestId={}", requestId);
            emitter.completeWithError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException e) {
            log.warn("[AI] SSE 推送异常 requestId={} err={}", requestId, e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private AiAppDtos.EventBatch batch(AiRequestEntity entity, Long after, Integer limit) {
        List<AiAppDtos.EventVo> events = new ArrayList<>();
        for (AiRequestEventEntity row : eventService.read(entity.getId(), after, limit)) {
            events.add(new AiAppDtos.EventVo(entity.getId(), row.getSequenceNo(), row.getEventType(),
                    toMap(row.getDataJson()), row.getCreateTime()));
        }
        long next = after == null ? 0L : after;
        if (!events.isEmpty()) {
            next = events.get(events.size() - 1).sequence();
        }
        // 同 App 端：终态 + 游标追平才算流结束，否则客户端会漏掉 message.completed
        boolean terminal = AiConstants.RequestStatus.isTerminal(entity.getStatus())
                && eventService.drained(entity.getId(), next);
        return new AiAppDtos.EventBatch(entity.getId(), entity.getStatus(), events, next, terminal);
    }

    /** 管理侧的第二道权限：答疑必须持有 qa:use，其余管理助手必须持有 assistant:use */
    private void requireUsePermission(AiAgentEntity agent) {
        if (AiConstants.AgentType.QA.equals(agent.getAgentType())) {
            accessService.requireExplicit(AiPlugin.Perms.QA_USE);
        } else {
            accessService.requireExplicit(AiPlugin.Perms.ASSISTANT_USE);
        }
    }


    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private AiAppDtos.Capabilities capabilitiesOf(AiAgentEntity agent) {
        boolean citationRequired = agent.getPublishedVersionId() != null
                && knowledgeService.hasReadyKnowledge(agent.getPublishedVersionId());
        return new AiAppDtos.Capabilities(
                Boolean.TRUE.equals(agent.getConversationEnabled()),
                Boolean.TRUE.equals(agent.getFeedbackEnabled()),
                Boolean.TRUE.equals(agent.getAllowFileContext()),
                Boolean.TRUE.equals(agent.getAllowUserContext()),
                citationRequired,
                AiConstants.AgentType.MUSIC.equals(agent.getAgentType()),
                AiConstants.ClientType.ASTRAL_FRONT);
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
                citations.add(new AiAppDtos.Citation(longOrNull(node, "sourceId"),
                        AiJson.text(node, "sourceType", null), AiJson.text(node, "title", null),
                        AiJson.text(node, "path", null), AiJson.text(node, "section", null),
                        AiJson.text(node, "version", null), AiJson.text(node, "matchedText", null)));
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
        for (String name : node.propertyNames()) {
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
