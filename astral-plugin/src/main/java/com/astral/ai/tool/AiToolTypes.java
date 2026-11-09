package com.astral.ai.tool;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 工具的类型定义集合。
 *
 * <p>工具分两种执行形态（{@code ai_tool.executor_type}）：</p>
 * <ul>
 *   <li><b>CODE</b>：{@code handler_key} 只能指向本包内真实注册的处理器（设计文档 §11.5），
 *       后台只能调整风险等级、确认策略与启停，<b>不能新增任意 Java / Shell 工具</b>；</li>
 *   <li><b>HTTP</b>：后台可创建的声明式工具，执行面收敛在
 *       {@link com.astral.ai.tool.declarative.HttpToolExecutor}——只能表达「按模板调用一个
 *       HTTP 接口」，host 必须字面量、非 GET 强制高风险确认、SSRF 护栏写死。</li>
 * </ul>
 *
 * <p>无论哪种形态，模型看到的都是同一套 {@link AiToolDef} + {@link AiToolResult} 契约。</p>
 */
public final class AiToolTypes {

    private AiToolTypes() {}

    /**
     * 工具静态定义（由代码提供，落库只为让后台可见可配）。
     *
     * @param toolCode       工具编码（模型看到的函数名）
     * @param name           中文名
     * @param description    给模型看的说明
     * @param handlerKey     处理器键（必须与代码注册一致）
     * @param riskLevel      READ / DRAFT / HIGH
     * @param approvalPolicy NONE / USER_CONFIRM / ADMIN_CONFIRM
     * @param clientTypes    可用客户端
     * @param permissionCodes 调用所需权限
     * @param argumentsSchema 参数 JSON Schema
     * @param resultSchema    结果 JSON Schema（可空）
     * @param sideEffects     副作用说明（为空表示只读）
     */
    public record AiToolDef(
            String toolCode,
            String name,
            String description,
            String handlerKey,
            String riskLevel,
            String approvalPolicy,
            List<String> clientTypes,
            List<String> permissionCodes,
            String argumentsSchema,
            String resultSchema,
            String sideEffects) {}

    /**
     * 工具执行上下文。
     *
     * @param requestId     请求 ID
     * @param conversationId 会话 ID
     * @param userId        用户 ID
     * @param agentId       智能体 ID
     * @param agentCode     智能体编码
     * @param agentType     智能体类型
     * @param clientType    客户端类型
     * @param arguments     模型给出的参数（已通过 Schema 粗校验）
     * @param context       请求上下文快照（只包含白名单键）
     * @param approved      是否为「已确认后执行」
     * @param approvedBy    确认人
     */
    public record AiToolContext(
            Long requestId,
            Long conversationId,
            Long userId,
            Long agentId,
            String agentCode,
            String agentType,
            String clientType,
            ObjectNode arguments,
            ObjectNode context,
            boolean approved,
            Long approvedBy,
            Long toolCallId) {
        public AiToolContext(
                Long requestId,
                Long conversationId,
                Long userId,
                Long agentId,
                String agentCode,
                String agentType,
                String clientType,
                ObjectNode arguments,
                ObjectNode context,
                boolean approved,
                Long approvedBy) {
            this(
                    requestId,
                    conversationId,
                    userId,
                    agentId,
                    agentCode,
                    agentType,
                    clientType,
                    arguments,
                    context,
                    approved,
                    approvedBy,
                    null);
        }

        /** Stable logical operation identity for handlers that support downstream idempotency. */
        public String executionKey() {
            return toolCallId == null ? null : "ai-tool-" + toolCallId;
        }
        /** 取字符串参数 */
        public String arg(String name) {
            if (arguments == null) {
                return null;
            }
            return com.astral.ai.common.AiJson.text(arguments, name, null);
        }

        /** 取整数参数（带默认值与区间钳制） */
        public int argInt(String name, int def, int min, int max) {
            int value = com.astral.ai.common.AiJson.intValue(arguments, name, def);
            return Math.max(min, Math.min(max, value));
        }

        /** 取字符串数组参数 */
        public List<String> argList(String name) {
            return com.astral.ai.common.AiJson.stringList(arguments, name);
        }

        /** 取上下文子节点 */
        public ObjectNode contextNode(String key) {
            if (context == null) {
                return null;
            }
            var node = context.get(key);
            return node instanceof ObjectNode objectNode ? objectNode : null;
        }
    }

