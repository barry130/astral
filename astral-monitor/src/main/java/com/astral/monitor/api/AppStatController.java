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
 * 统计数据接入控制器（App 端匿名上报，新路径 /api/v1/app/stat）
 * <p>旧接口 /api/v1/stat/report 保留并废弃（见 {@link StatIngestController}），App 迁移至此。</p>
 * <p>处理失败不影响客户端（恒返回 200，客户端失败即回队列重试）。IP 由服务端解析。</p>
 */
@Tag(name = "统计数据接入（App）", description = "App 端匿名批量上报")
@RestController
@RequestMapping("/api/v1/app/stat")
@RequiredArgsConstructor
public class AppStatController {

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
     * 解析客户端真实 IP：X-Forwarded-For → X-Real-IP → remoteAddr
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded) && !"unknown".equalsIgnoreCase(forwarded)) {
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
