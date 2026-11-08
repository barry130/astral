package com.astral.common.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedHttpTransportTest {
    @Test
    void deadlineIncludesBlockedAddressApprovalAndNeverSendsAfterTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        CountDownLatch resolved = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var call = executor.submit(() -> assertThrows(
                        IOException.class,
                        () -> BoundedHttpTransport.send(
                                URI.create("http://fixture.invalid:"
                                        + server.getAddress().getPort()),
                                "GET",
                                Map.of(),
                                null,
                                Duration.ofMillis(250),
                                1024,
                                target -> {
                                    resolving.countDown();
                                    // Native DNS may ignore cancellation. It must still never trigger a late send.
                                    while (releaseResolver.getCount() != 0) {
                                        try {
                                            releaseResolver.await();
                                        } catch (InterruptedException ignored) {
                                            // Deliberately model an uninterruptible resolver.
                                        }
                                    }
                                    resolved.countDown();
                                    return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                                })));
                assertTrue(resolving.await(1, TimeUnit.SECONDS));
                assertTrue(call.get(2, TimeUnit.SECONDS).getMessage().contains("超时"));
                assertEquals(1, releaseResolver.getCount());
            } finally {
                releaseResolver.countDown();
            }
            assertTrue(resolved.await(1, TimeUnit.SECONDS));
            assertEquals(0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesSingleApprovedResolutionAndPreservesHostWithoutFollowingRedirect() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> host = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.start();
        try {
            AtomicInteger resolutions = new AtomicInteger();
            URI uri = URI.create(
                    "http://approved-fixture.invalid:" + server.getAddress().getPort() + "/");
            var response =
                    BoundedHttpTransport.send(uri, "GET", Map.of(), null, Duration.ofSeconds(3), 4096, target -> {
                        assertEquals(uri, target);
                        resolutions.incrementAndGet();
                        return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
                    });
            assertEquals(307, response.status());
            assertEquals(1, hits.get());
            assertEquals(1, resolutions.get());
            assertEquals("approved-fixture.invalid:" + server.getAddress().getPort(), host.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedChunkedBodyBeforeSenderFinishes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch releaseSender = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[8192]);
                exchange.getResponseBody().flush();
                releaseSender.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> assertThrows(
                    IOException.class,
                    () -> BoundedHttpTransport.send(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            "GET",
                            Map.of(),
                            null,
                            Duration.ofSeconds(6),
                            4096,
                            target -> new InetAddress[] {InetAddress.getByName("127.0.0.1")})));
            IOException failure = future.get(3, TimeUnit.SECONDS);
            assertTrue(failure.getMessage().contains("体积"));
            assertEquals(1, releaseSender.getCount(), "overflow must return before EOF, not after buffering the body");
        } finally {
            releaseSender.countDown();
            server.stop(0);
        }
    }

    @Test
    void deadlineCoversStreamingBodyAndInvalidHeadersHaveSafeErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch releaseSender = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("small".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                releaseSender.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var failure = executor.submit(() -> assertThrows(
                            IOException.class,
                            () -> BoundedHttpTransport.send(
                                    uri, "GET", Map.of(), null, Duration.ofMillis(400), 4096, target ->
                                            new InetAddress[] {InetAddress.getByName("127.0.0.1")})))
                    .get(3, TimeUnit.SECONDS);
            assertTrue(failure.getMessage().contains("超时"));
            var invalid = assertThrows(
                    IllegalArgumentException.class,
                    () -> BoundedHttpTransport.send(
                            uri,
                            "GET",
                            Map.of("Authorization", "private-fixture\u0001"),
                            null,
                            Duration.ofSeconds(2),
                            4096,
                            target -> new InetAddress[] {InetAddress.getByName("127.0.0.1")}));
            assertFalse(invalid.getMessage().contains("private-fixture"));
        } finally {
            releaseSender.countDown();
            server.stop(0);
        }
    }
}
