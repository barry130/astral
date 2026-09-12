package com.astral.monitor.api;

import com.astral.common.result.Result;
import com.astral.monitor.dto.BusinessMonitorDTO;
import com.astral.monitor.dto.JvmMonitorDTO;
import com.astral.monitor.dto.SystemMonitorDTO;
import com.astral.monitor.service.BusinessMonitorService;
import com.astral.monitor.service.JvmMonitorService;
import com.astral.monitor.service.SystemMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 监控控制器
 * <p>
 * 提供系统监控相关的REST接口，包括系统监控、JVM监控、业务监控。
 * 接口路径前缀：{@code /api/v1/admin/monitor}
 * </p>
 */
@RestController
@RequestMapping("/api/v1/admin/monitor")
@RequiredArgsConstructor
public class MonitorController {
    /** 系统监控服务 */
    private final SystemMonitorService systemMonitorService;
    /** JVM监控服务 */
    private final JvmMonitorService jvmMonitorService;
    /** 业务监控服务 */
    private final BusinessMonitorService businessMonitorService;

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
     * 返回序列生成总数、QPS、配置数量等业务指标。
     * </p>
     *
     * @return 业务监控数据
     */
    @GetMapping("/business")
    public Result<BusinessMonitorDTO> getBusinessInfo() {
        return Result.success(businessMonitorService.getBusinessInfo());
    }
}