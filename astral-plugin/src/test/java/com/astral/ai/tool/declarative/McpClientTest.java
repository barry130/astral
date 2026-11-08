package com.astral.ai.tool.declarative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.common.AiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@link McpClient} 的传输层单元测试。
 *
 * <p>覆盖 SSE 分帧、JSON-RPC 报文匹配、端点地址的 SSRF 护栏与请求头模板渲染——
 * 这些是「手写 Streamable HTTP 客户端」里最容易出错、又不需要真连服务器的部分。</p>
 */
class McpClientTest {

    // ==================== SSE 分帧 ====================

    @Test
    @DisplayName("SSE 响应按空行拆帧，只取 data 负载")
    void splitsSseFrames() {
        String body = """
                event: message
                data: {"jsonrpc":"2.0","id":1,"result":{"ok":true}}

                :heartbeat

                event: message
                data: {"jsonrpc":"2.0","id":2,"result":{"ok":false}}

                """;
        List<String> frames = McpClient.splitSseFrames(body);
        assertEquals(2, frames.size());
        assertTrue(frames.get(0).contains("\"id\":1"));
        assertTrue(frames.get(1).contains("\"id\":2"));
    }

    @Test
    @DisplayName("多行 data 合并成一帧（规范行为）")
    void joinsMultilineData() {
        String body = """
                data: {"jsonrpc":"2.0",
                data: "id":7,"result":{}}

                """;
        List<String> frames = McpClient.splitSseFrames(body);
        assertEquals(1, frames.size());
        assertNotNull(McpClient.firstSseMatching(body, 7));
    }

    @Test
    @DisplayName("空响应不产帧")
    void ignoresEmptyBody() {
        assertTrue(McpClient.splitSseFrames(null).isEmpty());
        assertTrue(McpClient.splitSseFrames("  ").isEmpty());
    }

    // ==================== 报文匹配 ====================

    @Test
    @DisplayName("普通 JSON 响应按 id 校验")
    void parsesJsonEnvelope() {
        JsonNode node = McpClient.parseEnvelope(
                "application/json", "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[]}}", 3);
        assertEquals(3, node.get("id").asLong());
        assertTrue(node.get("result").has("tools"));
    }

    @Test
    @DisplayName("id 不匹配的 JSON 响应直接拒绝，避免张冠李戴")
    void rejectsMismatchedJsonId() {
        AiException error = assertThrows(
                AiException.class,
                () -> McpClient.parseEnvelope("application/json", "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}", 3));
        assertTrue(error.getMessage().contains("不匹配"));
    }

    @Test
    @DisplayName("非 JSON 响应报错时带上截断后的原文，便于排障")
    void rejectsNonJsonBody() {
        AiException error =
                assertThrows(AiException.class, () -> McpClient.parseEnvelope("text/html", "<html>404</html>", 1));
        assertTrue(error.getMessage().contains("合法 JSON"));
    }

    @Test
    @DisplayName("SSE 响应里挑 id 匹配的帧")
    void picksMatchingSseFrame() {
        String body = """
                data: {"jsonrpc":"2.0","id":1,"result":{"stage":"init"}}

                data: {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search"}]}}

                """;
        JsonNode node = McpClient.parseEnvelope("text/event-stream; charset=utf-8", body, 2);
        assertEquals(
                "search", node.get("result").get("tools").get(0).get("name").asString());
    }

    @Test
    @DisplayName("SSE 响应里找不到匹配帧时报错")
    void rejectsSseWithoutMatch() {
        AiException error = assertThrows(
                AiException.class, () -> McpClient.parseEnvelope("text/event-stream", "data: {\"id\":1}\n\n", 5));
        assertTrue(error.getMessage().contains("没有 id=5"));
    }

    // ==================== 端点校验（SSRF 护栏） ====================

    @Test
    @DisplayName("私网/环回/云元数据地址一律拒绝")
    void rejectsPrivateEndpoints() {
        for (String url : List.of(
                "http://127.0.0.1:8080/mcp",
                "http://169.254.169.254/mcp",
                "http://10.0.0.5/mcp",
                "http://192.168.1.7:3000/mcp",
                "http://[::1]:9000/mcp")) {
            assertThrows(AiException.class, () -> McpClient.requireEndpoint(url, null), url);
        }
    }

