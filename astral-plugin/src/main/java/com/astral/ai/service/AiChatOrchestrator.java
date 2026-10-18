package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAppDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiConversationEntity;
import com.astral.ai.entity.AiMessageEntity;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.entity.AiPromptVersionEntity;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.entity.AiProviderRouteEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.entity.AiToolCallEntity;
import com.astral.ai.entity.AiUsageEntity;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.provider.AiProvider;
import com.astral.ai.provider.AiProviderDtos.ChatRequest;
import com.astral.ai.provider.AiProviderDtos.ChatResult;
import com.astral.ai.provider.AiProviderDtos.Message;
import com.astral.ai.provider.AiProviderDtos.StreamListener;
import com.astral.ai.provider.AiProviderDtos.ToolCall;
import com.astral.ai.provider.AiProviderDtos.ToolSpec;
import com.astral.ai.provider.AiProviderRegistry;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话编排器：把「请求 → 模型 → 工具 → 落库 → 事件」这条链路串起来。
 *
 * <p>编排遵循设计文档 §8 的约定：</p>
 * <ul>
 *   <li>请求开始即绑定 {@code agent_id + agent_version_id} 并把配置快照落库，后续全部读快照；</li>
 *   <li>所有输出先落 {@code ai_request_event}，客户端按 {@code sequence} 游标拉取；</li>
 *   <li>工具必须来自「代码登记 + 后台绑定」的白名单，高风险工具先挂起等确认；</li>
 *   <li>额度与用量走「预占 → 结算」，异常路径必须释放。</li>
 * </ul>
 *
 * <p>编排本身在独立线程池中执行：接口线程只负责建请求、预占额度并立即返回，
 * 这样管理端 SSE 与客户端轮询都能在毫秒级拿到 {@code requestId}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatOrchestrator {

    /** 一次请求内最多允许多少轮「模型 → 工具 → 模型」 */
    private static final int MAX_TOOL_ROUNDS = 4;

    /** 后台可配置的轮数硬上限，防止填一个离谱的大数把请求拖死 */
    private static final int MAX_TOOL_ROUNDS_LIMIT = 8;

    /** 拼入模型的历史消息上限 */
    private static final int MAX_HISTORY_MESSAGES = 40;
    /** AiProviderDtos.Message 的角色字面量（小写，OpenAI 兼容格式） */
    private static final String ROLE_TOOL = "tool";

    /** 系统提示词里引用的知识条目上限 */
    private static final int MAX_KNOWLEDGE_HITS = 6;

    /** 缺省系统提示词（未配置 Prompt 时兜底，不含任何业务硬编码分支） */
    private static final String FALLBACK_SYSTEM_PROMPT = """
            你是 Astral 平台的智能助手。请使用简体中文回答，保持简洁、准确、可执行。
            当你不确定事实时，明确说明不确定，不要编造数据、引用或接口。
            """;

    private final AiAgentService agentService;
    private final AiRequestService requestService;
    private final AiConversationService conversationService;
    private final AiEventService eventService;
    private final AiQuotaService quotaService;
    private final AiUsageService usageService;
    private final AiProviderService providerService;
    private final AiModelService modelService;
    private final AiRouteService routeService;
    private final AiPromptService promptService;
    private final AiToolService toolService;
    private final AiKnowledgeService knowledgeService;
    private final AiProviderRegistry providerRegistry;
    private final AiToolCallMapper toolCallMapper;
    private final AiProperties properties;

    /** 编排线程池：固定大小，避免一次涌入把数据库连接打满 */
    private final ExecutorService executor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "ai-orchestrator");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ==================== 对外入口 ====================

    /**
     * 发起一次请求。
     *
     * @return 请求 ID / 用户消息 ID / 助手消息 ID（助手消息此时为空壳，内容靠事件推送）
     */
    public Start start(AiAgentEntity agent, AiAgentVersionEntity version, AiConversationEntity conversation,
                       Long userId, String clientType, String content, Map<String, Object> context,
                       String idempotencyKey) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            throw new AiException("AI007", "消息内容不能为空");
        }
        if (trimmed.length() > AiConstants.Runtime.MAX_MESSAGE_CHARS) {
            throw new AiException("AI007", "消息内容超出长度上限");
        }

        // 幂等：同一用户 + 智能体 + key 直接复用旧请求
        AiRequestEntity existing = requestService.findByIdempotency(userId, agent.getId(), idempotencyKey);
        if (existing != null) {
            AiMessageEntity assistant = latestAssistant(existing.getId());
            return new Start(existing.getId(), null, assistant == null ? null : assistant.getId(),
                    existing.getStatus(), false);
        }

        // 用户消息
        AiMessageEntity userMessage = new AiMessageEntity();
        userMessage.setConversationId(conversation.getId());
        userMessage.setRole(AiConstants.MessageRole.USER);
        userMessage.setContent(trimmed);
        userMessage.setStatus(AiConstants.MessageStatus.COMPLETED);
        userMessage.setContentType("TEXT");
        conversationService.append(userMessage);

        String inputSnapshot = snapshotInput(trimmed, context);
        AiRequestEntity request = requestService.create(conversation.getId(), agent, version, userId,
                clientType, idempotencyKey, inputSnapshot, AiJson.write(sanitizeContext(context, version, agent)));
        userMessage.setRequestId(request.getId());
        conversationService.update(userMessage);

        // 助手消息空壳（状态 PENDING，内容随流写入）
        AiMessageEntity assistantMessage = new AiMessageEntity();
        assistantMessage.setConversationId(conversation.getId());
        assistantMessage.setRequestId(request.getId());
        assistantMessage.setParentMessageId(userMessage.getId());
        assistantMessage.setRole(AiConstants.MessageRole.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setStatus(AiConstants.MessageStatus.PENDING);
        assistantMessage.setContentType("TEXT");
        conversationService.append(assistantMessage);

        conversationService.touch(conversation, version.getId(), trimmed);
        eventService.append(request.getId(), AiConstants.EventType.REQUEST_CREATED,
                Map.of("requestId", request.getId(), "agentCode", agent.getAgentCode(),
                        "agentVersionId", version.getId(), "clientType", String.valueOf(clientType)));

        executor.submit(() -> runSafely(request.getId(), assistantMessage.getId()));
        return new Start(request.getId(), userMessage.getId(), assistantMessage.getId(),
                AiConstants.RequestStatus.QUEUED, true);
    }

    /** 用户确认后继续执行挂起的工具 */
    public void resume(Long requestId, Long assistantMessageId) {
        executor.submit(() -> runSafely(requestId, assistantMessageId));
    }

    /** 发起结果 */
    public record Start(Long requestId, Long userMessageId, Long assistantMessageId, String status, boolean stream) {
    }

    // ==================== 编排主流程 ====================

    private void runSafely(Long requestId, Long assistantMessageId) {
        try {
            run(requestId, assistantMessageId);
        } catch (AiException e) {
            fail(requestId, assistantMessageId, e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[AI] 请求编排异常 requestId={}", requestId, e);
            fail(requestId, assistantMessageId, "AI006", e.getMessage());
        }
    }

    private void run(Long requestId, Long assistantMessageId) {
        AiRequestEntity request = requestService.require(requestId);
        if (AiConstants.RequestStatus.CANCELLED.equals(request.getStatus())) {
            return;
        }
        AiAgentEntity agent = agentService.require(request.getAgentId());
        AiAgentVersionEntity version = agentService.requireVersion(request.getAgentVersionId());
        AiConversationEntity conversation = conversationService.find(request.getConversationId());
        AiMessageEntity assistant = conversationService.message(assistantMessageId);

        // 解析路由 / 提供方 / 模型
        Resolved resolved = resolve(version);
        requestService.markRunning(requestId, resolved.provider().getId(), resolved.model().getId());

        // 额度预占（已预占过则跳过，resume 时复用）
        AiQuotaService.Limits limits = quotaService.resolveLimits(request.getUserId(), agent, version);
        AiQuotaService.Reservation reservation = quotaService.reserve(request.getUserId(), agent, version,
                inputChars(request), limits);
        AiUsageEntity usage = usageService.reserve(requestService.require(requestId), resolved.model());

        boolean succeeded = false;
        int tokenIn = 0;
        int tokenOut = 0;
        StringBuilder answer = new StringBuilder();
        List<AiAppDtos.Citation> citations = List.of();
        try {
            List<AiToolService.EffectiveTool> tools = toolService.resolveEffective(version.getId(),
                    request.getClientType());

            String systemPrompt = buildSystemPrompt(version, request, agent);
            Knowledge knowledge = retrieveKnowledge(version, request);
            citations = knowledge.citations();
            if (!citations.isEmpty()) {
                systemPrompt = systemPrompt + "\n\n" + knowledge.block();
            }

            int maxToolRounds = resolveMaxToolRounds(version);
            for (int round = 0; round < maxToolRounds; round++) {
                if (cancelled(requestId)) {
                    return;
                }
                List<Message> messages = new ArrayList<>();
                messages.add(Message.system(systemPrompt));
                messages.addAll(history(conversation.getId()));
                List<ToolSpec> specs = toolSpecs(tools);

                RoundResult result = callModel(request, assistant, resolved, messages, specs,
                        answer.length() == 0 ? assistant : null);
                tokenIn += result.promptTokens();
                tokenOut += result.completionTokens();
                if (!answer.isEmpty() && !result.content().isEmpty()) {
                    answer.append('\n');
                }
                answer.append(result.content());

                if (result.toolCalls().isEmpty()) {
                    break;
                }
                // 工具轮：先落库助手消息（含 toolCalls），再执行工具
                persistAssistantToolCalls(assistant, result.toolCalls(), resolved.model());
                boolean needApproval = executeTools(request, agent, version, conversation, result.toolCalls(),
                        tools, assistant);
                if (needApproval) {
                    requestService.markWaiting(requestId, true);
                    conversationService.update(status(assistant, AiConstants.MessageStatus.STREAMING));
                    return;
                }
            }

            // 工具轮用尽仍在调工具：模型会把「我先去搜索」这类过渡语当成最终回答，
            // 用户看到的就是一句空话。这里再给一次不带工具的机会，逼它用已有结果作答。
            // 判定条件不能用「answer 为空」——本次事故里 answer 是「我会为你制定一个搜索计划。」，
            // 非空但毫无信息量，兜底因此从未触发。改为「解析不出结构化 blocks」。
            if (!cancelled(requestId) && !hasStructuredBlocks(answer.toString())) {
                List<Message> messages = new ArrayList<>();
                messages.add(Message.system(systemPrompt + """


                        工具调用次数已用尽。请**立刻**基于上面已经拿到的工具结果直接作答，
                        不要再请求任何工具。若确实缺少信息，就用现有结果给出最接近的推荐。
                        """));
                messages.addAll(history(conversation.getId()));
                RoundResult result = callModel(request, assistant, resolved, messages, null, assistant);
                tokenIn += result.promptTokens();
                tokenOut += result.completionTokens();
                answer.append(result.content());
            }

            if (cancelled(requestId)) {
                return;
            }
            finalize(request, assistant, answer.toString(), citations, resolved.model(), tokenIn, tokenOut);
            succeeded = true;
        } finally {
            if (succeeded) {
                quotaService.settle(reservation, answer.length());
                usageService.settle(usage, tokenIn, tokenOut);
            } else {
                quotaService.release(reservation);
                usageService.release(usage, tokenIn, tokenOut);
            }
        }
    }

    // ==================== 模型调用 ====================

    private RoundResult callModel(AiRequestEntity request, AiMessageEntity assistant, Resolved resolved,
                                  List<Message> messages, List<ToolSpec> specs, AiMessageEntity streaming) {
        ChatRequest chatRequest = new ChatRequest(
                resolved.provider().getBaseUrl(),
                providerService.resolveApiKey(resolved.provider()),
                resolved.model().getModelCode(),
                messages,
                specs.isEmpty() ? null : specs,
                routeTemperature(resolved.route()),
                routeTopP(resolved.route()),
                routeMaxTokens(resolved.route(), resolved.model()),
                responseFormat(resolved.version().getResponseSchemaJson()),
                resolved.route() == null ? null : resolved.route().getExtraParametersJson(),
                resolved.provider().getConnectTimeoutMs() == null ? 10000 : resolved.provider().getConnectTimeoutMs(),
                resolved.provider().getReadTimeoutMs() == null ? 60000 : resolved.provider().getReadTimeoutMs(),
                resolved.provider().getMaxRetries() == null ? 0 : resolved.provider().getMaxRetries(),
                "ai-" + request.getId());

        AiProvider provider = providerRegistry.require(resolved.provider().getProviderType());
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        int[] tokens = new int[2];
        String[] finish = {"stop"};
        Throwable[] error = {null};
        AtomicBoolean started = new AtomicBoolean(false);

        provider.stream(chatRequest, new StreamListener() {
            @Override
            public void onDelta(String text) {
                if (text == null || text.isEmpty()) {
                    return;
                }
                if (started.compareAndSet(false, true)) {
                    requestService.markFirstToken(request.getId());
                    eventService.append(request.getId(), AiConstants.EventType.MESSAGE_STARTED,
                            Map.of("messageId", assistant.getId()));
                }
                content.append(text);
                eventService.append(request.getId(), AiConstants.EventType.MESSAGE_DELTA,
                        Map.of("messageId", assistant.getId(), "delta", text));
            }

            @Override
            public void onCompleted(ChatResult result) {
                // Provider 契约：onCompleted 的 result.content() 是完整累积内容。
                // 已收到过增量时它是同一份数据的重复副本，直接忽略（原 else-if 清空后未重写，
                // 会把整段回答抹成空串 —— 助手气泡空白、content len=0 的根因）；
                // 仅当全程没有任何增量（如非流式实现只回调 onCompleted）时才补发一次。
                if (result.content() != null && !result.content().isEmpty() && !started.get()) {
                    onDelta(result.content());
                }
                if (result.toolCalls() != null) {
                    toolCalls.addAll(result.toolCalls());
                }
                tokens[0] = result.promptTokens();
                tokens[1] = result.completionTokens();
                if (result.finishReason() != null) {
                    finish[0] = result.finishReason();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                error[0] = throwable;
            }
        });

        if (error[0] != null) {
            throw error[0] instanceof AiException ai ? ai
                    : new AiException("AI006", error[0].getMessage());
        }
        if (streaming != null && !started.get() && !content.isEmpty()) {
            requestService.markFirstToken(request.getId());
        }
        return new RoundResult(content.toString(), toolCalls, tokens[0], tokens[1], finish[0]);
    }

    // ==================== 工具执行 ====================

    private boolean executeTools(AiRequestEntity request, AiAgentEntity agent, AiAgentVersionEntity version,
                                 AiConversationEntity conversation, List<ToolCall> calls,
                                 List<AiToolService.EffectiveTool> tools, AiMessageEntity assistant) {
        for (ToolCall call : calls) {
            AiToolService.EffectiveTool effective = findTool(tools, call.name());
            if (effective == null) {
                appendToolMessage(conversation.getId(), request.getId(), call,
                        "工具 " + call.name() + " 未被当前智能体授权使用", AiToolResult.REJECTED);
                eventService.append(request.getId(), AiConstants.EventType.TOOL_COMPLETED,
                        Map.of("toolCode", String.valueOf(call.name()), "status", AiToolResult.REJECTED));
                continue;
            }
            ObjectNode arguments = AiJson.readObject(call.arguments());
            AiToolCallEntity record = new AiToolCallEntity();
            record.setRequestId(request.getId());
            record.setToolCode(effective.tool().getToolCode());
            record.setToolName(effective.tool().getName());
            record.setRiskLevel(effective.risk());
            record.setArgumentsJson(call.arguments());
            record.setStartedAt(LocalDateTime.now());

            boolean approvalRequired = requiresApproval(effective);
            if (approvalRequired) {
                record.setStatus(AiConstants.ToolCallStatus.WAITING_APPROVAL);
                record.setApprovalStatus(AiConstants.ApprovalPolicy.ADMIN_CONFIRM.equals(effective.approval())
                        ? AiConstants.ApprovalPolicy.ADMIN_CONFIRM : AiConstants.ApprovalPolicy.USER_CONFIRM);
                toolCallMapper.insert(record);
                eventService.append(request.getId(), AiConstants.EventType.TOOL_PLANNED,
                        Map.of("toolCallId", record.getId(), "toolCode", effective.tool().getToolCode(),
                                "name", String.valueOf(effective.tool().getName()), "risk", effective.risk()));
                eventService.append(request.getId(), AiConstants.EventType.TOOL_APPROVAL_REQUIRED,
                        Map.of("toolCallId", record.getId(), "toolCode", effective.tool().getToolCode(),
                                "name", String.valueOf(effective.tool().getName()), "risk", effective.risk(),
                                "arguments", arguments));
                appendToolMessage(conversation.getId(), request.getId(), call,
                        "该操作需要确认后执行：" + effective.tool().getName(), AiToolResult.REQUIRES_APPROVAL);
                return true;
            }

            record.setStatus(AiConstants.ToolCallStatus.RUNNING);
            record.setApprovalStatus(AiConstants.ApprovalPolicy.NONE);
            toolCallMapper.insert(record);
            eventService.append(request.getId(), AiConstants.EventType.TOOL_PLANNED,
                    Map.of("toolCallId", record.getId(), "toolCode", effective.tool().getToolCode(),
                            "name", String.valueOf(effective.tool().getName()), "risk", effective.risk()));
            eventService.append(request.getId(), AiConstants.EventType.TOOL_STARTED,
                    Map.of("toolCallId", record.getId(), "toolCode", effective.tool().getToolCode()));

            AiToolResult result = invoke(request, agent, effective, arguments, false, null);
            record.setResultJson(result.payload() == null ? null : AiJson.write(result.payload()));
            record.setStatus(mapToolStatus(result));
            record.setFinishedAt(LocalDateTime.now());
            toolCallMapper.updateById(record);
            // 只有 NEED_CLIENT 需要把搜索计划交给客户端执行，此时才把 payload 一起塞进事件；
            // 其余工具的明细已经落在 ai_tool_call.result_json 里，事件不重复搬大对象。
            Map<String, Object> completed = new LinkedHashMap<>();
            completed.put("toolCallId", record.getId());
            completed.put("toolCode", effective.tool().getToolCode());
            completed.put("status", result.status());
            completed.put("summary", String.valueOf(result.summary()));
            if (AiToolResult.NEED_CLIENT.equals(result.status()) && result.payload() != null) {
                completed.put("plan", result.payload());
                completed.put("needClient", Boolean.TRUE);
            }
            eventService.append(request.getId(), AiConstants.EventType.TOOL_COMPLETED, completed);
            appendToolMessage(conversation.getId(), request.getId(), call, result.summary(), result.status());
        }
        return false;
    }

    /** 执行单个工具（供编排与「确认后执行」两条路径共用） */
    public AiToolResult invoke(AiRequestEntity request, AiAgentEntity agent,
                               AiToolService.EffectiveTool effective, ObjectNode arguments,
                               boolean approved, Long approvedBy) {
        AiToolContext context = new AiToolContext(
                request.getId(), request.getConversationId(), request.getUserId(), agent.getId(),
                agent.getAgentCode(), agent.getAgentType(), request.getClientType(),
                arguments, AiJson.readObject(request.getContextSnapshot()), approved, approvedBy);
        try {
            return effective.handler().execute(context);
        } catch (AiException e) {
            return AiToolResult.failed(e.getMessage());
        } catch (Exception e) {
            log.warn("[AI] 工具执行失败 tool={}: {}", effective.tool().getToolCode(), e.getMessage());
            return AiToolResult.failed(e.getMessage() == null ? "工具执行失败" : e.getMessage());
        }
    }

    /** 高风险工具必须确认；代码下限不可被后台配置绕过 */
    private boolean requiresApproval(AiToolService.EffectiveTool effective) {
        if (AiConstants.RiskLevel.HIGH.equals(effective.risk())) {
            return true;
        }
        return effective.approval() != null && !AiConstants.ApprovalPolicy.NONE.equals(effective.approval());
    }

    private String mapToolStatus(AiToolResult result) {
        if (result.isOk()) {
            return AiConstants.ToolCallStatus.SUCCEEDED;
        }
        if (AiToolResult.REJECTED.equals(result.status())) {
            return AiConstants.ToolCallStatus.REJECTED;
        }
        return AiConstants.ToolCallStatus.FAILED;
    }

    private AiToolService.EffectiveTool findTool(List<AiToolService.EffectiveTool> tools, String name) {
        if (name == null) {
            return null;
        }
        for (AiToolService.EffectiveTool tool : tools) {
            if (name.equals(tool.tool().getToolCode())) {
                return tool;
            }
        }
        return null;
    }

    private void appendToolMessage(Long conversationId, Long requestId, ToolCall call, String summary,
                                   String status) {
        AiMessageEntity message = new AiMessageEntity();
        message.setConversationId(conversationId);
        message.setRequestId(requestId);
        message.setRole(AiConstants.MessageRole.TOOL);
        message.setContent(summary == null ? "" : summary);
        message.setContentType("TOOL_RESULT");
        message.setStatus(AiConstants.MessageStatus.COMPLETED);
        ObjectNode meta = AiJson.mapper().createObjectNode();
        meta.put("toolCallId", call.id());
        meta.put("toolName", call.name());
        meta.put("status", status);
        message.setBlocksJson(AiJson.write(List.of(meta)));
        conversationService.append(message);
    }

    private void persistAssistantToolCalls(AiMessageEntity assistant, List<ToolCall> calls,
                                           AiModelEntity model) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolCall call : calls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.id());
            item.put("name", call.name());
            item.put("arguments", call.arguments());
            items.add(item);
        }
        assistant.setBlocksJson(AiJson.write(Map.of("toolCalls", items)));
        assistant.setStatus(AiConstants.MessageStatus.STREAMING);
        assistant.setModelName(model.getModelName());
        conversationService.update(assistant);
    }

    // ==================== 收尾 ====================

    private void finalize(AiRequestEntity request, AiMessageEntity assistant, String rawContent,
                          List<AiAppDtos.Citation> citations, AiModelEntity model,
                          int tokenIn, int tokenOut) {
        Parsed parsed = parseAnswer(rawContent, request.getResponseSchemaSnapshot());
        assistant.setContent(parsed.answer());
        assistant.setStatus(AiConstants.MessageStatus.COMPLETED);
        assistant.setContentType(parsed.blocks().isEmpty() ? "TEXT" : "STRUCTURED");
        assistant.setBlocksJson(parsed.blocks().isEmpty() ? null : AiJson.write(parsed.blocks()));
        assistant.setCitationsJson(citations.isEmpty() ? null : AiJson.write(citations));
        assistant.setModelName(model.getModelName());
        assistant.setInputTokens(tokenIn);
        assistant.setOutputTokens(tokenOut);
        assistant.setFinishReason(parsed.finishReason());
        conversationService.update(assistant);

        if (!citations.isEmpty()) {
            eventService.append(request.getId(), AiConstants.EventType.MESSAGE_CITATION,
                    Map.of("messageId", assistant.getId(), "citations", citations));
        }
        if (!parsed.actions().isEmpty()) {
            eventService.append(request.getId(), AiConstants.EventType.RECOMMENDATION_UPDATED,
                    Map.of("messageId", assistant.getId(), "actions", parsed.actions()));
        }
        eventService.append(request.getId(), AiConstants.EventType.MESSAGE_COMPLETED,
                Map.of("messageId", assistant.getId(), "content", parsed.answer(),
                        "blocks", parsed.blocks(), "actions", parsed.actions()));
        eventService.append(request.getId(), AiConstants.EventType.MESSAGE_METADATA,
                Map.of("messageId", assistant.getId(), "modelName", String.valueOf(model.getModelName()),
                        "inputTokens", tokenIn, "outputTokens", tokenOut));
        requestService.markSucceeded(request.getId(), tokenIn, tokenOut);
    }

    private void fail(Long requestId, Long assistantMessageId, String errorCode, String message) {
        try {
            AiRequestEntity request = requestService.find(requestId);
            if (request == null) {
                return;
            }
            if (AiConstants.RequestStatus.CANCELLED.equals(request.getStatus())) {
                return;
            }
            AiMessageEntity assistant = conversationService.message(assistantMessageId);
            if (assistant != null) {
                assistant.setStatus(AiConstants.MessageStatus.FAILED);
                assistant.setContent(assistant.getContent() == null || assistant.getContent().isEmpty()
                        ? "请求失败：" + safe(message) : assistant.getContent());
                conversationService.update(assistant);
            }
            eventService.append(requestId, AiConstants.EventType.REQUEST_FAILED,
                    Map.of("errorCode", String.valueOf(errorCode), "message", safe(message)));
            requestService.markFailed(requestId, errorCode, message, 0, 0);
        } catch (Exception e) {
            log.error("[AI] 失败收尾异常 requestId={}", requestId, e);
        }
    }

    // ==================== 组装 ====================

    private Resolved resolve(AiAgentVersionEntity version) {
        AiProviderRouteEntity route = version.getProviderRouteId() == null ? null
                : routeService.requireEnabled(version.getProviderRouteId());
        AiModelEntity model;
        AiProviderEntity provider;
        if (route == null) {
            // 未绑定路由：取任一启用模型，避免版本校验漏洞导致运行期空指针
            List<AiModelEntity> models = modelService.list(null);
            model = models.stream().filter(m -> Boolean.TRUE.equals(m.getEnabled())).findFirst()
                    .orElseThrow(() -> new AiException("AI006", "没有可用的模型，请先配置提供方与模型"));
            provider = providerService.requireEnabled(model.getProviderId());
        } else {
            model = modelService.requireEnabled(route.getModelId());
            provider = providerService.requireEnabled(route.getProviderId());
        }
        return new Resolved(version, route, provider, model);
    }

    private String buildSystemPrompt(AiAgentVersionEntity version, AiRequestEntity request, AiAgentEntity agent) {
        StringBuilder builder = new StringBuilder();
        if (version.getSystemPromptVersionId() != null) {
            AiPromptVersionEntity prompt = promptService.findVersion(version.getSystemPromptVersionId());
            if (prompt != null && prompt.getSystemPrompt() != null && !prompt.getSystemPrompt().isBlank()) {
                builder.append(prompt.getSystemPrompt().trim());
            }
        }
        if (builder.length() == 0) {
            builder.append(FALLBACK_SYSTEM_PROMPT.trim());
        }
        builder.append("\n\n当前客户端：").append(request.getClientType());
        builder.append("\n当前智能体：").append(agent.getName());
        if (Boolean.TRUE.equals(agent.getAllowUserContext())) {
            builder.append("\n可以使用上下文中的用户信息，但不得编造未提供的数据。");
        }
        String schema = version.getResponseSchemaJson();
        if (schema != null && !schema.isBlank() && !"{}".equals(schema.trim())) {
            builder.append("\n\n请严格按以下 JSON Schema 输出，只输出 JSON 本身，不要包裹代码块：\n").append(schema);
        }
        return builder.toString();
    }

    private List<Message> history(Long conversationId) {
        List<AiMessageEntity> rows = conversationService.messages(conversationId, null, MAX_HISTORY_MESSAGES);
        List<Message> messages = new ArrayList<>();
        for (AiMessageEntity row : rows) {
            // 历史窗口是按条数截断的，可能把「助手 toolCalls」与紧随其后的「tool 结果」切成两半。
            // 提供方会因此报 400（orphaned or duplicate tool result），所以在下面统一做配对修复。
            if (row.getContent() == null || row.getContent().isBlank()) {
                continue;
            }
            if (AiConstants.MessageRole.USER.equals(row.getRole())) {
                messages.add(Message.user(row.getContent()));
            } else if (AiConstants.MessageRole.ASSISTANT.equals(row.getRole())
                    && row.getBlocksJson() != null && row.getBlocksJson().contains("toolCalls")) {
                JsonNode node = AiJson.read(row.getBlocksJson());
                List<ToolCall> calls = new ArrayList<>();
                JsonNode array = node == null ? null : node.get("toolCalls");
                if (array != null && array.isArray()) {
                    for (JsonNode item : array) {
                        calls.add(new ToolCall(AiJson.text(item, "id", "call_" + calls.size()),
                                AiJson.text(item, "name", ""), AiJson.text(item, "arguments", "{}")));
                    }
                }
                if (!calls.isEmpty()) {
                    messages.add(Message.assistantToolCalls(row.getContent(), calls));
                }
            } else if (AiConstants.MessageRole.TOOL.equals(row.getRole())) {
                String toolCallId = null;
                String toolName = null;
                JsonNode node = AiJson.read(row.getBlocksJson());
                if (node != null && node.isArray() && !node.isEmpty()) {
                    JsonNode first = node.get(0);
                    toolCallId = AiJson.text(first, "toolCallId", null);
                    toolName = AiJson.text(first, "toolName", null);
                }
                if (toolCallId != null) {
                    messages.add(Message.tool(toolCallId, toolName, row.getContent()));
                }
            } else if (AiConstants.MessageRole.ASSISTANT.equals(row.getRole())) {
                messages.add(Message.assistant(row.getContent()));
            }
        }
        return dropOrphanToolMessages(messages);
    }

    /**
     * 修复 tool 调用与 tool 结果的配对。
     * <p>模型服务要求每条 tool 消息都必须紧跟一条声明了同名 toolCallId 的助手消息；反过来，
     * 助手声明的 toolCalls 若缺少对应结果也会被拒。历史按条数截断时两头都可能被切断，
     * 这里把不成对的部分整体丢弃——宁可少一轮上下文，也不要整轮请求失败。</p>
     */
    private List<Message> dropOrphanToolMessages(List<Message> messages) {
        List<Message> fixed = new ArrayList<>(messages.size());
        int i = 0;
        while (i < messages.size()) {
            Message current = messages.get(i);
            if (ROLE_TOOL.equals(current.role())) {
                i++; // 没有助手声明在前：孤立结果，直接丢
                continue;
            }
            Set<String> declared = new LinkedHashSet<>();
            if (current.toolCalls() != null) {
                for (ToolCall call : current.toolCalls()) {
                    declared.add(call.id());
                }
            }
            if (declared.isEmpty()) {
                fixed.add(current);
                i++;
                continue;
            }
            // 先收集紧随其后的一整段 tool 结果，再判断是否与本条声明严格一一对应
            int end = i + 1;
            while (end < messages.size() && ROLE_TOOL.equals(messages.get(end).role())) {
                end++;
            }
            List<Message> results = messages.subList(i + 1, end);
            Set<String> answered = new LinkedHashSet<>();
            boolean paired = true;
            for (Message result : results) {
                if (result.toolCallId() == null || !declared.contains(result.toolCallId())
                        || !answered.add(result.toolCallId())) {
                    paired = false;
                    break;
                }
            }
            if (paired && declared.equals(answered)) {
                fixed.add(current);
                fixed.addAll(results);
            }
            // 配对不全（结果缺失、重复或对不上）：助手消息与结果整段丢弃，
            // 避免把孤立的 toolCalls / tool 结果发给模型服务导致 400。
            i = end;
        }
        return fixed;
    }

    private List<ToolSpec> toolSpecs(List<AiToolService.EffectiveTool> tools) {
        List<ToolSpec> specs = new ArrayList<>();
        for (AiToolService.EffectiveTool tool : tools) {
            specs.add(new ToolSpec(tool.tool().getToolCode(), tool.tool().getDescription(),
                    tool.tool().getArgumentsSchema()));
        }
        return specs;
    }

    private Knowledge retrieveKnowledge(AiAgentVersionEntity version, AiRequestEntity request) {
        String query = AiJson.text(AiJson.readObject(request.getInputSnapshot()), "content", "");
        List<AiKnowledgeService.AiCitationHit> hits = knowledgeService.search(version.getId(), query,
                MAX_KNOWLEDGE_HITS);
        if (hits.isEmpty()) {
            return new Knowledge("", List.of());
        }
        StringBuilder builder = new StringBuilder("可引用的资料（回答涉及事实时请据此作答，并在引用处标注标题）：");
        List<AiAppDtos.Citation> citations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int index = 1;
        for (AiKnowledgeService.AiCitationHit hit : hits) {
            String key = hit.documentId() + "#" + hit.chunkNo();
            if (!seen.add(key)) {
                continue;
            }
            builder.append("\n[").append(index++).append("] 《").append(hit.title()).append("》");
            if (hit.section() != null && !hit.section().isBlank()) {
                builder.append(" · ").append(hit.section());
            }
            builder.append("\n").append(AiJson.truncate(hit.content(), 700));
            citations.add(new AiAppDtos.Citation(hit.sourceId(), hit.sourceType(), hit.title(),
                    hit.path(), hit.section(), hit.version(), hit.matchedText()));
            if (citations.size() >= AiConstants.Runtime.MAX_CITATIONS) {
                break;
            }
        }
        return new Knowledge(builder.toString(), citations);
    }

    // ==================== 输出解析 ====================

    private Parsed parseAnswer(String raw, String responseSchema) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return new Parsed("", List.of(), List.of(), "stop");
        }
        String json = stripFence(text);
        if (json.startsWith("{") && json.endsWith("}")) {
            JsonNode node = AiJson.read(json);
            if (node != null && node.isObject() && (node.has("answer") || node.has("blocks"))) {
                String answer = AiJson.text(node, "answer", text);
                List<AiAppDtos.Block> blocks = parseBlocks(node.get("blocks"));
                List<AiAppDtos.ActionVo> actions = parseActions(node.get("actions"));
                return new Parsed(answer, blocks, actions, "stop");
            }
        }
        return new Parsed(text, List.of(), List.of(), "stop");
    }

    private List<AiAppDtos.Block> parseBlocks(JsonNode node) {
        List<AiAppDtos.Block> blocks = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return blocks;
        }
        for (JsonNode item : node) {
            String type = AiJson.text(item, "type", null);
            if (type == null) {
                continue;
            }
            String title = AiJson.text(item, "title", null);
            Map<String, Object> data = new LinkedHashMap<>();
            item.properties().forEach(entry -> {
                String key = entry.getKey();
                if ("type".equals(key) || "title".equals(key)) {
                    return;
                }
                data.put(key, AiJson.mapper().convertValue(entry.getValue(), Object.class));
            });
            blocks.add(new AiAppDtos.Block(type, title, data));
        }
        return blocks;
    }

    private List<AiAppDtos.ActionVo> parseActions(JsonNode node) {
        List<AiAppDtos.ActionVo> actions = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return actions;
        }
        for (JsonNode item : node) {
            String type = AiJson.text(item, "type", null);
            if (type == null || !AiConstants.ActionType.all().contains(type)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            item.properties().forEach(entry -> {
                if (!"type".equals(entry.getKey())) {
                    payload.put(entry.getKey(), AiJson.mapper().convertValue(entry.getValue(), Object.class));
                }
            });
            actions.add(new AiAppDtos.ActionVo("act_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                    type, payload));
        }
        return actions;
    }

    private static String stripFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            if (firstLine > 0) {
                trimmed = trimmed.substring(firstLine + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    // ==================== 小工具 ====================

    private Map<String, Object> sanitizeContext(Map<String, Object> context, AiAgentVersionEntity version,
                                                AiAgentEntity agent) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        Set<String> allowed = new LinkedHashSet<>(AiConstants.ContextKey.musicKeys());
        allowed.addAll(AiConstants.ContextKey.adminKeys());
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            if (allowed.contains(entry.getKey()) && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        if (!Boolean.TRUE.equals(agent.getAllowFileContext())) {
            result.remove("fileContent");
        }
        return result;
    }

    private String snapshotInput(String content, Map<String, Object> context) {
        ObjectNode node = AiJson.mapper().createObjectNode();
        node.put("content", content);
        node.set("contextKeys", arrayOf(context == null ? List.of() : List.copyOf(context.keySet())));
        return AiJson.write(node);
    }

    private int inputChars(AiRequestEntity request) {
        String content = AiJson.text(AiJson.readObject(request.getInputSnapshot()), "content", "");
        return content.length();
    }

    private boolean cancelled(Long requestId) {
        AiRequestEntity current = requestService.find(requestId);
        return current == null || AiConstants.RequestStatus.CANCELLED.equals(current.getStatus());
    }

    private AiMessageEntity latestAssistant(Long requestId) {
        List<AiMessageEntity> rows = conversationService.byRequest(requestId);
        AiMessageEntity latest = null;
        for (AiMessageEntity row : rows) {
            if (AiConstants.MessageRole.ASSISTANT.equals(row.getRole())) {
                latest = row;
            }
        }
        return latest;
    }

    private AiMessageEntity status(AiMessageEntity message, String status) {
        message.setStatus(status);
        conversationService.update(message);
        return message;
    }

    private static String safe(String value) {
        return value == null ? "未知错误" : AiJson.truncate(value, 500);
    }

    private static Double routeTemperature(AiProviderRouteEntity route) {
        return route == null || route.getTemperature() == null ? null : route.getTemperature().doubleValue();
    }

    private static Double routeTopP(AiProviderRouteEntity route) {
        return route == null || route.getTopP() == null ? null : route.getTopP().doubleValue();
    }

    private static Integer routeMaxTokens(AiProviderRouteEntity route, AiModelEntity model) {
        if (route != null && route.getMaxTokens() != null && route.getMaxTokens() > 0) {
            return route.getMaxTokens();
        }
        return model.getMaxOutputTokens();
    }

    private record Resolved(AiAgentVersionEntity version, AiProviderRouteEntity route,
                            AiProviderEntity provider, AiModelEntity model) {
    }

    private record RoundResult(String content, List<ToolCall> toolCalls, int promptTokens,
                               int completionTokens, String finishReason) {
    }

    private record Knowledge(String block, List<AiAppDtos.Citation> citations) {
    }

    private record Parsed(String answer, List<AiAppDtos.Block> blocks,
                          List<AiAppDtos.ActionVo> actions, String finishReason) {
    }

    /** 供事件里序列化 blocks 时复用 */
    static List<Map<String, Object>> blockMaps(List<AiAppDtos.Block> blocks) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiAppDtos.Block block : blocks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", block.type());
            item.put("title", block.title());
            item.put("data", block.data());
            result.add(item);
        }
        return result;
    }

    /** 便于测试观察当前线程池规模 */
    int poolSize() {
        return ((java.util.concurrent.ThreadPoolExecutor) executor).getPoolSize();
    }

    /** 便于测试判定是否已停止 */
    boolean terminated() {
        return executor.isShutdown() || executor.isTerminated();
    }

    /** 时间戳辅助（审计展示用） */
    static LocalDateTime now() {
        return LocalDateTime.now();
    }

    /** 数组节点追加辅助 */
    static ArrayNode arrayOf(List<String> values) {
        ArrayNode array = AiJson.mapper().createArrayNode();
        values.forEach(array::add);
        return array;
    }

    /**
     * 把版本上的 responseSchema 转成提供方要的 {@code response_format}。
     * <p>库里存的是<b>裸 JSON Schema</b>（顶层就是 {@code {"type":"object","properties":…}}），
     * 而 OpenAI 兼容协议要求 {@code response_format} 是
     * {@code {"type":"json_schema","json_schema":{"name":…,"schema":<裸schema>}}} 或
     * {@code {"type":"json_object"}}。以前把裸 schema 直接塞进 response_format，
     * {@code type} 值是 "object" 属于非法枚举，提供方会静默忽略 —— 于是模型从来不知道
     * 要输出 JSON，音乐助手只回一句散文。这里按协议重新包装。</p>
     */
    static String responseFormat(String responseSchemaJson) {
        if (responseSchemaJson == null || responseSchemaJson.isBlank()
                || "{}".equals(responseSchemaJson.trim())) {
            return null;
        }
        JsonNode schema = AiJson.read(responseSchemaJson);
        if (schema == null || !schema.isObject()) {
            return null;
        }
        // 已经是对协议格式的，原样使用
        String type = AiJson.text(schema, "type", "");
        if ("json_schema".equals(type) || "json_object".equals(type) || "text".equals(type)) {
            return responseSchemaJson;
        }
        ObjectNode format = AiJson.mapper().createObjectNode();
        format.put("type", "json_schema");
        ObjectNode wrapper = format.putObject("json_schema");
        wrapper.put("name", "agent_response");
        // strict=false：各家兼容层对 strict 支持不一，强制 strict 容易被拒
        wrapper.put("strict", false);
        format.set("schema", schema);
        return AiJson.write(format);
    }

    /**
     * 解析版本里 {@code tool_policy_json} 的 {@code maxRounds}。
     * <p>后台一直能配这个值，但编排器过去直接读常量 {@link #MAX_TOOL_ROUNDS}，
     * 配置从来没生效过。这里读配置并夹在 [1, MAX_TOOL_ROUNDS_LIMIT] 之间，
     * 避免后台填 0 或填一个离谱的大数把请求拖死。</p>
     */
    static int resolveMaxToolRounds(AiAgentVersionEntity version) {
        JsonNode policy = AiJson.read(version == null ? null : version.getToolPolicyJson());
        int configured = MAX_TOOL_ROUNDS;
        if (policy != null && policy.isObject()) {
            JsonNode node = policy.get("maxRounds");
            if (node != null && node.isNumber()) {
                configured = node.asInt();
            }
        }
        return Math.max(1, Math.min(configured, MAX_TOOL_ROUNDS_LIMIT));
    }

    /**
     * 判断模型输出里是否含有可渲染的结构化块（歌曲卡片、引用等）。
     * <p>只看「answer 非空」会把「我会先搜索候选歌曲」这种过渡语当成有效回答，
     * 于是兜底不触发、用户什么也拿不到。这里要求真的解析出非空 blocks。</p>
     */
    static boolean hasStructuredBlocks(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String json = stripFence(raw.trim());
        if (!json.startsWith("{") || !json.endsWith("}")) {
            return false;
        }
        JsonNode node = AiJson.read(json);
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode blocks = node.get("blocks");
        return blocks != null && blocks.isArray() && !blocks.isEmpty();
    }
}
