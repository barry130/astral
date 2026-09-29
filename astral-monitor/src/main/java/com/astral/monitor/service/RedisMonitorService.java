package com.astral.monitor.service;

import com.astral.monitor.dto.RedisMonitorDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Redis 运行状况监控服务
 * <p>
 * 通过 {@link RedisConnectionFactory} 执行 {@code INFO} 与 {@code DBSIZE}，采集版本、运行模式、
 * 客户端数、内存占用与碎片率、命中率、每秒命令数等指标。
 * </p>
 * <p>
 * <b>超时保护</b>：Redis 不可达时命令会阻塞到命令超时（Lettuce 默认 60 秒），
 * 而仪表盘是高频轮询接口，被拖住会迅速占满 Tomcat 线程。故整个探测放在虚拟线程里执行，
 * 超过 {@link #PROBE_TIMEOUT_MS} 直接返回降级结果。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisMonitorService {

    /** 探测超时（毫秒）：Redis 不可达时不拖垮仪表盘接口 */
    private static final long PROBE_TIMEOUT_MS = 3000L;

    /** 探测专用执行器（虚拟线程，用 ExecutorService 只为拿到可设超时的 Future） */
    private static final ExecutorService PROBE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 连接工厂可选注入：未配置 Redis 时不应导致应用启动失败 */
    private final ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;

    /** 当前使用的库索引，仅用于展示 */
    @Value("${spring.data.redis.database:0}")
    private int databaseIndex;

    /**
     * 获取 Redis 运行状况
     *
     * @return Redis 监控数据；未配置 Redis 时各字段为 null
     */
    public RedisMonitorDTO getRedisInfo() {
        RedisConnectionFactory factory = connectionFactoryProvider.getIfAvailable();
        if (factory == null) {
            return new RedisMonitorDTO();
        }
        RedisMonitorDTO dto;
        try {
            dto = CompletableFuture.supplyAsync(() -> probe(factory), PROBE_EXECUTOR)
                    .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dto = degrade("探测被中断");
        } catch (TimeoutException e) {
            dto = degrade("探测超时（>" + PROBE_TIMEOUT_MS + "ms）");
        } catch (Exception e) {
            dto = degrade(e.getMessage());
        }
        // 库索引来自配置，任何时候都能填
        dto.setDatabaseIndex(databaseIndex);
        return dto;
    }

    /** 真实探测：INFO + DBSIZE */
    private RedisMonitorDTO probe(RedisConnectionFactory factory) {
        RedisMonitorDTO dto = new RedisMonitorDTO();
        RedisConnection connection = null;
        long start = System.nanoTime();
        try {
            connection = factory.getConnection();
            Properties info = connection.serverCommands().info();
            dto.setPingMs((System.nanoTime() - start) / 1_000_000);
            dto.setAvailable(true);

            fillFromInfo(dto, info);
            dto.setTotalKeys(connection.serverCommands().dbSize());
        } catch (Exception e) {
            dto.setPingMs((System.nanoTime() - start) / 1_000_000);
            dto.setAvailable(false);
            log.warn("Redis 监控探测失败: {}", e.getMessage());
        } finally {
            if (connection != null) {
                try {
                    // 归还连接到共享连接（Lettuce）而非真正断开
                    connection.close();
                } catch (Exception e) {
                    log.debug("Redis 连接释放异常: {}", e.getMessage());
                }
            }
        }
        return dto;
    }

    /** 从 INFO 结果填充各项指标 */
    private void fillFromInfo(RedisMonitorDTO dto, Properties info) {
        if (info == null) {
            return;
        }
        dto.setVersion(info.getProperty("redis_version"));
        dto.setMode(info.getProperty("redis_mode"));
        dto.setUptimeSeconds(toLong(info.getProperty("uptime_in_seconds")));
        dto.setConnectedClients(toInt(info.getProperty("connected_clients")));
        dto.setUsedMemory(toLong(info.getProperty("used_memory")));
        dto.setUsedMemoryPeak(toLong(info.getProperty("used_memory_peak")));
        dto.setMaxMemory(toLong(info.getProperty("maxmemory")));
        dto.setMemFragmentationRatio(toDouble(info.getProperty("mem_fragmentation_ratio")));
        dto.setInstantaneousOpsPerSec(toLong(info.getProperty("instantaneous_ops_per_sec")));

        Long hits = toLong(info.getProperty("keyspace_hits"));
        Long misses = toLong(info.getProperty("keyspace_misses"));
        dto.setKeyspaceHits(hits);
        dto.setKeyspaceMisses(misses);
        dto.setHitRate(calcHitRate(hits, misses));
    }

    /**
     * 计算命中率
     * <p>命中率 = 命中 /（命中 + 未命中）。服务刚启动、尚无读写记录时返回 null 而非 0，
     * 避免把「没有数据」误报成「命中率 0%」。</p>
     */
    private Double calcHitRate(Long hits, Long misses) {
        long hit = hits == null ? 0L : hits;
        long miss = misses == null ? 0L : misses;
        long total = hit + miss;
        if (total <= 0) {
            return null;
        }
        return Math.round((double) hit / total * 10000) / 100.0;
    }

    /** 探测未完成时的降级结果 */
    private RedisMonitorDTO degrade(String reason) {
        log.warn("Redis 监控探测未完成: {}", reason);
        RedisMonitorDTO dto = new RedisMonitorDTO();
        dto.setAvailable(false);
        return dto;
    }

    private static Long toLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(String value) {
        Long parsed = toLong(value);
        return parsed == null ? null : parsed.intValue();
    }

    private static Double toDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
