package com.astral.monitor.service;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

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
 * 一个请求的异步重派发复用计数令牌；Servlet 完成回调与 MVC afterCompletion 共用一次性释放。
 * </p>
 */
@Component
public class RequestConcurrencyCounter implements AsyncHandlerInterceptor {

    private static final String TOKEN = RequestConcurrencyCounter.class.getName() + ".token";

    /** 当前处理中的请求数 */
    private final AtomicInteger activeRequests = new AtomicInteger();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(TOKEN) == null) {
            request.setAttribute(TOKEN, new AtomicBoolean());
            activeRequests.incrementAndGet();
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        release(request);
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.getAsyncContext().addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                release(request);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                /* Completion or error dispatch releases the token. */
            }

            @Override
            public void onError(AsyncEvent event) {
                /* Completion or error dispatch releases the token. */
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                event.getAsyncContext().addListener(this);
            }
        });
    }

    private void release(HttpServletRequest request) {
        Object token = request.getAttribute(TOKEN);
        if (token instanceof AtomicBoolean released && released.compareAndSet(false, true)) {
            activeRequests.decrementAndGet();
        }
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
