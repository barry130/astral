package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageConfigEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * S3 兼容对象存储操作（AWS Signature V4，适用于 R2 / S3 / 其他兼容实现）
 * <p>
 * 使用 path-style 寻址：{endpoint-host}/{bucket}/{key}（R2 的 S3 端点即 path-style）。
 * 上传/下载 URL 采用查询串预签名（只签名 host，UNSIGNED-PAYLOAD），
 * HEAD/DELETE 等服务端操作使用签名头。密钥来自配置的 provider_options，绝不落日志。
 * </p>
 */
@Slf4j
@Service
public class S3ObjectService {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter AMZ_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String EMPTY_SHA256 = hex(sha256(new byte[0]));

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public S3ObjectService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 连接信息解析 ====================

    /** 解析并校验 provider_options 中的 S3 连接信息（STORAGE024 缺凭证 / COMMON002 非法值） */
    public S3Options optionsOf(StorageConfigEntity config) {
        if (config.getProviderOptions() == null || config.getProviderOptions().isBlank()) {
            throw new BusinessException("STORAGE024");
        }
        try {
            JsonNode node = objectMapper.readTree(config.getProviderOptions());
            String endpoint = node.path("endpoint").asText("");
            String bucket = node.path("bucket").asText("");
            String accessKeyId = node.path("accessKeyId").asText("");
            String secretAccessKey = node.path("secretAccessKey").asText("");
            if (endpoint.isBlank() || !endpoint.matches("^https?://[\\w.-]+(:\\d+)?$")) {
                throw new BusinessException("COMMON002", "S3 endpoint 必须为合法的 http(s) 地址");
            }
            if (bucket.isBlank()) {
                throw new BusinessException("COMMON002", "bucket 不能为空");
            }
            if (accessKeyId.isBlank() || secretAccessKey.isBlank()) {
                throw new BusinessException("STORAGE024");
            }
            String region = node.path("region").asText("");
            if (region.isBlank()) {
                region = StorageConfigEntity.PROVIDER_R2.equals(config.getProviderType()) ? "auto" : "us-east-1";
            }
            return new S3Options(endpoint, region, bucket, accessKeyId, secretAccessKey,
                    node.path("publicBaseUrl").asText(null));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("COMMON002", "provider_options 解析失败");
        }
    }

    /** S3 连接信息（secretAccessKey 只在服务端内存中流转） */
    public record S3Options(String endpoint, String region, String bucket,
                            String accessKeyId, String secretAccessKey, String publicBaseUrl) {
    }

    // ==================== 预签名 URL ====================

    public record PresignedUrl(String url, long expiresAtEpochSeconds) {
    }

    /** 预签名 PUT：浏览器凭该 URL 直传对象正文 */
    public PresignedUrl presignPut(StorageConfigEntity config, String key, long ttlSeconds) {
        return presign("PUT", config, key, ttlSeconds);
    }

    /** 预签名 GET：浏览器凭该 URL 直接下载对象 */
    public PresignedUrl presignGet(StorageConfigEntity config, String key, long ttlSeconds) {
        return presign("GET", config, key, ttlSeconds);
    }

    private PresignedUrl presign(String method, StorageConfigEntity config, String key, long ttlSeconds) {
        S3Options o = optionsOf(config);
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now.atOffset(ZoneOffset.UTC));
        String day = AMZ_DAY.format(now.atOffset(ZoneOffset.UTC));
        String scope = day + "/" + o.region() + "/s3/aws4_request";
        String credential = o.accessKeyId() + "/" + scope;

        Host host = hostOf(o.endpoint());
        TreeMap<String, String> query = new TreeMap<>();
        query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.put("X-Amz-Credential", credential);
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", String.valueOf(Math.max(1, Math.min(ttlSeconds, 604800))));
        query.put("X-Amz-SignedHeaders", "host");
        String canonicalQuery = joinQuery(query);

        String canonicalUri = "/" + o.bucket() + "/" + encodeKeyPath(key);
        String canonicalRequest = String.join("\n", method, canonicalUri, canonicalQuery,
                "host:" + host.value(), "", "host", "UNSIGNED-PAYLOAD");
        String stringToSign = String.join("\n", "AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(canonicalRequest)));
        byte[] signingKey = signingKey(o.secretAccessKey(), day, o.region());
        String signature = hex(hmac(signingKey, stringToSign));

        String url = host.scheme() + "://" + host.value() + canonicalUri + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
        return new PresignedUrl(url, now.getEpochSecond() + Long.parseLong(query.get("X-Amz-Expires")));
    }

    // ==================== 服务端操作（签名头） ====================

    /** HEAD 对象：校验上传回执时确认对象真实存在并取 Content-Length；不存在返回 -1 */
    public long headObject(StorageConfigEntity config, String key) {
        try {
            HttpResponse<Void> resp = httpClient.send(signed("HEAD", config, key).build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200
                    ? Long.parseLong(resp.headers().firstValue("Content-Length").orElse("-1"))
                    : -1;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] HEAD 对象失败: bucket={}, key={}", safeBucket(config), key, e);
            return -1;
        }
    }

