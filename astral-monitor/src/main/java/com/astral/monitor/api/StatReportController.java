package com.astral.monitor.api;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.common.util.PageQuery;
import com.astral.dao.entity.StatErrorLog;
import com.astral.monitor.dto.ApiTopResultDTO;
import com.astral.monitor.dto.DeviceOverviewDTO;
import com.astral.monitor.dto.ErrorSummaryDTO;
import com.astral.monitor.dto.TrendDTO;
import com.astral.monitor.service.StatReportService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 统计报表控制器（六个查询接口 + 版本下拉数据源）
 * <p>
 * 面向 admin 前端（satoken 鉴权），权限 admin:statistics:view。
 * </p>
 * <p>
 * 三个 Tab 共用「日期 + 平台 + 版本」筛选维度：
 * {@code ut} 与 {@code version} 均为可选，不传 / 空 / all 表示不限制；
 * 但 {@code all} 与 {@code version} 互斥 —— 全部平台时前端会把版本置空。
 * </p>
 */
@Tag(name = "数据统计报表", description = "设备/接口/错误统计查询")
@RestController
@RequestMapping("/api/v1/admin/stat")
@RequiredArgsConstructor
public class StatReportController {

    private final StatReportService statReportService;

    /**
     * 设备统计概览（今日 vs 昨日对比）
     */
    @Operation(summary = "设备统计概览")
    @RequiresPermission("admin:statistics:view")
    @GetMapping("/overview")
    public Result<Map<String, DeviceOverviewDTO>> overview(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "all") String ut,
            @RequestParam(required = false) String version) {
        LocalDate d = date != null ? date : LocalDate.now();
        return Result.success(statReportService.getOverview(d, ut, version));
    }

    /**
     * 24 小时趋势（今日 vs 昨日，补零；gran 本期固定 hour）
     */
    @Operation(summary = "指标24小时趋势")
    @RequiresPermission("admin:statistics:view")
    @GetMapping("/trend")
    public Result<TrendDTO> trend(
            @RequestParam(defaultValue = "pv") String metric,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "all") String ut,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "hour") String gran) {
        LocalDate d = date != null ? date : LocalDate.now();
        return Result.success(statReportService.getTrend(metric, d, ut, version));
    }

    /**
     * 接口调用 Top 榜（兼容被删接口的字段契约）
     */
    @Operation(summary = "接口调用Top榜")
    @RequiresPermission("admin:statistics:view")
    @GetMapping("/api/top")
    public Result<ApiTopResultDTO> apiTop(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "all") String ut,
            @RequestParam(required = false) String version) {
        LocalDate d = date != null ? date : LocalDate.now();
        // 上界钳制：limit 直通 SQL 的 LIMIT，不钳制可由外部拼出超大结果集
        return Result.success(statReportService.getApiTop(d, PageQuery.limit(limit, PageQuery.MAX_LIMIT), ut, version));
    }

    /**
     * 单接口 24 小时调用趋势（callCount 与 avgMs，补零）
     */
    @Operation(summary = "单接口24小时趋势")
    @RequiresPermission("admin:statistics:view")
    @GetMapping("/api/trend")
    public Result<Map<String, List<Long>>> apiTrend(
            @RequestParam String uri,
            @RequestParam String method,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "all") String ut,
            @RequestParam(required = false) String version) {
        LocalDate d = date != null ? date : LocalDate.now();
        return Result.success(statReportService.getApiTrend(uri, method, d, ut, version));
    }

    /**
     * 错误明细分页
     */
    @Operation(summary = "错误明细分页")
    @RequiresPermission("admin:statistics:view")
    @GetMapping("/error/page")
    public Result<Page<StatErrorLog>> errorPage(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String errorType,
            @RequestParam(defaultValue = "all") String ut,
            @RequestParam(required = false) String appVersion,
            @RequestParam(required = false) String fingerprint) {
        return Result.success(statReportService.getErrorPage(
                PageQuery.pageNum(pageNum), PageQuery.pageSize(pageSize),
                errorType, ut, appVersion, fingerprint));
    }

    /**
     * 错误分组汇总（按 fingerprint）
     */
    @Operation(summary = "错误分组汇总")
    @RequiresPermission("admin:statistics:view")
    @GetMapping("/error/summary")
    public Result<List<ErrorSummaryDTO>> errorSummary(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "all") String ut,
            @RequestParam(required = false) String version) {
        LocalDate d = date != null ? date : LocalDate.now();
        return Result.success(statReportService.getErrorSummary(d, ut, version));
    }

    /**
     * 平台下可选版本列表（版本下拉数据源）
     * <p>ut 为 all / 空时返回空列表：全部平台下版本只能为空。</p>
     */
    @Operation(summary = "平台版本列表")
    @RequiresPermission("admin:statistics:view")
    @GetMapping("/versions")
    public Result<List<String>> versions(@RequestParam(defaultValue = "all") String ut) {
        return Result.success(statReportService.getVersions(ut));
    }
}
