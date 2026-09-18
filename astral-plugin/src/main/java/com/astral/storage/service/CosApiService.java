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
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 腾讯云 COS XML API 适配
 * <p>
 * 签名算法（官方 cos-js-sdk-v5 getAuth 一致）：SignKey = HmacSHA1(SecretKey, KeyTime)；
 * FormatString = method\nuri\nparams\nheaders\n；StringToSign = sha1\nKeyTime\nSHA1(FormatString)\n；
 * Signature = HmacSHA1(SignKey, StringToSign)。Header 鉴权走 Authorization；
 * 预签名 URL 把整个 q-authorization 串 encodeURIComponent 后作为单一 sign 参数附加。
 * 浏览器直传 = PUT 预签名 URL；服务端 HEAD/DELETE 走 Header 签名。密钥绝不落日志。
 * </p>
 */
@Slf4j
@Service
public class CosApiService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public CosApiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 连接信息 ====================

    /** 解析并校验 provider_options（STORAGE024 缺凭证 / COMMON002 非法值） */
    public CosOptions optionsOf(StorageConfigEntity config) {
        if (config.getProviderOptions() == null || config.getProviderOptions().isBlank()) {
            throw new BusinessException("STORAGE024");
        }
        try {
            JsonNode node = objectMapper.readTree(config.getProviderOptions());
            String bucket = node.path("bucket").asText("");
            String region = node.path("region").asText("");
            String secretId = node.path("secretId").asText("");
            String secretKey = node.path("secretKey").asText("");
            if (bucket.isBlank() || !bucket.matches("^[a-z0-9-]{1,63}$")) {
                throw new BusinessException("COMMON002", "bucket 必须为小写字母/数字/连字符");
            }
            if (region.isBlank() || !region.matches("^[a-z0-9-]{2,32}$")) {
                throw new BusinessException("COMMON002", "region 不能为空（如 ap-guangzhou）");
            }
            if (secretId.isBlank() || secretKey.isBlank()) {
                throw new BusinessException("STORAGE024");
            }
            return new CosOptions(bucket, region, secretId, secretKey,
                    node.path("publicBaseUrl").asText(null));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("COMMON002", "provider_options 解析失败");
        }
    }

    /** COS 连接信息（secretKey 只在服务端内存中流转） */
    public record CosOptions(String bucket, String region, String secretId, String secretKey,
                             String publicBaseUrl) {
    }

    /** 预签名 URL 统一结构（S3ObjectService.PresignedUrl 同构，直传/下载签发共用） */
    public record PresignedUrl(String url, long expiresAtEpochSeconds) {
    }

    // ==================== 签名 ====================

    /** 计算 q-authorization（可放 Header，也可整体编码后作为 URL 的 sign 参数） */
    private String authorization(CosOptions o, String method, String uriPath,
                                 Map<String, String> queryParams, Map<String, String> headers,
                                 long ttlSeconds) {
        long now = Instant.now().getEpochSecond() - 1;
        long exp = now + Math.max(60, Math.min(ttlSeconds, 86400));
        String keyTime = now + ";" + exp;

        String signKey = hex(hmacSha1(o.secretKey().getBytes(StandardCharsets.UTF_8), keyTime));
        String paramsStr = canonicalParams(queryParams);
        String headersStr = canonicalParams(headers);
        String formatString = String.join("\n", method.toUpperCase(Locale.ROOT), uriPath,
                paramsStr, headersStr, "");
        String stringToSign = String.join("\n", "sha1", keyTime,
                hex(sha1(formatString)), "");
        String signature = hex(hmacSha1(signKey.getBytes(StandardCharsets.UTF_8), stringToSign));

        StringBuilder headerList = new StringBuilder();
        TreeMap<String, String> sortedHeaders = new TreeMap<>(headers);
        sortedHeaders.forEach((k, v) -> {
            if (headerList.length() > 0) {
                headerList.append(';');
            }
            headerList.append(k.toLowerCase(Locale.ROOT));
        });
        StringBuilder paramList = new StringBuilder();
        TreeMap<String, String> sortedParams = new TreeMap<>(queryParams);
        sortedParams.forEach((k, v) -> {
            if (paramList.length() > 0) {
                paramList.append(';');
            }
            paramList.append(k.toLowerCase(Locale.ROOT));
        });
        return "q-sign-algorithm=sha1&q-ak=" + o.secretId()
                + "&q-sign-time=" + keyTime
                + "&q-key-time=" + keyTime
                + "&q-header-list=" + headerList
                + "&q-url-param-list=" + paramList
                + "&q-signature=" + signature;
    }

    /** COS camSafeUrlEncode：RFC3986 + ( ) ! * 三个字符额外编码 */
    private static String camSafeUrlEncode(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", (byte) c));
            }
        }
        return sb.toString();
    }

    /** key=value&…（键值均 camSafe 编码，字典序） */
    private String canonicalParams(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(params).forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(camSafeUrlEncode(k).toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(camSafeUrlEncode(v == null ? "" : v));
        });
        return sb.toString();
    }

    // ==================== 预签名 URL（浏览器直传/下载） ====================

    /** 预签名 PUT：URL = https://{bucket}.cos.{region}.myqcloud.com/{key}?sign=…（q-url-param-list 需再编码） */
    public PresignedUrl presignPut(StorageConfigEntity config, String key, long ttlSeconds) {
        return presignUrl("PUT", config, key, ttlSeconds);
    }

    /** 预签名 GET：浏览器直接下载 */
    public PresignedUrl presignGet(StorageConfigEntity config, String key, long ttlSeconds) {
        return presignUrl("GET", config, key, ttlSeconds);
    }

    private PresignedUrl presignUrl(String method, StorageConfigEntity config, String key, long ttlSeconds) {
        CosOptions o = optionsOf(config);
        String encodedKey = S3ObjectService.encodeKeyPath(key);
        String uriPath = "/" + encodedKey;
        String host = o.bucket() + ".cos." + o.region() + ".myqcloud.com";
        // 预签名 URL 只签名 host（q-header-list=host），与 js-sdk getObjectUrl 一致
        Map<String, String> headers = new TreeMap<>();
        headers.put("Host", host);
        long now = Instant.now().getEpochSecond() - 1;
        long exp = now + Math.max(60, Math.min(ttlSeconds, 86400));
        String auth = authorization(o, method, uriPath, Map.of(), headers, ttlSeconds);
        // q-url-param-list 值本身进入 URL 前需要整体再编码一次（js-sdk replaceUrlParamList 逻辑）
        String urlAuth = reEncodeUrlParamList(auth);
        String url = "https://" + host + uriPath + "?sign=" + encodeRfc3986(urlAuth);
        return new PresignedUrl(url, exp);
    }

    /** js-sdk replaceUrlParamList：把 q-url-param-list 的值在 URL 串内再编码一次 */
    private String reEncodeUrlParamList(String auth) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("q-url-param-list=([^&]*)").matcher(auth);
        if (m.find()) {
            return m.replaceFirst("q-url-param-list=" + encodeRfc3986(m.group(1)));
        }
        return auth;
    }

    /** 整串编码（用于 sign= 参数值）：与 JS encodeURIComponent 语义一致 */
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
            CosOptions o = optionsOf(config);
            String host = o.bucket() + ".cos." + o.region() + ".myqcloud.com";
            String uriPath = "/" + S3ObjectService.encodeKeyPath(key);
            Map<String, String> headers = new TreeMap<>();
            headers.put("Host", host);
            String auth = authorization(o, "HEAD", uriPath, Map.of(), headers, 300);
            HttpResponse<Void> resp = httpClient.send(HttpRequest.newBuilder(
                            URI.create("https://" + host + uriPath))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("Authorization", auth)
                    .header("Date", httpDate())
                    .timeout(Duration.ofSeconds(8))
                    .build(), HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200
                    ? Long.parseLong(resp.headers().firstValue("Content-Length").orElse("-1"))
                    : -1;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] COS HEAD 对象失败: key={}", key, e);
            return -1;
        }
    }

    /** DELETE 对象：COS 语义下删除不存在对象返回 204，视为成功 */
    public void deleteObject(StorageConfigEntity config, String key) {
        try {
            CosOptions o = optionsOf(config);
            String host = o.bucket() + ".cos." + o.region() + ".myqcloud.com";
            String uriPath = "/" + S3ObjectService.encodeKeyPath(key);
            Map<String, String> headers = new TreeMap<>();
            headers.put("Host", host);
            String auth = authorization(o, "DELETE", uriPath, Map.of(), headers, 300);
            HttpResponse<Void> resp = httpClient.send(HttpRequest.newBuilder(
                            URI.create("https://" + host + uriPath))
                    .method("DELETE", HttpRequest.BodyPublishers.noBody())
                    .header("Authorization", auth)
                    .header("Date", httpDate())
                    .timeout(Duration.ofSeconds(8))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                throw new BusinessException("STORAGE012", "COS 删除返回 HTTP " + resp.statusCode());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] COS DELETE 对象失败: key={}", key, e);
            throw new BusinessException("STORAGE012", rootMessage(e));
        }
    }

    /** HEAD 桶（GetBucket?max-keys=1）：连接测试，同时校验凭证 */
    public void headBucket(StorageConfigEntity config) {
        try {
            CosOptions o = optionsOf(config);
            String host = o.bucket() + ".cos." + o.region() + ".myqcloud.com";
            Map<String, String> params = new TreeMap<>();
            params.put("max-keys", "1");
            Map<String, String> headers = new TreeMap<>();
            headers.put("Host", host);
            // 签名时的 params 与 headers 均按 URL 编码规则参与 FormatString
            String auth = authorization(o, "GET", "/", params, headers, 300);
            String query = canonicalParams(params);
            HttpResponse<Void> resp = httpClient.send(HttpRequest.newBuilder(
                            URI.create("https://" + host + "/?" + query))
                    .GET()
                    .header("Authorization", auth)
                    .header("Date", httpDate())
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

    private String httpDate() {
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
    }

    // ==================== 原语 ====================

    private static byte[] hmacSha1(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("COS HMAC 计算失败", e);
        }
    }

    private static byte[] sha1(String data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 计算失败", e);
        }
    }

    private static String hex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
