package com.astral.storage.service;

import com.astral.storage.config.StorageProperties;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.security.StorageHmac;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Worker 边缘缓存主动失效（Cache API purge）。
 * <p>
 * Worker 把内容按 (publicId, contentVersion) 缓存在边缘（/_cache、/_pcache），回源元数据与
 * Telegram getFile 结果也有短 TTL 缓存（/_mcache、/_tgcache）。可见性变更会递增 contentVersion
 * 使旧键自然失效，但「删除文件」「停用存储配置」不递增版本——旧 URL 在签名有效期内仍能从
 * 边缘缓存出图。此服务在删除/停用时逐 Worker 调用 purge 端点，把撤销延迟从「边缘缓存 TTL」
 * 收敛到秒级；失败只记日志不阻断主流程（边缘副本最多存活一个 TTL 窗口）。
 * <p>
 * 鉴权与 Worker→Astral 服务身份同款 HMAC（规范串 {@code DELETE\nPATH\nTIMESTAMP\nNONCE}，
 * 密钥 STORAGE_ORIGIN_SHARED_SECRET），不新增共享密钥。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerCachePurgeService {

    /** purge 请求超时：失效是尽力而为的加速撤销，不能拖住主流程 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final StorageProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 逐配置通知 Worker 清除指定文件的全部边缘缓存（内容 + 永久 + 元数据 + getFile）。
     * 仅对配置了 workerBaseUrl 且密钥可用的配置生效；任何失败只记 warn。
     */
    public void purgeFile(StorageConfigEntity config, String publicId, long contentVersion, String telegramFileId) {
        if (config == null || publicId == null || publicId.isBlank()) {
            return;
        }
        String secret = properties.getOriginSharedSecret();
        if (secret == null || secret.isBlank()) {
            // 未配置服务密钥时无法签名，Worker 必然拒绝；直接跳过，不做注定失败的请求
            return;
        }
        String base = config.getWorkerBaseUrl();
        if (base == null || base.isBlank()) {
            return;
        }
        String safeBase = base.trim();
        if (!safeBase.startsWith("https://") && !safeBase.startsWith("http://")) {
            return;
        }
        // 内容缓存键随 contentVersion 变化：当前与上一版本都清，覆盖可见性回退场景
        String[] paths = {
                "/cache/" + publicId + "/" + contentVersion,
                "/cache/" + publicId + "/" + Math.max(1, contentVersion - 1),
                "/pcache/" + publicId + "/" + contentVersion,
                "/pcache/" + publicId + "/" + Math.max(1, contentVersion - 1),
                "/mcache/" + publicId + "/" + contentVersion,
        };
        // getFile 结果缓存的键是 telegramFileId；拿不到定位信息就跳过（该缓存只影响回源速度，不影响撤销正确性）
        if (telegramFileId != null && !telegramFileId.isBlank()) {
            paths = append(paths, "/tgcache/" + telegramFileId);
        }
        for (String path : paths) {
            send(safeBase, path, secret);
        }
        log.info("[Storage] Worker 缓存失效已请求: worker={}, publicId={}, contentVersion={}",
                safeBase, publicId, contentVersion);
    }

    /** 异步触发，不阻塞调用方事务/请求线程 */
    public void purgeFileAsync(StorageConfigEntity config, String publicId, long contentVersion, String telegramFileId) {
        CompletableFuture.runAsync(() -> {
            try {
                purgeFile(config, publicId, contentVersion, telegramFileId);
            } catch (Exception e) {
                log.warn("[Storage] Worker 缓存失效触发失败: publicId={}, {}", publicId, e.getMessage());
            }
        });
    }

    private void send(String base, String path, String secret) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            String nonce = UUID.randomUUID().toString();
            String canonical = "DELETE\n" + path + "\n" + timestamp + "\n" + nonce;
            String signature = StorageHmac.sign(secret.getBytes(StandardCharsets.UTF_8), canonical);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(base) + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("X-Storage-Origin-Timestamp", String.valueOf(timestamp))
                    .header("X-Storage-Origin-Nonce", nonce)
                    .header("X-Storage-Origin-Signature", signature)
                    .DELETE()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[Storage] Worker 缓存失效返回异常: base={}, path={}, status={}", base, path, resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("[Storage] Worker 缓存失效请求失败: base={}, path={}, {}", base, path, e.getMessage());
        }
    }

    private static String[] append(String[] source, String extra) {
        String[] target = new String[source.length + 1];
        System.arraycopy(source, 0, target, 0, source.length);
        target[source.length] = extra;
        return target;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
