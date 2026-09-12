package com.astral.server.interceptor;

import com.astral.common.annotation.RateLimit;
import com.astral.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 限流拦截器
 * <p>基于滑动窗口计数器实现，支持 "X 次 / Y 秒" 的精确限流语义，
 * 允许突发（X 次在 Y 秒窗口内均可），分布式场景需配合 Redis。</p>
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 每个限流 key 的滑动窗口计数器 */
    private final ConcurrentHashMap<String, SlidingWindow> windowMap = new ConcurrentHashMap<>();

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
        SlidingWindow window = windowMap.computeIfAbsent(key, k -> new SlidingWindow(limitVal, durationVal));

        if (!window.tryAcquire()) {
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
            return "rate_limit:" + request.getRemoteAddr() + ":" + request.getRequestURI();
        } else if ("user".equals(keyType)) {
            String userId = (String) request.getAttribute("userId");
            if (userId == null) userId = "anonymous";
            return "rate_limit:" + userId + ":" + request.getRequestURI();
        }
        return "rate_limit:global:" + request.getRequestURI();
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
            this.limit = limit;
            this.windowDurationMs = durationSeconds * 1000L;
            this.timestamps = new long[limit];
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