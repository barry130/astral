package com.astral.storage.security;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.service.CosApiService;
import com.astral.storage.service.OssApiService;
import com.astral.storage.service.ProviderSupport;
import com.astral.storage.service.S3ObjectService;
import com.astral.storage.service.UpyunApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 上传凭证（Upload Ticket）签发与回调校验
 * <p>
 * 浏览器先向 Astral 换取短时签名凭证，再携带凭证把文件直传 Cloudflare Worker；
 * Worker 用自身 Secret 中的 Bot Token 把文件流式转发到 Telegram，最后回调 Astral 登记元数据。
 * Astral 全程不接触文件正文。
 * </p>
 * <p>
 * ticket = base64url(payloadJSON) + "." + base64url(HMAC-SHA256(uploadTicketKey, payloadB64))。
 * 签发时在 Redis 保存凭证 payload（TTL = 2 × 有效期），回调时校验存在性、大小与 MIME；
 * 文件表以 upload_id 幂等，重复回调返回已有记录。
 * </p>
 */
@Slf4j
@Service
public class UploadTicketService {

    private static final String TICKET_KEY_PREFIX = "storage:ticket:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final S3ObjectService s3ObjectService;
    private final CosApiService cosApiService;
    private final OssApiService ossApiService;
    private final UpyunApiService upyunApiService;

    public UploadTicketService(StorageProperties properties,
                               StringRedisTemplate stringRedisTemplate,
                               ObjectMapper objectMapper,
                               S3ObjectService s3ObjectService,
                               CosApiService cosApiService,
                               OssApiService ossApiService,
                               UpyunApiService upyunApiService) {
        this.properties = properties;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.s3ObjectService = s3ObjectService;
        this.cosApiService = cosApiService;
        this.ossApiService = ossApiService;
        this.upyunApiService = upyunApiService;
    }

    /** 上传凭证签发结果（formFields 仅 UPYUN 表单直传非空） */
    public record IssuedTicket(String uploadId, String ticket, String uploadUrl, String formField,
                               String method, long expiresAtEpochSeconds, FormFields formFields) {

        public IssuedTicket(String uploadId, String ticket, String uploadUrl, String formField,
                            String method, long expiresAtEpochSeconds) {
            this(uploadId, ticket, uploadUrl, formField, method, expiresAtEpochSeconds, null);
        }
    }

    /** 表单直传（UPYUN）附加字段：policy 与 authorization 必须随签发一次性下发，不可重建 */
    public record FormFields(String policy, String authorization) {
    }

