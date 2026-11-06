package com.astral.ai.tool.declarative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 声明式 HTTP 执行器的纯单元测试：结构校验、SSRF 护栏、模板渲染与取值。
 *
 * <p>主机一律用字面量 IP，避免测试依赖 DNS；真实的 HTTP 调用不在单测范围。</p>
 */
class HttpToolExecutorTest {

    private static final String OK_HOST = "93.184.216.34";

    private static String schema(String... names) {
        StringBuilder properties = new StringBuilder();
        for (String name : names) {
            if (properties.length() > 0) {
                properties.append(',');
            }
            properties.append('"').append(name).append("\":{\"type\":\"string\"}");
        }
        return "{\"type\":\"object\",\"properties\":{" + properties + "}}";
    }

    private static String getConfig(String url, String extra) {
        return "{\"method\":\"GET\",\"url\":\"" + url + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private static ObjectNode object(String json) {
        return AiJson.readObject(json);
    }

    // ==================== 保存期校验 ====================

    @Test
    @DisplayName("合法的 GET 工具配置可以通过校验")
    void validateAcceptsValidGetTool() {
        String config = getConfig(
                "https://" + OK_HOST + "/v1/orders/${orderId}",
                "\"query\":{\"channel\":\"${channel}\"},\"headers\":{\"X-Token\":\"${secret:api_token}\"},"
                        + "\"timeoutMs\":3000,\"payloadPath\":\"data\",\"summaryPath\":\"message\"");
        HttpToolExecutor.validate(config, schema("orderId", "channel"), List.of());
    }

    @Test
    @DisplayName("host 里出现占位符一律拒绝")
    void validateRejectsPlaceholderInHost() {
        AiException error = assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(
                        getConfig("https://${target}/v1/x", null), schema("target"), List.of()));
        assertTrue(error.getMessage().contains("host 必须是字面量"));
    }

