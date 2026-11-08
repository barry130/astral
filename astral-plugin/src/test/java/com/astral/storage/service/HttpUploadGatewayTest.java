package com.astral.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.storage.config.StorageProperties;
import com.astral.storage.http.HttpChannelTestTransport;
import com.astral.storage.http.HttpProfileValidator;
import com.astral.storage.http.HttpResponseParser;
import com.astral.storage.http.HttpUploadProfile;
import com.astral.storage.http.PinnedDnsResolver;
import com.astral.storage.mapper.StorageConfigMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;

/**
 * 使用真实 HttpClient5 connection operator，将 Socket.connect 之前的底层 Socket 隔离。
 * 旧 LayeredConnectionSocketFactory.connectSocket 在当前 HttpClient5 中不再是 TCP 连接入口。
 * 测试截获固定地址立即抛异常，不执行 TCP/TLS 网络 I/O；原主机名断言不冒充证书握手验证。
 */
class HttpUploadGatewayTest {

    private static final String HOST = "img.example.com";
    private static final InetAddress PUBLIC_IP = ip("93.184.216.34");

    @Test
    void sendBindsValidatedPublicIpv4ToActualSocketConnectAndResolvesHostOnlyOnce() {
        AtomicInteger resolverCalls = new AtomicInteger();
        CapturingSocketFactory socketFactory = new CapturingSocketFactory();
        HttpUploadGateway gateway = new HttpUploadGateway(
                mock(StorageConfigMapper.class),
                null,
                mock(HttpProfileValidator.class),
                mock(HttpResponseParser.class));
        gateway.setHostResolverForTest(host -> {
            resolverCalls.incrementAndGet();
            assertEquals(HOST, host);
            return new InetAddress[] {PUBLIC_IP};
        });
        gateway.setHttpClientFactoryForTest(
                (resolver, connectTimeoutMs, attemptMs) -> clientWithCapturingSocketFactory(resolver, socketFactory));

        HttpUploadGateway.Result result = gateway.send(channel(), attempt());

        assertEquals(HttpUploadGateway.Kind.NOT_SENT, result.kind());
        assertEquals("CONNECT_REFUSED", result.safeCode());
        assertEquals(1, resolverCalls.get(), "DNS 只允许在 hostResolver 发生一次");
        assertEquals(1, socketFactory.connects.get(), "客户端只允许建立一次连接");
        assertNotNull(socketFactory.connectedAddress.get());
        assertEquals(PUBLIC_IP, socketFactory.connectedAddress.get(), "Socket.connect 必须使用已校验的固定 IPv4");
        assertEquals(HOST, socketFactory.requestedHost.get(), "真实 operator 仍使用原 URI 主机名");
    }

    @Test
    void sendRejectsNonPublicResolutionBeforeBuildingAnyClient() {
        HttpUploadGateway gateway = new HttpUploadGateway(
                mock(StorageConfigMapper.class),
                null,
                mock(HttpProfileValidator.class),
                mock(HttpResponseParser.class));
        AtomicInteger factoryCalls = new AtomicInteger();
        gateway.setHostResolverForTest(host -> new InetAddress[] {ip("127.0.0.1")});
        gateway.setHttpClientFactoryForTest((resolver, connectTimeoutMs, attemptMs) -> {
            factoryCalls.incrementAndGet();
            throw new AssertionError("private address must not reach HTTP client creation");
        });

        HttpUploadGateway.Result result = gateway.send(channel(), attempt());

        assertEquals(HttpUploadGateway.Kind.NOT_SENT, result.kind());
        assertEquals("TARGET_NOT_APPROVED", result.safeCode());
        assertEquals(0, factoryCalls.get());
    }

    @Test
    void probeErrorsNeverReflectTruncatedOrEncodedCredentials() throws Exception {
        var gateway = new HttpUploadGateway(
                mock(StorageConfigMapper.class),
                null,
                mock(HttpProfileValidator.class),
                mock(HttpResponseParser.class));
        gateway.setHostResolverForTest(host -> new InetAddress[] {PUBLIC_IP});
        String secret = "fixture-private-".repeat(24);
        var response = new BasicClassicHttpResponse(401);
        response.setEntity(
                new StringEntity("{\"message\":\"" + "x".repeat(120) + secret + "\"}", ContentType.APPLICATION_JSON));
        var client = mock(CloseableHttpClient.class);
        when(client.execute(any(HttpUriRequest.class))).thenReturn(CloseableHttpResponse.adapt(response));
        gateway.setHttpClientFactoryForTest((resolver, connect, attempt) -> client);
        var fixture = channel();
        var outcome = gateway.probe(new HttpChannelTestTransport.TestRequest(
                7L, 3L, "fixture", fixture.profile(), Map.of("key", secret), fixture.target(), 5000));
        assertFalse(outcome.success());
        assertFalse(outcome.safeMessage().contains("fixture-private"));
        assertFalse(outcome.safeMessage().contains("xxxxxxxx"));
    }

