package com.astral.sequence.generator;

import com.astral.common.exception.BusinessException;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据库序列号生成器
 * <p>
 * 每次生成序列号时都直接访问数据库，通过更新统计表中的当前值来实现。
 * 这种方式保证了序列号的持久化和全局唯一性，但性能相对较低。
 * </p>
 *
 * <p><b>并发模型（已重构）</b>：不再用「JVM 内锁 + 读改写」，而是让数据库做原子自增。</p>
 *
 * <p><b>为什么不能用应用层锁</b>：</p>
 * <ol>
 *   <li>{@code ReentrantLock} 是 JVM 内的，多实例部署时各实例锁各的，必然重号；</li>
 *   <li>即便单机，锁在 {@code finally} 中释放而事务在代理返回后才提交，
 *       两者的边界不重合——B 线程可以在 A 提交前拿到锁并读到旧值，同样生成重复号。</li>
 * </ol>
 *
 * <p>现在用一条 {@code UPDATE ... RETURNING}：自增与读取由数据库行锁一次性完成，
 * 无锁竞争、无事务边界问题、天然跨实例安全。</p>
 *
 * <p>适用场景：</p>
 * <ul>
 *   <li>对序列号持久化有严格要求的场景</li>
 *   <li>序列号生成频率不高，可以接受数据库访问开销</li>
 *   <li>需要精确控制序列号生成过程</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseGenerator implements SequenceGenerator {

    /** 序列统计数据访问接口 */
    private final SequenceStatisticsMapper statisticsMapper;

    /** 初始化重试次数：首次取号时行可能不存在，插入后重试一次 */
    private static final int MAX_INIT_RETRY = 2;

    @Override
    public String getType() {
        return "DATABASE";
    }

    /**
     * 生成下一个序列号
     *
     * <p>通过数据库原子自增取值，不需要应用层加锁，也不需要事务包裹。</p>
     *
     * @param bizKey 业务键
     * @return 生成的序列号
     */
    @Override
    public long next(String bizKey) {
        for (int attempt = 0; attempt <= MAX_INIT_RETRY; attempt++) {
            Long value = statisticsMapper.incrementAndGet(bizKey, 1);
            if (value != null) {
                log.debug("Database generated: bizKey={}, value={}", bizKey, value);
                return value;
            }
            // 行不存在（0 行受影响）：初始化后重试。
            // insertIfAbsent 走 ON CONFLICT DO NOTHING，并发下只有一个线程真正插入。
            statisticsMapper.insertIfAbsent(bizKey);
        }
        throw new BusinessException("SEQ004");
    }

    /**
     * 批量生成序列号
     *
     * <p>一次性自增 {@code count}，再在内存中展开成连续区间返回。
     * 相比循环调用 {@code next()}，这里只产生一次数据库往返。</p>
     *
     * @param bizKey 业务键
     * @param count  生成数量
     * @return 逗号分隔的序列号字符串
     */
    @Override
    public String batch(String bizKey, int count) {
        if (count <= 0) {
            return "";
        }
        Long max = null;
        for (int attempt = 0; attempt <= MAX_INIT_RETRY; attempt++) {
            max = statisticsMapper.incrementAndGet(bizKey, count);
            if (max != null) {
                break;
            }
            statisticsMapper.insertIfAbsent(bizKey);
        }
        if (max == null) {
            throw new BusinessException("SEQ004");
        }

        long start = max - count + 1;
        StringBuilder sb = new StringBuilder();
        for (long v = start; v <= max; v++) {
            if (v > start) {
                sb.append(",");
            }
            sb.append(v);
        }
        return sb.toString();
    }
}