    @Test
    @DisplayName("模板用了参数结构未声明的占位符时拒绝保存")
    void validateRejectsUndeclaredPlaceholder() {
        AiException error = assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(
                        getConfig("https://" + OK_HOST + "/v1/orders/${orderId}", null), schema("other"), List.of()));
        assertTrue(error.getMessage().contains("未声明的占位符"));
    }

    @Test
    @DisplayName("GET 携带 body 是配置错误")
    void validateRejectsGetWithBody() {
        AiException error = assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(
                        getConfig("https://" + OK_HOST + "/v1/x", "\"body\":\"{\\\"a\\\":1}\""), schema(), List.of()));
        assertTrue(error.getMessage().contains("不允许携带 body"));
    }

    @Test
    @DisplayName("未知 HTTP 方法被拒绝")
    void validateRejectsUnknownMethod() {
        assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(
                        "{\"method\":\"TRACE\",\"url\":\"https://" + OK_HOST + "/x\"}", schema(), List.of()));
    }

    @Test
    @DisplayName("超时与响应体积必须落在允许区间内")
    void validateRejectsOutOfRangeLimits() {
        assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(
                        getConfig("https://" + OK_HOST + "/x", "\"timeoutMs\":60000"), schema(), List.of()));
        assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(
                        getConfig("https://" + OK_HOST + "/x", "\"maxResponseBytes\":10"), schema(), List.of()));
    }

    @Test
    @DisplayName("headers 的值必须是字符串")
    void validateRejectsNonStringHeader() {
        assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(
                        getConfig("https://" + OK_HOST + "/x", "\"headers\":{\"X-N\":1}"), schema(), List.of()));
    }

    @Test
    @DisplayName("执行配置不是 JSON 对象时拒绝")
    void validateRejectsNonObjectConfig() {
        assertThrows(AiException.class, () -> HttpToolExecutor.validate("[1,2,3]", schema(), List.of()));
        assertThrows(AiException.class, () -> HttpToolExecutor.validate("  ", schema(), List.of()));
    }

    // ==================== SSRF 护栏 ====================

    @Test
    @DisplayName("环回地址被拦截")
    void guardRejectsLoopback() {
        AiException error = assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(getConfig("http://127.0.0.1:8080/x", null), schema(), List.of()));
        assertTrue(error.getMessage().contains("禁止调用私网"));
    }

    @Test
    @DisplayName("云元数据链路本地地址被拦截")
    void guardRejectsCloudMetadata() {
        AiException error = assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(
                        getConfig("http://169.254.169.254/latest/meta-data", null), schema(), List.of()));
        assertTrue(error.getMessage().contains("禁止调用私网"));
    }

    @Test
    @DisplayName("私网段地址被拦截")
    void guardRejectsPrivateRanges() {
        for (String host : List.of("10.1.2.3", "192.168.1.10", "172.16.5.5", "0.0.0.0")) {
            assertThrows(
                    AiException.class,
                    () -> HttpToolExecutor.validate(getConfig("http://" + host + "/x", null), schema(), List.of()),
                    host);
        }
    }

    @Test
    @DisplayName("白名单非空时，未列入的主机被拒绝")
    void guardRejectsHostOutsideAllowlist() {
        AiException error = assertThrows(
                AiException.class,
                () -> HttpToolExecutor.validate(
                        getConfig("https://" + OK_HOST + "/x", null), schema(), List.of("api.example.com")));
        assertTrue(error.getMessage().contains("不在白名单内"));
    }

    @Test
    @DisplayName("白名单允许 host:port 形态写法")
    void guardAcceptsHostInsideAllowlist() {
        HttpToolExecutor.validate(
                getConfig("https://" + OK_HOST + ":8443/x", null), schema(), List.of(OK_HOST + ":8443"));
    }

    @Test
    @DisplayName("从配置里取 host 时去掉端口与 IPv6 方括号")
    void hostOfStripsPort() {
        assertEquals("example.com", HttpToolExecutor.hostOf(object(getConfig("https://example.com:8443/x", null))));
        assertEquals("::1", HttpToolExecutor.hostOf(object(getConfig("https://[::1]:8443/x", null))));
    }

    // ==================== 模板渲染 ====================

    @Test
    @DisplayName("path 模板替换参数并做百分号编码")
    void renderSubstitutesAndEncodes() {
        ObjectNode args = object("{\"orderId\":\"A 1/2\",\"channel\":\"app\"}");
        String rendered = HttpToolExecutor.render("/v1/orders/${orderId}", args, key -> "unused", true);
        assertEquals("/v1/orders/A%201%2F2", rendered);
    }

    @Test
    @DisplayName("secret 占位符从密钥函数取值，且不参与参数校验")
    void renderUsesSecretFunction() {
        ObjectNode args = object("{}");
        Function<String, String> secrets = key -> "k-" + key;
        assertEquals("Bearer k-api_token", HttpToolExecutor.render("Bearer ${secret:api_token}", args, secrets, false));
    }

    @Test
    @DisplayName("query 模板按键值分别渲染并编码")
    void renderQueryEncodesPairs() {
        ObjectNode args = object("{\"channel\":\"app ios\",\"page\":\"2\"}");
        assertEquals(
                "channel=app%20ios&page=2",
                HttpToolExecutor.renderQuery("channel=${channel}&page=${page}", args, key -> "x"));
    }

    @Test
    @DisplayName("body 模板里字符串参数按 JSON 转义，对象参数原样内联")
    void renderJsonHandlesTypes() {
        ObjectNode args = object("{\"id\":42,\"note\":\"a\\\"b\",\"meta\":{\"x\":1}}");
        String rendered =
                HttpToolExecutor.renderJson("{\"id\":${id},\"note\":${note},\"meta\":${meta}}", args, key -> "x");
        assertEquals("{\"id\":42,\"note\":\"a\\\"b\",\"meta\":{\"x\":1}}", rendered);
    }

    @Test
    @DisplayName("缺少参数时渲染直接报错")
    void renderMissingArgThrows() {
        AiException error = assertThrows(
                AiException.class, () -> HttpToolExecutor.render("/x/${missing}", object("{}"), key -> "x", false));
        assertTrue(error.getMessage().contains("缺少参数"));
    }

    @Test
    @DisplayName("密钥找不到时给出配置指引")
    void lookupSecretMissingGivesHint() {
        AiException error = assertThrows(
                AiException.class, () -> HttpToolExecutor.lookupSecret("astral_test_absent_secret_key", Map.of()));
        assertTrue(error.getMessage().contains("找不到密钥"));
    }

    @Test
    @DisplayName("密钥优先取配置，其次环境变量")
    void lookupSecretPrefersConfigured() {
        assertEquals("from-config", HttpToolExecutor.lookupSecret("k", Map.of("k", "from-config")));
    }

    // ==================== 取值 ====================

    @Test
    @DisplayName("简化 JSON 路径支持嵌套字段与数组下标")
    void extractPathNestedAndIndexed() {
        JsonNode root = AiJson.read("{\"data\":{\"items\":[{\"name\":\"first\"},{\"name\":\"second\"}]}}");
        assertEquals(
                "second",
                HttpToolExecutor.extractPath(root, "data.items.1.name").asString());
        assertNull(HttpToolExecutor.extractPath(root, "data.missing.name"));
        assertNull(HttpToolExecutor.extractPath(root, ""));
        assertNull(HttpToolExecutor.extractPath(null, "data"));
    }
}
