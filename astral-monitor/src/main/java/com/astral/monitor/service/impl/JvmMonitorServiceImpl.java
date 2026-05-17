package com.astral.monitor.service.impl;

import com.astral.monitor.dto.JvmMonitorDTO;
import com.astral.monitor.service.JvmMonitorService;
import org.springframework.stereotype.Service;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * JVM监控服务实现类
 * <p>
 * 通过 Java Management Extensions (JMX) 采集JVM级别的监控指标。
 * 使用 MemoryMXBean 获取堆内存信息，
 * 使用 GarbageCollectorMXBean 获取GC统计信息，
 * 使用 RuntimeMXBean 获取JVM运行时间和版本信息。
 * </p>
 */
@Service
public class JvmMonitorServiceImpl implements JvmMonitorService {

    /**
     * 获取JVM监控信息
     * <p>
     * 采集以下指标：
     * <ul>
     *   <li>堆内存已使用/最大/使用率：从MemoryMXBean获取</li>
     *   <li>活跃线程数：Thread.activeCount()</li>
     *   <li>GC总次数：累加所有垃圾收集器的收集次数</li>
     *   <li>运行时间：JVM启动至今的毫秒数</li>
     *   <li>JDK版本：java.version 系统属性</li>
     *   <li>JVM名称：RuntimeMXBean.getVmName()</li>
     * </ul>
     * </p>
     *
     * @return JVM监控数据传输对象
     */
    @Override
    public JvmMonitorDTO getJvmInfo() {
        var heapMem = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var nonHeapMem = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long totalGcCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        
        JvmMonitorDTO dto = new JvmMonitorDTO();
        dto.setHeapUsed(heapMem.getUsed());
        dto.setHeapMax(heapMem.getMax());
        dto.setHeapUsage(heapMem.getMax() > 0 ? (double) heapMem.getUsed() / heapMem.getMax() * 100 : 0);
        dto.setThreadCount(Thread.activeCount());
        dto.setGcCount((int) totalGcCount);
        dto.setUptime(ManagementFactory.getRuntimeMXBean().getUptime());
        dto.setJdkVersion(System.getProperty("java.version"));
        dto.setJvmName(ManagementFactory.getRuntimeMXBean().getVmName());
        
        return dto;
    }
}