    /**
     * 签发上传凭证。调用方需已完成身份认证与文件夹 UPLOAD 权限校验。
     * <p>TELEGRAM：返回 Worker 直传地址（POST multipart，formField=file）；
     * S3 系（R2/S3/七牛）/COS/OSS：返回预签名 PUT 地址，浏览器直传对象存储；
     * UPYUN：返回表单 API 地址 + policy/authorization 附加字段（POST multipart，formField=file）。</p>
     */
    public IssuedTicket issue(StorageConfigEntity config, StorageFolderEntity folder,
                              String uploaderId, String fileName, String contentType, long sizeBytes) {
        String ticketKey = requireKey();
        long maxSize = effectiveMaxSize(config);
        if (sizeBytes <= 0) {
            throw new BusinessException("STORAGE006");
        }
        if (sizeBytes > maxSize) {
            throw new BusinessException("STORAGE006");
        }
        if (!isMimeAllowed(contentType)) {
            throw new BusinessException("STORAGE005", contentType);
        }

        String providerType = config.getProviderType() == null || config.getProviderType().isBlank()
                ? StorageConfigEntity.PROVIDER_TELEGRAM : config.getProviderType();
        String uploadId = "up_" + UUID.randomUUID().toString().replace("-", "");
        long now = Instant.now().getEpochSecond();
        long exp = now + properties.getUploadTicketTtlSeconds();
        String nonce = randomHex(16);

        String ticket;
        String uploadUrl;
        String formField;
        String method;
        String publicId = null;
        String objectKey = null;
        FormFields formFields = null;
        if (StorageConfigEntity.PROVIDER_TELEGRAM.equals(providerType)) {
            String base = config.getWorkerBaseUrl();
            if (base == null || base.isBlank()) {
                throw new BusinessException("STORAGE002");
            }
            uploadUrl = stripTrailingSlash(base) + "/upload?ticket=";
            method = "POST";
            formField = "file";
        } else if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
            // 又拍云：对象键与 publicId 在签发时确定，浏览器以 policy/authorization 表单直传
            publicId = UUID.randomUUID().toString().replace("-", "");
            objectKey = "astral/" + publicId + "/v1/" + keySegment(fileName);
            UpyunApiService.FormUploadGrant grant = upyunApiService.issueFormUploadGrant(
                    config, objectKey, properties.getUploadTicketTtlSeconds());
            uploadUrl = grant.url();
            method = "POST";
            formField = "file";
            formFields = new FormFields(grant.policy(), grant.authorization());
        } else {
            // S3 系（R2/S3/七牛）/COS/OSS：对象键在签发时确定，publicId 也随之生成并在登记时落库
            publicId = UUID.randomUUID().toString().replace("-", "");
            objectKey = "astral/" + publicId + "/v1/" + keySegment(fileName);
            String putUrl;
            if (ProviderSupport.isS3Family(providerType)) {
                putUrl = s3ObjectService.presignPut(config, objectKey, properties.getUploadTicketTtlSeconds()).url();
            } else if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
                putUrl = cosApiService.presignPut(config, objectKey, properties.getUploadTicketTtlSeconds()).url();
            } else if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
                putUrl = ossApiService.presignPut(config, objectKey, properties.getUploadTicketTtlSeconds()).url();
            } else {
                throw new BusinessException("COMMON002", "不支持的存储类型: " + providerType);
            }
            uploadUrl = putUrl;
            method = "PUT";
            formField = null;
        }

        String payload = buildPayload(config, folder, uploadId, uploaderId, fileName, contentType, exp, nonce,
                providerType, publicId, objectKey);
        String payloadB64 = StorageHmac.b64UrlEncode(payload.getBytes(StandardCharsets.UTF_8));
        String signature = StorageHmac.sign(ticketKey.getBytes(StandardCharsets.UTF_8), payloadB64);
        ticket = payloadB64 + "." + signature;
        if (StorageConfigEntity.PROVIDER_TELEGRAM.equals(providerType)) {
            uploadUrl = uploadUrl + ticket;
        }

        // 回执校验依赖 Redis 中的凭证上下文；TTL 为有效期的 2 倍，覆盖时钟偏差
        stringRedisTemplate.opsForValue().set(TICKET_KEY_PREFIX + uploadId, payload,
                Duration.ofSeconds(properties.getUploadTicketTtlSeconds() * 2L));

        return new IssuedTicket(uploadId, ticket, uploadUrl, formField, method, exp, formFields);
    }

    /** 读取凭证上下文（浏览器回执登记用）；缺失或过期抛 STORAGE008 */
    public JsonNode getPayload(String uploadId) {
        String payload = stringRedisTemplate.opsForValue().get(TICKET_KEY_PREFIX + uploadId);
        if (payload == null) {
            throw new BusinessException("STORAGE008");
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new BusinessException("STORAGE008");
        }
    }

    /**
     * Worker 上传回调校验：凭证必须仍存在（未过期）、回调大小不超过凭证授权上限。
     * 返回凭证上下文供登记文件元数据使用。
     */
    public JsonNode validateCallback(String uploadId, long sizeBytes, String contentType) {
        String payload = stringRedisTemplate.opsForValue().get(TICKET_KEY_PREFIX + uploadId);
        if (payload == null) {
            throw new BusinessException("STORAGE008");
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            long maxSize = node.path("maxSize").asLong(properties.getMaxFileSizeBytes());
            if (sizeBytes > maxSize) {
                throw new BusinessException("STORAGE006");
            }
            // MIME 授权以票据签发时校验过的 mime 为准；回调 contentType 只是 Telegram/存储方
            // 回报的元数据（Telegram 会把不认识的类型归一为 application/octet-stream，
            // 如 .js/.json 文档），不参与白名单判定，存储时原样保留（octet-stream 下载即纯下载）
            String mime = node.path("mime").asText(contentType);
            if (!isMimeAllowed(mime)) {
                throw new BusinessException("STORAGE005", mime);
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Storage] 上传凭证上下文解析失败: uploadId={}", uploadId, e);
            throw new BusinessException("STORAGE008");
        }
    }

    /** 凭证授权的文件夹 ID */
    public long folderIdOf(JsonNode ticket) {
        return ticket.path("folderId").asLong();
    }

    /** 实际生效的单文件上限：配置值与插件全局值取小 */
    public long effectiveMaxSize(StorageConfigEntity config) {
        long limit = properties.getMaxFileSizeBytes();
        if (config != null && config.getMaxFileSize() != null && config.getMaxFileSize() > 0) {
            limit = Math.min(limit, config.getMaxFileSize());
        }
        return limit;
    }

    /** JS 的历史 MIME 变体统一归一到 text/javascript：Telegram 对 .js 文档常回 application/x-javascript，回调校验按白名单别名匹配 */
    private static final java.util.Map<String, String> MIME_ALIASES = java.util.Map.of(
            "application/javascript", "text/javascript",
            "application/x-javascript", "text/javascript");

    public boolean isMimeAllowed(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String mime = contentType.trim().toLowerCase();
        if (mime.contains(";")) {
            mime = mime.substring(0, mime.indexOf(';')).trim();
        }
        mime = MIME_ALIASES.getOrDefault(mime, mime);
        return properties.getAllowedMimeTypes().stream().anyMatch(mime::equals);
    }

    private String buildPayload(StorageConfigEntity config, StorageFolderEntity folder, String uploadId,
                                String uploaderId, String fileName, String contentType,
                                long exp, String nonce, String providerType, String publicId, String objectKey) {
        try {
            return objectMapper.writeValueAsString(new TicketPayload(
                    1, uploadId, config.getId(), folder.getId(), config.getChatId(),
                    effectiveMaxSize(config), contentType.toLowerCase(), uploaderId,
                    Instant.now().getEpochSecond(), exp, nonce,
                    providerType, fileName, publicId, objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("上传凭证序列化失败", e);
        }
    }

    /** 凭证负载（与 Worker 侧字段一致；Worker 只读不改；后四项仅 R2/S3 使用） */
    record TicketPayload(int v, String uploadId, Long configId, Long folderId, String chatId,
                         long maxSize, String mime, String uploaderId,
                         long iat, long exp, String nonce,
                         String providerType, String fileName, String publicId, String objectKey) {
    }

    /** 对象键安全段：仅保留文件名字符，防止路径穿越与编码歧义 */
    private String keySegment(String fileName) {
        String cleaned = (fileName == null ? "file" : fileName)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        cleaned = cleaned.replaceAll("^\\.+", "_");
        if (cleaned.isBlank() || cleaned.length() > 120) {
            cleaned = cleaned.isBlank() ? "file" : cleaned.substring(cleaned.length() - 120);
        }
        return cleaned;
    }

    private String requireKey() {
        String key = properties.getUploadTicketKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException("STORAGE024");
        }
        return key;
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
