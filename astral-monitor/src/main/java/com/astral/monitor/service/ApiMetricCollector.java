package com.astral.monitor.service;

import lombok.Getter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 接口指标内存累加器（STATS_DESIGN.md §5.3 ApiMetricCollector）
 * <p>
 * 拦截器在请求结束后仅写内存（纳秒级开销），由 {@code StatAggregationJob}
 * 每分钟 flush 落库到 {@code stat_api_hourly}，DB 写入量 = uri × 分钟。
 * 维度：小时桶 + uri + method + status。
 * </p>
 */
@Component
@ConditionalOnProperty(name = "astral.stat.enabled", havingValue = "true", matchIfMissing = true)
public class ApiMetricCollector {

    private final ConcurrentHashMap<ApiMetricKey, ApiMetricValue> metrics = new ConcurrentHashMap<>();

    /**
     * 记录一次接口调用（拦截器 afterCompletion 调用）
     *
     * @param uri    接口路径（不含 query）
     * @param method HTTP 方法
     * @param status HTTP 状态码
     * @param costMs 耗时（毫秒）
     */
    public void record(String uri, String method, int status, long costMs) {
        LocalDateTime bucketHour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        ApiMetricKey key = new ApiMetricKey(bucketHour, uri, method, status);
        metrics.computeIfAbsent(key, k -> new ApiMetricValue()).add(costMs);
    }

    /**
     * 取出并清空当前累计（供定时任务 flush；原子移除避免丢数据）
     */
    public Map<ApiMetricKey, ApiMetricValue> drain() {
        Map<ApiMetricKey, ApiMetricValue> drained = new HashMap<>();
        metrics.forEach((key, value) -> {
            if (metrics.remove(key, value)) {
                drained.put(key, value);
            }
        });
        return drained;
    }

    /**
     * 桶维度键
     */
    @Getter
    public static class ApiMetricKey {
        private final LocalDateTime bucketHour;
        private final String uri;
        private final String method;
        private final int status;

        public ApiMetricKey(LocalDateTime bucketHour, String uri, String method, int status) {
            this.bucketHour = bucketHour;
            this.uri = uri;
            this.method = method;
            this.status = status;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ApiMetricKey that)) {
                return false;
            }
            return status == that.status
                    && bucketHour.equals(that.bucketHour)
                    && uri.equals(that.uri)
                    && method.equals(that.method);
        }

        @Override
        public int hashCode() {
            int result = bucketHour.hashCode();
            result = 31 * result + uri.hashCode();
            result = 31 * result + method.hashCode();
            result = 31 * result + status;
            return result;
        }
    }

    /**
     * 桶维度累计值（LongAdder 无锁计数；maxMs 用同步保证正确性）
     */
    public static class ApiMetricValue {
        private final LongAdder callCount = new LongAdder();
        private final LongAdder sumMs = new LongAdder();
        private int maxMs = 0;

        public synchronized void add(long costMs) {
            callCount.increment();
            sumMs.add(costMs);
            if (costMs > maxMs) {
                maxMs = (int) Math.min(costMs, Integer.MAX_VALUE);
            }
        }

        public long getCallCount() {
            return callCount.sum();
        }

        public long getSumMs() {
            return sumMs.sum();
        }

        public synchronized int getMaxMs() {
            return maxMs;
        }
    }
}
