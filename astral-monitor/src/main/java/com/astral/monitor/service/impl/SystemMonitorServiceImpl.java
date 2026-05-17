package com.astral.monitor.service.impl;

import com.astral.monitor.dto.SystemMonitorDTO;
import com.astral.monitor.service.SystemMonitorService;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;

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
     *   <li>CPU使用率：优先使用进程CPU负载，回退到系统负载平均值</li>
     *   <li>堆内存使用情况：从MemoryMXBean获取</li>
     *   <li>内存使用率：已使用堆内存 / 最大堆内存</li>
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
        
        long totalHeap = heap.getMax();
        long usedHeap = heap.getUsed();
        dto.setTotalMemory(totalHeap);
        dto.setUsedMemory(usedHeap);
        dto.setAvailableMemory(totalHeap - usedHeap);
        
        double memoryUsage = totalHeap > 0 ? (double) usedHeap / totalHeap * 100 : 0;
        dto.setMemoryUsage(memoryUsage);
        
        dto.setThreadCount(Thread.activeCount());
        dto.setUptime(runtimeBean.getUptime());
        dto.setDiskUsage(getDiskUsage());
        
        return dto;
    }
    
    /**
     * 获取CPU使用率
     * <p>
     * 获取策略：
     * 1. 优先尝试使用 Sun 扩展的 OperatingSystemMXBean 获取进程CPU负载（更精确）
     * 2. 如果不可用，回退到系统负载平均值 / CPU核心数 的估算方式
     * 3. 如果都不可用，返回 0.0
     * </p>
     *
     * @return CPU使用率（百分比）
     */
    private double getCpuUsage() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                double cpuLoad = sunOsBean.getProcessCpuLoad();
                if (cpuLoad >= 0) {
                    return cpuLoad * 100;
                }
            }
            
            double loadAverage = osBean.getSystemLoadAverage();
            if (loadAverage >= 0) {
                int processors = Runtime.getRuntime().availableProcessors();
                return Math.min(100, loadAverage * 100 / processors);
            }
            
            return 0.0;
            
        } catch (Exception e) {
            return 0.0;
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
    private double getDiskUsage() {
        try {
            File file = new File(".");
            long total = file.getTotalSpace();
            long free = file.getFreeSpace();
            return total > 0 ? (double) (total - free) / total * 100 : 0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
