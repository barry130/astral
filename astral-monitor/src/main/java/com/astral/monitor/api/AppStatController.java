package com.astral.monitor.api;

import org.springframework.beans.factory.annotation.Value;
import com.astral.common.result.Result;
import com.astral.common.util.ClientIp;
import com.astral.monitor.dto.StatReportRequest;
import com.astral.monitor.service.StatIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计数据接入控制器（App 端匿名上报，新路径 /api/v1/app/stat）
 * <p>旧接口 /api/v1/stat/report 保留并废弃（见 {@link StatIngestController}），App 迁移至此。</p>
 * <p>处理失败不影响客户端（恒返回 200，客户端失败即回队列重试）。IP 由服务端解析。</p>
 */
@Tag(name = "统计数据接入（App）", description = "App 端匿名批量上报")
@RestController
@RequestMapping("/api/v1/app/stat")
@RequiredArgsConstructor
public class AppStatController {

    /** 可信代理列表（决定能否采信 X-Forwarded-For），与全站口径一致 */
    @Value("${astral.web.trusted-proxies:" + ClientIp.DEFAULT_TRUSTED_PROXIES + "}")
    private String trustedProxies;

    private final StatIngestService statIngestService;

    /**
     * 匿名批量上报统计事件（单批 ≤200 条）
     */
    @Operation(summary = "匿名批量上报统计事件")
    @PostMapping("/report")
    public Result<Void> report(@Valid @RequestBody StatReportRequest request,
                               HttpServletRequest servletRequest) {
        String ip = resolveClientIp(servletRequest);
        statIngestService.processBatchAsync(request.getEvents(), ip);
        return Result.success();
    }

    /**
     * 解析客户端真实 IP
     * <p>与全站一致走 {@link ClientIp} 的可信代理白名单解析。
     * 原实现无条件取 X-Forwarded-For 最左段 —— 该头客户端可任意伪造，
     * 会导致统计口径里的地域/设备分布被污染，也会被用来绕过按 IP 的限速。</p>
     */
    private String resolveClientIp(HttpServletRequest request) {
        return ClientIp.resolve(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr(),
                trustedProxies);
    }
}
