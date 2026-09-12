package com.astral.sequence.generator;

import com.astral.dao.entity.SequenceStatistics;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 数据库序列号生成器
 * <p>
 * 每次生成序列号时都直接访问数据库，通过更新统计表中的当前值来实现。
 * 这种方式保证了序列号的持久化和全局唯一性，但性能相对较低。
 * </p>
 * <p>
 * 适用场景：
 * <ul>
 *   <li>对序列号持久化有严格要求的场景</li>
 *   <li>序列号生成频率不高，可以接受数据库访问开销</li>
 *   <li>需要精确控制序列号生成过程</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseGenerator implements SequenceGenerator {

    /** 序列统计数据访问接口 */
    private final SequenceStatisticsMapper statisticsMapper;

    /**
     * 每业务键独立锁（ReentrantLock 而非 synchronized）
     * <p>
     * 虚拟线程友好：阻塞在 DB I/O 期间可从 carrier 卸载，避免 synchronized pinning。
     * </p>
     */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private ReentrantLock lockFor(String bizKey) {
        return locks.computeIfAbsent(bizKey, k -> new ReentrantLock());
    }

    /**
     * 值缓存映射表
     * <p>
     * 用于缓存每个业务键的最后生成值，避免重复查询数据库。
     * 注意：这个缓存主要用于优化查询，实际的序列号生成仍然依赖数据库保证一致性。
     * </p>
     */
    private final Map<String, Long> valueCache = new ConcurrentHashMap<>();

    @Override
    public String getType() {
        return "DATABASE";
    }

    /**
     * 生成下一个序列号
     * <p>
     * 使用 ReentrantLock 保证线程安全（虚拟线程友好，避免 synchronized pinning），
     * 通过事务保证数据库操作的原子性。如果业务键不存在，则自动创建初始记录。
     * </p>
     *
     * @param bizKey 业务键
     * @return 生成的序列号
     */
    @Override
    @Transactional
    public long next(String bizKey) {
        ReentrantLock lock = lockFor(bizKey);
        lock.lock();
        try {
            // 查询该业务键的统计记录
            SequenceStatistics stat = statisticsMapper.selectByBizKey(bizKey);
            if (stat == null) {
                // 如果不存在，创建新的统计记录，初始值为 0
                stat = new SequenceStatistics();
                stat.setBizKey(bizKey);
                stat.setCurrentValue(0L);
                statisticsMapper.insert(stat);
            }

            // 当前值加 1 并更新到数据库
            long value = stat.getCurrentValue() + 1;
            stat.setCurrentValue(value);
            statisticsMapper.updateById(stat);

            log.debug("Database generated: bizKey={}, value={}", bizKey, value);
            return value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 批量生成序列号
     * <p>
     * 循环调用 next() 方法逐个生成序列号。
     * 每个序列号都会触发一次数据库更新操作，因此批量生成的性能开销与数量成正比。
     * </p>
     *
     * @param bizKey 业务键
     * @param count  生成数量
     * @return 逗号分隔的序列号字符串
     */
    @Override
    @Transactional
    public String batch(String bizKey, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(next(bizKey));
            if (i < count - 1) sb.append(",");
        }
        return sb.toString();
    }
}