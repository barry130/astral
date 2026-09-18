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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 阿里云 OSS 适配（V1 签名，与 oss2 python-sdk Auth 一致）
 * <p>
 * StringToSign = VERB\nContent-MD5\nContent-Type\nDate\nCanonicalizedOSSHeaders CanonicalizedResource；
 * Header 鉴权：Authorization: "OSS {AccessKeyId}:{Base64(HmacSHA1(Secret, StringToSign))}"。
 * URL 签名：Date 位替换为 Expires 绝对时间戳，追加 ?OSSAccessKeyId=&Expires=&Signature=。
 * 浏览器直传 = 预签名 PUT URL；服务端 HEAD/DELETE 走 Header 签名。
 * 密钥绝不落日志。
 * </p>
 */
@Slf4j
@Service
public class OssApiService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public OssApiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 连接信息 ====================

    /** 解析并校验 provider_options（STORAGE024 缺凭证 / COMMON002 非法值） */
    public OssOptions optionsOf(StorageConfigEntity config) {
        if (config.getProviderOptions() == null || config.getProviderOptions().isBlank()) {
            throw new BusinessException("STORAGE024");
        }
        try {
            JsonNode node = objectMapper.readTree(config.getProviderOptions());
            String bucket = node.path("bucket").asText("");
            String endpoint = node.path("endpoint").asText("");
            String accessKeyId = node.path("accessKeyId").asText("");
            String accessKeySecret = node.path("accessKeySecret").asText("");
            if (bucket.isBlank() || !bucket.matches("^[a-z0-9][a-z0-9-]{1,61}$")) {
                throw new BusinessException("COMMON002", "bucket 不能为空且仅小写字母/数字/连字符");
            }
            if (endpoint.isBlank() || !endpoint.matches("^[\\w.-]+(:\\d+)?$")) {
                throw new BusinessException("COMMON002", "endpoint 必须为 OSS 域名，如 oss-cn-hangzhou.aliyuncs.com");
            }
            if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
                throw new BusinessException("STORAGE024");
            }
            return new OssOptions(bucket, endpoint, accessKeyId, accessKeySecret,
                    node.path("publicBaseUrl").asText(null));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("COMMON002", "provider_options 解析失败");
        }
    }

    /** OSS 连接信息（accessKeySecret 只在服务端内存中流转） */
    public record OssOptions(String bucket, String endpoint, String accessKeyId,
                             String accessKeySecret, String publicBaseUrl) {
    }

    public record PresignedUrl(String url, long expiresAtEpochSeconds) {
    }

    // ==================== 签名 ====================

    /**
     * 计算 V1 StringToSign。headerDate 为 URL 签名时的绝对过期时间戳（替代 Date）。
     * canonicalizedResource = /{bucket}/{key}（对象操作）。
     */
    private String stringToSign(String method, String date, String contentMd5, String contentType,
                                Map<String, String> ossHeaders, String canonicalizedResource) {
        StringBuilder ossHeaderStr = new StringBuilder();
        new TreeMap<>(ossHeaders).forEach((k, v) ->
                ossHeaderStr.append(k.toLowerCase(Locale.ROOT)).append(':').append(v).append('\n'));
        return String.join("\n", method, contentMd5 == null ? "" : contentMd5,
                contentType == null ? "" : contentType, date)
                + "\n" + ossHeaderStr + canonicalizedResource;
    }

    private String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("OSS HMAC 计算失败", e);
        }
    }

    // ==================== 预签名 URL ====================

    /** 预签名 PUT：浏览器凭该 URL 直传对象正文 */
    public PresignedUrl presignPut(StorageConfigEntity config, String key, long ttlSeconds) {
        return presignUrl("PUT", config, key, ttlSeconds);
    }

    /** 预签名 GET：浏览器凭该 URL 直接下载对象 */
    public PresignedUrl presignGet(StorageConfigEntity config, String key, long ttlSeconds) {
        return presignUrl("GET", config, key, ttlSeconds);
    }

    private PresignedUrl presignUrl(String method, StorageConfigEntity config, String key, long ttlSeconds) {
        OssOptions o = optionsOf(config);
        long exp = Instant.now().getEpochSecond() + Math.max(60, Math.min(ttlSeconds, 604800));
        String canonicalResource = "/" + o.bucket() + "/" + key;
        String signature = hmacSha1Base64(o.accessKeySecret(),
                stringToSign(method, String.valueOf(exp), null, null, Map.of(), canonicalResource));
        String url = "https://" + o.bucket() + "." + o.endpoint()
                + "/" + S3ObjectService.encodeKeyPath(key)
                + "?OSSAccessKeyId=" + encodeRfc3986(o.accessKeyId())
                + "&Expires=" + exp
                + "&Signature=" + encodeRfc3986(signature);
        return new PresignedUrl(url, exp);
    }

    /** 整串编码（与 JS encodeURIComponent 语义一致） */
    private String encodeRfc3986(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    // ==================== 服务端操作（Header 签名） ====================

    /** HEAD 对象：确认对象存在并取 Content-Length；不存在返回 -1 */
    public long headObject(StorageConfigEntity config, String key) {
        try {
            HttpResponse<Void> resp = httpClient.send(signedRequest("HEAD", config, key),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200
                    ? Long.parseLong(resp.headers().firstValue("Content-Length").orElse("-1"))
                    : -1;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] OSS HEAD 对象失败: key={}", key, e);
            return -1;
        }
    }

    /** DELETE 对象：OSS 删除不存在对象也返回 204，视为成功 */
    public void deleteObject(StorageConfigEntity config, String key) {
        try {
            HttpResponse<Void> resp = httpClient.send(signedRequest("DELETE", config, key),
                    HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                throw new BusinessException("STORAGE012", "OSS 删除返回 HTTP " + resp.statusCode());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] OSS DELETE 对象失败: key={}", key, e);
            throw new BusinessException("STORAGE012", rootMessage(e));
        }
    }

    /** GetBucket（/?prefix=astral%2F&max-keys=1）：连接测试 */
    public void headBucket(StorageConfigEntity config) {
        try {
            OssOptions o = optionsOf(config);
            String date = httpDate();
            String host = o.bucket() + "." + o.endpoint();
            // 桶操作 canonicalizedResource = /{bucket}/ + 子资源（max-keys 为普通参数不参与 V1 资源串）
            String canonicalResource = "/" + o.bucket() + "/";
            String signature = hmacSha1Base64(o.accessKeySecret(),
                    stringToSign("GET", date, null, null, Map.of(), canonicalResource));
            HttpResponse<Void> resp = httpClient.send(HttpRequest.newBuilder(
                            URI.create("https://" + host + "/?max-keys=1"))
                    .GET()
                    .header("Date", date)
                    .header("Authorization", "OSS " + o.accessKeyId() + ":" + signature)
                    .timeout(Duration.ofSeconds(8))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e));
        }
    }

    private HttpRequest signedRequest(String method, StorageConfigEntity config, String key) {
        OssOptions o = optionsOf(config);
        String date = httpDate();
        String host = o.bucket() + "." + o.endpoint();
        String canonicalResource = "/" + o.bucket() + "/" + key;
        String signature = hmacSha1Base64(o.accessKeySecret(),
                stringToSign(method, date, null, null, Map.of(), canonicalResource));
        return HttpRequest.newBuilder(URI.create("https://" + host + "/" + S3ObjectService.encodeKeyPath(key)))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Date", date)
                .header("Authorization", "OSS " + o.accessKeyId() + ":" + signature)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    private String httpDate() {
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
    }

    // ==================== 原语 ====================

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
