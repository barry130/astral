package com.astral.monitor.job;

import com.astral.dao.entity.StatApiHourly;
import com.astral.dao.entity.StatErrorLog;
import com.astral.dao.entity.StatMetricHourly;
import com.astral.dao.entity.StatPageHourly;
import com.astral.dao.mapper.StatApiHourlyMapper;
import com.astral.dao.mapper.StatErrorLogMapper;
import com.astral.dao.mapper.StatMetricHourlyMapper;
import com.astral.dao.mapper.StatPageHourlyMapper;
import com.astral.monitor.service.ApiMetricCollector;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 统计聚合与清理定时任务（StatAggregationJob）
 * <p>
 * ① 每分钟将内存累加器的接口指标 flush 到 stat_api_hourly（先 UPDATE 后 INSERT 两步 upsert）；
 * ② 每天凌晨 4 点清理过期数据（错误默认 &gt;90 天、小时桶默认 &gt;180 天，天数可配）。
 * 开关：astral.stat.enabled（默认 true）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "astral.stat.enabled", havingValue = "true", matchIfMissing = true)
public class StatAggregationJob {

    private final ApiMetricCollector apiMetricCollector;
    private final StatApiHourlyMapper statApiHourlyMapper;
    private final StatErrorLogMapper statErrorLogMapper;
    private final StatMetricHourlyMapper statMetricHourlyMapper;
    private final StatPageHourlyMapper statPageHourlyMapper;

    /** 错误明细保留天数 */
    @Value("${astral.stat.error-retention-days:90}")
    private int errorRetentionDays;

    /** 小时桶保留天数 */
    @Value("${astral.stat.hourly-retention-days:180}")
    private int hourlyRetentionDays;

    /**
     * 每分钟 flush 接口指标到 stat_api_hourly
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void flushApiMetrics() {
        Map<ApiMetricCollector.ApiMetricKey, ApiMetricCollector.ApiMetricValue> drained = apiMetricCollector.drain();
        if (drained.isEmpty()) {
            return;
        }
        int inserted = 0;
        int updated = 0;
        int failed = 0;
        for (Map.Entry<ApiMetricCollector.ApiMetricKey, ApiMetricCollector.ApiMetricValue> entry : drained.entrySet()) {
            ApiMetricCollector.ApiMetricKey key = entry.getKey();
            ApiMetricCollector.ApiMetricValue value = entry.getValue();
            // 逐桶隔离：drain() 已经从内存摘除，这里任一步抛异常都不能中断整个方法，
            // 否则本分钟「其余所有桶」的指标会连同这个桶一起永久丢失（静默、不可恢复）。
            try {
                long callCount = value.getCallCount();
                long sumMs = value.getSumMs();
                int maxMs = value.getMaxMs();

                int rows = statApiHourlyMapper.incrementApi(
                        key.getBucketHour(),
                        key.getUri(),
                        key.getMethod(),
                        key.getStatus(),
                        key.getUt(),
                        key.getAppVersion(),
                        callCount,
                        sumMs,
                        maxMs);
                if (rows > 0) {
                    updated++;
                    continue;
                }
                StatApiHourly bucket = new StatApiHourly();
                bucket.setBucketHour(key.getBucketHour());
                bucket.setUri(key.getUri());
                bucket.setMethod(key.getMethod());
                bucket.setStatus(key.getStatus());
                bucket.setUt(key.getUt());
                bucket.setAppVersion(key.getAppVersion());
                bucket.setCallCount(callCount);
                bucket.setSumMs(sumMs);
                bucket.setMaxMs(maxMs);
                try {
                    statApiHourlyMapper.insert(bucket);
                    inserted++;
                } catch (DuplicateKeyException e) {
                    // 并发建桶冲突：补一次累加
                    statApiHourlyMapper.incrementApi(
                            key.getBucketHour(),
                            key.getUri(),
                            key.getMethod(),
                            key.getStatus(),
                            key.getUt(),
                            key.getAppVersion(),
                            callCount,
                            sumMs,
                            maxMs);
                    updated++;
                }
            } catch (Exception e) {
                failed++;
                // 带上完整桶维度，便于按需从日志补录
                log.error(
                        "[stat] 指标桶落库失败 bucketHour={} uri={} method={} status={} ut={} appVersion={} callCount={}: {}",
                        key.getBucketHour(),
                        key.getUri(),
                        key.getMethod(),
                        key.getStatus(),
                        key.getUt(),
                        key.getAppVersion(),
                        value.getCallCount(),
                        e.getMessage(),
                        e);
            }
        }
        log.info(
                "[stat] 接口指标落库完成 buckets={} updated={} inserted={} failed={}",
                drained.size(),
                updated,
                inserted,
                failed);
    }

    /**
     * 每天凌晨 4 点清理过期数据
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanExpired() {
        // 保留天数 <=0 时关闭该类别清理：负值会让 minusDays 得到未来时间并删空整表，
        // 配置误写一个 -1 就是不可恢复的数据事故。
        int errors = 0;
        int api = 0;
        int metric = 0;
        int page = 0;
        if (errorRetentionDays > 0) {
            LocalDateTime errorBefore =
                    LocalDate.now().minusDays(errorRetentionDays).atStartOfDay();
            try {
                errors = statErrorLogMapper.delete(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StatErrorLog>()
                                .lt("occur_time", errorBefore));
            } catch (Exception e) {
                log.error("[stat] 过期错误日志清理失败，保留数据待下次重试: {}", e.getMessage(), e);
            }
        } else {
            log.warn("[stat] astral.stat.error-retention-days={} 非正数，跳过错误日志清理", errorRetentionDays);
        }
        if (hourlyRetentionDays > 0) {
            LocalDateTime hourlyBefore =
                    LocalDate.now().minusDays(hourlyRetentionDays).atStartOfDay();
            try {
                api = statApiHourlyMapper.delete(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StatApiHourly>()
                                .lt("bucket_hour", hourlyBefore));
            } catch (Exception e) {
                log.error("[stat] 过期接口桶清理失败，保留数据待下次重试: {}", e.getMessage(), e);
            }
            try {
                metric = statMetricHourlyMapper.delete(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StatMetricHourly>()
                                .lt("bucket_hour", hourlyBefore));
            } catch (Exception e) {
                log.error("[stat] 过期指标桶清理失败，保留数据待下次重试: {}", e.getMessage(), e);
            }
            try {
                page = statPageHourlyMapper.delete(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StatPageHourly>()
                                .lt("bucket_hour", hourlyBefore));
            } catch (Exception e) {
                log.error("[stat] 过期页面桶清理失败，保留数据待下次重试: {}", e.getMessage(), e);
            }
        } else {
            log.warn("[stat] astral.stat.hourly-retention-days={} 非正数，跳过小时桶清理", hourlyRetentionDays);
        }

        if (errors > 0 || api > 0 || metric > 0 || page > 0) {
            log.info(
                    "[stat] 过期数据清理完成 errors={} apiBuckets={} metricBuckets={} pageBuckets={}",
                    errors,
                    api,
                    metric,
                    page);
        }
    }
}
