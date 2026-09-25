package com.astral.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * storage 插件配置（astral.plugins.storage.*）
 * <p>密钥只能通过环境变量注入（STORAGE_UPLOAD_TICKET_KEY 等），
 * 缺失时对应能力不可用并在接口返回 STORAGE024，绝不回退明文或默认密钥。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "astral.plugins.storage")
public class StorageProperties {

    /** 单文件上限（字节）：公共 Bot API 下载上限 20MiB，超过将无法再次下载 */
    private long maxFileSizeBytes = 20L * 1024 * 1024;

    /** 上传凭证有效期（秒） */
    private int uploadTicketTtlSeconds = 600;

    /** 下载签名 URL 有效期（秒）；公开缓存撤销窗口 = 该 TTL */
    private int downloadUrlTtlSeconds = 300;

    /** Worker HMAC 时间戳允许窗口（秒） */
    private int originTimestampWindowSeconds = 300;

    /** 允许上传的 MIME 白名单：空列表 = 不限制文件类型 */
    private List<String> allowedMimeTypes = List.of();

    /** 上传凭证 HMAC 密钥（Astral 与 Worker 共享） */
    private String uploadTicketKey;

    /** Worker → Astral 服务认证 HMAC 密钥（Astral 与 Worker 共享） */
    private String originSharedSecret;

    /** 下载签名 HMAC 密钥（Astral 签发、Worker 校验） */
    private String downloadSigningKey;

    /** 下载签名密钥版本号（MVP 单版本） */
    private int downloadSigningKeyVersion = 1;
}
