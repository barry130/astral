package com.astral.sequence.service;

import com.astral.common.error.ErrorCodes;
import com.astral.common.exception.BusinessException;
import com.astral.common.util.SequenceMetrics;
import com.astral.dao.entity.SequenceConfig;
import com.astral.dao.entity.SequenceHistory;
import com.astral.dao.entity.SequenceStatistics;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceHistoryMapper;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import com.astral.sequence.generator.DatabaseGenerator;
import com.astral.sequence.generator.RedisGenerator;
import com.astral.sequence.generator.SegmentGenerator;
import com.astral.sequence.generator.SequenceGenerator;
import com.astral.sequence.generator.SimpleGenerator;
import com.astral.sequence.generator.SnowflakeGenerator;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成器工厂
 * <p>
 * 核心调度类，负责：
 * <ul>
 *   <li>根据业务键和类型选择对应的序列号生成器</li>
 *   <li>自动创建和管理序列配置（首次使用时）</li>
 *   <li>执行业务键与序列类型的绑定校验（防止类型切换）</li>
 *   <li>异步记录生成历史和更新统计数据</li>
 *   <li>收集序列号生成指标</li>
 * </ul>
 * </p>
 * <p>
 * 该类是整个序列生成系统的中枢，连接了控制器层、生成器层和数据访问层。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratorFactory {

    /** Snowflake 算法生成器 */
    private final SnowflakeGenerator snowflakeGenerator;
    /** 简单内存计数器生成器 */
    private final SimpleGenerator simpleGenerator;
    /** 数据库逐次生成器 */
    private final DatabaseGenerator databaseGenerator;
    /** 号段模式生成器（默认） */
    private final SegmentGenerator segmentGenerator;
    /** 序列历史服务 */
    private final SequenceHistoryService historyService;
    /** 序列历史数据访问接口 */
    private final SequenceHistoryMapper historyMapper;
    /** 序列配置数据访问接口 */
    private final SequenceConfigMapper configMapper;
    /** 序列统计数据访问接口 */
    private final SequenceStatisticsMapper statisticsMapper;
    /** 实体 ID 全局序列提供者 */
    private final com.astral.sequence.config.EntityIdSequenceProvider entityIdSequenceProvider;

    /**
     * Redis 生成器（可选）
     * <p>
     * 使用 @Autowired(required = false) 注入，因为 Redis 生成器是条件加载的。
     * 如果 Redis 未启用，该字段为 null。
     * </p>
     */
    @Nullable
    @Autowired(required = false)
    private RedisGenerator redisGenerator;

    /**
     * 默认序列类型
     * <p>
     * 从配置文件读取，默认为 SEGMENT（号段模式）。
     * 当业务键没有指定类型或配置中未设置类型时使用此默认值。
     * </p>
     */
    @Value("${astral.sequence.default-type:SEGMENT}")
    private String defaultType;

    /**
     * 获取下一个序列号
     * <p>
     * 完整的序列号生成流程：
     * <ol>
     *   <li>获取或创建业务键的配置</li>
     *   <li>规范化类型名称（统一为大写）</li>
     *   <li>获取对应的生成器实例</li>
     *   <li>生成序列号</li>
     *   <li>记录指标</li>
     *   <li>异步保存历史记录</li>
     *   <li>异步更新统计数据</li>
     * </ol>
     * </p>
     *
     * @param bizKey 业务键
     * @param type   可选的生成器类型
     * @return 生成的序列号
     */
    public long next(String bizKey, String type) {
        // 获取或自动创建业务键的配置，首次使用时会自动初始化
        SequenceConfig config = getOrCreateConfig(bizKey, type);
        // 确定实际使用的类型：优先使用配置中的类型，否则使用默认类型
        String actualType = normalizeType(config.getSequenceType() != null ? config.getSequenceType() : defaultType);

        log.debug("Getting next: bizKey={}, type={}", bizKey, actualType);
        long value = getGenerator(actualType).next(bizKey);
        // 记录生成指标，用于监控和统计
        SequenceMetrics.recordGeneration();
        // 异步保存历史记录，不阻塞主流程
        saveHistoryAsync(bizKey, actualType, value);
        // 异步更新统计数据
        updateStatisticsAsync(bizKey, value);
        return value;
    }

    /**
     * 批量获取序列号
     * <p>
     * 与单个生成类似，但需要处理多个序列号的历史记录。
     * 历史记录采用批量异步保存，提高性能。
     * </p>
     *
     * @param bizKey 业务键
     * @param count  生成数量
     * @param type   可选的生成器类型
     * @return 逗号分隔的序列号字符串
     */
    public String batch(String bizKey, int count, String type) {
        SequenceConfig config = getOrCreateConfig(bizKey, type);
        String actualType = normalizeType(config.getSequenceType() != null ? config.getSequenceType() : defaultType);

        String result = getGenerator(actualType).batch(bizKey, count);
        String[] values = result.split(",");

        // 为每个生成的序列号记录指标
        for (String v : values) {
            SequenceMetrics.recordGeneration();
        }

        // 构建批量历史记录列表
        List<SequenceHistory> batchHistory = new ArrayList<>(values.length);
        for (String v : values) {
            try {
                long val = Long.parseLong(v);
                SequenceHistory history = new SequenceHistory();
                history.setBizKey(bizKey);
                history.setSequenceType(actualType);
                history.setSequenceValue(val);
                history.setCreateTime(LocalDateTime.now());
                batchHistory.add(history);
            } catch (NumberFormatException e) {
                // 解析失败时记录警告，但不影响其他记录的处理
                log.warn("Failed to parse batch value: {}", v, e);
            }
        }

        // 如果有有效的历史记录，异步批量保存
        if (!batchHistory.isEmpty()) {
            saveHistoryBatchAsync(batchHistory);
            // 使用最后一个序列号更新统计数据
            long lastValue = Long.parseLong(values[values.length - 1]);
            updateStatisticsAsync(bizKey, lastValue);
        }

        return result;
    }

    /**
     * 异步保存单条历史记录
     * <p>
     * 使用 @Async 注解在独立的线程池中执行，不阻塞序列号生成的主流程。
     * 异常被捕获并记录日志，确保历史记录保存失败不会影响主业务。
     * </p>
     *
     * @param bizKey 业务键
     * @param type   生成器类型
     * @param value  序列号值
     */
    @Async("sequenceAsyncExecutor")
    public void saveHistoryAsync(String bizKey, String type, long value) {
        try {
            historyService.save(bizKey, type, value);
        } catch (Exception e) {
            log.warn("Async history save failed: bizKey={}, type={}, value={}", bizKey, type, value, e);
        }
    }

    /**
     * 异步批量保存历史记录
     * <p>
     * 逐条插入历史记录，而不是使用批量插入。
     * 这样可以在某条记录失败时继续处理其他记录。
     * </p>
     *
     * @param records 历史记录列表
     */
    @Async("sequenceAsyncExecutor")
    public void saveHistoryBatchAsync(List<SequenceHistory> records) {
        try {
            historyService.saveBatch(records);
        } catch (Exception e) {
            log.warn("Async batch history save failed: count={}", records.size(), e);
        }
    }

    /**
     * 异步更新统计数据
     * <p>
     * 更新业务键的当前序列号值和更新时间。
     * 如果统计记录不存在，则自动创建。
     * </p>
     *
     * @param bizKey        业务键
     * @param currentValue  当前序列号值
     */
    @Async("sequenceAsyncExecutor")
    public void updateStatisticsAsync(String bizKey, long currentValue) {
        try {
        SequenceStatistics stat = statisticsMapper.selectByBizKey(bizKey);
        if (stat == null) {
            // 统计记录不存在，创建新记录
            stat = new SequenceStatistics();
            stat.setBizKey(bizKey);
            stat.setCurrentValue(currentValue);
            // sequence_* 表被 MetaObjectHandler 排除自动填充，需手动设置时间
            stat.setCreateTime(LocalDateTime.now());
            stat.setUpdateTime(LocalDateTime.now());
            statisticsMapper.insert(stat);
        } else {
            // 更新现有记录
            stat.setCurrentValue(currentValue);
            stat.setUpdateTime(LocalDateTime.now());
            statisticsMapper.updateById(stat);
        }
        } catch (Exception e) {
            log.warn("Async statistics update failed: bizKey={}", bizKey, e);
        }
    }

    /**
     * 规范化类型名称
     * <p>
     * 将所有类型名称统一转换为大写，确保配置中可以存储小写值，
     * 但在比较和使用时统一使用大写，避免大小写不一致导致的问题。
     * </p>
     *
     * @param type 原始类型名称
     * @return 大写形式的类型名称
     */
    private String normalizeType(String type) {
        if (type == null) return defaultType.toUpperCase();
        return type.toUpperCase();
    }

    /**
     * 获取或创建序列配置
     * <p>
     * 核心逻辑：
     * <ul>
     *   <li>如果配置不存在，自动创建并使用请求的类型（或默认类型）</li>
     *   <li>如果配置已存在，校验请求的类型是否与已绑定的类型一致</li>
     *   <li>类型不一致时抛出业务异常，防止业务键切换序列类型</li>
     * </ul>
     * </p>
     * <p>
     * 业务键一旦绑定了某种序列类型，就不允许切换，这是为了保证序列号的连续性和一致性。
     * </p>
     *
     * @param bizKey 业务键
     * @param type   请求的类型（可为 null）
     * @return 序列配置
     * @throws BusinessException 当尝试切换已绑定类型的业务键时抛出
     */
    private SequenceConfig getOrCreateConfig(String bizKey, String type) {
        // 所有实体 ID 序列（业务键以 _id 结尾）只允许号段模式，任何路径都不能切换类型
        if (entityIdSequenceProvider.isEntityIdBizKey(bizKey)) {
            String requestedType = type != null ? type.toUpperCase() : defaultType.toUpperCase();
            if (!"SEGMENT".equals(requestedType)) {
                throw new BusinessException("SEQ006", bizKey, "SEGMENT", requestedType);
            }
        }
        SequenceConfig config = configMapper.selectByBizKey(bizKey);
        if (config == null) {
            // 配置不存在，自动创建
            config = new SequenceConfig();
            config.setBizKey(bizKey);
            config.setSequenceType(type != null ? type.toUpperCase() : defaultType.toUpperCase());
            config.setEnabled(true);
            config.setCreateTime(LocalDateTime.now());
            config.setUpdateTime(LocalDateTime.now());
            configMapper.insert(config);
            log.info("Auto created config for bizKey: {}, type: {}", bizKey, config.getSequenceType());
        } else {
            // 配置已存在，校验类型是否一致
            String requestedType = type != null ? type.toUpperCase() : defaultType.toUpperCase();
            String existingType = config.getSequenceType().toUpperCase();
            if (!existingType.equals(requestedType)) {
                throw new BusinessException("SEQ006", bizKey, existingType, requestedType);
            }
        }
        return config;
    }

    /**
     * 根据类型获取对应的生成器实例
     * <p>
     * 使用 switch 表达式进行类型分发。
     * 对于 Redis 生成器，需要特别检查是否已启用，因为它是条件加载的。
     * </p>
     *
     * @param type 规范化后的类型名称（大写）
     * @return 对应的生成器实例
     * @throws IllegalStateException 当 Redis 生成器未启用但请求使用时抛出
     */
    private SequenceGenerator getGenerator(String type) {
        log.debug("Using generator type: {}", type);
        return switch (type) {
            case "SNOWFLAKE" -> snowflakeGenerator;
            case "SEGMENT" -> segmentGenerator;
            case "REDIS" -> {
                // Redis 生成器是条件加载的，使用前需要检查是否启用
                if (redisGenerator == null) {
                    throw new IllegalStateException("Redis generator is not enabled. Set astral.sequence.types.redis.enabled=true");
                }
                yield redisGenerator;
            }
            case "DATABASE" -> databaseGenerator;
            case "SIMPLE" -> simpleGenerator;
            default -> snowflakeGenerator; // 未知类型默认使用 Snowflake
        };
    }
}
