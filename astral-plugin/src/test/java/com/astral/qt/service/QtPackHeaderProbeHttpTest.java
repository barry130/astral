package com.astral.qt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;

import com.astral.qt.mapper.QtSourceReleaseMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class QtPackHeaderProbeHttpTest {
    private static final String HEADER = "/*__QT_PACK__{\"kind\":\"meta\",\"id\":\"fixture\",\"versionCode\":7}*/";

    @Test
    void ignoredRangeDoesNotRequireDownloadingTheWholeArtifact() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    assertEquals("bytes=0-8191", exchange.getRequestHeaders().getFirst("Range"));
                    exchange.sendResponseHeaders(200, 1024L * 1024 * 1024);
                    exchange.getResponseBody()
                            .write((HEADER + " ".repeat(8192 - HEADER.length())).getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                var probe = localProbe();
                var header = assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () -> probe.probe(
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/", Duration.ofMillis(700)));
                assertNotNull(header);
                assertEquals(7, header.versionCode());
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    @Test
    void slowBodyCannotExtendTheTotalDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                var probe = localProbe();
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () -> assertNull(probe.probe(
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/", Duration.ofMillis(200))));
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    @Test
    void redirectsAreApprovedIndividuallyAndStopAfterFiveHops() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var approvals = new java.util.concurrent.atomic.AtomicInteger();
        var forbiddenHits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/pack");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/pack", exchange -> {
            byte[] body = HEADER.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/blocked", exchange -> {
            exchange.getResponseHeaders().set("Location", "/forbidden");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/forbidden", exchange -> {
            forbiddenHits.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var probe = new QtPackHeaderProbe(mock(QtSourceReleaseMapper.class), new ObjectMapper(), target -> {
                approvals.incrementAndGet();
                if (target.getPath().equals("/forbidden")) throw new java.io.IOException("fixture rejection");
                return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            });
            String base = "http://pack.invalid:" + server.getAddress().getPort();
            assertEquals(
                    7, probe.probe(base + "/redirect", Duration.ofSeconds(2)).versionCode());
            assertEquals(2, approvals.getAndSet(0));
            assertNull(probe.probe(base + "/loop", Duration.ofSeconds(2)));
            assertEquals(6, approvals.getAndSet(0));
            assertNull(probe.probe(base + "/blocked", Duration.ofSeconds(2)));
            assertEquals(2, approvals.get());
            assertEquals(0, forbiddenHits.get());
        } finally {
            server.stop(0);
        }
    }

    private QtPackHeaderProbe localProbe() {
        return new QtPackHeaderProbe(mock(QtSourceReleaseMapper.class), new ObjectMapper(), target ->
                new InetAddress[] {InetAddress.getByName("127.0.0.1")});
    }
}
