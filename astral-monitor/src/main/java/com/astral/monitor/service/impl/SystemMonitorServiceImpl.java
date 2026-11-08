package com.astral.monitor.service.impl;

import com.astral.monitor.dto.SystemMonitorDTO;
import com.astral.monitor.service.SystemMonitorService;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import org.springframework.stereotype.Service;

/**
 * 系统监控服务实现类
 * <p>
 * 通过 Java Management Extensions (JMX) 采集系统级别的监控指标。
 * 使用 {@link OperatingSystemMXBean} 获取CPU和系统负载信息，
 * 使用 {@link RuntimeMXBean} 获取JVM运行时间，
 * 通过 {@link File} API 获取磁盘使用情况。
 * </p>
 */
@Service
public class SystemMonitorServiceImpl implements SystemMonitorService {

    /** 操作系统MXBean，用于获取CPU和系统负载信息 */
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    /** 运行时MXBean，用于获取JVM运行时间 */
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

    /**
     * 获取系统监控信息
     * <p>
     * 采集以下指标：
     * <ul>
     *   <li>主机 CPU 和物理内存：从操作系统扩展 MXBean 获取，不支持时返回未知</li>
     *   <li>JVM 堆内存：从 MemoryMXBean 单独获取</li>
     *   <li>活跃线程数：Thread.activeCount()</li>
     *   <li>运行时间：JVM启动至今的毫秒数</li>
     *   <li>磁盘使用率：当前工作目录所在磁盘的使用率</li>
     * </ul>
     * </p>
     *
     * @return 系统监控数据传输对象
     */
    @Override
    public SystemMonitorDTO getSystemInfo() {
        var memory = ManagementFactory.getMemoryMXBean();
        var heap = memory.getHeapMemoryUsage();

        SystemMonitorDTO dto = new SystemMonitorDTO();

        dto.setCpuUsage(getCpuUsage());

        dto.setHeapUsed(heap.getUsed());
        dto.setHeapMax(heap.getMax() >= 0 ? heap.getMax() : null);
        if (osBean instanceof com.sun.management.OperatingSystemMXBean extended) {
            long total = extended.getTotalMemorySize();
            long available = extended.getFreeMemorySize();
            if (total > 0 && available >= 0 && available <= total) {
                dto.setTotalMemory(total);
                dto.setAvailableMemory(available);
                dto.setUsedMemory(total - available);
                dto.setMemoryUsage((total - available) * 100.0 / total);
            }
        }

        dto.setThreadCount(ManagementFactory.getThreadMXBean().getThreadCount());
        dto.setUptime(runtimeBean.getUptime());
        dto.setDiskUsage(getDiskUsage());

        return dto;
    }

    /**
     * 获取CPU使用率
     * <p>
     * 使用操作系统整体 CPU 采样，不将进程负载或 load average 伪装成主机利用率。
     * </p>
     *
     * @return CPU使用率（百分比）
     */
    private Double getCpuUsage() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                double cpuLoad = sunOsBean.getCpuLoad();
                if (Double.isFinite(cpuLoad) && cpuLoad >= 0 && cpuLoad <= 1) {
                    return cpuLoad * 100;
                }
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取磁盘使用率
     * <p>
     * 基于当前工作目录所在磁盘计算使用率。
     * 计算公式：(总空间 - 可用空间) / 总空间 * 100
     * </p>
     *
     * @return 磁盘使用率（百分比）
     */
    private Double getDiskUsage() {
        try {
            File file = new File(".");
            long total = file.getTotalSpace();
            long free = file.getFreeSpace();
            return total > 0 ? (double) (total - free) / total * 100 : null;
        } catch (Exception e) {
            return null;
        }
    }
}