    /**
     * 工具执行结果。
     *
     * @param status  SUCCEEDED / REQUIRES_APPROVAL / REJECTED / FAILED / NEED_CLIENT
     * @param summary 给模型看的简短结论（会进入 tool 消息内容）
     * @param payload 结构化数据（同时写入 ai_tool_call.result_json）
     */
    public record AiToolResult(String status, String summary, ObjectNode payload) {
        public static final String UNKNOWN = "UNKNOWN";

        /** 状态：成功 */
        public static final String SUCCEEDED = "SUCCEEDED";
        /** 状态：需要确认（高风险） */
        public static final String REQUIRES_APPROVAL = "REQUIRES_APPROVAL";
        /** 状态：被拒绝（参数非法/无权限） */
        public static final String REJECTED = "REJECTED";
        /** 状态：失败 */
        public static final String FAILED = "FAILED";
        /** 状态：需要客户端执行（如多音源搜索） */
        public static final String NEED_CLIENT = "NEED_CLIENT";

        /** 成功 */
        public static AiToolResult ok(String summary, ObjectNode payload) {
            return new AiToolResult(SUCCEEDED, summary, payload);
        }

        /** 需要确认 */
        public static AiToolResult approval(String summary, ObjectNode payload) {
            return new AiToolResult(REQUIRES_APPROVAL, summary, payload);
        }

        /** 拒绝 */
        public static AiToolResult rejected(String summary) {
            return new AiToolResult(
                    REJECTED, summary, com.astral.ai.common.AiJson.mapper().createObjectNode());
        }

        /** 失败 */
        public static AiToolResult failed(String summary) {
            return new AiToolResult(
                    FAILED, summary, com.astral.ai.common.AiJson.mapper().createObjectNode());
        }

        /** 需要客户端执行 */
        public static AiToolResult needClient(String summary, ObjectNode payload) {
            return new AiToolResult(NEED_CLIENT, summary, payload);
        }

        /** 是否成功 */
        public boolean isOk() {
            return SUCCEEDED.equals(status) || NEED_CLIENT.equals(status);
        }
    }

    /** 工具处理器 */
    public interface AiToolHandler {

        /** 静态定义 */
        AiToolDef definition();

        /** Server-owned plan enrichment, called once before persistence, never on approval replay. */
        default ObjectNode prepareArguments(ObjectNode arguments) {
            return arguments;
        }

        /**
         * 执行。
         *
         * @param context 上下文
         * @return 结果（不得返回 null）
         */
        AiToolResult execute(AiToolContext context);
    }

    /** 批量提供工具处理器（一个 Bean 注册一组，减少类数量） */
    public interface AiToolProvider {

        /**
         * 提供的处理器列表。
         *
         * @return 处理器
         */
        List<AiToolHandler> handlers();
    }

    /** 便捷构造：参数 Schema 统一是 {@code {"type":"object","properties":{...}} } */
    public static String objectSchema(String propertiesJson) {
        return "{\"type\":\"object\",\"properties\":" + propertiesJson + ",\"additionalProperties\":false}";
    }

    /** 便捷构造：空参数 */
    public static String emptySchema() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    /** 便捷构造：字符串数组参数 */
    public static String stringArrayParam(String description) {
        return "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"" + description + "\"}";
    }

    /** 便捷构造：整型参数 */
    public static String intParam(String description, int min, int max) {
        return "{\"type\":\"integer\",\"minimum\":" + min + ",\"maximum\":" + max + ",\"description\":\"" + description
                + "\"}";
    }

    /** 便捷构造：字符串参数 */
    public static String stringParam(String description) {
        return "{\"type\":\"string\",\"description\":\"" + description + "\"}";
    }

    /** 只读空结果 */
    public static ObjectNode emptyPayload() {
        return com.astral.ai.common.AiJson.mapper().createObjectNode();
    }

    /** 由 Map 构造 payload */
    public static ObjectNode payload(Map<String, Object> values) {
        return (ObjectNode) com.astral.ai.common.AiJson.mapper().valueToTree(values);
    }
}
