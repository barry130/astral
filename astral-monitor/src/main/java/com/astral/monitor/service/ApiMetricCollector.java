package com.astral.monitor.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import lombok.Getter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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

    /** 所有维度合计最多 20,000 桶；预留两个溢出桶，保留总量、耗时和成功/失败口径。 */
    private static final int MAX_BUCKETS = 20_000;

    private final Map<ApiMetricKey, ApiMetricValue> metrics = new HashMap<>();
    private ApiMetricKey successOverflow;
    private ApiMetricKey failureOverflow;

    /**
     * 记录一次接口调用（拦截器 afterCompletion 调用）
     *
     * @param uri        接口路径（不含 query）
     * @param method     HTTP 方法
     * @param status     HTTP 状态码
     * @param elapsedNs  本次请求的**纳秒数**（取自拦截器 preHandle 的起点）。
     * @param ut         客户端平台（取自请求头，缺失时为空串）
     * @param appVersion 客户端版本（取自请求头，缺失时为空串）
     */
    public synchronized void record(
            String uri, String method, int status, long elapsedNs, String ut, String appVersion) {
        // 旧实现由采集侧自己 start=now，导致「耗时恒为 0」——所有接口耗时报表都是假数据。
        // 现在由拦截器传「自 preHandle 起的纳秒数」；负数（时钟异常）当 0 处理。
        long costMs = Math.max(elapsedNs, 0L) / 1_000_000;
        LocalDateTime bucketHour =
                LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        ApiMetricKey key = new ApiMetricKey(bucketHour, uri, method, status, ut, appVersion);
        if (!metrics.containsKey(key) && metrics.size() >= MAX_BUCKETS - 2) {
            // 不再接纳任何新的路径/平台/版本组合，避免任一维度突破硬上限。
            // 溢出桶沿用本次 drain 周期首次溢出的小时；按成功/失败归并，保留整体失败率。
            if (status >= 400) {
                if (failureOverflow == null) {
                    failureOverflow = new ApiMetricKey(bucketHour, "/__overflow__", "*", 500, "", "");
                }
                key = failureOverflow;
            } else {
                if (successOverflow == null) {
                    successOverflow = new ApiMetricKey(bucketHour, "/__overflow__", "*", 200, "", "");
                }
                key = successOverflow;
            }
        }
        metrics.computeIfAbsent(key, ignored -> new ApiMetricValue()).add(costMs);
    }

    /**
     * 取出本轮累计并开始新周期。与短暂的内存累加共享锁，切换时不丢失、不重复计数。
     * 数据库写入在返回后由调用方完成，不占用此锁。
     */
    public synchronized Map<ApiMetricKey, ApiMetricValue> drain() {
        Map<ApiMetricKey, ApiMetricValue> drained = new HashMap<>(metrics);
        metrics.clear();
        successOverflow = null;
        failureOverflow = null;
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

        public ApiMetricKey(
                LocalDateTime bucketHour, String uri, String method, int status, String ut, String appVersion) {
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

    /** 桶维度累计值；只由 collector 在锁内更新，drain 后作为独立结果读取。 */
    public static class ApiMetricValue {
        private final LongAdder callCount = new LongAdder();
        private final LongAdder sumMs = new LongAdder();
        private int maxMs;

        private void add(long costMs) {
            callCount.increment();
            sumMs.add(costMs);
            maxMs = (int) Math.min(Math.max(maxMs, costMs), Integer.MAX_VALUE);
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
