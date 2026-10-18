package com.astral.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * App 端（qt-uniappx / qt-pc）与管理端使用侧的 DTO 集合。
 *
 * <p>App 与 PC 共用同一套接口与结构，客户端只按 {@code clientType} 区分能力上报；
 * 管理端的「管理助手 / 答疑」也复用这套会话模型，只是 {@code agentCode} 不同。</p>
 */
public final class AiAppDtos {

    private AiAppDtos() {
    }

    // ==================== 智能体发现 ====================

    /**
     * 智能体可见信息。
     *
     * @param agentCode    编码
     * @param name         名称
     * @param agentType    类型
     * @param description  描述
     * @param icon         图标
     * @param entryConfig  入口配置（JSON 字符串）
     * @param capabilities 能力开关
     */
    public record AgentCard(
            String agentCode,
            String name,
            String agentType,
            String description,
            String icon,
            String entryConfig,
            Capabilities capabilities
    ) {
    }

    /**
     * 能力开关：前端据此显隐功能，<b>不作为权限依据</b>（权限仍由服务端校验）。
     *
     * @param conversationEnabled 多轮会话
     * @param feedbackEnabled     反馈
     * @param fileContextEnabled  文件上下文
     * @param userContextEnabled  用户上下文
     * @param citationRequired    是否必须带引用（答疑）
     * @param musicActionsEnabled 是否可下发音乐动作
     * @param clientType          当前客户端
     */
    public record Capabilities(
            boolean conversationEnabled,
            boolean feedbackEnabled,
            boolean fileContextEnabled,
            boolean userContextEnabled,
            boolean citationRequired,
            boolean musicActionsEnabled,
            String clientType
    ) {
    }

    // ==================== 会话 ====================

    /**
     * 创建会话请求。
     *
     * @param agentCode 智能体编码
     * @param title     标题（可空，服务端按首条消息补）
     */
    public record ConversationCreateReq(
            @NotBlank @Size(max = 64) String agentCode,
            @Size(max = 200) String title
    ) {
    }

    /** 会话重命名请求 */
    public record ConversationRenameReq(@NotBlank @Size(max = 200) String title) {
    }

    /** 会话视图 */
    public record ConversationVo(
            Long id,
            String agentCode,
            String agentName,
            String title,
            String status,
            Integer messageCount,
            Boolean pinned,
            LocalDateTime lastMessageTime,
            LocalDateTime createTime
    ) {
    }

    // ==================== 消息 ====================

    /**
     * 发送消息请求。
     *
     * <p>{@code context} 只允许白名单键（见 {@code AiConstants.ContextKey}），
     * 服务端会过滤未知键，避免客户端把任意数据塞进模型上下文。</p>
     *
     * @param content        用户输入
     * @param clientMessageId 客户端消息 ID（幂等）
     * @param context        客户端上下文
     * @param idempotencyKey 幂等键
     */
    public record SendMessageReq(
            @NotBlank @Size(max = 20000) String content,
            @Size(max = 64) String clientMessageId,
            Map<String, Object> context,
            @Size(max = 96) String idempotencyKey
    ) {
    }

    /**
     * 发送消息响应。
     *
     * @param requestId         请求 ID
     * @param userMessageId     用户消息 ID
     * @param assistantMessageId 助手消息 ID
     * @param status            初始状态
     * @param stream            是否走流式
     */
    public record SendMessageResp(
            Long requestId,
            Long userMessageId,
            Long assistantMessageId,
            String status,
            boolean stream
    ) {
    }

    /** 消息视图 */
    public record MessageVo(
            Long id,
            Long conversationId,
            Long requestId,
            String role,
            String content,
            String contentType,
            String status,
            List<Block> blocks,
            List<Citation> citations,
            String modelName,
            LocalDateTime createTime
    ) {
    }

    /**
     * 结构化块：音乐卡片等。
     *
     * @param type  块类型，如 {@code song_list}
     * @param title 标题
     * @param data  块数据（JSON 对象）
     */
    public record Block(String type, String title, Map<String, Object> data) {
    }

    /**
     * 引用（答疑必须可核验）。
     *
     * @param sourceId    知识源 ID
     * @param sourceType  来源类型
     * @param title       文档标题
     * @param path        文档路径
     * @param section     章节
     * @param version     文档版本
     * @param matchedText 命中片段
     */
    public record Citation(
            Long sourceId,
            String sourceType,
            String title,
            String path,
            String section,
            String version,
            String matchedText
    ) {
    }

