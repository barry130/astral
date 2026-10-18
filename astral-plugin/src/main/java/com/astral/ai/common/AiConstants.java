package com.astral.ai.common;

import java.util.List;

/**
 * AI 模块枚举常量（与数据字典 {@code ai_*} 一一对应）。
 *
 * <p>字典是前端下拉的唯一来源；这里的常量只用于服务端状态机判断，
 * <b>不得</b>作为前端选项来源，也不得散落成魔法字符串。</p>
 */
public final class AiConstants {

    private AiConstants() {
    }

    /** 智能体类型 */
    public static final class AgentType {
        public static final String MUSIC = "MUSIC";
        public static final String ADMIN = "ADMIN";
        public static final String QA = "QA";
        public static final String CUSTOM = "CUSTOM";

        private AgentType() {
        }

        public static List<String> all() {
            return List.of(MUSIC, ADMIN, QA, CUSTOM);
        }
    }

    /** 智能体状态 */
    public static final class AgentStatus {
        public static final String DRAFT = "DRAFT";
        public static final String PUBLISHED = "PUBLISHED";
        public static final String DISABLED = "DISABLED";

        private AgentStatus() {
        }

        public static List<String> all() {
            return List.of(DRAFT, PUBLISHED, DISABLED);
        }
    }

    /** 智能体版本状态 */
    public static final class VersionStatus {
        public static final String DRAFT = "DRAFT";
        public static final String PUBLISHED = "PUBLISHED";
        public static final String ARCHIVED = "ARCHIVED";

        private VersionStatus() {
        }

        public static List<String> all() {
            return List.of(DRAFT, PUBLISHED, ARCHIVED);
        }
    }

    /** 客户端类型 */
    public static final class ClientType {
        public static final String QT_UNIAPPX = "QT_UNIAPPX";
        public static final String QT_PC = "QT_PC";
        public static final String ASTRAL_FRONT = "ASTRAL_FRONT";

        private ClientType() {
        }

        public static List<String> all() {
            return List.of(QT_UNIAPPX, QT_PC, ASTRAL_FRONT);
        }
    }

    /** 请求状态 */
    public static final class RequestStatus {
        public static final String QUEUED = "QUEUED";
        public static final String RUNNING = "RUNNING";
        public static final String WAITING_TOOL = "WAITING_TOOL";
        public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
        public static final String SUCCEEDED = "SUCCEEDED";
        public static final String FAILED = "FAILED";
        public static final String CANCELLED = "CANCELLED";

        private RequestStatus() {
        }

        public static List<String> all() {
            return List.of(QUEUED, RUNNING, WAITING_TOOL, WAITING_APPROVAL, SUCCEEDED, FAILED, CANCELLED);
        }

        /** 终止态：不再产生新事件 */
        public static boolean isTerminal(String status) {
            return SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
        }
    }

    /** 消息状态 */
    public static final class MessageStatus {
        public static final String PENDING = "PENDING";
        public static final String STREAMING = "STREAMING";
        public static final String COMPLETED = "COMPLETED";
        public static final String FAILED = "FAILED";
        public static final String CANCELLED = "CANCELLED";

        private MessageStatus() {
        }

        public static List<String> all() {
            return List.of(PENDING, STREAMING, COMPLETED, FAILED, CANCELLED);
        }
    }

    /** 消息角色 */
    public static final class MessageRole {
        public static final String USER = "USER";
        public static final String ASSISTANT = "ASSISTANT";
        public static final String SYSTEM = "SYSTEM";
        public static final String TOOL = "TOOL";

        private MessageRole() {
        }
    }

    /** 会话状态 */
    public static final class ConversationStatus {
        public static final String ACTIVE = "ACTIVE";
        public static final String CLOSED = "CLOSED";
        public static final String DELETED = "DELETED";

        private ConversationStatus() {
        }
    }

    /** 工具风险等级 */
    public static final class RiskLevel {
        public static final String READ = "READ";
        public static final String DRAFT = "DRAFT";
        public static final String HIGH = "HIGH";

        private RiskLevel() {
        }

        public static List<String> all() {
            return List.of(READ, DRAFT, HIGH);
        }

