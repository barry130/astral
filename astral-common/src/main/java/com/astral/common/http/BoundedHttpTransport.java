package com.astral.common.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

/** One-shot HTTP transport with approved DNS, bounded response reads and an absolute deadline. */
public final class BoundedHttpTransport {
    private static final ExecutorService RESOLVERS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("outbound-http-resolver-", 0).factory());
    // Native DNS may ignore interruption. Bound concurrent resolver work even after callers time out.
    private static final Semaphore RESOLVER_SLOTS = new Semaphore(32);
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "outbound-http-deadline");
        thread.setDaemon(true);
        return thread;
    });

    private BoundedHttpTransport() {}

    /** The caller's security policy must validate every resolved address before returning it. */
    @FunctionalInterface
    public interface TargetPolicy {
        InetAddress[] approve(URI target) throws IOException;
    }

    public record Response(int status, Map<String, String> headers, byte[] body) {
        public Response {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        public String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public static Response send(
            URI target,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration budget,
            int maxBytes,
            TargetPolicy policy)
            throws IOException {
        return stream(
                target,
                method,
                headers,
                body,
                budget,
                maxBytes,
                policy,
                (status, responseHeaders, input) -> new Response(status, responseHeaders, input.readAllBytes()));
    }

    /** Read only an artifact's prefix, even when the server ignores Range. Larger bodies are cancelled, not drained. */
    public static Response prefix(
            URI target, Map<String, String> headers, Duration budget, int maxBytes, TargetPolicy policy)
            throws IOException {
        return stream(
                target,
                "GET",
                headers,
                null,
                budget,
                budget,
                maxBytes,
                policy,
                false,
                (status, responseHeaders, input) -> new Response(
                        status,
                        responseHeaders,
                        status == 200 || status == 206 ? input.readNBytes(maxBytes) : new byte[0]));
    }

    /** The stream is only valid during the callback and is bounded by the same deadline and byte cap. */
    @FunctionalInterface
    public interface StreamHandler<T> {
        T read(int status, Map<String, String> headers, InputStream input) throws IOException;
    }

    public static <T> T stream(
            URI target,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration budget,
            int maxBytes,
            TargetPolicy policy,
            StreamHandler<T> handler)
            throws IOException {
        return stream(target, method, headers, body, budget, budget, maxBytes, policy, true, handler);
    }

    /** Bound address resolution, TCP and TLS establishment separately from the overall response budget. */
    public static <T> T stream(
            URI target,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration budget,
            Duration connectBudget,
            int maxBytes,
            TargetPolicy policy,
            StreamHandler<T> handler)
            throws IOException {
        return stream(target, method, headers, body, budget, connectBudget, maxBytes, policy, true, handler);
    }

    private static <T> T stream(
            URI target,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration budget,
            Duration connectBudget,
            int maxBytes,
            TargetPolicy policy,
            boolean rejectLargerResponse,
            StreamHandler<T> handler)
            throws IOException {
        if (target == null
                || target.getHost() == null
                || target.getUserInfo() != null
                || target.getFragment() != null
                || !("https".equalsIgnoreCase(target.getScheme()) || "http".equalsIgnoreCase(target.getScheme()))) {
            throw new IllegalArgumentException("HTTP 目标地址非法");
        }
        if (budget == null
                || budget.isNegative()
                || budget.isZero()
                || connectBudget == null
                || connectBudget.isNegative()
                || connectBudget.isZero()
                || maxBytes < 0
                || maxBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("HTTP 预算非法");
        }
        long expiresAt = System.nanoTime() + budget.toNanos();
        long connectExpiresAt = System.nanoTime() + Math.min(connectBudget.toNanos(), budget.toNanos());
        for (var header : headers.entrySet()) {
            validateHeader(header.getKey(), header.getValue());
        }
        InetAddress[] addresses = approveBefore(target, policy, Math.min(expiresAt, connectExpiresAt));
        if (addresses.length == 0) {
            throw new IOException("HTTP 目标没有获准的地址");
        }
        String expectedHost = host(target.getHost());
        DnsResolver pinned = new DnsResolver() {
            private void requireHost(String requested) throws UnknownHostException {
                if (!expectedHost.equals(host(requested))) {
                    throw new UnknownHostException("HTTP 目标主机发生变化");
                }
            }

            @Override
            public InetAddress[] resolve(String requested) throws UnknownHostException {
                requireHost(requested);
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String requested) throws UnknownHostException {
                requireHost(requested);
                return expectedHost;
            }
        };
        long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining(expiresAt)));
        Timeout timeout = Timeout.ofMilliseconds(millis);
        Timeout connectTimeout = Timeout.ofMilliseconds(
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining(Math.min(expiresAt, connectExpiresAt)))));
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinned)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(connectTimeout)
                        .setSocketTimeout(timeout)
                        .build())
                .setDefaultTlsConfig(
                        TlsConfig.custom().setHandshakeTimeout(connectTimeout).build())
                .build();
        var config = RequestConfig.custom()
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .setHardCancellationEnabled(true)
                .build();
        HttpUriRequestBase request = new HttpUriRequestBase(method, target);
        headers.forEach(request::setHeader);
        if (body != null) {
            request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_OCTET_STREAM));
        }
        AtomicBoolean expired = new AtomicBoolean();
        var deadline = DEADLINES.schedule(
                () -> {
                    expired.set(true);
                    request.cancel();
                },
                Math.max(0, expiresAt - System.nanoTime()),
                TimeUnit.NANOSECONDS);
        try (var client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(config)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .build()) {
            remaining(expiresAt);
            return client.execute(request, response -> {
                Map<String, String> responseHeaders = new LinkedHashMap<>();
                for (var header : response.getHeaders()) {
                    responseHeaders.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
                }
                var entity = response.getEntity();
                if (rejectLargerResponse && entity != null && entity.getContentLength() > maxBytes) {
                    request.cancel();
                    throw new IOException("HTTP 响应超过体积上限");
                }
                try {
                    InputStream input = entity == null ? InputStream.nullInputStream() : entity.getContent();
                    InputStream bounded = new FilterInputStream(input) {
                        private int consumed;

                        @Override
                        public int read() throws IOException {
                            byte[] single = new byte[1];
                            return read(single, 0, 1) == -1 ? -1 : single[0] & 0xff;
                        }

                        @Override
                        public int read(byte[] bytes, int offset, int length) throws IOException {
                            remaining(expiresAt);
                            int count = in.read(bytes, offset, Math.min(length, maxBytes - consumed + 1));
                            if (count > 0) consumed += count;
                            if (consumed > maxBytes) throw new IOException("HTTP 响应超过体积上限");
                            remaining(expiresAt);
                            return count;
                        }
                    };
                    T result = handler.read(response.getCode(), Map.copyOf(responseHeaders), bounded);
                    if (expired.get()) {
                        throw new IOException("HTTP 请求超时");
                    }
                    return result;
                } finally {
                    // A handler may stop at an SSE terminator or reject the status before EOF.
                    // Never drain an untrusted remainder after it has finished.
                    request.cancel();
                }
            });
        } catch (IOException | RuntimeException e) {
            if (expired.get() || e instanceof java.net.SocketTimeoutException) {
                throw new IOException("HTTP 请求超时");
            }
            if (e instanceof IOException failure && "HTTP 响应超过体积上限".equals(e.getMessage())) {
                throw failure;
            }
            // Transport exceptions may contain a credential-bearing URI or header. Do not propagate values.
            throw new IOException("HTTP 连接或读取失败");
        } finally {
            deadline.cancel(false);
        }
    }

    /** Validate at configuration time with the same bounded resolver used by actual sends. */
    public static InetAddress[] approve(URI target, Duration budget, TargetPolicy policy) throws IOException {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("HTTP 预算非法");
        }
        return approveBefore(target, policy, System.nanoTime() + budget.toNanos());
    }

    private static InetAddress[] approveBefore(URI target, TargetPolicy policy, long expiresAt) throws IOException {
        var resolution = RESOLVERS.submit(() -> {
            if (!RESOLVER_SLOTS.tryAcquire()) {
                throw new IOException("HTTP 地址解析繁忙");
            }
            try {
                return policy.approve(target).clone();
            } finally {
                RESOLVER_SLOTS.release();
            }
        });
        try {
            return resolution.get(remaining(expiresAt), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new IOException("HTTP 请求超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP 请求已中断");
        } catch (ExecutionException e) {
            throw new IOException("HTTP 目标地址未获批准或无法解析");
        } finally {
            resolution.cancel(true);
        }
    }

    private static long remaining(long expiresAt) throws IOException {
        long nanos = expiresAt - System.nanoTime();
        if (nanos <= 0) {
            throw new IOException("HTTP 请求超时");
        }
        return nanos;
    }

    public static void validateHeader(String name, String value) {
        if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value == null) {
            throw new IllegalArgumentException("HTTP 请求头非法");
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.equals("host")
                || normalized.equals("content-length")
                || normalized.equals("transfer-encoding")
                || normalized.equals("connection")
                || normalized.equals("upgrade")
                || normalized.equals("proxy-authorization")) {
            throw new IllegalArgumentException("HTTP 请求头由传输层管理");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < 0x20 && character != '\t') || character == 0x7f || character > 0xff) {
                throw new IllegalArgumentException("HTTP 请求头含有非法字符");
            }
        }
    }

    private static String host(String value) {
        String result = value.toLowerCase(Locale.ROOT);
        if (result.startsWith("[") && result.endsWith("]")) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }
}
