package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageConfigEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 又拍云对象存储适配（官方 REST/FORM API 签名，文档 docs.upyun.com）
 * <p>
 * REST 签名：Authorization: "UPYUN {Operator}:{Base64(HmacSHA1(MD5(Password),
 * "METHOD&URI&DATE[&Content-MD5]"))}"，URI = /{bucket}/{key}（quote safe='~/'），Date 为 RFC1123 GMT。
 * FORM 直传：policy = Base64(JSON{bucket, save-key, expiration, date?})，
 * authorization = 同签名算法（"POST&/{bucket}&[date&]{policy}"）。MD5 一律 32 位小写。
 * 密钥（操作员密码）绝不落日志。
 * </p>
 */
@Slf4j
@Service
public class UpyunApiService {

    private static final String DEFAULT_ENDPOINT = "v0.api.upyun.com";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public UpyunApiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 连接信息 ====================

    /** 解析并校验 provider_options（STORAGE024 缺凭证 / COMMON002 非法值） */
    public UpyunOptions optionsOf(StorageConfigEntity config) {
        if (config.getProviderOptions() == null || config.getProviderOptions().isBlank()) {
            throw new BusinessException("STORAGE024");
        }
        try {
            JsonNode node = objectMapper.readTree(config.getProviderOptions());
            String bucket = node.path("bucket").asText("");
            String operator = node.path("operator").asText("");
            String password = node.path("password").asText("");
            if (bucket.isBlank()) {
                throw new BusinessException("COMMON002", "bucket（服务名）不能为空");
            }
            if (operator.isBlank() || password.isBlank()) {
                throw new BusinessException("STORAGE024");
            }
            String endpoint = node.path("endpoint").asText(DEFAULT_ENDPOINT);
            if (endpoint.isBlank() || !endpoint.matches("^[\\w.-]+(:\\d+)?$")) {
                throw new BusinessException("COMMON002", "endpoint 必须为 API 域名，如 v0.api.upyun.com");
            }
            return new UpyunOptions(bucket, endpoint, operator, password,
                    node.path("publicBaseUrl").asText(null),
                    node.path("tokenKey").asText(null));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("COMMON002", "provider_options 解析失败");
        }
    }

    /** 又拍云连接信息（password 只在服务端内存中流转） */
    public record UpyunOptions(String bucket, String endpoint, String operator, String password,
                              String publicBaseUrl, String tokenKey) {
    }

    public record PresignedUrl(String url, long expiresAtEpochSeconds) {
    }

    /** FORM 直传授权结果：浏览器以 multipart POST 提交 policy/authorization/file 字段 */
    public record FormUploadGrant(String url, String policy, String authorization,
                                  String saveKey, long expiresAtEpochSeconds) {
    }

    // ==================== 签名原语 ====================

