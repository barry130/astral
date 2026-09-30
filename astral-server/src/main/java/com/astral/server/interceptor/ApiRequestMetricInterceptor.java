package com.astral.server.interceptor;

import com.astral.common.util.ClientHeaders;
import com.astral.monitor.service.ApiMetricCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 接口指标采集拦截器
 * <p>
 * preHandle 记录起始时间，afterCompletion 交给 {@link ApiMetricCollector}
 * 内存累加（uri、method、status、耗时、客户端平台、客户端版本），由定时任务每分钟落库。
 * 自身仅写内存，开销纳秒级；/api/v1/stat/report 已在注册处排除（防自举）。
 * 开关：astral.stat.enabled（关闭时本拦截器不注册，见 WebMvcConfig）。
 * </p>
 * <p>
 * 客户端平台与版本取自<b>统一客户端系统头</b>（{@link ClientHeaders}，头名可配）。
 * 接口调用是服务端测量行为，请求上下文里本没有这两个维度，只能由客户端主动携带；
 * 未携带时按空串落库，报表侧「全部平台 / 全部版本」的口径不受影响。
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

    /** 客户端平台请求头名；所有端需在每个 astral 请求携带（契约见 ClientHeaders） */
    @Value("${astral.stat.client-ut-header:" + ClientHeaders.H_UT + "}")
    private String clientUtHeader;

    /** 客户端版本请求头名；所有端需在每个 astral 请求携带 */
    @Value("${astral.stat.client-version-header:" + ClientHeaders.H_VERSION + "}")
    private String clientVersionHeader;

    /** 遗留平台头名（旧版 App 的反馈实现），仅在新头缺失时兜底 */
    @Value("${astral.stat.client-legacy-platform-header:" + ClientHeaders.H_PLATFORM_LEGACY + "}")
    private String legacyPlatformHeader;

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
            String uri = ClientHeaders.normalize(request.getRequestURI(), MAX_URI_LENGTH);
            String ut = ClientHeaders.resolveUt(
                    request.getHeader(clientUtHeader), request.getHeader(legacyPlatformHeader));
            String appVersion = ClientHeaders.normalize(
                    request.getHeader(clientVersionHeader), ClientHeaders.MAX_VERSION);
            apiMetricCollector.record(uri, request.getMethod(), response.getStatus(), costMs, ut, appVersion);
        }
    }
}
