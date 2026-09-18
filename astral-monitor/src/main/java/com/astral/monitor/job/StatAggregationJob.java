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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

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
        for (Map.Entry<ApiMetricCollector.ApiMetricKey, ApiMetricCollector.ApiMetricValue> entry : drained.entrySet()) {
            ApiMetricCollector.ApiMetricKey key = entry.getKey();
            ApiMetricCollector.ApiMetricValue value = entry.getValue();
            long callCount = value.getCallCount();
            long sumMs = value.getSumMs();
            int maxMs = value.getMaxMs();

            int rows = statApiHourlyMapper.incrementApi(
                    key.getBucketHour(), key.getUri(), key.getMethod(), key.getStatus(),
                    callCount, sumMs, maxMs);
            if (rows > 0) {
                updated++;
                continue;
            }
            StatApiHourly bucket = new StatApiHourly();
            bucket.setBucketHour(key.getBucketHour());
            bucket.setUri(key.getUri());
            bucket.setMethod(key.getMethod());
            bucket.setStatus(key.getStatus());
            bucket.setCallCount(callCount);
            bucket.setSumMs(sumMs);
            bucket.setMaxMs(maxMs);
            try {
                statApiHourlyMapper.insert(bucket);
                inserted++;
            } catch (DuplicateKeyException e) {
                // 并发建桶冲突：补一次累加
                statApiHourlyMapper.incrementApi(
                        key.getBucketHour(), key.getUri(), key.getMethod(), key.getStatus(),
                        callCount, sumMs, maxMs);
                updated++;
            }
        }
        log.info("[stat] 接口指标落库完成 buckets={} updated={} inserted={}", drained.size(), updated, inserted);
    }

    /**
     * 每天凌晨 4 点清理过期数据
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanExpired() {
        LocalDateTime errorBefore = LocalDate.now().minusDays(errorRetentionDays).atStartOfDay();
        LocalDateTime hourlyBefore = LocalDate.now().minusDays(hourlyRetentionDays).atStartOfDay();

        int errors = statErrorLogMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StatErrorLog>()
                .lt("occur_time", errorBefore));
        int api = statApiHourlyMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StatApiHourly>()
                .lt("bucket_hour", hourlyBefore));
        int metric = statMetricHourlyMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StatMetricHourly>()
                .lt("bucket_hour", hourlyBefore));
        int page = statPageHourlyMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StatPageHourly>()
                .lt("bucket_hour", hourlyBefore));

        if (errors > 0 || api > 0 || metric > 0 || page > 0) {
            log.info("[stat] 过期数据清理完成 errors={} apiBuckets={} metricBuckets={} pageBuckets={}",
                    errors, api, metric, page);
        }
    }
}