    /** 操作员密码的 MD5（32 位小写）—又拍云签名密钥 */
    private String passwordMd5(String password) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }

    /**
     * REST/Header 签名：Base64(HmacSHA1(md5(password), METHOD&URI&DATE[&Content-MD5]))
     */
    private String restAuthorization(UpyunOptions o, String method, String uri, String date, String contentMd5) {
        String data = method + "&" + uri + "&" + date;
        if (contentMd5 != null && !contentMd5.isBlank()) {
            data = data + "&" + contentMd5;
        }
        return "UPYUN " + o.operator() + ":" + hmacSha1Base64(passwordMd5(o.password()), data);
    }

    /**
     * FORM 直传 authorization：可选 date 与 policy 均在 Content-MD5 之前参与（官方文档示例：
     * POST&/{bucket}&{date}&{policy}[&{content-md5}]；date 缺省时省略该段与其后的 &）。
     */
    private String formAuthorization(UpyunOptions o, String bucket, String date, String policy) {
        String data = "POST&/" + bucket;
        if (date != null && !date.isBlank()) {
            data = data + "&" + date;
        }
        if (policy != null && !policy.isBlank()) {
            data = data + "&" + policy;
        }
        return "UPYUN " + o.operator() + ":" + hmacSha1Base64(passwordMd5(o.password()), data);
    }

    private String hmacSha1Base64(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("又拍云 HMAC 计算失败", e);
        }
    }

    /** RFC1123 GMT 日期串 */
    private String httpDate() {
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
    }

    /** URI 编码：quote(safe='~/')——仅 / 与 ~ 保留，其余非保留字符转义 */
    private String quotePath(String uri) {
        StringBuilder sb = new StringBuilder();
        for (byte b : uri.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", (byte) c));
            }
        }
        return sb.toString();
    }

    // ==================== FORM 直传授权（签发给浏览器） ====================

    /**
     * 签发表单直传授权：save-key 固定为「{publicId 占位}/v1/{文件名段}」。
     * 又拍云表单 API 不支持按 save-key 回传最终路径（不支持占位符透传上传结果外的值），
     * 因此 save-key 采用 upyun 的 {filename} 占位符时存在重名覆盖风险——这里改为
     * 由服务端直接指定不含占位符的完整 save-key（每个 publicId 唯一）。
     */
    public FormUploadGrant issueFormUploadGrant(StorageConfigEntity config, String objectKey, long ttlSeconds) {
        UpyunOptions o = optionsOf(config);
        long exp = Instant.now().getEpochSecond() + Math.max(60, Math.min(ttlSeconds, 1800));
        String date = httpDate();
        ObjectNode policyJson = objectMapper.createObjectNode();
        policyJson.put("bucket", o.bucket());
        policyJson.put("save-key", "/" + objectKey);
        policyJson.put("expiration", exp);
        policyJson.put("date", date);
        String policy;
        try {
            policy = Base64.getEncoder().encodeToString(
                    policyJson.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BusinessException("COMMON002", "policy 生成失败");
        }
        String authorization = formAuthorization(o, o.bucket(), date, policy);
        String url = "https://" + o.endpoint() + "/" + o.bucket();
        return new FormUploadGrant(url, policy, authorization, "/" + objectKey, exp);
    }

    // ==================== 下载 URL（CDN token 防盗链） ====================

    /**
     * 下载 URL：又拍云 REST 不支持 URL 预签名，走空间绑定域名的 CDN token 防盗链。
     * _upt = MD5("{tokenKey}&{etime}&{uri}"){中间8位} + etime（官方 CDN advanced.md 签名方式）。
     * tokenKey 未配置时退化为裸公开 URL（空间须为可公开访问），调用方需以注释/文档告知运维。
     */
    public UpyunApiService.DownloadUrl downloadUrl(StorageConfigEntity config, String key, long ttlSeconds) {
        UpyunOptions o = optionsOf(config);
        if (o.publicBaseUrl() == null || o.publicBaseUrl().isBlank()) {
            throw new BusinessException("STORAGE019", "该配置未设置公开访问域名 publicBaseUrl");
        }
        String path = "/" + key;
        long etime = Instant.now().getEpochSecond() + Math.max(60, Math.min(ttlSeconds, 86400));
        String url = "https://" + o.publicBaseUrl() + quotePath(path);
        if (o.tokenKey() != null && !o.tokenKey().isBlank()) {
            String sign = md5Hex(o.tokenKey() + "&" + etime + "&" + path);
            String upt = sign.substring(12, 20) + etime;
            url = url + "?_upt=" + upt;
        }
        return new DownloadUrl(url, etime);
    }

    public record DownloadUrl(String url, long expiresAtEpochSeconds) {
    }

    private String md5Hex(String data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }

    // ==================== 服务端操作（REST Header 签名） ====================

    /** HEAD 对象：确认对象存在并取 x-upyun-file-size；不存在返回 -1 */
    public long headObject(StorageConfigEntity config, String key) {
        try {
            UpyunOptions o = optionsOf(config);
            String uri = quotePath("/" + o.bucket() + "/" + key);
            String date = httpDate();
            HttpResponse<Void> resp = httpClient.send(HttpRequest.newBuilder(
                            URI.create("https://" + o.endpoint() + uri))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("Authorization", restAuthorization(o, "HEAD", uri, date, null))
                    .header("Date", date)
                    .timeout(Duration.ofSeconds(8))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 200) {
                return -1;
            }
            return resp.headers().firstValue("x-upyun-file-size")
                    .map(Long::parseLong).orElse(-1L);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] 又拍云 HEAD 对象失败: key={}", key, e);
            return -1;
        }
    }

    /** DELETE 对象：又拍云删除不存在对象返回 404，同样视为成功（幂等语义） */
    public void deleteObject(StorageConfigEntity config, String key) {
        try {
            UpyunOptions o = optionsOf(config);
            String uri = quotePath("/" + o.bucket() + "/" + key);
            String date = httpDate();
            HttpResponse<Void> resp = httpClient.send(HttpRequest.newBuilder(
                            URI.create("https://" + o.endpoint() + uri))
                    .method("DELETE", HttpRequest.BodyPublishers.noBody())
                    .header("Authorization", restAuthorization(o, "DELETE", uri, date, null))
                    .header("Date", date)
                    .timeout(Duration.ofSeconds(8))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 200 && resp.statusCode() != 404) {
                throw new BusinessException("STORAGE012", "又拍云删除返回 HTTP " + resp.statusCode());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] 又拍云 DELETE 对象失败: key={}", key, e);
            throw new BusinessException("STORAGE012", rootMessage(e));
        }
    }

    /** HEAD 根目录（GET /{bucket}/?usage=1 取容量）：连接测试 */
    public void headBucket(StorageConfigEntity config) {
        try {
            UpyunOptions o = optionsOf(config);
            // GET /{bucket}/?usage 的 URI 参与签名的部分为 quote 后的路径（python-sdk: uri + args）
            String path = quotePath("/" + o.bucket() + "/");
            String date = httpDate();
            HttpResponse<Void> resp = httpClient.send(HttpRequest.newBuilder(
                            URI.create("https://" + o.endpoint() + path + "?usage"))
                    .GET()
                    .header("Authorization", restAuthorization(o, "GET", path + "?usage", date, null))
                    .header("Date", date)
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

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
