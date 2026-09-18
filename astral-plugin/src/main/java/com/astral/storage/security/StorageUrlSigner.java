package com.astral.storage.security;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFileEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 下载签名 URL 签发（Astral 签发、Cloudflare Worker 校验）
 * <p>
 * URL 形态：{workerBaseUrl}/f/{publicId}/{contentVersion}?e={expiresEpoch}&v={keyVersion}&s={sig}
 * 签名规范串：GET\n/f/{publicId}/{contentVersion}\n{expiresEpoch}\n{keyVersion}（与 Worker 逐字节一致）。
 * contentVersion 进入路径与缓存键：替换内容或切换可见性时递增，实现版本化缓存失效。
 * </p>
 */
@Service
public class StorageUrlSigner {

    private final StorageProperties properties;

    public StorageUrlSigner(StorageProperties properties) {
        this.properties = properties;
    }

    public record SignedUrl(String url, long expiresAtEpochSeconds) {
    }

    public SignedUrl issue(StorageConfigEntity config, StorageFileEntity file) {
        String key = properties.getDownloadSigningKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException("STORAGE024");
        }
        String workerBase = config.getWorkerBaseUrl();
        if (workerBase == null || workerBase.isBlank()) {
            throw new BusinessException("STORAGE002");
        }
        long expires = Instant.now().getEpochSecond() + properties.getDownloadUrlTtlSeconds();
        int keyVersion = properties.getDownloadSigningKeyVersion();
        String signature = StorageHmac.sign(key.getBytes(StandardCharsets.UTF_8), canonical(expires, keyVersion, file));
        String url = stripTrailingSlash(workerBase) + pathOf(file)
                + "?e=" + expires + "&v=" + keyVersion + "&s=" + signature;
        return new SignedUrl(url, expires);
    }

    /** 供 Worker 固定向量测试复用的规范串 */
    public static String canonical(long expiresEpoch, int keyVersion, StorageFileEntity file) {
        return "GET\n" + pathOf(file) + "\n" + expiresEpoch + "\n" + keyVersion;
    }

    public static String pathOf(StorageFileEntity file) {
        return "/f/" + file.getPublicId() + "/" + file.getContentVersion();
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
