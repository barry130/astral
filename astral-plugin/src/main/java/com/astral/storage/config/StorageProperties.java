package com.astral.storage.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** 第三方 HTTP 直连渠道（CUSTOM_HTTP）配置 */
    private CustomHttp customHttp = new CustomHttp();

    /** 两步上传操作（服务端代传正文）配置 */
    private UploadOperation uploadOperation = new UploadOperation();

    /**
     * CUSTOM_HTTP 直连渠道开关与批准目标清单。
     * <p>清单只由部署侧维护，管理接口不能扩大清单；清单为空时不允许任何出站请求。</p>
     */
    @Data
    public static class CustomHttp {

        /** 全局并发硬上限：设计允许配置到 2，超过按 2 处理 */
        public static final int MAX_CONCURRENT_LIMIT = 2;

        /** 总开关，默认关闭 */
        private boolean enabled = false;

        /** 批准目标清单（只读，来自部署配置） */
        private List<ApprovedTarget> approvedTargets = List.of();

        /** 全局同时进行的上游请求数（1 或 2），默认 1 */
        private int maxConcurrentUploads = 1;

        /** 单个渠道允许的命名凭据数量上限 */
        private int maxSecretsPerChannel = 8;
    }

    /** 一个批准目标：精确 origin + 精确上传路径 + 允许回传的输出 origin 集合 */
    @Data
    public static class ApprovedTarget {

        /** 精确 HTTPS origin，如 https://api.example.com；不接受的写法在启动校验阶段拒绝 */
        private String origin;

        /** 精确上传路径，如 /v1/upload */
        private String path;

        /** 该目标允许出现在响应里的输出 URL origin 集合 */
        private List<String> urlOrigins = List.of();
    }

    /** 两步上传操作参数 */
    @Data
    public static class UploadOperation {

        /** 从首次 PUT 准入开始的固定截止时间（秒） */
        private int deadlineSeconds = 90;

        /** 单次上传正文上限（字节），硬上限 5 MiB，只能收紧 */
        private long maxContentBytes = 5242880L;

        /** 全局并发准入数（1 或 2），超出的请求直接拒绝，不排队 */
        private int maxConcurrentUploads = 1;

        /** 单用户并发准入数 */
        private int maxConcurrentPerUser = 1;

        /** 推进租约（秒），过期后只允许恢复流程接管 */
        private int leaseSeconds = 30;

        /** 恢复扫描间隔（秒） */
        private int recoveryIntervalSeconds = 60;

        /** 恢复扫描开关 */
        private boolean recoveryEnabled = true;
    }
}