    /** 消息分页 */
    public record MessagePage(List<MessageVo> items, Long nextCursor, boolean hasMore) {
    }

    // ==================== 事件 ====================

    /**
     * 事件视图。
     *
     * @param requestId 请求 ID
     * @param sequence  序列号（断线恢复游标）
     * @param type      事件类型
     * @param data      事件体
     * @param createdAt 发生时间
     */
    public record EventVo(Long requestId, long sequence, String type, Map<String, Object> data,
                          LocalDateTime createdAt) {
    }

    /** 事件批次 */
    public record EventBatch(Long requestId, String status, List<EventVo> events, long nextSequence,
                             boolean terminal) {
    }

    // ==================== 动作 ====================

    /**
     * 客户端动作。
     *
     * @param actionId 动作 ID
     * @param type     动作类型（白名单）
     * @param payload  动作参数
     */
    public record ActionVo(String actionId, String type, Map<String, Object> payload) {
    }

    /**
     * 动作执行回执（客户端本地执行后回报，用于审计与纠偏）。
     *
     * @param actionId 动作 ID
     * @param actionType 动作类型
     * @param success  是否成功
     * @param message  失败原因
     */
    public record ActionExecuteReq(
            @NotBlank @Size(max = 64) String actionId,
            @NotBlank @Size(max = 32) String actionType,
            @NotNull Boolean success,
            @Size(max = 512) String message
    ) {
    }

    // ==================== 反馈 ====================

    /**
     * 消息反馈请求。
     *
     * @param feedbackType 类型（LIKE/DISLIKE/REPORT）
     * @param reasonCode   原因码
     * @param comment      备注
     */
    public record FeedbackReq(
            @NotBlank @Size(max = 16) String feedbackType,
            @Size(max = 64) String reasonCode,
            @Size(max = 1000) String comment
    ) {
    }

    /** 取消请求 */
    public record CancelReq(@Size(max = 200) String reason) {
    }

    // ==================== 音乐助手上下文 ====================

    /**
     * 音乐候选（客户端多音源搜索后回传，服务端不抓取音频）。
     *
     * @param sourceId   音源 ID
     * @param songId     歌曲 ID
     * @param name       歌名
     * @param artist     歌手
     * @param album      专辑
     * @param duration   时长（秒）
     * @param coverUrl   封面
     * @param quality    可用音质
     * @param extra      音源扩展字段
     */
    public record MusicCandidate(
            String sourceId,
            String songId,
            String name,
            String artist,
            String album,
            Integer duration,
            String coverUrl,
            String quality,
            Map<String, Object> extra
    ) {
    }

    /**
     * 搜索计划（模型输出，服务端做 JSON Schema 级校验后下发客户端）。
     *
     * @param intent  意图
     * @param keywords 关键词
     * @param sources  音源
     * @param limit    条数上限
     * @param filters  过滤条件
     */
    public record MusicSearchPlan(
            String intent,
            List<String> keywords,
            List<String> sources,
            Integer limit,
            Map<String, Object> filters
    ) {
    }

    /** 幂等去重用的最大条数上限 */
    public static final int MAX_CANDIDATES = 50;

    /** 单次输入字符硬上限（与 AiProperties.hardMaxInputChars 对齐的下限） */
    public static final int MIN_CHARS = 1;

    /** 分页参数上限 */
    public static final int MAX_LIMIT = 100;

    /** 默认分页大小 */
    public static final int DEFAULT_LIMIT = 30;

    /** 消息条数下限 */
    public static final int MIN_LIMIT = 1;

    /** 事件批次上限 */
    public static final int MAX_EVENT_BATCH = 500;

    /** 反馈类型白名单（与字典 ai_feedback_type 对齐） */
    public static final List<String> FEEDBACK_TYPES = List.of("LIKE", "DISLIKE", "REGENERATE", "REPORT");

    /** 分页钳制 */
    public static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < MIN_LIMIT) {
            return MIN_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 事件批次钳制 */
    public static int clampEventBatch(Integer size) {
        if (size == null || size <= 0) {
            return 100;
        }
        return Math.min(size, MAX_EVENT_BATCH);
    }

    /** 候选条数钳制 */
    @Min(1)
    @Max(MAX_CANDIDATES)
    public @interface CandidateLimit {
    }
}
