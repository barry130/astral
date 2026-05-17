package com.astral.sequence.generator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单内存计数器序列号生成器
 * <p>
 * 使用内存中的 AtomicLong 作为计数器生成序列号。
 * 这是最简单的实现方式，但序列号不会持久化，应用重启后会从 0 重新开始。
 * </p>
 * <p>
 * <b>注意</b>：此生成器仅适用于测试环境或开发调试，不应用于生产环境。
 * 在分布式部署时，不同节点的计数器是独立的，会导致序列号重复。
 * </p>
 */
@Slf4j
@Component
public class SimpleGenerator implements SequenceGenerator {

    /**
     * 每个业务键的计数器映射表
     * <p>
     * 使用 ConcurrentHashMap 保证线程安全，每个业务键独立维护自己的计数器。
     * AtomicLong 提供了原子性的递增操作，无需额外的同步措施。
     * </p>
     */
    private final Map<String, AtomicLong> stateMap = new ConcurrentHashMap<>();

    @Override
    public String getType() {
        return "SIMPLE";
    }

    /**
     * 生成下一个序列号
     * <p>
     * 如果业务键不存在，则创建初始值为 0 的计数器，然后递增返回。
     * </p>
     *
     * @param bizKey 业务键
     * @return 递增后的序列号
     */
    @Override
    public long next(String bizKey) {
        AtomicLong counter = stateMap.computeIfAbsent(bizKey, k -> new AtomicLong(0));
        long value = counter.incrementAndGet();
        log.debug("Simple generated: bizKey={}, value={}", bizKey, value);
        return value;
    }

    /**
     * 批量生成序列号
     * <p>
     * 使用 AtomicLong 的 addAndGet 原子操作一次性增加指定数量，
     * 然后计算出这批序列号的范围，逐个生成结果字符串。
     * 这种方式比循环调用 next() 更高效，因为只需要一次原子操作。
     * </p>
     *
     * @param bizKey 业务键
     * @param count  生成数量
     * @return 逗号分隔的序列号字符串
     */
    @Override
    public String batch(String bizKey, int count) {
        AtomicLong counter = stateMap.computeIfAbsent(bizKey, k -> new AtomicLong(0));
        // addAndGet 返回增加后的值（即最后一个序列号）
        long start = counter.addAndGet(count);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            // 计算每个序列号：起始值 = 最后一个值 - 数量 + 1 + 索引
            sb.append(start - count + 1 + i);
            if (i < count - 1) sb.append(",");
        }
        return sb.toString();
    }
}
