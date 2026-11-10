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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /**
     * 线上官方 meta 包回归：首行包头含完整 changelog，实测 30,755 字节，固定 8K 窗口永远拿不到
     * 结尾 {@code *&#47;}。自适应窗口必须升到 64K 档取到完整包头并解析出版本号——这正是线上
     * manifest 的 packId/packVersionCode/packVersionName 恒为 null 的根因。
     */
    @Test
    void thirtyKilobyteHeaderIsParsedByWideningTheRangeWindow() throws Exception {
        byte[] file = (packHeaderLine(30_755) + "\n(function(){})();\n").getBytes(StandardCharsets.UTF_8);
        var ranges = new CopyOnWriteArrayList<String>();
        HttpServer server = startRangeAwareServer(file, ranges);
        try {
            var probe = localProbe();
            var header = probe.probe(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/meta-bundle.js", Duration.ofSeconds(4));
            assertNotNull(header);
            assertEquals("meta-official", header.id());
            assertEquals(2026110901L, header.versionCode());
            assertEquals(List.of("bytes=0-8191", "bytes=0-65535"), ranges);
        } finally {
            server.stop(0);
        }
    }

    /** HTML 跳转页没有包头标记：不得升档重试，一次请求就返回 null（否则等于反复锤跳转页） */
    @Test
    void htmlPageWithoutMarkerIsNotRetried() throws Exception {
        byte[] html = ("<!DOCTYPE html><html><head><title>302 Found</title></head>"
                        + "<body>Redirecting to OSS...</body></html>")
                .getBytes(StandardCharsets.UTF_8);
        var ranges = new CopyOnWriteArrayList<String>();
        HttpServer server = startRangeAwareServer(html, ranges);
        try {
            var probe = localProbe();
            assertNull(probe.probe(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect.html", Duration.ofSeconds(2)));
            assertEquals(List.of("bytes=0-8191"), ranges);
        } finally {
            server.stop(0);
        }
    }

    /** 有结尾 {@code *&#47;} 但 JSON 损坏：立即 null，不靠「换大窗口再试」掩盖坏内容 */
    @Test
    void brokenJsonIsNotRetried() throws Exception {
        byte[] body = "/*__QT_PACK__{kind:meta}*/\nbody".getBytes(StandardCharsets.UTF_8);
        var ranges = new CopyOnWriteArrayList<String>();
        HttpServer server = startRangeAwareServer(body, ranges);
        try {
            var probe = localProbe();
            assertNull(probe.probe(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/broken.js", Duration.ofSeconds(2)));
            assertEquals(List.of("bytes=0-8191"), ranges);
        } finally {
            server.stop(0);
        }
    }

    /** 重试到 512KiB 上限仍被截断 → 返回 null，窗口阶梯恰好走完 8K/64K/256K/512K 各一次 */
    @Test
    void truncatedHeaderStopsAfterReachingTheWindowCap() throws Exception {
        byte[] body = (QtPackHeaderProbe.PACK_HEADER_MARK + "{\"kind\":\"meta\",\"id\":\"truncated\",\"notes\":\""
                        + "x".repeat(600 * 1024))
                .getBytes(StandardCharsets.UTF_8);
        var ranges = new CopyOnWriteArrayList<String>();
        HttpServer server = startRangeAwareServer(body, ranges);
        try {
            var probe = localProbe();
            assertNull(probe.probe(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/truncated.js", Duration.ofSeconds(10)));
            assertEquals(List.of("bytes=0-8191", "bytes=0-65535", "bytes=0-262143", "bytes=0-524287"), ranges);
        } finally {
            server.stop(0);
        }
    }

    /**
     * 单行自述包头（与官方 meta 包同形态）：notes 把首行撑到 {@code totalBytes} 字节。
     * 纯 ASCII 构造，字符数=字节数，窗口按 Range 字节数切分时长度可精确预期。
     */
    private static String packHeaderLine(int totalBytes) {
        String prefix = "/*__QT_PACK__{\"kind\":\"meta\",\"id\":\"meta-official\",\"name\":\"official-data\","
                + "\"versionCode\":2026110901,\"versionName\":\"2026.11.09.01\",\"notes\":\"";
        String suffix = "\"}*/";
        return prefix + "n".repeat(totalBytes - prefix.length() - suffix.length()) + suffix;
    }

    /** 按 Range 返回前缀的本地服务（206）：每次请求的 Range 头按序记进 {@code ranges}，便于断言窗口阶梯 */
    private static HttpServer startRangeAwareServer(byte[] body, List<String> ranges) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String range = exchange.getRequestHeaders().getFirst("Range");
                ranges.add(range == null ? "<missing>" : range);
                int length = body.length;
                if (range != null && range.startsWith("bytes=0-")) {
                    length = Math.min(body.length, Integer.parseInt(range.substring("bytes=0-".length())) + 1);
                }
                exchange.sendResponseHeaders(206, length);
                exchange.getResponseBody().write(body, 0, length);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private QtPackHeaderProbe localProbe() {
        return new QtPackHeaderProbe(mock(QtSourceReleaseMapper.class), new ObjectMapper(), target ->
                new InetAddress[] {InetAddress.getByName("127.0.0.1")});
    }
}
