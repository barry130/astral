package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.http.ApprovedTargetPolicy;
import com.astral.storage.http.HttpChannelTestTransport;
import com.astral.storage.http.HttpProfileValidator;
import com.astral.storage.http.HttpResponseParser;
import com.astral.storage.http.HttpSecretEnvelope;
import com.astral.storage.http.HttpTemplateEngine;
import com.astral.storage.http.HttpUploadProfile;
import com.astral.storage.mapper.StorageConfigMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 唯一例外：渠道测试 probe（仅管理端诊断入口）会回传经截断脱敏的上游错误摘要（≤140 字符、
 * 剥离控制字符与凭据字面量），真实上传路径仍只消费固定安全码。</p>
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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 上游错误摘要提取：JSON 的 message/status_txt/error 字段（诊断用，见 {@link #upstreamReason}） */
    private static final Pattern UPSTREAM_REASON_FIELD =
            Pattern.compile("\"(?:message|status_txt|error)\"\\s*:\\s*\"([^\"]{0,200})\"");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

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
        StorageConfigEntity config = configMapper.selectById(configId);
        if (config == null || !StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
            throw new BusinessException("STORAGE002");
        }
        if (!PROVIDER_CUSTOM_HTTP.equalsIgnoreCase(config.getProviderType())) {
            throw new BusinessException("STORAGE039");
        }
        if (!approvedTargetPolicy.enabled()) {
            throw new BusinessException("STORAGE048");
        }
        HttpSecretEnvelope envelope;
        try {
            envelope = HttpSecretEnvelope.parse(config.getProviderOptions());
        } catch (RuntimeException ex) {
            log.warn("自定义 HTTP 渠道信封解析失败: configId={}, reason={}", configId, ex.getMessage());
            throw new BusinessException("STORAGE040");
        }
        if (envelope.profile() == null) {
            throw new BusinessException("STORAGE040");
        }
        HttpProfileValidator.Validated validated;
        try {
            validated = profileValidator.validate(envelope.profile());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("自定义 HTTP 画像校验失败: configId={}, reason={}", configId, ex.getMessage());
            throw new BusinessException("STORAGE040");
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
            log.warn("上传 URL/头部渲染失败: uploadId={}, reason={}", attempt.uploadId(), ex.getMessage());
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
        try {
            rejectNonPublicHost(uri.getHost());
        } catch (UnknownHostException ex) {
            return Result.notSent("DNS_FAILED");
        } catch (IOException ex) {
            log.warn("上游目标地址不被允许: uploadId={}, reason={}", attempt.uploadId(), ex.getMessage());
            return Result.notSent("TARGET_NOT_APPROVED");
        }

        String boundary = "----astral" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try {
            body = multipartBody(profile, fields, renderedFileName, attempt, boundary);
        } catch (IOException | UncheckedIOException ex) {
            log.warn("multipart 组装失败: uploadId={}, reason={}", attempt.uploadId(), ex.getMessage());
            return Result.notSent("BODY_BUILD_FAILED");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(attemptMs(profile)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            builder.header(header.getKey(), header.getValue());
        }
        builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
        builder.header("Accept-Encoding", "identity");

        HttpResponse<InputStream> response;
        try {
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException ex) {
            return Result.notSent("CONNECT_TIMEOUT");
        } catch (ConnectException ex) {
            return Result.notSent("CONNECT_REFUSED");
        } catch (UnknownHostException ex) {
            return Result.notSent("DNS_FAILED");
        } catch (NoRouteToHostException ex) {
            return Result.notSent("NO_ROUTE");
        } catch (HttpTimeoutException ex) {
            return Result.unknown(null, "ATTEMPT_TIMEOUT");
        } catch (IOException ex) {
            return Result.unknown(null, "IO_ERROR");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.unknown(null, "INTERRUPTED");
        }

        int status = response.statusCode();
        byte[] responseBody;
        try (InputStream in = response.body()) {
            responseBody = readCapped(in, MAX_RESPONSE_BYTES);
        } catch (ResponseTooLargeException ex) {
            return Result.unknown(status, "RESPONSE_TOO_LARGE");
        } catch (IOException ex) {
            return Result.unknown(status, "RESPONSE_READ_FAILED");
        }
        if (status >= 300 && status < 400) {
            return Result.rejected(status, "UPSTREAM_REDIRECT");
        }
        if (status >= 500) {
            return Result.unknown(status, "UPSTREAM_5XX", upstreamReason(channel, responseBody));
        }
        if (status >= 400) {
            return Result.rejected(status, "UPSTREAM_4XX", upstreamReason(channel, responseBody));
        }
        if (status < 200 || status > 299) {
            return Result.rejected(status, "UPSTREAM_HTTP_STATUS", upstreamReason(channel, responseBody));
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
            log.warn("上游响应解析失败: uploadId={}, code={}", attempt.uploadId(), ex.getMessage());
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
        Result result = send(channel, attempt);
        return switch (result.kind()) {
            case REMOTE_CREATED -> new TestOutcome(
                    true, "OK", "上游已接收探测内容", result.httpStatus(), result.remoteSizeBytes());
            case DEFINITE_REJECTED -> new TestOutcome(
                    false,
                    result.safeCode(),
                    "上游拒绝探测请求" + upstreamSuffix(result),
                    result.httpStatus(),
                    result.remoteSizeBytes());
            case NOT_SENT -> TestOutcome.unavailable(result.safeCode(), "未能建立到上游的可信 HTTPS 连接");
            case UNKNOWN -> new TestOutcome(
                    false,
                    result.safeCode(),
                    "上游结果未知，请稍后核对" + upstreamSuffix(result),
                    result.httpStatus(),
                    result.remoteSizeBytes());
        };
    }

    // ==================== 内部 ====================

    /** 探测消息后缀：透传脱敏后的上游错误摘要（仅管理端诊断可见） */
    private static String upstreamSuffix(Result result) {
        String head = result.httpStatus() == null ? "" : "HTTP " + result.httpStatus();
        String reason = result.upstreamReason();
        if (reason != null && !reason.isBlank()) {
            return "（" + (head.isBlank() ? "" : head + "：") + reason + "）";
        }
        return head.isBlank() ? "" : "（" + head + "）";
    }

    /**
     * 从上游错误响应提取诊断摘要：优先 JSON 的 message/status_txt/error 字段，否则退化为整段文本前缀。
     * 统一截断 140 字符、剥离控制字符，并把命中的渠道凭据字面量替换为 ***；
     * 该摘要只进入 probe 的管理端测试消息，真实上传路径（UploadOperationService）只消费 safeCode，
     * 「错误只回固定安全码」的边界在此一处、有损放宽。
     */
    private static String upstreamReason(Channel channel, byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        String reason = null;
        Matcher matcher = UPSTREAM_REASON_FIELD.matcher(text);
        while (reason == null && matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (!candidate.isBlank()) {
                reason = candidate;
            }
        }
        if (reason == null) {
            reason = text.replaceAll("\\s+", " ").trim();
        }
        reason = reason.replaceAll("[\\x00-\\x1F\\x7F]", "");
        if (reason.length() > 140) {
            reason = reason.substring(0, 140);
        }
        for (String secret : channel.secrets().values()) {
            if (secret != null && !secret.isBlank() && reason.contains(secret)) {
                reason = reason.replace(secret, "***");
            }
        }
        return reason.isBlank() ? null : reason;
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

    private void rejectNonPublicHost(String host) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (!isPublicIpv4(address)) {
                throw new IOException("目标地址不是公网 IPv4");
            }
        }
    }

    private boolean isPublicIpv4(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (raw.length != 4) {
            return false;
        }
        int first = raw[0] & 0xFF;
        int second = raw[1] & 0xFF;
        if (first == 0 || first == 10 || first == 127) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192) {
            if (second == 168 || second == 0 || second == 2) {
                // 192.168/16 私有；192.0.0/24 文档保留；192.0.2/24 TEST-NET-1
                return false;
            }
            if (second == 88 && (raw[2] & 0xFF) == 99) {
                // 192.88.99/24 6to4 中继保留
                return false;
            }
        }
        if (first == 198) {
            if ((second & 0xFE) == 18) {
                // 198.18/15 基准测试
                return false;
            }
            if (second == 51 && (raw[2] & 0xFF) == 100) {
                // 198.51.100/24 TEST-NET-2
                return false;
            }
        }
        if (first == 203 && second == 0 && (raw[2] & 0xFF) == 113) {
            // 203.0.113/24 TEST-NET-3
            return false;
        }
        return first < 224;
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