        /** 风险等级用于「只能提高不能降低」的比较，值越大越危险 */
        public static int order(String level) {
            if (HIGH.equals(level)) {
                return 3;
            }
            if (DRAFT.equals(level)) {
                return 2;
            }
            return READ.equals(level) ? 1 : 0;
        }
    }

    /** 工具确认策略 */
    public static final class ApprovalPolicy {
        public static final String NONE = "NONE";
        public static final String USER_CONFIRM = "USER_CONFIRM";
        public static final String ADMIN_CONFIRM = "ADMIN_CONFIRM";

        private ApprovalPolicy() {
        }

        public static List<String> all() {
            return List.of(NONE, USER_CONFIRM, ADMIN_CONFIRM);
        }
    }

    /** 工具调用状态 */
    public static final class ToolCallStatus {
        public static final String PLANNED = "PLANNED";
        public static final String WAITING_APPROVAL = "WAITING_APPROVAL";
        public static final String RUNNING = "RUNNING";
        public static final String SUCCEEDED = "SUCCEEDED";
        public static final String FAILED = "FAILED";
        public static final String REJECTED = "REJECTED";
        public static final String CANCELLED = "CANCELLED";

        private ToolCallStatus() {
        }
    }

    /** 知识源状态 */
    public static final class KnowledgeStatus {
        public static final String PENDING = "PENDING";
        public static final String INDEXING = "INDEXING";
        public static final String READY = "READY";
        public static final String FAILED = "FAILED";
        public static final String DISABLED = "DISABLED";

        private KnowledgeStatus() {
        }

        public static List<String> all() {
            return List.of(PENDING, INDEXING, READY, FAILED, DISABLED);
        }
    }

    /** 索引任务状态 */
    public static final class IndexJobStatus {
        public static final String PENDING = "PENDING";
        public static final String RUNNING = "RUNNING";
        public static final String SUCCEEDED = "SUCCEEDED";
        public static final String FAILED = "FAILED";
        public static final String CANCELLED = "CANCELLED";

        private IndexJobStatus() {
        }

        public static List<String> all() {
            return List.of(PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED);
        }
    }

    /** 事件类型（与设计文档 §8.2 完全一致） */
    public static final class EventType {
        public static final String REQUEST_CREATED = "request.created";
        public static final String MESSAGE_STARTED = "message.started";
        public static final String MESSAGE_DELTA = "message.delta";
        public static final String MESSAGE_METADATA = "message.metadata";
        public static final String TOOL_PLANNED = "tool.planned";
        public static final String TOOL_STARTED = "tool.started";
        public static final String TOOL_PROGRESS = "tool.progress";
        public static final String TOOL_APPROVAL_REQUIRED = "tool.approval_required";
        public static final String TOOL_COMPLETED = "tool.completed";
        public static final String RECOMMENDATION_UPDATED = "recommendation.updated";
        public static final String MESSAGE_CITATION = "message.citation";
        public static final String MESSAGE_COMPLETED = "message.completed";
        public static final String REQUEST_CANCELLED = "request.cancelled";
        public static final String REQUEST_FAILED = "request.failed";

        private EventType() {
        }
    }

    /** 客户端动作白名单（服务端签发、客户端二次校验） */
    public static final class ActionType {
        public static final String PLAY_SONG = "PLAY_SONG";
        public static final String PLAY_LIST = "PLAY_LIST";
        public static final String ADD_TO_QUEUE = "ADD_TO_QUEUE";
        public static final String ADD_TO_FAVORITE = "ADD_TO_FAVORITE";
        public static final String OPEN_SONG = "OPEN_SONG";
        public static final String OPEN_ARTIST = "OPEN_ARTIST";
        public static final String OPEN_ALBUM = "OPEN_ALBUM";
        public static final String OPEN_PLAYLIST = "OPEN_PLAYLIST";
        public static final String SEARCH_MUSIC = "SEARCH_MUSIC";

        private ActionType() {
        }

        public static List<String> all() {
            return List.of(PLAY_SONG, PLAY_LIST, ADD_TO_QUEUE, ADD_TO_FAVORITE,
                    OPEN_SONG, OPEN_ARTIST, OPEN_ALBUM, OPEN_PLAYLIST, SEARCH_MUSIC);
        }
    }

