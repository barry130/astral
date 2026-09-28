package com.astral.server.interceptor;

import com.astral.common.annotation.RateLimit;
import com.astral.common.util.ClientIp;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 限流拦截器
 * <p>基于滑动窗口计数器实现，支持 "X 次 / Y 秒" 的精确限流语义，
 * 允许突发（X 次在 Y 秒窗口内均可），分布式场景需配合 Redis。</p>
 *
 * <p><b>窗口容器的容量约束</b>：key 里含客户端 IP，而 IP 是攻击者可控的维度
 * （轮换 IP 即可产生无限多 key）。因此窗口容器必须带<b>过期淘汰 + 硬上限</b>，
 * 否则在 2C2G 这类小内存机器上，刷 {@code /api/v1/auth/login} 就能把堆撑爆。
 * 这里用 Caffeine：{@code expireAfterAccess} 按窗口期淘汰，{@code maximumSize} 兜底。</p>
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 窗口容器上限：超过后按访问时间淘汰最久未用的窗口（兜底，正常远达不到） */
    private static final long MAX_WINDOWS = 50_000L;

    /**
     * 每个限流 key 的滑动窗口计数器。
     *
     * <p>过期时间取「最长窗口期 × 2」：窗口期最长的注解配置也不会让条目长期驻留，
     * 同时保证仍在活跃窗口内的计数不会被误淘汰。</p>
     */
    private final Cache<String, SlidingWindow> windowMap = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(MAX_WINDOWS)
            .build();

    /** 可信代理列表：决定是否采信 X-Forwarded-For，默认回环 + 私有网段 */
    @Value("${astral.web.trusted-proxies:" + ClientIp.DEFAULT_TRUSTED_PROXIES + "}")
    private String trustedProxies;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RateLimit methodLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        RateLimit classLimit = handlerMethod.getBeanType().getAnnotation(RateLimit.class);
        RateLimit rateLimit = methodLimit != null ? methodLimit : classLimit;
        if (rateLimit == null) {
            return true;
        }

        final int limitVal = rateLimit.limit();
        final int durationVal = rateLimit.duration();
        String key = generateKey(request, rateLimit);
        SlidingWindow window = windowMap.get(key, k -> new SlidingWindow(limitVal, durationVal));

        if (window == null || !window.tryAcquire()) {
            log.warn("限流拦截: key={}, limit={}/{}s", key, rateLimit.limit(), rateLimit.duration());
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimit.limit()));
            response.setHeader("X-RateLimit-Remaining", "0");
            response.getWriter().write(
                "{\"code\":429,\"message\":\"" + rateLimit.message() + "\"}"
            );
            return false;
        }

        response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimit.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(window.getRemainingTokens()));
        return true;
    }

    private String generateKey(HttpServletRequest request, RateLimit rateLimit) {
        String keyType = rateLimit.key();
        if ("ip".equals(keyType)) {
            // 不再直接取 remoteAddr：反向代理后所有请求的 remoteAddr 都是代理 IP，
            // 会让「按 IP 限流」退化成全局限流（一个人刷就把所有人挡住）；
            // 同时也不能无条件采信 XFF（可伪造）。走带可信代理白名单的解析。
            return "rate_limit:" + resolveClientIp(request) + ":" + request.getRequestURI();
        } else if ("user".equals(keyType)) {
            Object userId = request.getAttribute("userId");
            return "rate_limit:" + (userId == null ? "anonymous" : userId) + ":" + request.getRequestURI();
        }
        return "rate_limit:global:" + request.getRequestURI();
    }

    /** 解析客户端 IP（带可信代理白名单，防 X-Forwarded-For 伪造） */
    private String resolveClientIp(HttpServletRequest request) {
        return ClientIp.resolve(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr(),
                trustedProxies);
    }

    /**
     * 滑动窗口计数器
     * <p>在窗口时间内最多允许 {@code limit} 次请求，时间精度为秒。</p>
     */
    static class SlidingWindow {
        private final int limit;
        private final long windowDurationMs;
        private final long[] timestamps;
        private int head;
        private int count;
        private final ReentrantLock lock = new ReentrantLock();

        SlidingWindow(int limit, int durationSeconds) {
            this.limit = Math.max(1, limit);
            this.windowDurationMs = durationSeconds * 1000L;
            this.timestamps = new long[this.limit];
            this.head = 0;
            this.count = 0;
        }

        boolean tryAcquire() {
            lock.lock();
            try {
                long now = System.currentTimeMillis();
                // 移除过期的记录
                while (count > 0 && now - timestamps[(head - count + limit) % limit] > windowDurationMs) {
                    count--;
                }
                if (count < limit) {
                    timestamps[head] = now;
                    head = (head + 1) % limit;
                    count++;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        int getRemainingTokens() {
            lock.lock();
            try {
                long now = System.currentTimeMillis();
                while (count > 0 && now - timestamps[(head - count + limit) % limit] > windowDurationMs) {
                    count--;
                }
                return Math.max(0, limit - count);
            } finally {
                lock.unlock();
            }
        }
    }
}
