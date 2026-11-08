package com.astral.ai.tool.declarative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolHttpTransportTest {
    @Test
    void mcpProtocolErrorsAndMalformedUrlsNeverEchoCredentials() throws Exception {
        String marker = "credential-echo-fixture";
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            var body = AiJson.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = AiJson.mapper().createObjectNode().put("jsonrpc", "2.0");
            response.set("id", body.get("id"));
            response.putObject("error")
                    .put("code", -32000)
                    .put(
                            "message",
                            "Invalid token: " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = AiJson.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        receiver.start();
        try {
            var failure = assertThrows(
                    AiException.class,
                    () -> McpClient.connectApproved(
                            URI.create("http://mcp-fixture.invalid:"
                                    + receiver.getAddress().getPort()),
                            Map.of("Authorization", "Bearer " + marker),
                            Duration.ofSeconds(3),
                            uri -> new InetAddress[] {InetAddress.getByName("127.0.0.1")}));
            assertFalse(failure.getMessage().contains(marker));
            for (String url : List.of("http://bad host/?token=" + marker, "http:///path?token=" + marker)) {
                var invalid = assertThrows(AiException.class, () -> McpClient.requireEndpoint(url, List.of()));
                assertFalse(invalid.getMessage().contains(marker));
            }
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void httpExecutorUsesApprovedAddressAndReturnsBoundedFailure() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        receiver.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, 8192);
            exchange.getResponseBody().write(new byte[8192]);
            exchange.close();
        });
        receiver.start();
        try {
            AiToolEntity tool = new AiToolEntity();
            tool.setToolCode("bounded_fixture");
            var config = AiJson.mapper().createObjectNode();
            config.put(
                    "url",
                    "http://http-fixture.invalid:" + receiver.getAddress().getPort() + "/");
            config.put("maxResponseBytes", 4096);
            var executor = new HttpToolExecutor(tool, config, new AiProperties.HttpTool(), uri ->
                    new InetAddress[] {InetAddress.getByName("127.0.0.1")});
            var result = executor.execute(new AiToolContext(
                    1L,
                    1L,
                    1L,
                    1L,
                    "fixture",
                    "CUSTOM",
                    "ASTRAL_FRONT",
                    AiJson.mapper().createObjectNode(),
                    AiJson.mapper().createObjectNode(),
                    false,
                    null));
            assertEquals("FAILED", result.status());
            assertEquals(1, hits.get());
            assertFalse(result.summary().contains("http-fixture.invalid"));
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void mcpHandshakeListCallAndCloseUsePinnedTransportAndSession() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        receiver.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) {
                assertEquals("fixture-session", exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                closed.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            } else {
                var body = AiJson.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String method = body.path("method").asString();
                if (method.equals("notifications/initialized")) {
                    exchange.sendResponseHeaders(202, -1);
                } else {
                    var response = AiJson.mapper().createObjectNode().put("jsonrpc", "2.0");
                    response.set("id", body.get("id"));
                    switch (method) {
                        case "initialize" -> response.set("result", AiJson.read("{}"));
                        case "tools/list" ->
                            response.set(
                                    "result", AiJson.read("{\"tools\":[{\"name\":\"fixture\",\"inputSchema\":{}}]}"));
                        case "tools/call" -> {
                            if (body.path("params")
                                    .path("arguments")
                                    .path("fail")
                                    .asBoolean(false)) {
                                var result = response.putObject("result").put("isError", true);
                                result.putArray("content")
                                        .addObject()
                                        .put("type", "text")
                                        .put(
                                                "text",
                                                "Invalid token: "
                                                        + exchange.getRequestHeaders()
                                                                .getFirst("Authorization"));
                            } else {
                                response.set(
                                        "result",
                                        AiJson.read(
                                                "{\"content\":[{\"type\":\"text\",\"text\":\"fixture completed\"}]}"));
                            }
                        }
                        default -> throw new IllegalStateException("Unexpected method");
                    }
                    exchange.getResponseHeaders().set("Mcp-Session-Id", "fixture-session");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    byte[] bytes = AiJson.write(response).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            }
            exchange.close();
        });
        receiver.start();
        URI endpoint =
                URI.create("http://mcp-fixture.invalid:" + receiver.getAddress().getPort() + "/mcp");
        try {
            try (var client = McpClient.connectApproved(
                    endpoint, Map.of("Authorization", "Bearer fixture"), Duration.ofSeconds(3), uri -> {
                        assertEquals(endpoint, uri);
                        approved.incrementAndGet();
                        return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                    })) {
                assertEquals("fixture", client.listTools().getFirst().name());
                assertEquals(
                        "fixture completed", client.callTool("fixture", null).text());
                var failed = client.callTool(
                        "fixture", AiJson.mapper().createObjectNode().put("fail", true));
                assertTrue(failed.error());
                assertFalse(failed.text().contains("Bearer fixture"));
                assertFalse(AiJson.write(failed.payload()).contains("Bearer fixture"));
            }
            assertEquals(6, approved.get());
            assertEquals(1, closed.get());
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void mcpRejectsOversizedHandshakeWithoutReturningRemoteBody() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 1048577);
            exchange.close();
        });
        receiver.start();
        try {
            var failure = assertThrows(
                    AiException.class,
                    () -> McpClient.connectApproved(
                            URI.create("http://mcp-fixture.invalid:"
                                    + receiver.getAddress().getPort()),
                            Map.of(),
                            Duration.ofSeconds(3),
                            uri -> new InetAddress[] {InetAddress.getByName("127.0.0.1")}));
            assertTrue(failure.getMessage().contains("响应大小"));
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void rejectsPrivateAndMetadataDestinationsEvenWhenHostnameIsAllowlisted() {
        for (String host : List.of("127.0.0.1", "169.254.169.254", "100.100.100.200", "198.18.0.1")) {
            assertThrows(
                    AiException.class,
                    () -> HttpToolExecutor.approveTarget(URI.create("http://" + host), List.of(host)));
        }
        assertThrows(
                AiException.class,
                () -> HttpToolExecutor.approveTarget(
                        URI.create("https://93.184.216.34:444"), List.of("93.184.216.34:443")));
    }
}
