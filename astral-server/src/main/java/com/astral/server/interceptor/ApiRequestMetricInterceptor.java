package com.astral.server.interceptor;

import com.astral.monitor.service.ApiMetricCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 接口指标采集拦截器（STATS_DESIGN.md §5.3）
 * <p>
 * preHandle 记录起始时间，afterCompletion 交给 {@link ApiMetricCollector}
 * 内存累加（uri、method、status、耗时），由定时任务每分钟落库。
 * 自身仅写内存，开销纳秒级；/api/v1/stat/report 已在注册处排除（防自举）。
 * 开关：astral.stat.enabled（关闭时本拦截器不注册，见 WebMvcConfig）。
 * </p>
 */
@Component
@ConditionalOnProperty(name = "astral.stat.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ApiRequestMetricInterceptor implements HandlerInterceptor {

    /** 请求起始时间属性名（纳秒） */
    private static final String ATTR_START_NS = "__stat_metric_start_ns";

    /** uri 最大长度（与 stat_api_hourly.uri 列宽一致） */
    private static final int MAX_URI_LENGTH = 256;

    private final ApiMetricCollector apiMetricCollector;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START_NS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object startObj = request.getAttribute(ATTR_START_NS);
        if (startObj instanceof Long startNs) {
            long costMs = (System.nanoTime() - startNs) / 1_000_000;
            String uri = request.getRequestURI();
            if (uri.length() > MAX_URI_LENGTH) {
                uri = uri.substring(0, MAX_URI_LENGTH);
            }
            apiMetricCollector.record(uri, request.getMethod(), response.getStatus(), costMs);
        }
    }
}
