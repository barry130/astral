package com.astral.sequence.generator;

import com.astral.dao.entity.SequenceSegment;
import com.astral.dao.mapper.SequenceSegmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 号段模式序列号生成器（默认生成器）
 * <p>
 * 号段模式是一种高效的序列号生成策略。它从数据库中批量获取一段连续的 ID（号段），
 * 然后在内存中逐个分配，减少数据库访问频率。核心特性包括：
 * <ul>
 *   <li><b>双缓冲机制</b>：维护当前号段和下一个号段，当前号段用完时立即切换</li>
 *   <li><b>异步预加载</b>：当使用率达到 80% 时，异步加载下一个号段，避免等待</li>
 *   <li><b>乐观锁</b>：使用 version 字段保证并发安全，冲突时重试</li>
 *   <li><b>连续 ID</b>：生成的 ID 是连续的，便于业务使用</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SegmentGenerator implements SequenceGenerator {

    /** 默认号段步长，即每次从数据库获取的 ID 数量 */
    private static final int DEFAULT_STEP = 1000;
    /** 预加载阈值：当号段使用率达到 80% 时触发异步预加载 */
    private static final double PRELOAD_THRESHOLD = 0.8;

    /** 号段数据访问接口 */
    private final SequenceSegmentMapper segmentMapper;

    /**
     * 异步预加载线程池
     * <p>
     * 使用固定大小的线程池（2 个线程）来异步预加载下一个号段。
     * 线程配置为守护线程，不会阻止 JVM 退出。
     * 自定义线程名称便于调试和监控。
     * </p>
     */
    private final ExecutorService preloadExecutor = Executors.newFixedThreadPool(
            2,
            new ThreadFactory() {
                private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = defaultFactory.newThread(r);
                    t.setName("segment-preload-" + t.threadId());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    /**
     * 每个业务键的号段缓冲区映射表
     * <p>
     * 每个业务键独立维护自己的双缓冲区，互不干扰。
     * 使用 ConcurrentHashMap 保证线程安全。
     * </p>
     */
    private final ConcurrentHashMap<String, SegmentBuffer> bufferMap = new ConcurrentHashMap<>();

    @Override
    public String getType() {
        return "SEGMENT";
    }

    /**
     * 获取下一个序列号
     * <p>
     * 从当前号段中分配一个 ID。如果当前号段已耗尽，则切换到预加载的下一个号段
     * 或从数据库重新获取新的号段。
     * </p>
     *
     * @param bizKey 业务键
     * @return 分配的序列号
     */
    @Override
    public long next(String bizKey) {
        // 获取或创建该业务键的缓冲区
        SegmentBuffer buffer = bufferMap.computeIfAbsent(bizKey, k -> new SegmentBuffer());
        return buffer.next(bizKey);
    }

    /**
     * 批量生成序列号
     *
     * @param bizKey 业务键
     * @param count  生成数量
     * @return 逗号分隔的序列号字符串
     */
    @Override
    public String batch(String bizKey, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(next(bizKey));
            if (i < count - 1) sb.append(",");
        }
        return sb.toString();
    }

    /**
     * 号段缓冲区内部类
     * <p>
     * 实现双缓冲机制：维护 current（当前号段）和 next（下一个号段）两个引用。
     * 当 current 耗尽时，优先使用预加载的 next 号段，否则从数据库获取新号段。
     * 预加载在当前号段使用率达到 80% 时异步触发，确保切换时 next 已经就绪。
     * </p>
     */
    private class SegmentBuffer {
        /** 当前正在使用的号段 */
        private final AtomicReference<Segment> current = new AtomicReference<>();
        /** 预加载的下一个号段 */
        private final AtomicReference<Segment> next = new AtomicReference<>();
        /** 是否正在加载下一个号段，防止重复加载 */
        private volatile boolean loadingNext = false;

        /**
         * 获取下一个序列号
         *
         * @param bizKey 业务键
         * @return 序列号
         */
        public long next(String bizKey) {
            Segment seg = current.get();
            // 如果当前号段为空或已耗尽，需要加载或切换号段
            if (seg == null || seg.isExhausted()) {
                seg = loadOrSwitchSegment(bizKey, seg);
            }

            // 检查是否需要预加载下一个号段（使用率达到 80%）
            if (shouldPreload(bizKey, seg)) {
                tryPreloadNextAsync(bizKey);
            }

            return seg.nextValue();
        }

        /**
         * 加载或切换号段
         * <p>
         * 优先使用预加载的 next 号段（如果存在且未耗尽），
         * 否则从数据库获取新的号段。
         * </p>
         *
         * @param bizKey 业务键
         * @param seg    当前号段（可能为 null 或已耗尽）
         * @return 新的可用号段
         */
        private Segment loadOrSwitchSegment(String bizKey, Segment seg) {
            // 尝试获取预加载的下一个号段，获取成功后将 next 置为 null
            Segment nextSeg = next.getAndSet(null);
            if (nextSeg != null && !nextSeg.isExhausted()) {
                // 预加载的号段可用，直接切换
                current.set(nextSeg);
                return nextSeg;
            }

            // 预加载的号段不可用，从数据库获取新号段
            Segment newSeg = fetchSegmentFromDb(bizKey, seg);
            current.set(newSeg);
            return newSeg;
        }

        /**
         * 从数据库获取新的号段
         * <p>
         * 使用乐观锁机制（version 字段）保证并发安全。
         * 如果数据库中没有该业务键的记录，则自动初始化。
         * 最多重试 10 次，防止无限重试。
         * </p>
         *
         * @param bizKey 业务键
         * @param oldSeg 旧号段（用于日志记录，可为 null）
         * @return 新分配的号段
         * @throws RuntimeException 如果重试 10 次后仍然失败
         */
        private Segment fetchSegmentFromDb(String bizKey, Segment oldSeg) {
            int maxRetries = 10;
            for (int i = 0; i < maxRetries; i++) {
                SequenceSegment dbSeg = segmentMapper.selectByBizKey(bizKey);
                if (dbSeg == null) {
                    // 数据库中不存在该业务键的配置，自动初始化
                    initializeSegment(bizKey);
                    continue;
                }

                // 获取步长，如果未配置或无效则使用默认值
                int step = dbSeg.getStep() != null && dbSeg.getStep() > 0 ? dbSeg.getStep() : DEFAULT_STEP;
                int version = dbSeg.getVersion();
                // 使用乐观锁更新：只有 version 匹配时才会更新成功
                int updated = segmentMapper.allocateNextSegment(bizKey, version, step);

                if (updated > 0) {
                    // 更新成功，计算新号段的范围
                    long min = dbSeg.getMaxValue() + 1;
                    long max = min + step - 1;
                    log.info("Segment allocated: bizKey={}, range=[{}, {}]", bizKey, min, max);
                    return new Segment(min, max);
                }

                // 更新失败，说明有其他线程/节点已经更新了该号段，需要重试
                log.debug("Segment allocation conflict, retrying: bizKey={}, attempt={}", bizKey, i + 1);
            }
            throw new RuntimeException("Failed to allocate segment after " + maxRetries + " retries: " + bizKey);
        }

        /**
         * 初始化号段配置
         * <p>
         * 当数据库中不存在该业务键的配置时，创建初始号段。
         * 初始号段范围为 [1, DEFAULT_STEP]。
         * </p>
         *
         * @param bizKey 业务键
         */
        private void initializeSegment(String bizKey) {
            long max = DEFAULT_STEP;
            segmentMapper.insertSegment(bizKey, 1, max, DEFAULT_STEP);
            log.info("Initialized segment for bizKey: {}, range=[1, {}]", bizKey, max);
        }

        /**
         * 判断是否需要预加载下一个号段
         * <p>
         * 当号段使用率达到或超过 80% 时触发预加载。
         * 提前预加载可以避免号段耗尽时的等待时间。
         * </p>
         *
         * @param bizKey 业务键
         * @param seg    当前号段
         * @return 是否需要预加载
         */
        private boolean shouldPreload(String bizKey, Segment seg) {
            long used = seg.currentValue.get() - seg.min;
            long total = seg.max - seg.min + 1;
            return total > 0 && ((double) used / total) >= PRELOAD_THRESHOLD;
        }

        /**
         * 异步预加载下一个号段
         * <p>
         * 使用双重检查锁定模式防止重复提交预加载任务。
         * 预加载在后台线程中执行，不会阻塞当前的序列号获取请求。
         * 如果预加载失败，只记录警告日志，不影响当前号段的使用。
         * </p>
         *
         * @param bizKey 业务键
         */
        private void tryPreloadNextAsync(String bizKey) {
            // 第一次检查：如果正在加载或已有预加载的号段，则直接返回
            if (loadingNext || next.get() != null) return;
            // 加锁防止并发提交多个预加载任务
            synchronized (this) {
                // 第二次检查：获取锁后再次检查，避免重复加载
                if (loadingNext || next.get() != null) return;
                loadingNext = true;
            }
            // 提交异步预加载任务
            preloadExecutor.submit(() -> {
                try {
                    Segment seg = current.get();
                    if (seg != null) {
                        Segment preloaded = fetchSegmentFromDb(bizKey, seg);
                        next.set(preloaded);
                        log.debug("Preloaded next segment for bizKey: {}", bizKey);
                    }
                } catch (Exception e) {
                    log.warn("Failed to preload next segment for bizKey: {}", bizKey, e);
                } finally {
                    // 无论成功还是失败，都要重置加载标志
                    loadingNext = false;
                }
            });
        }
    }

    /**
     * 号段内部类
     * <p>
     * 表示一个连续的 ID 号段，包含最小值、最大值和当前分配位置。
     * 使用 AtomicLong 保证多线程环境下的线程安全。
     * </p>
     */
    private static class Segment {
        /** 号段最小值（包含） */
        final long min;
        /** 号段最大值（包含） */
        final long max;
        /** 当前已分配到的值，初始值为 min-1，表示尚未分配任何 ID */
        final AtomicLong currentValue;

        Segment(long min, long max) {
            this.min = min;
            this.max = max;
            // 初始值设为 min-1，这样第一次 incrementAndGet() 返回的就是 min
            this.currentValue = new AtomicLong(min - 1);
        }

        /**
         * 获取下一个序列号
         *
         * @return 下一个可用的序列号
         * @throws IllegalStateException 如果号段已耗尽
         */
        long nextValue() {
            long value = currentValue.incrementAndGet();
            if (value > max) {
                throw new IllegalStateException("Segment exhausted: [" + min + ", " + max + "]");
            }
            return value;
        }

        /**
         * 判断号段是否已耗尽
         *
         * @return 如果当前值已达到或超过最大值，返回 true
         */
        boolean isExhausted() {
            return currentValue.get() >= max;
        }
    }
}
