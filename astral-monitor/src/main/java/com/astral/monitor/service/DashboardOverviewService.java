package com.astral.monitor.service;

import com.astral.monitor.dto.DashboardOverviewDTO;
import com.astral.monitor.dto.DeviceOverviewDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 仪表盘总览聚合服务
 * <p>
 * 把首页所需的三类数据（系统资源 / JVM / 业务与统计）聚合成一次请求返回：
 * <ul>
 *   <li>系统资源、JVM：走 {@link SystemMonitorService}、{@link JvmMonitorService}，纯 JMX 读取，无 IO</li>
 *   <li>业务规模：走 {@link BusinessMonitorService}，一次 count + 容器线程数</li>
 *   <li>今日/昨日概览、接口汇总：复用 {@link StatReportService}，与统计报表页口径完全一致</li>
 * </ul>
 * </p>
 * <p>
 * 调用方（前端仪表盘）按 30 秒轮询，故此处不做额外缓存；统计维度本就是按天聚合，
 * 秒级刷新没有意义。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardOverviewService {

    /** 接口 Top 榜取 1 条即可 —— 首页只用 summary（与 limit 无关），避免多查数据 */
    private static final int API_SUMMARY_ONLY_LIMIT = 1;

    /** 全平台口径，与统计报表页默认值一致 */
    private static final String UT_ALL = "all";

    private final SystemMonitorService systemMonitorService;
    private final JvmMonitorService jvmMonitorService;
    private final BusinessMonitorService businessMonitorService;
    private final StatReportService statReportService;

    /**
     * 聚合首页仪表盘全部数据
     *
     * @return 仪表盘总览
     */
    public DashboardOverviewDTO getOverview() {
        DashboardOverviewDTO dto = new DashboardOverviewDTO();

        dto.setSystem(systemMonitorService.getSystemInfo());
        dto.setJvm(jvmMonitorService.getJvmInfo());
        dto.setBusiness(businessMonitorService.getBusinessInfo());

        LocalDate today = LocalDate.now();
        Map<String, DeviceOverviewDTO> deviceOverview = statReportService.getOverview(today, UT_ALL);
        dto.setToday(deviceOverview.get("today"));
        dto.setYesterday(deviceOverview.get("yesterday"));

        dto.setApi(statReportService.getApiTop(today, API_SUMMARY_ONLY_LIMIT).getSummary());

        return dto;
    }
}
