package com.astral.ai.tool.declarative;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.security.AiMcpHeaderSecurity;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 声明式 MCP 工具执行器。
 *
 * <p>一个远端 MCP 工具在本地只是一行 {@code ai_tool}：{@code executor_type=MCP}、
 * {@code handler_key=declarative:mcp}、{@code executor_config={serverId, toolName}}。
 * 执行时按 serverId 找到服务器配置，握手后转成一次 {@code tools/call}。安全边界：</p>
 * <ul>
 *   <li>端点地址沿用 HTTP 工具的 SSRF 护栏（私网/环回/链路本地/云元数据一律拒绝）；</li>
 *   <li>非只读服务器发现的工具在发现阶段就被写成 HIGH + ADMIN_CONFIRM，运行期同样按
 *       {@link com.astral.ai.service.AiToolService} 的既有规则再拦截一次；</li>
 *   <li>每次调用独立会话（握手 → 调用 → 关闭），不在进程内缓存长连接。</li>
 *   <li>headers 里的 {@code v1:} 密文只在调用瞬间解密（R9），解不开的敏感头按「凭证缺失」省略。</li>
 * </ul>
 */
@Slf4j
public class McpToolExecutor implements AiToolHandler {

    /** 声明式 MCP 工具的 handlerKey */
    public static final String HANDLER_KEY = "declarative:mcp";

    private static final int MAX_SUMMARY = 1500;

    private final AiToolEntity tool;
    private final AiMcpServerEntity server;
    private final AiProperties properties;
    private final String toolName;
    private final String planFingerprint;
    private final Map<String, String> renderedHeaders;

