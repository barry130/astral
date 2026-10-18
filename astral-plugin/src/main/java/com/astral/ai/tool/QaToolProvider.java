package com.astral.ai.tool;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiKnowledgeSourceEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.service.AiKnowledgeService;
import com.astral.ai.service.AiRequestService;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolProvider;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.common.error.ErrorCodes;
import com.astral.dao.entity.DictData;
import com.astral.dao.entity.DictType;
import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginRegistry;
import com.astral.system.service.DictDataService;
import com.astral.system.service.DictTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 答疑助手工具集（设计文档 §4.9）。
 *
 * <p>与管理助手的关键区别：这里的工具<b>只读已发布文档与只读元数据</b>，且不与管理助手共享
 * 任何写工具。答疑的价值在于「说清依据」——因此每个检索类工具都必须返回可核验的引用字段
 * （文档标题、路径、章节、版本、命中片段），模型再据此作答。</p>
 *
 * <p>当检索不到内容时，工具返回空结果而不是编造。模型被要求在无依据时明确说明「文档中未找到」，
 * 这条约束在系统提示词与验收标准里各写了一次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaToolProvider implements AiToolProvider {

    /** 单次检索返回条数上限 */
    private static final int DEFAULT_TOPK = 6;

    /** 返回给模型的单条片段最大字符数 */
    private static final int MAX_EXCERPT = 1200;

    private final AiKnowledgeService knowledgeService;
    private final AiRequestService requestService;
    private final DictTypeService dictTypeService;
    private final DictDataService dictDataService;
    private final PluginRegistry pluginRegistry;

    @Override
    public List<AiToolHandler> handlers() {
        return List.of(
                new SearchProjectDocumentTool(),
                new GetApiContractTool(),
                new GetPluginGuideTool(),
                new GetDeploymentGuideTool(),
                new GetDictionaryDefinitionTool(),
                new GetErrorCodeExplanationTool(),
                new GetCurrentPluginStatusTool(),
                new GetCurrentConfigSchemaTool()
        );
    }

    // ==================== 文档检索 ====================

    private class SearchProjectDocumentTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("search_project_document", "检索项目文档",
                    "在已发布的项目文档库中检索与问题相关的段落，返回带标题、路径、章节与版本的引用。"
                            + "如果返回为空，说明文档中没有相关内容，不要编造。",
                    "qa.searchProjectDocument", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"query":{"type":"string","description":"检索关键词或问题"},
                             "topK":{"type":"integer","minimum":1,"maximum":20,"description":"返回条数，默认 6"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return search(context, context.arg("query"), List.of(), "检索项目文档");
        }
    }

    private class GetApiContractTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_api_contract", "查询接口契约",
                    "在 API 文档类知识源中检索某个接口的请求路径、参数、返回结构与错误码。",
                    "qa.getApiContract", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"query":{"type":"string","description":"接口路径或功能描述"},
                             "topK":{"type":"integer","minimum":1,"maximum":20,"description":"返回条数，默认 6"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return search(context, context.arg("query"),
                    List.of("API_DOCUMENT", "PROJECT_DOCUMENT"), "检索接口契约");
        }
    }

    private class GetPluginGuideTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_plugin_guide", "查询插件开发指南",
                    "检索插件 SPI、生命周期、前端扩展、权限提供者等插件开发说明。",
                    "qa.getPluginGuide", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"query":{"type":"string","description":"插件相关问题"},
                             "topK":{"type":"integer","minimum":1,"maximum":20,"description":"返回条数，默认 6"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return search(context, context.arg("query"),
                    List.of("PLUGIN_DOCUMENT", "PROJECT_DOCUMENT"), "检索插件开发指南");
        }
    }

    private class GetDeploymentGuideTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_deployment_guide", "查询部署指南",
                    "检索环境变量、数据库迁移、反向代理、备份与升级等部署相关说明。",
                    "qa.getDeploymentGuide", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"query":{"type":"string","description":"部署相关问题"},
                             "topK":{"type":"integer","minimum":1,"maximum":20,"description":"返回条数，默认 6"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return search(context, context.arg("query"),
                    List.of("DEPLOYMENT_DOCUMENT", "RELEASE_NOTE", "FAQ", "PROJECT_DOCUMENT"), "检索部署指南");
        }
    }

    // ==================== 元数据 ====================

    private class GetDictionaryDefinitionTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_dictionary_definition", "查询数据字典",
                    "读取某个字典编码下的全部取值与标签，用于解释状态、类型等枚举含义。只读。",
                    "qa.getDictionaryDefinition", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"dictCode":{"type":"string","description":"字典编码，如 ai_request_status"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String code = context.arg("dictCode");
            if (code == null || code.isBlank()) {
                return AiToolResult.rejected("缺少 dictCode");
            }
            List<DictType> types = dictTypeService.list();
            DictType matched = null;
            for (DictType type : types) {
                if (code.trim().equalsIgnoreCase(type.getDictCode())) {
                    matched = type;
                    break;
                }
            }
            if (matched == null) {
                return AiToolResult.ok("未找到字典：" + code, AiToolTypes.emptyPayload());
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (DictData data : dictDataService.listByCode(matched.getDictCode())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", data.getDictLabel());
                row.put("value", data.getDictValue());
                row.put("sort", data.getDictSort());
                row.put("status", data.getStatus());
                items.add(row);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dictCode", matched.getDictCode());
            payload.put("dictName", matched.getDictName());
            payload.put("items", items);
            return AiToolResult.ok("字典 " + matched.getDictCode() + " 共 " + items.size() + " 项。",
                    AiToolTypes.payload(payload));
        }
    }

    private class GetErrorCodeExplanationTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_error_code_explanation", "解释错误码",
                    "按错误码返回系统内置的中文含义，用于解释接口报错。只读。",
                    "qa.getErrorCodeExplanation", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"errorCode":{"type":"string","description":"错误码，如 AI005 或 COMMON005"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String code = context.arg("errorCode");
            if (code == null || code.isBlank()) {
                return AiToolResult.rejected("缺少 errorCode");
            }
            String normalized = code.trim().toUpperCase(Locale.ROOT);
            String message = ErrorCodes.getMessage(normalized);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("errorCode", normalized);
            payload.put("message", message);
            payload.put("known", !"未知错误".equals(message));
            return AiToolResult.ok("错误码 " + normalized + "：" + message, AiToolTypes.payload(payload));
        }
    }

    private class GetCurrentPluginStatusTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_current_plugin_status_readonly", "查询当前插件状态",
                    "读取当前系统实际的插件启用状态。这是实时数据，不是文档内容，回答时必须标明来源。只读。",
                    "qa.getCurrentPluginStatus", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AstralPlugin plugin : pluginRegistry.getAllPlugins()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pluginId", plugin.getPluginId());
                row.put("name", plugin.getPluginName());
                row.put("version", plugin.getVersion());
                row.put("enabled", pluginRegistry.isEnabled(plugin.getPluginId()));
                row.put("required", plugin.isRequired());
                rows.add(row);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "LIVE_READONLY_DATA");
            payload.put("plugins", rows);
            return AiToolResult.ok("已读取当前插件实时状态（来源：实时只读数据）。", AiToolTypes.payload(payload));
        }
    }

    private class GetCurrentConfigSchemaTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_current_config_schema_readonly", "查询配置项结构",
                    "读取 AI 插件当前生效的配置项结构与默认值（不含密钥值）。属于实时数据，回答时需标明来源。只读。",
                    "qa.getCurrentConfigSchema", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(schema("astral.plugins.ai.enabled", "boolean", "true", "AI 插件总开关"));
            rows.add(schema("astral.plugins.ai.secret-key", "string", "(已隐藏)", "提供方密钥加密主密钥"));
            rows.add(schema("astral.plugins.ai.auto-schema", "boolean", "true", "启动时自动建表"));
            rows.add(schema("astral.plugins.ai.hard-max-input-chars", "int", "60000", "单次请求输入字符硬上限"));
            rows.add(schema("astral.plugins.ai.hard-max-output-tokens", "int", "8192", "单次响应输出 Token 硬上限"));
            rows.add(schema("astral.plugins.ai.hard-max-context-tokens", "int", "128000", "上下文 Token 硬上限"));
            rows.add(schema("astral.plugins.ai.request-timeout-seconds", "int", "180", "单次请求超时秒数"));
            rows.add(schema("astral.plugins.ai.event-batch-size", "int", "200", "事件批量拉取上限"));
            rows.add(schema("astral.plugins.ai.event-retention-days", "int", "14", "事件保留天数"));
            rows.add(schema("astral.plugins.ai.event-long-poll-millis", "int", "25000", "长轮询最长等待毫秒"));
            rows.add(schema("astral.plugins.ai.log-payload", "boolean", "false", "是否记录请求正文（默认关闭）"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "LIVE_READONLY_DATA");
            payload.put("items", rows);
            return AiToolResult.ok("已读取配置项结构（来源：实时只读数据）。", AiToolTypes.payload(payload));
        }

        private Map<String, Object> schema(String key, String type, String def, String desc) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("type", type);
            row.put("defaultValue", def);
            row.put("description", desc);
            return row;
        }
    }

    // ==================== 共用检索 ====================

    /**
     * 执行一次知识检索并转成模型可读的结构。
     *
     * @param context     工具上下文
     * @param query       查询串
     * @param sourceTypes 限定知识源类型（空表示不限）
     * @param label       场景描述，用于返回摘要
     */
    private AiToolResult search(AiToolContext context, String query, List<String> sourceTypes, String label) {
        if (query == null || query.isBlank()) {
            return AiToolResult.rejected("缺少 query");
        }
        Long versionId = resolveVersionId(context);
        if (versionId == null) {
            return AiToolResult.failed("无法解析智能体版本，暂时不能检索文档");
        }
        int topK = context.argInt("topK", DEFAULT_TOPK, 1, 20);
        List<AiKnowledgeService.AiCitationHit> hits;
        try {
            hits = knowledgeService.search(versionId, query, topK);
        } catch (RuntimeException e) {
            log.warn("[AI] 答疑检索失败 query={} err={}", query, e.getMessage());
            return AiToolResult.failed("文档检索失败：" + e.getMessage());
        }
        if (hits.isEmpty()) {
            return AiToolResult.ok("文档库中未检索到与该问题相关的内容（" + label + "）。如无依据，请如实说明未找到，不要编造。",
                    AiToolTypes.emptyPayload());
        }
        List<Map<String, Object>> citations = new ArrayList<>();
        ObjectNode payload = AiToolTypes.emptyPayload();
        var list = payload.putArray("citations");
        for (AiKnowledgeService.AiCitationHit hit : hits) {
            if (!sourceTypes.isEmpty() && hit.sourceType() != null
                    && !sourceTypes.contains(hit.sourceType().toUpperCase(Locale.ROOT))) {
                continue;
            }
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("sourceId", hit.sourceId());
            citation.put("sourceType", hit.sourceType());
            citation.put("title", hit.title());
            citation.put("path", hit.path());
            citation.put("section", hit.section());
            citation.put("version", hit.version());
            citation.put("matchedText", hit.matchedText());
            citation.put("score", Math.round(hit.score() * 100.0) / 100.0);
            citations.add(citation);
            ObjectNode node = list.addObject();
            node.put("sourceId", hit.sourceId());
            node.put("sourceType", hit.sourceType());
            node.put("title", hit.title());
            node.put("path", hit.path());
            node.put("section", hit.section());
            node.put("version", hit.version());
            node.put("matchedText", AiJson.truncate(hit.matchedText(), AiConstants.Runtime.MAX_MATCHED_TEXT));
            node.put("excerpt", AiJson.truncate(hit.content(), MAX_EXCERPT));
        }
        if (citations.isEmpty()) {
            return AiToolResult.ok("文档库中未检索到符合类型要求的内容（" + label + "）。如无依据，请如实说明未找到。",
                    AiToolTypes.emptyPayload());
        }
        payload.put("source", "PROJECT_DOCUMENT");
        return AiToolResult.ok("检索到 " + citations.size() + " 段文档依据（" + label
                + "）。回答时请逐条标注来源标题与章节。", payload);
    }

    /** 由请求反查本次绑定的智能体版本 */
    private Long resolveVersionId(AiToolContext context) {
        if (context == null || context.requestId() == null) {
            return null;
        }
        try {
            AiRequestEntity request = requestService.require(context.requestId());
            return request.getAgentVersionId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 知识源类型 → 中文名（便于日志与排障） */
    static String sourceTypeName(String type) {
        if (type == null) {
            return "未知";
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "PROJECT_DOCUMENT" -> "项目文档";
            case "API_DOCUMENT" -> "接口文档";
            case "PLUGIN_DOCUMENT" -> "插件文档";
            case "DEPLOYMENT_DOCUMENT" -> "部署文档";
            case "RELEASE_NOTE" -> "版本说明";
            case "FAQ" -> "常见问题";
            case "LIVE_READONLY_DATA" -> "实时只读数据";
            default -> type;
        };
    }

    /** 校验知识源是否可用（供种子数据与后台提示复用） */
    static boolean usable(AiKnowledgeSourceEntity source) {
        return source != null && Boolean.TRUE.equals(source.getEnabled())
                && AiConstants.KnowledgeStatus.READY.equals(source.getStatus());
    }
}