    @Test
    void dnsTimeoutReturnsBeforeResolverAndDoesNotConnectLater() throws Exception {
        var gateway = new HttpUploadGateway(
                mock(StorageConfigMapper.class),
                null,
                mock(HttpProfileValidator.class),
                mock(HttpResponseParser.class));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resolved = new CountDownLatch(1);
        var clientCalls = new AtomicInteger();
        gateway.setHostResolverForTest(host -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    /* emulate native DNS */
                }
            }
            resolved.countDown();
            return new InetAddress[] {PUBLIC_IP};
        });
        gateway.setHttpClientFactoryForTest((resolver, connect, attempt) -> {
            clientCalls.incrementAndGet();
            throw new IOException("must not send after resolution timed out");
        });
        var fixture = channel();
        var profile = fixture.profile();
        var shortProfile = new HttpUploadProfile(
                profile.schemaVersion(),
                profile.presetId(),
                profile.presetVersion(),
                profile.endpoint(),
                profile.method(),
                profile.query(),
                profile.headers(),
                profile.auth(),
                profile.body(),
                profile.response(),
                profile.limits(),
                new HttpUploadProfile.Timeouts(1000));
        var shortChannel =
                new HttpUploadGateway.Channel(7L, 3L, fixture.providerType(), shortProfile, Map.of(), fixture.target());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var call = pool.submit(() -> gateway.send(shortChannel, attempt()));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertEquals(
                        HttpUploadGateway.Kind.NOT_SENT,
                        call.get(3, TimeUnit.SECONDS).kind());
                assertEquals(1, release.getCount());
            } finally {
                release.countDown();
            }
            assertTrue(resolved.await(1, TimeUnit.SECONDS));
            assertEquals(0, clientCalls.get());
        }
    }

    @Test
    void tlsFailureAfterSendingCannotBeClassifiedAsSafeToRetry() throws Exception {
        for (IOException failure : List.of(
                new javax.net.ssl.SSLException("fixture response record failure"),
                new IOException("fixture wrapper", new javax.net.ssl.SSLException("fixture record failure")))) {
            var gateway = new HttpUploadGateway(
                    mock(StorageConfigMapper.class),
                    null,
                    mock(HttpProfileValidator.class),
                    mock(HttpResponseParser.class));
            gateway.setHostResolverForTest(host -> new InetAddress[] {PUBLIC_IP});
            var client = mock(CloseableHttpClient.class);
            when(client.execute(any(HttpUriRequest.class))).thenThrow(failure);
            gateway.setHttpClientFactoryForTest((resolver, connect, attempt) -> client);
            assertEquals(
                    HttpUploadGateway.Kind.UNKNOWN,
                    gateway.send(channel(), attempt()).kind());
        }
    }

    private static CloseableHttpClient clientWithCapturingSocketFactory(
            PinnedDnsResolver resolver, CapturingSocketFactory socketFactory) {
        PoolingHttpClientConnectionManagerBuilder builder = new PoolingHttpClientConnectionManagerBuilder() {
            @Override
            protected HttpClientConnectionOperator createConnectionOperator(
                    SchemePortResolver schemePortResolver, DnsResolver dnsResolver, TlsSocketStrategy tlsStrategy) {
                TlsSocketStrategy effectiveTls =
                        tlsStrategy == null ? DefaultClientTlsStrategy.createDefault() : tlsStrategy;
                return new DefaultHttpClientConnectionOperator(
                        socketFactory,
                        schemePortResolver,
                        dnsResolver,
                        RegistryBuilder.<TlsSocketStrategy>create()
                                .register("https", effectiveTls)
                                .build()) {
                    @Override
                    protected void onBeforeSocketConnect(HttpContext context, HttpHost host) {
                        socketFactory.requestedHost.set(host.getHostName());
                    }
                };
            }
        };
        PoolingHttpClientConnectionManager connectionManager = builder.setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(5))
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .disableRedirectHandling()
                .build();
    }

    private static HttpUploadGateway.Channel channel() {
        StorageProperties.ApprovedTarget target = new StorageProperties.ApprovedTarget();
        target.setOrigin("https://" + HOST);
        target.setPath("/upload");
        target.setUrlOrigins(List.of("https://" + HOST));
        HttpUploadProfile profile = new HttpUploadProfile(
                1,
                null,
                null,
                "https://" + HOST + "/upload",
                HttpUploadProfile.METHOD_POST,
                Map.of(),
                Map.of(),
                new HttpUploadProfile.Auth(HttpUploadProfile.AUTH_NONE, null, null, null, null),
                new HttpUploadProfile.Body(
                        HttpUploadProfile.BODY_MULTIPART, "file", "${publicId}.${extension}", Map.of()),
                new HttpUploadProfile.ResponseSpec(
                        List.of(new HttpUploadProfile.Condition("/ok", "EQ", "BOOLEAN", "true")),
                        "/url",
                        null,
                        null,
                        null,
                        HttpUploadProfile.SIZE_UNIT_BYTES,
                        null,
                        null,
                        List.of(),
                        List.of("https://" + HOST)),
                new HttpUploadProfile.Limits(5242880L, List.of("image/png")),
                new HttpUploadProfile.Timeouts(5000));
        return new HttpUploadGateway.Channel(7L, 3L, HttpUploadGateway.PROVIDER_CUSTOM_HTTP, profile, Map.of(), target);
    }

    private static HttpUploadGateway.Attempt attempt() {
        byte[] content = {(byte) 0x89, 0x50, 0x4E, 0x47};
        return new HttpUploadGateway.Attempt(
                "upload-1", "public-1", "a.png", "image/png", content.length, content, "hash");
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private static final class CapturingSocketFactory implements DetachedSocketFactory {
        private final AtomicReference<InetAddress> connectedAddress = new AtomicReference<>();
        private final AtomicReference<String> requestedHost = new AtomicReference<>();
        private final AtomicInteger connects = new AtomicInteger();

        @Override
        public Socket create(Proxy proxy) {
            return new Socket(proxy == null ? Proxy.NO_PROXY : proxy) {
                @Override
                public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    connects.incrementAndGet();
                    connectedAddress.set(((InetSocketAddress) endpoint).getAddress());
                    throw new ConnectException("blocked before actual Socket.connect network I/O");
                }
            };
        }
    }
}
