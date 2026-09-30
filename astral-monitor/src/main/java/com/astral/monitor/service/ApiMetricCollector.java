package com.astral.monitor.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
@ConditionalOnProperty(name = "astral.stat.enabled", havingValue = "true", matchIfMissing = true)
public class ApiMetricCollector {

    /**
     * 桶基数上限。
     * <p>{@code uri} 来自服务端路由（有界）、{@code method}/{@code status} 有界、
     * {@code ut} 走白名单有界，唯一无界的是客户端自报的版本号。正常水位下
     * 桶数 = 接口数 × 平台数 × 版本数，量级在几十到几千；这里留出的上限远高于正常水位。
     * 一旦触顶，则<b>只丢弃版本维度</b>（归并到空串桶），保证按接口/平台的统计仍然准确，
     * 同时内存与落库行数不再无界增长。</p>
     */
    private static final int MAX_BUCKETS = 20_000;

    /**
     * 单次记录最多重试入桶次数。
     * <p>仅在「恰好撞上 flush 换段」时才会重试（每分钟一次、窗口极窄），
     * 正常流量一次即命中。</p>
     */
    private static final int MAX_INBOUND_RETRY = 3;

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
        if (metrics.get(key) == null && metrics.mappingCount() >= MAX_BUCKETS) {
            // 桶数触顶：丢弃版本维度降级归并，避免被伪造的 X-App-Version 撑爆内存与落库行数。
            // 仅在「新桶」路径上做一次计数检查，命中已有桶的热路径无额外开销。
            key = new ApiMetricKey(bucketHour, uri, method, status, ut, "");
        }
        for (int attempt = 0; attempt < MAX_INBOUND_RETRY; attempt++) {
            ApiMetricValue bucket = metrics.computeIfAbsent(key, k -> new ApiMetricValue());
            if (bucket.add(costMs)) {
                return;
            }
            // 该桶在本次累加之前刚被 flush 摘除（已退休）：剔除退役对象，下一轮重建新桶，
            // 使本次计数落到仍然生效的桶上，而不是丢失在已脱链的对象里。
            metrics.remove(key, bucket);
        }
        log.warn("[stat] 指标入桶重试 {} 次仍失败，本次计数丢弃: uri={} method={} status={}",
                MAX_INBOUND_RETRY, uri, method, status);
    }

    /**
     * 取出并清空当前累计（供定时任务 flush）。
     * <p>每个桶在摘除前先「退休 + 快照」：退休标记让此后到达的 {@code add} 被拒绝并由
     * {@link #record} 改投新桶，快照则保证已计入的数值不会漏掉。二者在同一个锁内完成，
     * 因此既不会丢计数、也不会把同一次请求统计两次。</p>
     */
    public Map<ApiMetricKey, ApiMetricValue> drain() {
        Map<ApiMetricKey, ApiMetricValue> drained = new HashMap<>();
        for (Map.Entry<ApiMetricKey, ApiMetricValue> entry : metrics.entrySet()) {
            ApiMetricValue bucket = entry.getValue();
            ApiMetricValue snapshot = bucket.retire();
            if (snapshot == null) {
                // 已被其他流程退休（理论上不会并发发生），跳过
                continue;
            }
            if (!metrics.remove(entry.getKey(), bucket)) {
                // remove 失败说明并发 record 刚把这个 key 的旧桶摘除并建了新桶（见 record 的重投递路径）。
                // 此时快照不能再 putIfAbsent 放回——新桶已占位，放回是 no-op、快照会被静默丢弃；
                // 直接把快照计入本轮产出即可，新桶留到下一轮 drain。
                drained.put(entry.getKey(), snapshot);
                continue;
            }
            drained.put(entry.getKey(), snapshot);
        }
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
     * 桶维度累计值。
     * <p>计数用 {@link LongAdder} 无锁累加，{@code maxMs} 与「退休状态」在同一个锁内维护，
     * 使 {@link #add} 与 {@link #retire} 互斥：被 flush 摘除的桶不会再接收写入。</p>
     */
    public static class ApiMetricValue {
        private final LongAdder callCount = new LongAdder();
        private final LongAdder sumMs = new LongAdder();
        private int maxMs = 0;
        /** 已被 flush 摘除：拒绝后续写入，避免计数落在已脱链对象上而丢失 */
        private boolean retired = false;

        /**
         * 计入一次调用。
         *
         * @param costMs 本次耗时（毫秒）
         * @return true 表示已计入本桶；false 表示本桶已被 flush 退休，调用方应重新入桶
         */
        synchronized boolean add(long costMs) {
            if (retired) {
                return false;
            }
            callCount.increment();
            sumMs.add(costMs);
            if (costMs > maxMs) {
                maxMs = (int) Math.min(costMs, Integer.MAX_VALUE);
            }
            return true;
        }

        /**
         * 退休并返回当前计数快照（供 {@link #drain} 在摘除前调用）。
         *
         * @return 计数快照；若本桶已是退休状态则返回 null（表示已被其他流程摘除）
         */
        synchronized ApiMetricValue retire() {
            if (retired) {
                return null;
            }
            retired = true;
            ApiMetricValue snapshot = new ApiMetricValue();
            snapshot.callCount.add(callCount.sum());
            snapshot.sumMs.add(sumMs.sum());
            snapshot.maxMs = maxMs;
            return snapshot;
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
