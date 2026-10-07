package com.astral.system.notify.sms;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.astral.dao.entity.SysSmsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 阿里云短信（dysmsapi SendSms）实现：RPC 风格 HMAC-SHA1 签名，JDK HttpClient 直调，
 * 不引入阿里云 SDK（避免为一条 HTTP 调用拉全家桶）。
 *
 * <p>签名规则（阿里云旧版 RPC API）：请求参数按名排序后 URL 编码拼接，
 * stringToSign = "POST&%2F&" + urlencode(查询串)，HMAC-SHA1 密钥 = accessSecret + "&"，
 * 结果 Base64 后作为 Signature 参数。业务成功以响应 JSON 的 Code == "OK" 为准。</p>
 */
@Slf4j
@Component
public class AliyunSmsProvider implements SmsProvider {

    private static final String DEFAULT_ENDPOINT = "https://dysmsapi.aliyuncs.com";
    private static final String DEFAULT_REGION = "cn-hangzhou";
    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String type() {
        return "ALIYUN";
    }

    @Override
    public void send(SysSmsProvider provider, String phone, String signName, String templateCode, Map<String, String> params) throws Exception {
        if (signName == null || signName.isBlank()) {
            throw new IllegalArgumentException("阿里云短信缺少签名（模板或 sys_sms_provider.sign_name）");
        }
        String endpoint = provider.getEndpoint() == null || provider.getEndpoint().isBlank()
                ? DEFAULT_ENDPOINT : provider.getEndpoint();
        if (!endpoint.startsWith("http")) {
            endpoint = "https://" + endpoint;
        }

        TreeMap<String, String> p = new TreeMap<>();
        p.put("AccessKeyId", provider.getAccessKey());
        p.put("Action", "SendSms");
        p.put("Format", "JSON");
        p.put("PhoneNumbers", phone);
        p.put("RegionId", provider.getRegion() == null || provider.getRegion().isBlank() ? DEFAULT_REGION : provider.getRegion());
        p.put("SignName", signName);
        p.put("SignatureMethod", "HMAC-SHA1");
        p.put("SignatureNonce", UUID.randomUUID().toString());
        p.put("SignatureVersion", "1.0");
        p.put("TemplateCode", templateCode);
        p.put("TemplateParam", objectMapper.writeValueAsString(params == null ? Map.of() : params));
        p.put("Timestamp", UTC_ISO.format(ZonedDateTime.now(ZoneOffset.UTC)));
        p.put("Version", "2017-05-25");

        String canonical = p.entrySet().stream()
                .map(e -> rfc3986Encode(e.getKey()) + "=" + rfc3986Encode(e.getValue()))
                .collect(Collectors.joining("&"));
        String stringToSign = "POST&" + rfc3986Encode("/") + "&" + rfc3986Encode(canonical);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((provider.getAccessSecret() + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String signature = java.util.Base64.getEncoder()
                .encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        p.put("Signature", signature);

        String body = p.entrySet().stream()
                .map(e -> rfc3986Encode(e.getKey()) + "=" + rfc3986Encode(e.getValue()))
                .collect(Collectors.joining("&"));

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        String resp = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        log.info("[Sms][ALIYUN] phone={} template={} resp={}", phone, templateCode, resp);

        // {"Code":"OK","BizId":"...","RequestId":"..."}；失败如 isv.BUSINESS_LIMIT_CONTROL
        String code = objectMapper.readTree(resp).path("Code").asText("");
        if (!"OK".equalsIgnoreCase(code)) {
            throw new IllegalStateException("阿里云短信返回 Code=" + code);
        }
    }

    /** 阿里云 RPC 签名的 RFC3986 编码（* 等 7 个额外字符也要转义） */
    private static String rfc3986Encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
