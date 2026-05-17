package com.astral.sequence.generator;

import com.astral.common.constant.SequenceType;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis 序列号生成器
 * <p>
 * 使用 Redisson 的 RAtomicLong 实现分布式序列号生成。
 * Redis 的原子操作保证了在多节点部署环境下的序列号唯一性。
 * </p>
 * <p>
 * 该生成器是条件加载的，只有在配置文件中设置了
 * {@code astral.sequence.types.redis.enabled=true} 时才会启用。
 * 这是为了避免在不需要 Redis 的环境中产生不必要的依赖。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "astral.sequence.types.redis.enabled", havingValue = "true")
public class RedisGenerator implements SequenceGenerator {

    /** Redis 键前缀，用于区分序列号键和其他业务键 */
    private static final String KEY_PREFIX = "astral:seq:";

    /** Redisson 客户端，用于操作 Redis 数据结构 */
    @Autowired
    private RedissonClient redissonClient;

    @Override
    public String getType() {
        return SequenceType.REDIS.name();
    }

    /**
     * 使用 Redis 原子递增生成下一个序列号
     * <p>
     * 每个业务键对应 Redis 中的一个原子计数器。
     * incrementAndGet() 操作在 Redis 中是原子的，保证了分布式环境下的安全性。
     * </p>
     *
     * @param bizKey 业务键
     * @return 递增后的序列号
     */
    @Override
    public long next(String bizKey) {
        String key = KEY_PREFIX + bizKey;
        RAtomicLong atomicLong = redissonClient.getAtomicLong(key);
        long value = atomicLong.incrementAndGet();
        log.debug("Redis generated: bizKey={}, value={}", bizKey, value);
        return value;
    }

    /**
     * 批量生成序列号
     * <p>
     * 使用 Redis 的 addAndGet 原子操作一次性增加指定数量，
     * 然后计算出这批序列号的起始值，逐个生成结果字符串。
     * 这种方式比循环调用 next() 更高效，因为只需要一次 Redis 网络请求。
     * </p>
     *
     * @param bizKey 业务键
     * @param count  生成数量
     * @return 逗号分隔的序列号字符串
     */
    @Override
    public String batch(String bizKey, int count) {
        String key = KEY_PREFIX + bizKey;
        RAtomicLong atomicLong = redissonClient.getAtomicLong(key);
        // 原子增加 count，返回增加后的值（即最后一个序列号）
        long value = atomicLong.addAndGet(count);
        // 计算起始序列号：最后一个值 - 数量 + 1
        long start = value - count + 1;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(start + i);
            if (i < count - 1) sb.append(",");
        }
        return sb.toString();
    }
}
