package com.astral.monitor.service.impl;

import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.monitor.dto.BusinessMonitorDTO;
import com.astral.monitor.service.BusinessMonitorService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 业务监控服务实现类
 * <p>
 * 采集业务级别的监控指标：
 * <ul>
 *   <li>序列配置数量：从数据库查询</li>
 *   <li>活跃连接数：读取容器线程池中正在处理请求的线程数</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessMonitorServiceImpl implements BusinessMonitorService {

    /**
     * 容器线程池忙碌线程数指标名。
     * <p>Micrometer 为内嵌容器注册的标准指标（Tomcat 下即当前正在处理请求的线程数），
     * 走指标注册表读取可避免直接依赖 Spring Boot 内嵌容器的内部类 ——
     * 这类类在 Spring Boot 4 的模块化重构中已变更包名。</p>
     */
    private static final String METRIC_CONTAINER_THREADS_BUSY = "tomcat.threads.busy";

    /** 序列配置Mapper，用于查询序列配置数量 */
    private final SequenceConfigMapper sequenceConfigMapper;

    /**
     * 指标注册表（可选依赖）。
     * <p>用 ObjectProvider 而非直接注入：未启用 actuator/metrics 时不应导致应用启动失败，
     * 此时活跃连接数降级为 null，由前端隐藏该项。</p>
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * 获取业务监控信息
     *
     * @return 业务监控数据传输对象
     */
    @Override
    public BusinessMonitorDTO getBusinessInfo() {
        BusinessMonitorDTO dto = new BusinessMonitorDTO();
        dto.setConfigCount(sequenceConfigMapper.selectCount(null));
        dto.setActiveConnections(resolveActiveConnections());
        return dto;
    }

    /**
     * 读取当前活跃请求线程数
     * <p>
     * 容器非 Tomcat、或未注册对应指标时返回 null，由前端降级展示，不影响其余指标。
     * </p>
     *
     * @return 活跃线程数；不可获取时返回 null
     */
    private Integer resolveActiveConnections() {
        try {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry == null) {
                return null;
            }
            Gauge busyThreads = registry.find(METRIC_CONTAINER_THREADS_BUSY).gauge();
            if (busyThreads == null) {
                return null;
            }
            double value = busyThreads.value();
            return Double.isNaN(value) ? null : (int) Math.round(value);
        } catch (Exception e) {
            // 指标读取异常不应影响整个监控接口，降级为不展示
            log.debug("获取活跃连接数失败，降级返回 null", e);
            return null;
        }
    }
}
