package com.astral.monitor.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发请求计数器
 * <p>
 * 统计此刻正在处理中的 HTTP 请求数，作为服务实时压力的度量。
 * </p>
 * <p>
 * <b>为什么不用容器线程池的 busy threads</b>：一方面内嵌容器的实现类在 Spring Boot 4
 * 的模块化重构中变更了包名（{@code WebServerApplicationContext}、{@code TomcatWebServer}
 * 等已不在原包）；另一方面 Micrometer 的 {@code tomcat.threads.busy} 指标在启用虚拟线程
 * （生产 profile 的 {@code spring.threads.virtual.enabled=true}）时并不保证注册 —— 实测取不到值。
 * 这里改为在请求进出时自行计数，只依赖 Servlet 规范与 Spring MVC 拦截器，
 * 不随容器实现或线程模型变化而失效。
 * </p>
 * <p>
 * 计数成对性由 Spring MVC 保证：{@code preHandle} 返回 true 后，无论请求正常结束还是抛异常，
 * {@code afterCompletion} 都会被调用；异步请求亦在其完成后调用。
 * </p>
 */
@Component
public class RequestConcurrencyCounter implements HandlerInterceptor {

    /** 当前处理中的请求数 */
    private final AtomicInteger activeRequests = new AtomicInteger();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        activeRequests.incrementAndGet();
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        activeRequests.decrementAndGet();
    }

    /**
     * 读取当前并发请求数
     *
     * @return 处理中的请求数；下限收敛到 0，避免极端情况下计数漂移导致展示负数
     */
    public int getActiveRequests() {
        return Math.max(0, activeRequests.get());
    }
}
