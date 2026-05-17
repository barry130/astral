package com.astral.server.interceptor;

import com.astral.common.annotation.RateLimit;
import com.astral.common.result.Result;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ConcurrentHashMap<String, RateLimiter> rateLimiterMap = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }

        String key = generateKey(request, rateLimit);
        RateLimiter rateLimiter = rateLimiterMap.computeIfAbsent(key, k -> 
            RateLimiter.create(rateLimit.limit() / (double) rateLimit.duration())
        );

        if (!rateLimiter.tryAcquire()) {
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
        response.setHeader("X-RateLimit-Remaining", "1");

        return true;
    }

    private String generateKey(HttpServletRequest request, RateLimit rateLimit) {
        String keyType = rateLimit.key();
        if ("ip".equals(keyType)) {
            String ip = request.getRemoteAddr();
            String uri = request.getRequestURI();
            return "rate_limit:" + ip + ":" + uri;
        } else if ("user".equals(keyType)) {
            String userId = request.getHeader("X-User-Id");
            if (userId == null) {
                userId = "anonymous";
            }
            String uri = request.getRequestURI();
            return "rate_limit:" + userId + ":" + uri;
        }
        return "rate_limit:global:" + request.getRequestURI();
    }
}
