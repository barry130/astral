package com.astral.monitor.api;

import com.astral.common.result.Result;
import com.astral.monitor.dto.StatReportRequest;
import com.astral.monitor.service.StatIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计数据接入控制器（STATS_DESIGN.md §4.1，旧路径 /api/v1/stat/report，已废弃）
 * <p>App 端请迁移到 /api/v1/app/stat/report（见 {@link AppStatController}）。</p>
 * @deprecated 使用 {@link AppStatController}
 */
@Deprecated
@Tag(name = "统计数据接入", description = "App 端匿名批量上报")
@RestController
@RequestMapping("/api/v1/stat")
@RequiredArgsConstructor
public class StatIngestController {

    private final StatIngestService statIngestService;

    /**
     * 匿名批量上报统计事件（单批 ≤200 条）
     * <p>
     * 处理失败不影响客户端（恒返回 200，客户端失败即回队列重试）。
     * IP 由服务端从请求解析（X-Forwarded-For 优先），客户端上报的 IP 一律不采信，防伪造。
     * </p>
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
     * 解析客户端真实 IP：X-Forwarded-For → X-Real-IP → remoteAddr
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded) && !"unknown".equalsIgnoreCase(forwarded)) {
            // X-Forwarded-For 可能为逗号分隔的链路，取最左（真实客户端）
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp) && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
