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
 * 接口指标内存累加器（ApiMetricCollector）
 * <p>
 * 拦截器在请求结束后仅写内存（纳秒级开销），由 {@code StatAggregationJob}
 * 每分钟 flush 落库到 {@code stat_api_hourly}，DB 写入量 = uri × 分钟 × 平台 × 版本。
 * 维度：小时桶 + uri + method + status + 客户端平台 + 客户端版本。
 * </p>
 */
@Component
@ConditionalOnProperty(name = "astral.stat.enabled", havingValue = "true", matchIfMissing = true)
public class ApiMetricCollector {

    private final ConcurrentHashMap<ApiMetricKey, ApiMetricValue> metrics = new ConcurrentHashMap<>();

    /**
     * 记录一次接口调用（拦截器 afterCompletion 调用）
     *
     * @param uri        接口路径（不含 query）
     * @param method     HTTP 方法
     * @param status     HTTP 状态码
     * @param costMs     耗时（毫秒）
     * @param ut         客户端平台（取自请求头，缺失时为空串）
     * @param appVersion 客户端版本（取自请求头，缺失时为空串）
     */
    public void record(String uri, String method, int status, long costMs, String ut, String appVersion) {
        LocalDateTime bucketHour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        ApiMetricKey key = new ApiMetricKey(bucketHour, uri, method, status, ut, appVersion);
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
     * <p>客户端平台与版本参与 key，缺省为空串 —— 未携带请求头的调用统一归到空串桶。</p>
     */
    @Getter
    public static class ApiMetricKey {
        private final LocalDateTime bucketHour;
        private final String uri;
        private final String method;
        private final int status;
        private final String ut;
        private final String appVersion;

        public ApiMetricKey(LocalDateTime bucketHour, String uri, String method, int status,
                            String ut, String appVersion) {
            this.bucketHour = bucketHour;
            this.uri = uri;
            this.method = method;
            this.status = status;
            this.ut = ut == null ? "" : ut;
            this.appVersion = appVersion == null ? "" : appVersion;
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
                    && method.equals(that.method)
                    && ut.equals(that.ut)
                    && appVersion.equals(that.appVersion);
        }

        @Override
        public int hashCode() {
            int result = bucketHour.hashCode();
            result = 31 * result + uri.hashCode();
            result = 31 * result + method.hashCode();
            result = 31 * result + status;
            result = 31 * result + ut.hashCode();
            result = 31 * result + appVersion.hashCode();
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
