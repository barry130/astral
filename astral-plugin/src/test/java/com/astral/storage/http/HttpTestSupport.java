package com.astral.storage.http;

import com.astral.storage.config.StorageProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CUSTOM_HTTP 单测公共夹具。
 *
 * <p>{@link com.astral.storage.http.ApprovedTargetPolicy#parseApprovedTargets()} 是包级可见，
 * 所以夹具与测试都放在 {@code com.astral.storage.http} 包内，直接构造部署侧批准清单。</p>
 */
final class HttpTestSupport {

    static final String ORIGIN = "https://img.example.com";
    static final String PATH = "/upload";
    static final String CDN = "https://cdn.example.com";
    static final String ENDPOINT = ORIGIN + PATH;

    private HttpTestSupport() {}

    static ApprovedTargetPolicy policy(boolean enabled) {
        StorageProperties properties = new StorageProperties();
        StorageProperties.CustomHttp custom = new StorageProperties.CustomHttp();
        custom.setEnabled(enabled);
        StorageProperties.ApprovedTarget target = new StorageProperties.ApprovedTarget();
        target.setOrigin(ORIGIN);
        target.setPath(PATH);
        target.setUrlOrigins(new ArrayList<>(List.of(CDN)));
        custom.setApprovedTargets(new ArrayList<>(List.of(target)));
        properties.setCustomHttp(custom);
        ApprovedTargetPolicy policy = new ApprovedTargetPolicy(properties);
        policy.parseApprovedTargets();
        return policy;
    }

    static StorageProperties.ApprovedTarget approvedTarget() {
        return policy(true).require(ENDPOINT);
    }

    /** 合法画像：HEADER 认证引用 apiKey，成功条件 EQ BOOLEAN，直链只允许 CDN */
    static HttpUploadProfile profile() {
        return profile(new HttpUploadProfile.Auth("HEADER", "X-Api-Key", "${secret:apiKey}", null, null));
    }

    static HttpUploadProfile profile(HttpUploadProfile.Auth auth) {
        return profile(auth, ENDPOINT, Map.of(), List.of(CDN), 1048576L);
    }

    static HttpUploadProfile profile(
            HttpUploadProfile.Auth auth,
            String endpoint,
            Map<String, String> query,
            List<String> allowedUrlOrigins,
            Long maxBytes) {
        return new HttpUploadProfile(
                1,
                null,
                null,
                endpoint,
                "POST",
                query,
                Map.of("X-Client", "astral"),
                auth,
                new HttpUploadProfile.Body("MULTIPART", "file", "${publicId}.${extension}", Map.of()),
                new HttpUploadProfile.ResponseSpec(
                        List.of(new HttpUploadProfile.Condition("/success", "EQ", "BOOLEAN", "true")),
                        "/url",
                        "/thumb",
                        "/id",
                        "/size",
                        "BYTES",
                        null,
                        null,
                        List.of(),
                        allowedUrlOrigins),
                new HttpUploadProfile.Limits(maxBytes, List.of("image/png")),
                new HttpUploadProfile.Timeouts(30000));
    }

    static HttpTemplateEngine.RenderContext context(Map<String, String> secrets) {
        return new HttpTemplateEngine.RenderContext(
                "up_0001", "pub_abcdef", "photo.png", "png", "image/png", 2048L, secrets);
    }
}
