package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.common.http.BoundedHttpTransport;
import com.astral.storage.config.StorageProperties;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.http.ApprovedTargetPolicy;
import com.astral.storage.http.HttpChannelTestTransport;
import com.astral.storage.http.HttpProfileValidator;
import com.astral.storage.http.HttpResponseParser;
import com.astral.storage.http.HttpSecretEnvelope;
import com.astral.storage.http.HttpTemplateEngine;
import com.astral.storage.http.HttpUploadProfile;
import com.astral.storage.http.PinnedDnsResolver;
import com.astral.storage.mapper.StorageConfigMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * 自定义 HTTP 渠道上传执行器（§11.4/§11.5/§12）。
 *
 * <p>职责边界：只做「把已校验的字节按画像发出去，并把上游结果分类」，不碰数据库状态机——
 * 状态迁移全部由 {@link UploadOperationService} 在事务内完成，因此这里的调用顺序是安全的：
 * SENDING 必须先提交，再进入本类。</p>
 *
 * <p>安全底线：仅 HTTPS + 画像端点必须仍在批准清单内；不跟随重定向；不复用连接失败到未知结果；
 * 响应体读取即封顶（超限即 PARSE 失败，不整包缓冲）；错误只回固定安全码，绝不回传上游正文/凭据。
 * 渠道测试只返回传输阶段与 HTTP 状态，不回传上游错误正文。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpUploadGateway implements HttpChannelTestTransport {

    /** 渠道 providerType */
    public static final String PROVIDER_CUSTOM_HTTP = "CUSTOM_HTTP";

    /** 响应体硬上限 256 KiB（§11.1） */
    public static final int MAX_RESPONSE_BYTES = 262144;

    /** multipart 非文件字段总字节上限 16 KiB（§11.1） */
    public static final int MAX_FIELD_BYTES = 16384;

    /** 单次尝试默认预算 30s，硬顶 60s */
    public static final int MAX_ATTEMPT_MS = 60000;

    /** 建连硬上限 5s；总预算仍由 attemptMs 绝对截止时间封顶 */
    private static final long CONNECT_TIMEOUT_MS = 5000L;

    /** 共享守护调度器只负责到点取消实际请求，不执行任何业务逻辑 */
    private static final ScheduledExecutorService DEADLINE_SCHEDULER = Executors.newScheduledThreadPool(1, r -> {
        Thread thread = new Thread(r, "storage-http-upload-deadline");
        thread.setDaemon(true);
        return thread;
    });

    /** 测试可替换：系统 DNS 只允许在这里发生一次 */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** 测试可替换：真实客户端必须绑定 PinnedDnsResolver，禁止再次系统解析 */
    @FunctionalInterface
    interface HttpClientFactory {
        CloseableHttpClient create(PinnedDnsResolver resolver, long connectTimeoutMs, long attemptMs)
                throws IOException;
    }

    private volatile HostResolver hostResolver = PinnedDnsResolver::resolvePublicIpv4;
    private volatile HttpClientFactory httpClientFactory = HttpUploadGateway::buildPinnedClient;
    private volatile ScheduledExecutorService deadlineScheduler = DEADLINE_SCHEDULER;

    /** 1×1 PNG，仅用于渠道连通性探测 */
    private static final byte[] PROBE_PNG = {
        (byte) 0x89,
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        0x00,
        0x00,
        0x00,
        0x0D,
        0x49,
        0x48,
        0x44,
        0x52,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
        0x08,
        0x06,
        0x00,
        0x00,
        0x00,
        0x1F,
        (byte) 0x15,
        (byte) 0xC4,
        (byte) 0x89,
        0x00,
        0x00,
        0x00,
        0x0A,
        0x49,
        0x44,
        0x41,
        0x54,
        0x78,
        (byte) 0x9C,
        0x63,
        0x00,
        0x01,
        0x00,
        0x00,
        0x05,
        0x00,
        0x01,
        0x0D,
        0x0A,
        0x2D,
        (byte) 0xB4,
        0x00,
        0x00,
        0x00,
        0x00,
        0x49,
        0x45,
        0x4E,
        0x44,
        (byte) 0xAE,
        0x42,
        0x60,
        (byte) 0x82
    };

    private final StorageConfigMapper configMapper;
    private final ApprovedTargetPolicy approvedTargetPolicy;
    private final HttpProfileValidator profileValidator;
    private final HttpResponseParser responseParser;

    /** 解析后的可发送渠道 */
    public record Channel(
            long configId,
            long configVersion,
            String providerType,
            HttpUploadProfile profile,
            Map<String, String> secrets,
            StorageProperties.ApprovedTarget target) {}

    /** 一次发送请求（正文已在内存中，可重放） */
    public record Attempt(
            String uploadId,
            String publicId,
            String fileName,
            String contentType,
            long sizeBytes,
            byte[] content,
            String sha256) {}

    /** 结果分类：只有 REMOTE_CREATED 表示上游确认已创建 */
    public enum Kind {
        /** 确定未发出（可安全换目标重试） */
        NOT_SENT,
        /** 已发出但确定未创建（4xx/3xx） */
        DEFINITE_REJECTED,
        /** 上游确认创建 */
        REMOTE_CREATED,
        /** 结果未知，绝不自动重试 */
        UNKNOWN
    }

    public record Result(
            Kind kind,
            Integer httpStatus,
            String safeCode,
            String url,
            String thumbUrl,
            String remoteId,
            Long remoteSizeBytes,
            Integer width,
            Integer height,
            String upstreamReason) {

        /** 兼容旧构造点（无上游摘要） */
        Result(
                Kind kind,
                Integer httpStatus,
                String safeCode,
                String url,
                String thumbUrl,
                String remoteId,
                Long remoteSizeBytes,
                Integer width,
                Integer height) {
            this(kind, httpStatus, safeCode, url, thumbUrl, remoteId, remoteSizeBytes, width, height, null);
        }

        static Result notSent(String safeCode) {
            return new Result(Kind.NOT_SENT, null, safeCode, null, null, null, null, null, null);
        }

        static Result rejected(Integer status, String safeCode) {
            return new Result(Kind.DEFINITE_REJECTED, status, safeCode, null, null, null, null, null, null);
        }

        static Result rejected(Integer status, String safeCode, String upstreamReason) {
            return new Result(
                    Kind.DEFINITE_REJECTED, status, safeCode, null, null, null, null, null, null, upstreamReason);
        }

        static Result unknown(Integer status, String safeCode) {
            return new Result(Kind.UNKNOWN, status, safeCode, null, null, null, null, null, null);
        }

        static Result unknown(Integer status, String safeCode, String upstreamReason) {
            return new Result(Kind.UNKNOWN, status, safeCode, null, null, null, null, null, null, upstreamReason);
        }
    }

    /**
     * 加载渠道画像与凭据：走 {@code provider_options} 信封解析（与 HTTP 配置服务同一套校验），
     * 不依赖对方模块的 Service，避免跨线编译耦合。
     */
    public Channel loadChannel(long configId) {
        return channelFrom(configMapper.selectById(configId));
    }

    /** The caller commits its SENDING transition before releasing this configuration row lock. */
    public Channel loadChannelForSend(long configId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Sending authorization requires a transaction");
        }
        return channelFrom(configMapper.lockForSend(configId));
    }

    private Channel channelFrom(StorageConfigEntity config) {
        if (config == null || !StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
            throw new BusinessException("STORAGE002");
        }
        long configId = config.getId();
        if (!PROVIDER_CUSTOM_HTTP.equalsIgnoreCase(config.getProviderType())) {
            throw new BusinessException("STORAGE039");
        }
        if (!approvedTargetPolicy.enabled()) {
            // 部署侧总开关未开：approved-targets 出站白名单是 SSRF 防护面，必须部署配置显式批准
            throw new BusinessException("STORAGE048", "自定义 HTTP 图床渠道未启用（部署侧未配置 custom-http 批准目标清单）");
        }
        HttpSecretEnvelope envelope;
        try {
            envelope = HttpSecretEnvelope.parse(config.getProviderOptions());
        } catch (BusinessException ex) {
            // STORAGE034 的 reason 是归一化的安全文案（信封损坏/缺画像/schemaVersion 不符），
            // 直接透传，让客户端看到可排查的原因而不是无参 040
            throw ex;
        } catch (RuntimeException ex) {
            log.warn(
                    "自定义 HTTP 渠道信封解析失败: configId={}, reason={}",
                    configId,
                    ex.getClass().getSimpleName());
            throw new BusinessException("STORAGE040", "渠道凭据信封解析失败，请在管理端重新保存该渠道");
        }
        if (envelope.profile() == null) {
            throw new BusinessException("STORAGE040", "渠道画像缺失，请在管理端重新保存该渠道");
        }
        HttpProfileValidator.Validated validated;
        try {
            validated = profileValidator.validate(envelope.profile());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn(
                    "自定义 HTTP 画像校验失败: configId={}, reason={}",
                    configId,
                    ex.getClass().getSimpleName());
            throw new BusinessException("STORAGE040");
        }
        // 服务端绑定证明必须与当前渠道和本次校验命中的批准目标一致：否则一份曾用于 A 渠道/A 目标的
        // 信封被复制到 B 渠道（或画像端点被改写）时，旧凭据会被发往新地址，凭据边界失效。
        HttpSecretEnvelope.Binding binding = envelope.binding();
        if (!config.getId().equals(binding.channelId())
                || !binding.origin().equals(validated.target().getOrigin())
                || !binding.path().equals(validated.target().getPath())) {
            log.warn(
                    "自定义 HTTP 渠道凭据绑定不匹配: configId={}, bindingChannelId={}, bindingOrigin={}, bindingPath={}",
                    configId,
                    binding.channelId(),
                    binding.origin(),
                    binding.path());
            throw new BusinessException("STORAGE034", "渠道凭据绑定与当前渠道或目标不一致，请在管理端重新保存该渠道");
        }
        long version = config.getConfigVersion() == null ? 0L : config.getConfigVersion();
        return new Channel(
                config.getId(),
                version,
                config.getProviderType(),
                validated.profile(),
                envelope.secretValues(),
                validated.target());
    }

    /** 发送一次；不抛 IO 异常，所有失败都分类返回 */
    public Result send(Channel channel, Attempt attempt) {
        return send(channel, attempt, attemptMs(channel.profile()));
    }

    private Result send(Channel channel, Attempt attempt, int budgetMs) {
        long expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        HttpUploadProfile profile = channel.profile();
        HttpTemplateEngine.RenderContext context = new HttpTemplateEngine.RenderContext(
                attempt.uploadId(),
                attempt.publicId(),
                attempt.fileName(),
                extensionOf(attempt.fileName()),
                attempt.contentType(),
                attempt.sizeBytes(),
                channel.secrets());
        String url;
        Map<String, String> headers;
        Map<String, String> fields;
        String renderedFileName;
        try {
            url = HttpTemplateEngine.buildUrl(profile, context);
            headers = HttpTemplateEngine.renderHeaders(profile, context);
            fields = HttpTemplateEngine.renderFormFields(profile, context);
            renderedFileName = HttpTemplateEngine.render(
                    HttpTemplateEngine.compile(profile.body().fileNameTemplate(), "fileNameTemplate"),
                    context,
                    "fileNameTemplate");
        } catch (RuntimeException ex) {
            log.warn(
                    "上传 URL/头部渲染失败: uploadId={}, reason={}",
                    attempt.uploadId(),
                    ex.getClass().getSimpleName());
            return Result.notSent("TEMPLATE_ERROR");
        }
        // 批准目标在画像校验阶段已确定（channel.target()），这里只做渲染后 URI 的合法性复核：
        // buildUrl 会拼上 query/QUERY 认证参数，因此不能再用 require(url) 比精确 origin+path。
        StorageProperties.ApprovedTarget target = channel.target();
        if (target == null) {
            return Result.notSent("TARGET_NOT_APPROVED");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return Result.notSent("BAD_URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return Result.notSent("TARGET_NOT_APPROVED");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            return Result.notSent("TARGET_NOT_APPROVED");
        }
        // 唯一一次系统 DNS；后续实际连接只使用这份已校验的公网 IPv4，不再查询系统 DNS。
        InetAddress[] resolved;
        try {
            resolved = BoundedHttpTransport.approve(
                    uri,
                    Duration.ofNanos(Math.max(1, expiresAt - System.nanoTime())),
                    targetUri -> hostResolver.resolve(targetUri.getHost()));
        } catch (IOException ex) {
            return Result.notSent(System.nanoTime() >= expiresAt ? "ATTEMPT_TIMEOUT" : "DNS_FAILED");
        } catch (RuntimeException ex) {
            log.warn(
                    "上游地址校验失败: uploadId={}, reason={}",
                    attempt.uploadId(),
                    ex.getClass().getSimpleName());
            return Result.notSent("TARGET_NOT_APPROVED");
        }
        PinnedDnsResolver pinned;
        try {
            pinned = new PinnedDnsResolver(uri.getHost(), resolved);
        } catch (IllegalArgumentException ex) {
            return Result.notSent("TARGET_NOT_APPROVED");
        }

        String boundary = "----astral" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try {
            body = multipartBody(profile, fields, renderedFileName, attempt, boundary);
        } catch (IOException | UncheckedIOException ex) {
            log.warn(
                    "multipart 组装失败: uploadId={}, reason={}",
                    attempt.uploadId(),
                    ex.getClass().getSimpleName());
            return Result.notSent("BODY_BUILD_FAILED");
        }

        long attemptMs = TimeUnit.NANOSECONDS.toMillis(expiresAt - System.nanoTime());
        if (attemptMs <= 0) {
            return Result.notSent("ATTEMPT_TIMEOUT");
        }
        long connectTimeoutMs = Math.min(CONNECT_TIMEOUT_MS, attemptMs);
        HttpPost request = new HttpPost(uri);
        request.setEntity(new ByteArrayEntity(
                body, org.apache.hc.core5.http.ContentType.parse("multipart/form-data; boundary=" + boundary)));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            request.setHeader(header.getKey(), header.getValue());
        }
        request.setHeader("Accept-Encoding", "identity");
        request.setConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(attemptMs))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .setHardCancellationEnabled(true)
                .build());

        AtomicBoolean deadlineHit = new AtomicBoolean(false);
        ScheduledFuture<?> deadlineTask = null;
        CloseableHttpClient client = null;
        try {
            client = httpClientFactory.create(pinned, connectTimeoutMs, attemptMs);
            if (System.nanoTime() >= expiresAt) {
                return Result.notSent("ATTEMPT_TIMEOUT");
            }
            deadlineTask = deadlineScheduler.schedule(
                    () -> {
                        deadlineHit.set(true);
                        // cancel() 同时中断阻塞中的连接/读写，避免 responseTimeout 仅作为空闲超时
                        // 无法约束慢速握手/慢速发送造成的总预算超时。
                        request.cancel();
                    },
                    Math.max(0, expiresAt - System.nanoTime()),
                    TimeUnit.NANOSECONDS);
            try (CloseableHttpResponse response = client.execute(request)) {
                int status = response.getCode();
                byte[] responseBody;
                try (InputStream in = response.getEntity() == null
                        ? InputStream.nullInputStream()
                        : response.getEntity().getContent()) {
                    responseBody = readCapped(in, MAX_RESPONSE_BYTES);
                } catch (ResponseTooLargeException ex) {
                    return Result.unknown(status, "RESPONSE_TOO_LARGE");
                } catch (IOException ex) {
                    return deadlineHit.get()
                            ? Result.unknown(status, "ATTEMPT_TIMEOUT")
                            : Result.unknown(status, "RESPONSE_READ_FAILED");
                }
                if (deadlineHit.get()) {
                    return Result.unknown(status, "ATTEMPT_TIMEOUT");
                }
                if (status >= 300 && status < 400) {
                    return Result.rejected(status, "UPSTREAM_REDIRECT");
                }
                if (status >= 500) {
                    return Result.unknown(status, "UPSTREAM_5XX");
                }
                if (status >= 400) {
                    return Result.rejected(status, "UPSTREAM_4XX");
                }
                if (status < 200) {
                    return Result.rejected(status, "UPSTREAM_HTTP_STATUS");
                }

                HttpResponseParser.ParsedResult parsed;
                try {
                    parsed = responseParser.parse(
                            status,
                            new String(responseBody, StandardCharsets.UTF_8),
                            profile,
                            channel.target(),
                            channel.secrets());
                } catch (BusinessException ex) {
                    log.warn(
                            "上游响应解析失败: uploadId={}, code={}",
                            attempt.uploadId(),
                            ex.getClass().getSimpleName());
                    return Result.unknown(status, "PARSE_FAILED");
                }
                switch (parsed.kind()) {
                    case OK:
                        // §3.4.6：远端大小是记录性元数据（上游可压缩/转码），不与本地声明比对——
                        // 本地完整性由 actualSha256 保障；误判 UNKNOWN 会冻结操作并永久占用额度。
                        return new Result(
                                Kind.REMOTE_CREATED,
                                status,
                                "OK",
                                parsed.url(),
                                parsed.thumbUrl(),
                                parsed.remoteId(),
                                parsed.remoteSizeBytes(),
                                parsed.width(),
                                parsed.height());
                    case URL_REJECTED:
                        // 上游已创建，但回传 URL 不可用：对象存在，不能当失败重传
                        return Result.unknown(status, "OUTPUT_URL_REJECTED");
                    case CREDENTIAL_REFLECTION:
                        return Result.unknown(status, "CREDENTIAL_REFLECTION");
                    case PARSE_FAILED:
                    default:
                        return Result.unknown(status, "PARSE_FAILED");
                }
            }
        } catch (ConnectTimeoutException ex) {
            return deadlineHit.get() ? Result.unknown(null, "ATTEMPT_TIMEOUT") : Result.notSent("CONNECT_TIMEOUT");
        } catch (org.apache.hc.client5.http.HttpHostConnectException ex) {
            if (deadlineHit.get()) {
                return Result.unknown(null, "ATTEMPT_TIMEOUT");
            }
            return hasCause(ex, NoRouteToHostException.class)
                    ? Result.notSent("NO_ROUTE")
                    : Result.notSent("CONNECT_REFUSED");
        } catch (NoRouteToHostException ex) {
            return deadlineHit.get() ? Result.unknown(null, "ATTEMPT_TIMEOUT") : Result.notSent("NO_ROUTE");
        } catch (ConnectException ex) {
            return deadlineHit.get() ? Result.unknown(null, "ATTEMPT_TIMEOUT") : Result.notSent("CONNECT_REFUSED");
        } catch (UnknownHostException ex) {
            return Result.notSent("DNS_FAILED");
        } catch (SSLHandshakeException ex) {
            // 证书链无效/主机名不匹配/协议握手失败都发生在 HTTP 请求写出之前，可安全换目标重试。
            log.warn(
                    "上游 TLS 握手失败: uploadId={}, reason={}",
                    attempt.uploadId(),
                    ex.getClass().getSimpleName());
            return deadlineHit.get() ? Result.unknown(null, "ATTEMPT_TIMEOUT") : Result.notSent("TLS_HANDSHAKE_FAILED");
        } catch (SSLException ex) {
            log.warn(
                    "上游 TLS 连接失败: uploadId={}, reason={}",
                    attempt.uploadId(),
                    ex.getClass().getSimpleName());
            return Result.unknown(null, deadlineHit.get() ? "ATTEMPT_TIMEOUT" : "TLS_ERROR");
        } catch (SocketTimeoutException ex) {
            // 只有 ConnectTimeoutException 已证明处于连接阶段；其余 SocketTimeout 可能发生在发送/读取，
            // 无法证明请求未到达上游，必须按 UNKNOWN 处理。
            return Result.unknown(null, "ATTEMPT_TIMEOUT");
        } catch (java.io.InterruptedIOException ex) {
            if (deadlineHit.get()) {
                return Result.unknown(null, "ATTEMPT_TIMEOUT");
            }
            Thread.currentThread().interrupt();
            return Result.unknown(null, "INTERRUPTED");
        } catch (IOException ex) {
            // 只有握手失败证明 HTTP 正文尚未发送；普通 TLS 错误也可能发生在发送后读取回执时。
            if (hasCause(ex, SSLHandshakeException.class)) {
                log.warn(
                        "上游 TLS 握手失败: uploadId={}, reason={}",
                        attempt.uploadId(),
                        ex.getClass().getSimpleName());
                return deadlineHit.get()
                        ? Result.unknown(null, "ATTEMPT_TIMEOUT")
                        : Result.notSent("TLS_HANDSHAKE_FAILED");
            }
            if (hasCause(ex, SSLException.class)) {
                log.warn(
                        "上游 TLS 连接失败: uploadId={}, reason={}",
                        attempt.uploadId(),
                        ex.getClass().getSimpleName());
                return Result.unknown(null, deadlineHit.get() ? "ATTEMPT_TIMEOUT" : "TLS_ERROR");
            }
            return Result.unknown(null, deadlineHit.get() ? "ATTEMPT_TIMEOUT" : "IO_ERROR");
        } finally {
            if (deadlineTask != null) {
                deadlineTask.cancel(false);
            }
            if (client != null) {
                try {
                    client.close();
                } catch (IOException ex) {
                    log.debug(
                            "关闭上传 HTTP 客户端失败: uploadId={}, reason={}",
                            attempt.uploadId(),
                            ex.getClass().getSimpleName());
                }
            }
        }
    }

    /** 渠道测试（PROBE）：发送 1×1 PNG，仅回报分类结果 */
    @Override
    public TestOutcome probe(TestRequest request) {
        HttpUploadProfile profile = request.profile();
        if (profile == null) {
            return TestOutcome.unavailable("PROFILE_MISSING", "缺少自定义 HTTP 画像");
        }
        Channel channel = new Channel(
                request.channelId() == null ? 0L : request.channelId(),
                request.configVersion() == null ? 0L : request.configVersion(),
                PROVIDER_CUSTOM_HTTP,
                profile,
                request.secrets() == null ? Map.of() : request.secrets(),
                request.target());
        String requestId = request.requestId() == null ? "local" : request.requestId();
        Attempt attempt = new Attempt(
                "probe-" + requestId,
                "probe-" + requestId,
                "probe.png",
                "image/png",
                PROBE_PNG.length,
                PROBE_PNG,
                sha256Hex(PROBE_PNG));
        int budgetMs = request.attemptMs() > 0 ? Math.min(request.attemptMs(), attemptMs(profile)) : attemptMs(profile);
        Result result = send(channel, attempt, budgetMs);
        return switch (result.kind()) {
            case REMOTE_CREATED ->
                new TestOutcome(true, "OK", "上游已接收探测内容", result.httpStatus(), result.remoteSizeBytes());
            case DEFINITE_REJECTED ->
                new TestOutcome(
                        false,
                        result.safeCode(),
                        "上游拒绝探测请求" + upstreamSuffix(result),
                        result.httpStatus(),
                        result.remoteSizeBytes());
            case NOT_SENT -> TestOutcome.unavailable(result.safeCode(), notSentMessage(result.safeCode()));
            case UNKNOWN ->
                new TestOutcome(
                        false,
                        result.safeCode(),
                        "上游结果未知，请稍后核对" + upstreamSuffix(result),
                        result.httpStatus(),
                        result.remoteSizeBytes());
        };
    }

    // ==================== 内部 ====================

    /** 未发出类失败的诊断消息：按传输阶段给出可排查原因，不臆测为「结果未知」 */
    private static String notSentMessage(String safeCode) {
        return switch (safeCode == null ? "" : safeCode) {
            case "TLS_HANDSHAKE_FAILED" -> "上游 HTTPS 证书校验或握手失败（请检查证书链与主机名）";
            case "TLS_ERROR" -> "上游 HTTPS 连接失败（TLS 协议错误）";
            case "CONNECT_TIMEOUT" -> "连接上游超时";
            case "ATTEMPT_TIMEOUT" -> "上游请求超时，未发出上传内容";
            case "CONNECT_REFUSED" -> "上游拒绝连接";
            case "DNS_FAILED" -> "上游域名解析失败";
            case "NO_ROUTE" -> "无法路由到上游地址";
            case "TARGET_NOT_APPROVED" -> "目标地址不在批准清单内";
            case "BAD_URL" -> "目标 URL 非法";
            case "TEMPLATE_ERROR" -> "请求模板渲染失败";
            default -> "未能建立到上游的可信 HTTPS 连接";
        };
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 上游正文可能以任意编码回显凭据，诊断仅使用可信状态字段。 */
    private static String upstreamSuffix(Result result) {
        String head = result.httpStatus() == null ? "" : "HTTP " + result.httpStatus();
        return head.isBlank() ? "" : "（" + head + "）";
    }

    private int attemptMs(HttpUploadProfile profile) {
        Integer configured =
                profile.timeouts() == null ? null : profile.timeouts().attemptMs();
        int value = configured == null ? HttpUploadProfile.DEFAULT_ATTEMPT_MS : configured;
        return Math.max(1000, Math.min(value, MAX_ATTEMPT_MS));
    }

    private byte[] multipartBody(
            HttpUploadProfile profile,
            Map<String, String> fields,
            String renderedFileName,
            Attempt attempt,
            String boundary)
            throws IOException {
        int fieldBytes = fields.entrySet().stream()
                .mapToInt(entry -> utf8(entry.getKey()) + utf8(entry.getValue()))
                .sum();
        if (fieldBytes > MAX_FIELD_BYTES) {
            throw new IOException("multipart 字段超出上限");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024 + attempt.content().length);
        String crlf = "\r\n";
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            writeUtf8(out, "--" + boundary + crlf);
            writeUtf8(out, "Content-Disposition: form-data; name=\"" + quote(entry.getKey()) + "\"" + crlf + crlf);
            writeUtf8(out, entry.getValue() + crlf);
        }
        writeUtf8(out, "--" + boundary + crlf);
        writeUtf8(
                out,
                "Content-Disposition: form-data; name=\"" + quote(profile.body().fileField()) + "\"; filename=\""
                        + quote(safeName(renderedFileName)) + "\"" + crlf);
        writeUtf8(out, "Content-Type: " + attempt.contentType() + crlf + crlf);
        out.write(attempt.content());
        writeUtf8(out, crlf + "--" + boundary + "--" + crlf);
        return out.toByteArray();
    }

    /**
     * 生产客户端：连接管理器绑定 PinnedDnsResolver，实际 Socket.connect 只使用已校验 IPv4；
     * 原 HTTPS URI 主机保留，TLS 仍按原主机名做 SNI/证书校验。
     */
    static CloseableHttpClient buildPinnedClient(PinnedDnsResolver resolver, long connectTimeoutMs, long attemptMs) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                        // socket timeout 只做单次读写保底；整次 attempt 的硬边界由 scheduled cancel 保证。
                        .setSocketTimeout(Timeout.ofMilliseconds(attemptMs))
                        .build())
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(attemptMs))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .setProtocolUpgradeEnabled(false)
                .setHardCancellationEnabled(true)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .disableRedirectHandling()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    void setHostResolverForTest(HostResolver resolver) {
        this.hostResolver = resolver;
    }

    void setHttpClientFactoryForTest(HttpClientFactory factory) {
        this.httpClientFactory = factory;
    }

    void setDeadlineSchedulerForTest(ScheduledExecutorService scheduler) {
        this.deadlineScheduler = scheduler;
    }

    private static byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > cap) {
                throw new ResponseTooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot + 1);
        return extension.length() > 16 ? "" : extension.toLowerCase(java.util.Locale.ROOT);
    }

    private static String safeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload";
        }
        String cleaned = fileName.replaceAll("[\\r\\n\"\\\\]", "_");
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }

    private static String quote(String value) {
        return value == null ? "" : value.replace("\"", "_");
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void writeUtf8(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 响应体超限（内部信号，不对外暴露） */
    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() {
            super("上游响应体超出上限");
        }
    }
}
