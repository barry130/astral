package com.astral.monitor.api;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.monitor.dto.BusinessMonitorDTO;
import com.astral.monitor.dto.DashboardOverviewDTO;
import com.astral.monitor.dto.JvmMonitorDTO;
import com.astral.monitor.dto.SystemMonitorDTO;
import com.astral.monitor.service.BusinessMonitorService;
import com.astral.monitor.service.DashboardOverviewService;
import com.astral.monitor.service.JvmMonitorService;
import com.astral.monitor.service.SystemMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 监控控制器
 * <p>
 * 提供系统监控相关的REST接口，包括系统监控、JVM监控、业务监控、仪表盘总览。
 * 接口路径前缀：{@code /api/v1/admin/monitor}
 * </p>
 * <p>
 * 首页仪表盘请优先使用 {@code GET /dashboard}：一次返回全部所需数据，
 * 其余三个接口保留给需要单独取某类指标的调用方。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/monitor")
@RequiresPermission(value = "admin:monitor:view", name = "系统监控", description = "系统/业务监控指标查看")
@RequiredArgsConstructor
public class MonitorController {
    /** 系统监控服务 */
    private final SystemMonitorService systemMonitorService;
    /** JVM监控服务 */
    private final JvmMonitorService jvmMonitorService;
    /** 业务监控服务 */
    private final BusinessMonitorService businessMonitorService;
    /** 仪表盘聚合服务 */
    private final DashboardOverviewService dashboardOverviewService;

    /**
     * 获取仪表盘总览（首页专用聚合接口）
     * <p>
     * 一次返回系统资源、JVM、业务规模、今日/昨日设备概览与今日接口调用汇总，
     * 替代首页原先并发调用五个接口的做法。
     * </p>
     *
     * @return 仪表盘总览数据
     */
    @GetMapping("/dashboard")
    public Result<DashboardOverviewDTO> getDashboard() {
        return Result.success(dashboardOverviewService.getOverview());
    }

    /**
     * 获取系统监控信息
     * <p>
     * 返回CPU使用率、内存使用率、磁盘使用率、线程数、运行时间等系统指标。
     * </p>
     *
     * @return 系统监控数据
     */
    @GetMapping("/system")
    public Result<SystemMonitorDTO> getSystemInfo() {
        return Result.success(systemMonitorService.getSystemInfo());
    }

    /**
     * 获取JVM监控信息
     * <p>
     * 返回堆内存使用情况、GC次数、线程数、JDK版本等JVM指标。
     * </p>
     *
     * @return JVM监控数据
     */
    @GetMapping("/jvm")
    public Result<JvmMonitorDTO> getJvmInfo() {
        return Result.success(jvmMonitorService.getJvmInfo());
    }

    /**
     * 获取业务监控信息
     * <p>
     * 返回序列配置数量、活跃连接数等业务指标。
     * </p>
     *
     * @return 业务监控数据
     */
    @GetMapping("/business")
    public Result<BusinessMonitorDTO> getBusinessInfo() {
        return Result.success(businessMonitorService.getBusinessInfo());
    }
}