    @Test
    @DisplayName("非 http(s)、带占位符、缺主机的地址被拒绝")
    void rejectsBadUrls() {
        assertThrows(AiException.class, () -> McpClient.requireEndpoint("ftp://example.com/mcp", null));
        assertThrows(AiException.class, () -> McpClient.requireEndpoint("http://${host}/mcp", null));
        assertThrows(AiException.class, () -> McpClient.requireEndpoint("http:///mcp", null));
        assertThrows(AiException.class, () -> McpClient.requireEndpoint("  ", null));
    }

    @Test
    @DisplayName("公网地址通过；白名单非空时收紧到白名单")
    void acceptsPublicAndHonoursAllowlist() {
        // 用公网 IP 字面量，避免单测依赖 DNS
        assertNotNull(McpClient.requireEndpoint("https://93.184.216.34/mcp", null));
        assertEquals(
                "93.184.216.34",
                McpClient.requireEndpoint("https://93.184.216.34/mcp", List.of("93.184.216.34"))
                        .getHost());
        assertThrows(
                AiException.class,
                () -> McpClient.requireEndpoint("https://93.184.216.35/mcp", List.of("93.184.216.34")));
    }

    // ==================== 请求头渲染 ====================

    @Test
    @DisplayName("请求头里的 ${secret:key} 从配置取，未配置时报错")
    void rendersSecretHeaders() {
        Map<String, String> headers = McpClient.renderHeaders(
                "{\"Authorization\":\"Bearer ${secret:MCP_TOKEN}\",\"X-Trace\":\"astral\"}",
                Map.of("MCP_TOKEN", "abc123"));
        assertEquals("Bearer abc123", headers.get("Authorization"));
        assertEquals("astral", headers.get("X-Trace"));

        assertThrows(
                AiException.class,
                () -> McpClient.renderHeaders("{\"Authorization\":\"Bearer ${secret:MISSING_TOKEN}\"}", Map.of()));
    }

    @Test
    @DisplayName("非法请求头名称被拒绝，避免 CRLF 注入")
    void rejectsBadHeaderName() {
        assertThrows(AiException.class, () -> McpClient.renderHeaders("{\"Bad Header\":\"x\"}", Map.of()));
        assertThrows(AiException.class, () -> McpClient.renderHeaders("{\"A\\r\\nB\":\"x\"}", Map.of()));
    }

    @Test
    @DisplayName("空配置渲染出空表")
    void rendersEmptyHeaders() {
        assertTrue(McpClient.renderHeaders(null, null).isEmpty());
        assertTrue(McpClient.renderHeaders("[]", null).isEmpty());
        assertTrue(McpClient.renderHeaders("{}", Map.of()).isEmpty());
    }

    // ==================== 工具定义解析 ====================

    @Test
    @DisplayName("工具定义解析：名称/标题/参数结构/只读标注")
    void parsesToolInfo() {
        JsonNode item = com.astral.ai.common.AiJson.read("""
                {"name":"search","title":"搜索","description":"搜一搜",
                 "inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},
                 "annotations":{"readOnlyHint":false}}
                """);
        McpClient.McpToolInfo info = McpClient.toolInfo(item);
        assertNotNull(info);
        assertEquals("search", info.name());
        assertEquals("搜索", info.title());
        assertEquals("搜一搜", info.description());
        assertEquals(Boolean.FALSE, info.readOnlyHint());
        assertNotNull(info.inputSchema());
    }

    @Test
    @DisplayName("缺 name 的工具定义被忽略")
    void skipsToolWithoutName() {
        assertNull(McpClient.toolInfo(com.astral.ai.common.AiJson.read("{\"description\":\"x\"}")));
    }

    @Test
    @DisplayName("只读标注缺失时保持 null（由服务器 read_only 决定风险）")
    void leavesMissingHintNull() {
        McpClient.McpToolInfo info = McpClient.toolInfo(com.astral.ai.common.AiJson.read("{\"name\":\"ping\"}"));
        assertNotNull(info);
        assertNull(info.readOnlyHint());
        assertNull(info.title());
    }
}
