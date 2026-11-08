package com.astral.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.common.AiException;
import com.astral.ai.provider.AiProviderDtos.ChatRequest;
import com.astral.ai.provider.AiProviderDtos.ChatResult;
import com.astral.ai.provider.AiProviderDtos.Message;
import com.astral.ai.provider.AiProviderDtos.StreamListener;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderTransportTest {
    @Test
    void sameNamedStreamCallsWithoutProviderIdsKeepDistinctIdentities() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools/chat/completions", exchange -> {
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"tool_calls\":["
                            + "{\"index\":0,\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}},"
                            + "{\"index\":1,\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}},"
                            + "{\"index\":2,\"id\":\"upstream-id\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}"
                            + "]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String previous = System.getProperty("astral.plugins.ai.allow-private-hosts");
        System.setProperty("astral.plugins.ai.allow-private-hosts", "127.0.0.1");
        try {
            var listener = new RecordingListener();
            new OpenAiCompatibleProvider().stream(request(server, "tools", 2000), listener);
            assertEquals(null, listener.error.get());
            var calls = listener.result.get().toolCalls();
            assertEquals(
                    3,
                    calls.stream().map(AiProviderDtos.ToolCall::id).distinct().count());
            assertEquals("upstream-id", calls.getLast().id());
            assertTrue(calls.stream()
                    .noneMatch(call -> call.id() == null || call.id().isBlank()));
        } finally {
            restore(previous);
            server.stop(0);
        }
    }

    @Test
    void configuredConnectTimeoutBoundsStalledTlsHandshakeIndependentlyOfReadBudget() throws Exception {
        String previous = System.getProperty("astral.plugins.ai.allow-private-hosts");
        System.setProperty("astral.plugins.ai.allow-private-hosts", "127.0.0.1");
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> {
                try (var socket = server.accept()) {
                    accepted.countDown();
                    release.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            var request = new ChatRequest(
                    "https://127.0.0.1:" + server.getLocalPort(),
                    "fixture-secret",
                    "fixture",
                    List.of(Message.user("hello")),
                    null,
                    null,
                    null,
                    16,
                    null,
                    null,
                    250,
                    8000,
                    0,
                    "fixture");
            var result = pool.submit(
                    () -> assertThrows(AiException.class, () -> new OpenAiCompatibleProvider().chat(request)));
            try {
                assertTrue(accepted.await(2, TimeUnit.SECONDS));
                assertFalse(result.get(2, TimeUnit.SECONDS).getMessage().contains("fixture-secret"));
            } finally {
                release.countDown();
            }
        } finally {
            restore(previous);
        }
    }

    @Test
    void appendsEndpointBeforeQueryAndRetainsLongerResponseBudget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> target = new AtomicReference<>();
        server.createContext("/", exchange -> {
            target.set(exchange.getRequestURI().toString());
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String previous = System.getProperty("astral.plugins.ai.allow-private-hosts");
        System.setProperty("astral.plugins.ai.allow-private-hosts", "127.0.0.1");
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/?route=fixture%20value";
            var request = new ChatRequest(
                    base,
                    null,
                    "fixture",
                    List.of(Message.user("hello")),
                    null,
                    null,
                    null,
                    16,
                    null,
                    null,
                    100,
                    3000,
                    0,
                    "fixture");
            assertEquals("ok", new OpenAiCompatibleProvider().chat(request).content());
            assertEquals("/v1/chat/completions?route=fixture%20value", target.get());
        } finally {
            restore(previous);
            server.stop(0);
        }
    }

    @Test
    void streamsBeforeEofAndDeadlineStopsAStalledBodyWithoutClaimingSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delta = new CountDownLatch(1);
        server.createContext("/stall/chat/completions", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody()
                        .write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        String previous = System.getProperty("astral.plugins.ai.allow-private-hosts");
        System.setProperty("astral.plugins.ai.allow-private-hosts", "127.0.0.1");
        try (var pool = Executors.newSingleThreadExecutor()) {
            var listener = new RecordingListener();
            listener.onText = delta::countDown;
            var future =
                    pool.submit(() -> new OpenAiCompatibleProvider().stream(request(server, "stall", 600), listener));
            assertTrue(delta.await(2, TimeUnit.SECONDS));
            future.get(3, TimeUnit.SECONDS);
            assertEquals(1, release.getCount());
            assertNotNull(listener.error.get());
            assertEquals(null, listener.result.get());
            assertEquals("first", listener.content.toString());
        } finally {
            release.countDown();
            restore(previous);
            server.stop(0);
        }
    }

    @Test
    void errorResponsesNeverExposeEchoedCredentialsAndIncompleteStreamsNeverSucceed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/error/chat/completions", exchange -> {
            byte[] body = ("{\"error\":{\"message\":\""
                            + exchange.getRequestHeaders().getFirst("Authorization") + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/partial/chat/completions", exchange -> {
            byte[] body =
                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String previous = System.getProperty("astral.plugins.ai.allow-private-hosts");
        System.setProperty("astral.plugins.ai.allow-private-hosts", "127.0.0.1");
        try {
            var provider = new OpenAiCompatibleProvider();
            var error = assertThrows(AiException.class, () -> provider.chat(request(server, "error", 2000)));
            assertFalse(error.getMessage().contains("fixture-secret"));
            assertFalse(provider.testConnection(request(server, "error", 2000))
                    .message()
                    .contains("fixture-secret"));
            var listener = new RecordingListener();
            provider.stream(request(server, "partial", 2000), listener);
            assertNotNull(listener.error.get());
            assertEquals(null, listener.result.get());
            assertEquals("partial", listener.content.toString());
        } finally {
            restore(previous);
            server.stop(0);
        }
    }

    private static ChatRequest request(HttpServer server, String path, int timeout) {
        return new ChatRequest(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/" + path,
                "fixture-secret",
                "fixture",
                List.of(Message.user("hello")),
                null,
                null,
                null,
                16,
                null,
                null,
                timeout,
                timeout,
                0,
                "fixture");
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty("astral.plugins.ai.allow-private-hosts");
        else System.setProperty("astral.plugins.ai.allow-private-hosts", previous);
    }

    private static final class RecordingListener implements StreamListener {
        private final StringBuilder content = new StringBuilder();
        private final AtomicReference<ChatResult> result = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private Runnable onText = () -> {};

        @Override
        public void onDelta(String text) {
            content.append(text);
            onText.run();
        }

        @Override
        public void onCompleted(ChatResult completed) {
            result.set(completed);
        }

        @Override
        public void onError(Throwable failure) {
            error.set(failure);
        }
    }
}
