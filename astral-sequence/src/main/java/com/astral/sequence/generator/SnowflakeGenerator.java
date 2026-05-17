package com.astral.sequence.generator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snowflake 算法序列号生成器
 * <p>
 * 实现 Twitter 的 Snowflake 分布式 ID 生成算法。生成的 ID 是一个 64 位长整型，结构如下：
 * <ul>
 *   <li>41 位时间戳（毫秒级，相对于自定义纪元时间）</li>
 *   <li>5 位数据中心 ID</li>
 *   <li>5 位工作机器 ID</li>
 *   <li>12 位序列号（同一毫秒内的自增序号）</li>
 * </ul>
 * 该算法保证在分布式环境下生成的 ID 全局唯一且大致有序。
 * </p>
 */
@Slf4j
@Component
public class SnowflakeGenerator implements SequenceGenerator {

    /** 自定义纪元时间：2024-01-01 00:00:00 UTC，用于减少时间戳位数 */
    private static final long EPOCH = 1704067200000L;
    /** 工作机器 ID 占用的位数 */
    private static final long WORKER_ID_BITS = 5L;
    /** 数据中心 ID 占用的位数 */
    private static final long DATACENTER_ID_BITS = 5L;
    /** 序列号占用的位数，决定了同一毫秒内最多可生成 4096 个 ID */
    private static final long SEQUENCE_BITS = 12L;

    /** 最大工作机器 ID：31（2^5 - 1） */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    /** 最大数据中心 ID：31（2^5 - 1） */
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);

    /** 工作机器 ID 左移位数 */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    /** 数据中心 ID 左移位数 */
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    /** 时间戳左移位数，用于将时间戳移到最高位 */
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    /** 序列号掩码：4095（2^12 - 1），用于序列号溢出时归零 */
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    /** 时钟回退容忍阈值（毫秒），超过此值则拒绝生成 ID */
    private static final long CLOCK_BACKWARD_TOLERANCE_MS = 10L;

    /** 工作机器 ID，用于区分不同的生成器实例 */
    private final long workerId;
    /** 数据中心 ID，用于区分不同的数据中心 */
    private final long datacenterId;
    /**
     * 每个业务键的序列号状态映射表
     * <p>
     * 使用 ConcurrentHashMap 保证线程安全，每个业务键独立维护自己的序列号状态，
     * 避免不同业务之间的序列号生成相互干扰。
     * </p>
     */
    private final Map<String, SequenceState> stateMap = new ConcurrentHashMap<>();
    /**
     * 每个业务键的锁对象映射表
     * <p>
     * 使用细粒度锁（每个业务键一个锁）而不是全局锁，提高并发性能。
     * 不同业务键的序列号生成可以并行执行。
     * </p>
     */
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    /**
     * 默认构造函数，使用默认的工作机器 ID 和数据中心 ID
     */
    public SnowflakeGenerator() {
        this(1, 1);
    }

    /**
     * 带参数的构造函数
     *
     * @param workerId     工作机器 ID，范围 1-31
     * @param datacenterId 数据中心 ID，范围 1-31
     */
    public SnowflakeGenerator(long workerId, long datacenterId) {
        // 边界检查：如果 ID 超出有效范围，则使用默认值 1
        if (workerId > MAX_WORKER_ID || workerId < 1) {
            workerId = 1;
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 1) {
            datacenterId = 1;
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    @Override
    public String getType() {
        return "SNOWFLAKE";
    }

    /**
     * 生成下一个 Snowflake 序列号
     * <p>
     * 该方法使用细粒度锁保证同一业务键的序列号生成是线程安全的。
     * 处理了时钟回退的情况：如果时钟回退在容忍范围内，使用上次时间戳；
     * 如果超过容忍范围，则抛出异常拒绝生成。
     * </p>
     *
     * @param bizKey 业务键
     * @return 生成的 64 位 Snowflake ID
     * @throws IllegalStateException 当时钟回退超过容忍阈值时抛出
     */
    @Override
    public long next(String bizKey) {
        // 获取该业务键的专属锁对象，如果不存在则创建
        Object lock = keyLocks.computeIfAbsent(bizKey, k -> new Object());
        synchronized (lock) {
            // 获取或创建该业务键的序列号状态
            SequenceState state = stateMap.computeIfAbsent(bizKey, k -> new SequenceState());
            long timestamp = System.currentTimeMillis();

            // 处理时钟回退的情况
            if (timestamp < state.lastTimestamp) {
                long diff = state.lastTimestamp - timestamp;
                if (diff <= CLOCK_BACKWARD_TOLERANCE_MS) {
                    // 回退时间在容忍范围内，使用上次时间戳继续生成
                    timestamp = state.lastTimestamp;
                } else {
                    // 回退时间超过容忍范围，拒绝生成 ID 以防止重复
                    throw new IllegalStateException(
                            String.format("Clock moved backwards by %d ms, refused to generate ID for bizKey=%s", diff, bizKey));
                }
            }

            // 同一毫秒内，序列号递增
            if (timestamp == state.lastTimestamp) {
                // 序列号加 1 并使用掩码处理溢出（超过 4095 后归零）
                state.sequence = (state.sequence + 1) & SEQUENCE_MASK;
                // 如果序列号归零，说明本毫秒的 ID 已用完，需要等待下一毫秒
                if (state.sequence == 0) {
                    timestamp = waitNextMillis(state.lastTimestamp);
                }
            } else {
                // 新的毫秒，序列号从 0 开始
                state.sequence = 0;
            }

            state.lastTimestamp = timestamp;
            // 按位组装 ID：时间戳 | 数据中心ID | 工作机器ID | 序列号
            long id = ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                    | (datacenterId << DATACENTER_ID_SHIFT)
                    | (workerId << WORKER_ID_SHIFT)
                    | state.sequence;

            log.debug("Snowflake generated: bizKey={}, id={}", bizKey, id);
            return id;
        }
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
     * 等待下一毫秒
     * <p>
     * 当同一毫秒内的序列号用完时，需要自旋等待直到进入下一毫秒。
     * 这是一个忙等待循环，但由于通常只需要等待不到 1 毫秒，所以性能影响很小。
     * </p>
     *
     * @param lastTimestamp 上一次生成 ID 的时间戳
     * @return 大于 lastTimestamp 的当前时间戳
     */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 序列号状态内部类
     * <p>
     * 用于保存每个业务键的序列号生成状态，包括上次生成 ID 的时间戳和当前毫秒内的序列号。
     * 每个业务键独立维护自己的状态，互不干扰。
     * </p>
     */
    private static class SequenceState {
        /** 上一次生成 ID 的时间戳（毫秒） */
        long lastTimestamp = 0;
        /** 当前毫秒内的序列号，范围 0-4095 */
        long sequence = 0;
    }
}