    /** DELETE 对象：S3 语义下删除不存在的对象也返回 204，视为成功 */
    public void deleteObject(StorageConfigEntity config, String key) {
        try {
            HttpResponse<Void> resp = httpClient.send(signed("DELETE", config, key).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                throw new BusinessException("STORAGE012", "S3 删除返回 HTTP " + resp.statusCode());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] DELETE 对象失败: bucket={}, key={}", safeBucket(config), key, e);
            throw new BusinessException("STORAGE012", rootMessage(e));
        }
    }

    /** HEAD 桶：连接测试 */
    public void headBucket(StorageConfigEntity config) {
        try {
            S3Options o = optionsOf(config);
            String amzDate = AMZ_DATE.format(Instant.now().atOffset(ZoneOffset.UTC));
            Host host = hostOf(o.endpoint());
            String canonicalUri = "/" + o.bucket();
            String canonicalRequest = String.join("\n", "HEAD", canonicalUri, "",
                    "host:" + host.value(), "x-amz-content-sha256:" + EMPTY_SHA256, "x-amz-date:" + amzDate,
                    "", "host;x-amz-content-sha256;x-amz-date", EMPTY_SHA256);
            String day = amzDate.substring(0, 8);
            String scope = day + "/" + o.region() + "/s3/aws4_request";
            String stringToSign = String.join("\n", "AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(canonicalRequest)));
            String signature = hex(hmac(signingKey(o.secretAccessKey(), day, o.region()), stringToSign));
            HttpRequest request = HttpRequest.newBuilder(URI.create(host.scheme() + "://" + host.value() + canonicalUri))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("x-amz-content-sha256", EMPTY_SHA256)
                    .header("x-amz-date", amzDate)
                    .header("Authorization", authorization(o, amzDate, scope,
                            "host;x-amz-content-sha256;x-amz-date", signature))
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<Void> resp = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e));
        }
    }

    private HttpRequest.Builder signed(String method, StorageConfigEntity config, String key) {
        S3Options o = optionsOf(config);
        String amzDate = AMZ_DATE.format(Instant.now().atOffset(ZoneOffset.UTC));
        Host host = hostOf(o.endpoint());
        String canonicalUri = "/" + o.bucket() + "/" + encodeKeyPath(key);
        String canonicalRequest = String.join("\n", method, canonicalUri, "",
                "host:" + host.value(), "x-amz-content-sha256:" + EMPTY_SHA256, "x-amz-date:" + amzDate,
                "", "host;x-amz-content-sha256;x-amz-date", EMPTY_SHA256);
        String day = amzDate.substring(0, 8);
        String scope = day + "/" + o.region() + "/s3/aws4_request";
        String stringToSign = String.join("\n", "AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(canonicalRequest)));
        String signature = hex(hmac(signingKey(o.secretAccessKey(), day, o.region()), stringToSign));
        return HttpRequest.newBuilder(URI.create(host.scheme() + "://" + host.value() + canonicalUri))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("x-amz-content-sha256", EMPTY_SHA256)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization(o, amzDate, scope,
                        "host;x-amz-content-sha256;x-amz-date", signature))
                .timeout(Duration.ofSeconds(10));
    }

    private String authorization(S3Options o, String amzDate, String scope, String signedHeaders, String signature) {
        return "AWS4-HMAC-SHA256 Credential=" + o.accessKeyId() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    // ==================== SigV4 原语 ====================

    private record Host(String value, String scheme) {
    }

    private Host hostOf(String endpoint) {
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        int port = uri.getPort();
        // 预签名 URL 的 scheme 跟随 endpoint：自建 S3 兼容端点（MinIO/本地开发）可能是 http
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
        if (port > 0 && port != 443) {
            return new Host(host + ":" + port, scheme);
        }
        return new Host(host, scheme);
    }

    private byte[] signingKey(String secretKey, String day, String region) {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        return hmac(kService, "aws4_request");
    }

    private String joinQuery(TreeMap<String, String> query) {
        StringBuilder sb = new StringBuilder();
        query.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(uriEncode(k)).append('=').append(uriEncode(v));
        });
        return sb.toString();
    }

    /** RFC3986 编码（SigV4 要求：仅不保留字符不编码） */
    private String uriEncode(String s) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    /** 对象键路径编码：逐段 RFC3986 编码，'/' 保持原样（canonical URI 规则） */
    public static String encodeKeyPath(String key) {
        StringBuilder sb = new StringBuilder();
        for (String segment : key.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            StringBuilder seg = new StringBuilder();
            for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
                char c = (char) (b & 0xFF);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '.' || c == '_' || c == '~') {
                    seg.append(c);
                } else {
                    seg.append('%').append(String.format("%02X", b));
                }
            }
            sb.append(seg);
        }
        return sb.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SigV4 HMAC 计算失败", e);
        }
    }

    private static byte[] sha256(String data) {
        return sha256(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String hex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }

    private String safeBucket(StorageConfigEntity config) {
        try {
            return optionsOf(config).bucket();
        } catch (Exception e) {
            return "?";
        }
    }

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