    /** 上下文允许字段（超出即被服务端过滤） */
    public static final class ContextKey {
        public static final String CURRENT_SONG = "currentSong";
        public static final String CURRENT_ARTIST = "currentArtist";
        public static final String CURRENT_ALBUM = "currentAlbum";
        public static final String CURRENT_PLAYLIST = "currentPlaylist";
        public static final String CURRENT_QUEUE = "currentQueue";
        public static final String RECENTLY_PLAYED_SUMMARY = "recentlyPlayedSummary";
        public static final String USER_PREFERENCE_SUMMARY = "userPreferenceSummary";
        public static final String INSTALLED_SOURCE_REGISTRY = "installedSourceRegistry";
        public static final String MUSIC_CANDIDATES = "musicCandidates";
        public static final String PAGE_CONTEXT = "pageContext";
        public static final String SELECTED_RECORD = "selectedRecord";

        private ContextKey() {
        }

        public static List<String> musicKeys() {
            return List.of(CURRENT_SONG, CURRENT_ARTIST, CURRENT_ALBUM, CURRENT_PLAYLIST, CURRENT_QUEUE,
                    RECENTLY_PLAYED_SUMMARY, USER_PREFERENCE_SUMMARY, INSTALLED_SOURCE_REGISTRY, MUSIC_CANDIDATES);
        }

        public static List<String> adminKeys() {
            return List.of(PAGE_CONTEXT, SELECTED_RECORD);
        }
    }

    /** 反馈类型 */
    public static final class FeedbackType {
        public static final String LIKE = "LIKE";
        public static final String DISLIKE = "DISLIKE";
        public static final String REGENERATE = "REGENERATE";
        public static final String REPORT = "REPORT";

        private FeedbackType() {
        }

        public static List<String> all() {
            return List.of(LIKE, DISLIKE, REGENERATE, REPORT);
        }
    }

    /** 额度作用域 */
    public static final class QuotaScope {
        public static final String GLOBAL = "GLOBAL";
        public static final String AGENT = "AGENT";
        public static final String ROLE = "ROLE";
        public static final String USER = "USER";

        private QuotaScope() {
        }

        public static List<String> all() {
            return List.of(GLOBAL, AGENT, ROLE, USER);
        }
    }

    /** 用量结算状态 */
    public static final class UsageStatus {
        public static final String RESERVED = "RESERVED";
        public static final String SETTLED = "SETTLED";
        public static final String RELEASED = "RELEASED";

        private UsageStatus() {
        }
    }

    /** 知识源类型（决定答疑要检索哪一类文档） */
    public static final class KnowledgeSourceType {
        public static final String PROJECT_DOCUMENT = "PROJECT_DOCUMENT";
        public static final String API_DOCUMENT = "API_DOCUMENT";
        public static final String PLUGIN_DOCUMENT = "PLUGIN_DOCUMENT";
        public static final String DEPLOYMENT_DOCUMENT = "DEPLOYMENT_DOCUMENT";
        public static final String RELEASE_NOTE = "RELEASE_NOTE";
        public static final String FAQ = "FAQ";
        public static final String LIVE_READONLY_DATA = "LIVE_READONLY_DATA";

        private KnowledgeSourceType() {
        }

        public static List<String> all() {
            return List.of(PROJECT_DOCUMENT, API_DOCUMENT, PLUGIN_DOCUMENT, DEPLOYMENT_DOCUMENT,
                    RELEASE_NOTE, FAQ, LIVE_READONLY_DATA);
        }
    }

    /** 插件运行期关键常量 */
    public static final class Runtime {
        /** 事件序列号步长：单请求事件远少于该值，够用且省一次查库 */
        public static final int EVENT_SEQUENCE_STEP = 1;
        /** MatchedText 截断长度，避免响应体膨胀 */
        public static final int MAX_MATCHED_TEXT = 500;
        /** 引用条数上限 */
        public static final int MAX_CITATIONS = 20;
        /** 单条消息正文保存上限 */
        public static final int MAX_MESSAGE_CHARS = 200000;

        private Runtime() {
        }
    }
}
