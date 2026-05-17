package com.astral.server.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求ID过滤器
 * <p>为每个HTTP请求生成或提取唯一的Request ID，并将其设置到MDC（Mapped Diagnostic Context）中</p>
 * <p>用于日志追踪，确保同一请求的所有日志都包含相同的Request ID</p>
 * <p>优先级最高（HIGHEST_PRECEDENCE），确保在其他过滤器和拦截器之前执行</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements Filter {

    /** 请求ID请求头名称 */
    private static final String HEADER_REQUEST_ID = "X-Request-Id";

    /**
     * 执行过滤逻辑
     * <p>从请求头提取Request ID，如果不存在则生成一个16位的UUID；
     * 将Request ID设置到MDC和响应头中，请求结束后清理MDC</p>
     *
     * @param request Servlet请求
     * @param response Servlet响应
     * @param chain 过滤器链
     * @throws IOException IO异常
     * @throws ServletException Servlet异常
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 优先使用客户端传入的Request ID，否则生成一个新的
        String requestId = httpRequest.getHeader(HEADER_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            // 生成16位UUID作为Request ID（去掉横杠后截取前16位）
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        // 将Request ID设置到MDC中，使日志自动包含该ID
        MdcUtil.setRequestId(requestId);
        // 同时将Request ID写入响应头，方便客户端追踪
        httpResponse.setHeader(HEADER_REQUEST_ID, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // 请求结束后清理MDC，防止线程池复用导致数据污染
            MdcUtil.clear();
        }
    }
}