    private McpToolExecutor(AiToolEntity tool, AiMcpServerEntity server, AiProperties properties, String toolName) {
        this.tool = new AiToolEntity();
        BeanUtils.copyProperties(tool, this.tool);
        this.server = new AiMcpServerEntity();
        BeanUtils.copyProperties(server, this.server);
        this.properties = new AiProperties();
        if (properties != null) {
            this.properties.setSecretKey(properties.getSecretKey());
            if (properties.getHttpTool() != null) {
                this.properties
                        .getHttpTool()
                        .setAllowedHosts(
                                new ArrayList<>(properties.getHttpTool().getAllowedHosts()));
                this.properties
                        .getHttpTool()
                        .setSecrets(new LinkedHashMap<>(properties.getHttpTool().getSecrets()));
                this.properties
                        .getHttpTool()
                        .setMcpTimeoutSeconds(properties.getHttpTool().getMcpTimeoutSeconds());
            }
        }
        this.toolName = toolName;
        this.renderedHeaders = Map.copyOf(McpClient.renderHeaders(
                AiMcpHeaderSecurity.revealForCall(this.properties.getSecretKey(), this.server.getHeadersJson()),
                this.properties.getHttpTool().getSecrets()));
        var snapshot = AiJson.mapper().createObjectNode();
        snapshot.put("serverId", server.getId());
        snapshot.put("url", server.getUrl());
        snapshot.put("headers", server.getHeadersJson());
        snapshot.set("effectiveHeaders", AiJson.mapper().valueToTree(new java.util.TreeMap<>(renderedHeaders)));
        snapshot.put("readOnly", server.getReadOnly());
        snapshot.put("status", server.getStatus());
        try {
            this.planFingerprint = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(AiJson.write(snapshot).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** Digest only: endpoint and credential material never leave the executor snapshot. */
    public String planFingerprint() {
        return planFingerprint;
    }

    /**
     * 按工具记录构造执行器。
     *
     * @param tool       工具记录（executor_config 里必须有 serverId / toolName）
     * @param server     所属 MCP 服务器（已按 serverId 取出，服务器被禁用时传 null）
     * @param properties 插件配置
     * @return 执行器；配置缺失或服务器不可用时返回 {@code null}
     */
    public static McpToolExecutor create(AiToolEntity tool, AiMcpServerEntity server, AiProperties properties) {
        if (server == null) {
            log.warn("[AI] 声明式 MCP 工具 {} 不可用：所属 MCP 服务器不存在", tool.getToolCode());
            return null;
        }
        if (!"ENABLED".equalsIgnoreCase(server.getStatus())) {
            log.warn("[AI] 声明式 MCP 工具 {} 不可用：服务器 {} 已禁用", tool.getToolCode(), server.getServerCode());
            return null;
        }
        ObjectNode config = AiJson.readObject(tool.getExecutorConfig());
        String toolName = AiJson.text(config, "toolName", null);
        if (toolName == null || toolName.isBlank()) {
            log.warn("[AI] 声明式 MCP 工具 {} 的 executor_config 缺少 toolName", tool.getToolCode());
            return null;
        }
        try {
            return new McpToolExecutor(tool, server, properties, toolName);
        } catch (com.astral.ai.common.AiException e) {
            log.warn("[AI] MCP 工具 {} 凭据配置不可用", tool.getId());
            return null;
        }
    }

    /** 取 executor_config 里的 serverId（缺失或非法返回 null） */
    /** 保存时记下的远端 readOnlyHint（远端未给出时为 null） */
    public static Boolean readOnlyHintOf(AiToolEntity tool) {
        ObjectNode config = AiJson.readObject(tool.getExecutorConfig());
        if (config == null) {
            return null;
        }
        JsonNode node = config.get("readOnlyHint");
        return node == null || node.isNull() ? null : node.asBoolean();
    }

    public static Long serverIdOf(AiToolEntity tool) {
        ObjectNode config = AiJson.readObject(tool.getExecutorConfig());
        if (config == null) {
            return null;
        }
        var node = config.get("serverId");
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.asLong();
    }

    @Override
    public AiToolDef definition() {
        return new AiToolDef(
                tool.getToolCode(),
                tool.getName(),
                tool.getDescription(),
                HANDLER_KEY,
                riskLevelOf(Boolean.TRUE.equals(server.getReadOnly()), readOnlyHintOf(tool)),
                approvalOf(Boolean.TRUE.equals(server.getReadOnly()), readOnlyHintOf(tool)),
                AiJson.splitCsv(tool.getClientTypes()),
                AiJson.splitCsv(tool.getPermissionCodes()),
                tool.getArgumentsSchema(),
                tool.getResultSchema(),
                tool.getSideEffects());
    }

    @Override
    public AiToolResult execute(AiToolContext context) {
        AiProperties.HttpTool config = properties == null ? null : properties.getHttpTool();
        int timeoutSeconds = config == null ? 20 : config.getMcpTimeoutSeconds();
        try (McpClient client = McpClient.connectWithHeaders(
                server.getUrl(), renderedHeaders, config == null ? null : config.getAllowedHosts(), timeoutSeconds)) {
            ObjectNode arguments =
                    context.arguments() == null ? AiJson.mapper().createObjectNode() : context.arguments();
            McpClient.McpCallResult result = client.callTool(toolName, arguments);
            if (result.error()) {
                return AiToolResult.failed(
                        "MCP 工具 " + toolName + " 返回错误：" + AiJson.truncate(result.text(), MAX_SUMMARY));
            }
            String text = result.text();
            String summary = text == null || text.isBlank()
                    ? "MCP 工具 " + toolName + " 执行完成（无文本输出）"
                    : AiJson.truncate(text, MAX_SUMMARY);
            return AiToolResult.ok(summary, result.payload());
        } catch (AiException e) {
            log.warn("[AI] MCP 工具 {} 执行失败: {}", tool.getToolCode(), e.getMessage());
            return AiToolResult.failed(e.getMessage());
        } catch (Exception e) {
            log.warn("[AI] MCP 工具 {} 执行异常: {}", tool.getToolCode(), e.getClass().getSimpleName());
            return AiToolResult.failed("MCP 工具执行失败，请检查服务配置与网络");
        }
    }

    /**
     * 远端工具落库时的风险等级：只有「服务器声明只读」且「远端没有明说有副作用」才算 READ。
     *
     * @param serverReadOnly 服务器 read_only 配置
     * @param readOnlyHint   远端工具的 annotations.readOnlyHint（未知传 null）
     * @return 风险等级
     */
    public static String riskLevelOf(boolean serverReadOnly, Boolean readOnlyHint) {
        return readOnlyOf(serverReadOnly, readOnlyHint)
                ? com.astral.ai.common.AiConstants.RiskLevel.READ
                : com.astral.ai.common.AiConstants.RiskLevel.HIGH;
    }

    /**
     * 远端工具落库时的确认策略：与 {@link #riskLevelOf} 同一判定，HIGH 一律 ADMIN_CONFIRM。
     *
     * @param serverReadOnly 服务器 read_only 配置
     * @param readOnlyHint   远端工具的 annotations.readOnlyHint
     * @return 确认策略
     */
    public static String approvalOf(boolean serverReadOnly, Boolean readOnlyHint) {
        return readOnlyOf(serverReadOnly, readOnlyHint)
                ? com.astral.ai.common.AiConstants.ApprovalPolicy.NONE
                : com.astral.ai.common.AiConstants.ApprovalPolicy.ADMIN_CONFIRM;
    }

    /** 只有服务器只读且远端未否认时才视为只读 */
    public static boolean readOnlyOf(boolean serverReadOnly, Boolean readOnlyHint) {
        return serverReadOnly && !Boolean.FALSE.equals(readOnlyHint);
    }

    /** 工具编码：{服务器编码}__{远端工具名}，规整成小写 snake_case */
    public static String toolCodeOf(String serverCode, String remoteToolName) {
        String prefix = sanitize(serverCode);
        String name = sanitize(remoteToolName);
        String combined = (prefix.isEmpty() ? "mcp" : prefix) + "__" + (name.isEmpty() ? "tool" : name);
        if (combined.length() > 64) {
            combined = combined.substring(0, 64);
        }
        // ai_tool.tool_code 的形状要求：小写字母开头，只含小写字母/数字/下划线
        if (!combined.matches("[a-z][a-z0-9_]{2,63}")) {
            combined = "mcp_" + combined;
            combined = combined.length() > 64 ? combined.substring(0, 64) : combined;
        }
        return combined;
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String lowered = text.trim().toLowerCase(java.util.Locale.ROOT);
        return lowered.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
