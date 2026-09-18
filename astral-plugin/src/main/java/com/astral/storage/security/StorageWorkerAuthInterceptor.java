package com.astral.storage.security;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Worker 服务身份 HMAC 拦截器（STORAGE017）
 * <p>
 * 校验 Cloudflare Worker → Astral 的服务间请求：
 * <ul>
 *   <li>Timestamp 与服务器时间偏差 ≤ astral.plugins.storage.origin-timestamp-window-seconds；</li>
 *   <li>Nonce 首次出现（Redis SETNX 防重放，TTL = 2 × 窗口）；</li>
 *   <li>签名 = base64url(HMAC-SHA256(originSharedSecret, "METHOD\nPATH\nTIMESTAMP\nNONCE"))，常量时间比较。</li>
 * </ul>
 * 密钥未配置时对该前缀全部拒绝（fail closed），不回退到登录态或放行。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageWorkerAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_TIMESTAMP = "X-Storage-Origin-Timestamp";
    public static final String HEADER_NONCE = "X-Storage-Origin-Nonce";
    public static final String HEADER_SIGNATURE = "X-Storage-Origin-Signature";

    private static final String NONCE_KEY_PREFIX = "storage:nonce:";

    private final StorageProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String secret = properties.getOriginSharedSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("[Storage] Worker HMAC 密钥未配置，拒绝 Worker 请求: {}", request.getRequestURI());
            throw new BusinessException("STORAGE017");
        }
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);
        if (timestamp == null || nonce == null || signature == null) {
            throw new BusinessException("STORAGE017");
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("STORAGE017");
        }
        long window = properties.getOriginTimestampWindowSeconds();
        long skew = Math.abs(System.currentTimeMillis() / 1000 - ts);
        if (skew > window) {
            log.warn("[Storage] Worker 请求时间戳超窗: skew={}s", skew);
            throw new BusinessException("STORAGE017");
        }

        // Nonce 防重放：SETNX 失败说明该 Nonce 已在窗口内使用过
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(
                NONCE_KEY_PREFIX + nonce, "1", Duration.ofSeconds(window * 2L));
        if (!Boolean.TRUE.equals(first)) {
            log.warn("[Storage] Worker 请求 Nonce 重放: uri={}", request.getRequestURI());
            throw new BusinessException("STORAGE017");
        }

        String canonical = request.getMethod() + "\n"
                + request.getRequestURI() + "\n"
                + timestamp.trim() + "\n"
                + nonce;
        boolean ok = StorageHmac.verify(secret.getBytes(StandardCharsets.UTF_8), canonical, signature);
        if (!ok) {
            log.warn("[Storage] Worker 请求签名校验失败: uri={}", request.getRequestURI());
            throw new BusinessException("STORAGE017");
        }
        return true;
    }
}
