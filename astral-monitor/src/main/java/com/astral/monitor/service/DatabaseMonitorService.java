package com.astral.monitor.service;

import com.astral.monitor.dto.DatabaseMonitorDTO;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 数据库运行状况监控服务
 * <p>
 * 采集两类信息：
 * <ul>
 *   <li>连接池水位：HikariCP 的 {@code HikariPoolMXBean} 提供活跃 / 空闲 / 总数 / 等待线程数，
 *       均来自内存计数，开销可忽略</li>
 *   <li>连通性与容量：执行一次 {@code SELECT 1} 得到端到端往返耗时，再按数据库方言统计库容量</li>
 * </ul>
 * </p>
 * <p>
 * <b>超时保护</b>：数据库不可达时，取连接会一直等到连接池的 connection-timeout（生产配置为 30 秒）。
 * 仪表盘是高频轮询接口，若被拖住会迅速占满 Tomcat 线程。故整个探测放在虚拟线程里执行，
 * 超过 {@link #PROBE_TIMEOUT_MS} 直接返回降级结果。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseMonitorService {

    /** 探测超时（毫秒）：数据库不可达时不拖垮仪表盘接口 */
    private static final long PROBE_TIMEOUT_MS = 3000L;

    /**
     * 探测专用执行器。
     * <p>虚拟线程本身很廉价、无需池化，用 ExecutorService 只是为了拿到可设超时的 Future。</p>
     */
    private static final ExecutorService PROBE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 数据源可选注入：未配置数据库时不应导致应用启动失败 */
    private final ObjectProvider<DataSource> dataSourceProvider;

    /**
     * 获取数据库运行状况
     *
     * @return 数据库监控数据；未配置数据源时各字段为 null
     */
    public DatabaseMonitorDTO getDatabaseInfo() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            // available 保持 null。与「探测失败(false)」区分：前者是没配库，后者是库有问题
            return new DatabaseMonitorDTO();
        }
        try {
            return CompletableFuture.supplyAsync(() -> probe(dataSource), PROBE_EXECUTOR)
                    .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return degrade(dataSource, "探测被中断");
        } catch (TimeoutException e) {
            return degrade(dataSource, "探测超时（>" + PROBE_TIMEOUT_MS + "ms）");
        } catch (Exception e) {
            return degrade(dataSource, e.getMessage());
        }
    }

    /**
     * 真实探测：池水位 + 一次查询往返 + 元数据 + 库容量
     */
    private DatabaseMonitorDTO probe(DataSource dataSource) {
        DatabaseMonitorDTO dto = new DatabaseMonitorDTO();
        fillPoolMetrics(dto, dataSource);

        long start = System.nanoTime();
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            statement.execute("SELECT 1");
            // 在关闭连接前取耗时，语义是「从池取连接 + 一次查询往返」的完整链路
            dto.setPingMs((System.nanoTime() - start) / 1_000_000);
            dto.setAvailable(true);

            DatabaseMetaData meta = conn.getMetaData();
            dto.setProductName(meta.getDatabaseProductName());
            dto.setProductVersion(meta.getDatabaseProductVersion());
            dto.setDriverInfo(meta.getDriverName() + " " + meta.getDriverVersion());
            dto.setDatabaseSize(resolveDatabaseSize(conn, meta.getDatabaseProductName()));
        } catch (Exception e) {
            dto.setPingMs((System.nanoTime() - start) / 1_000_000);
            dto.setAvailable(false);
            log.warn("数据库监控探测失败: {}", e.getMessage());
        }
        return dto;
    }

    /**
     * 读取连接池水位
     * <p>非 Hikari 数据源（或被代理包装）时只填池类型名，其余留空由前端降级。</p>
     */
    private void fillPoolMetrics(DatabaseMonitorDTO dto, DataSource dataSource) {
        dto.setPoolName(dataSource.getClass().getSimpleName());
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return;
        }
        dto.setMaxConnections(hikari.getMaximumPoolSize());
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool == null) {
            // 池尚未初始化（如首次请求前）
            return;
        }
        dto.setActiveConnections(pool.getActiveConnections());
        dto.setIdleConnections(pool.getIdleConnections());
        dto.setTotalConnections(pool.getTotalConnections());
        dto.setWaitingThreads(pool.getThreadsAwaitingConnection());
    }

    /**
     * 按数据库方言统计当前库占用的磁盘空间
     *
     * @return 字节数；方言不支持或无权限时返回 null
     */
    private Long resolveDatabaseSize(Connection conn, String productName) {
        String sql = resolveSizeSql(productName);
        if (sql == null) {
            return null;
        }
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : null;
        } catch (Exception e) {
            // 某些托管库会限制统计函数权限，取不到就留空，不影响其余指标
            log.debug("获取数据库容量失败（{}）: {}", productName, e.getMessage());
            return null;
        }
    }

    /**
     * 各数据库的库容量查询语句
     * <p>统一返回单行单列（字节）。H2 等无等价函数的产品返回 null，前端不展示该项。</p>
     */
    private String resolveSizeSql(String productName) {
        if (productName == null) {
            return null;
        }
        String product = productName.toLowerCase(Locale.ROOT);
        if (product.contains("postgresql")) {
            return "SELECT pg_database_size(current_database())";
        }
        if (product.contains("mysql") || product.contains("mariadb")) {
            return "SELECT COALESCE(SUM(data_length + index_length), 0) "
                    + "FROM information_schema.tables WHERE table_schema = DATABASE()";
        }
        return null;
    }

    /** 探测未完成时的降级结果（池水位仍可读，因为它在内存里） */
    private DatabaseMonitorDTO degrade(DataSource dataSource, String reason) {
        log.warn("数据库监控探测未完成: {}", reason);
        DatabaseMonitorDTO dto = new DatabaseMonitorDTO();
        dto.setAvailable(false);
        fillPoolMetrics(dto, dataSource);
        return dto;
    }
}
