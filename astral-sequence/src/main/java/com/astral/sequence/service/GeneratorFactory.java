package com.astral.sequence.service;

import com.astral.common.exception.BusinessException;
import com.astral.common.util.SequenceMetrics;
import com.astral.dao.entity.SequenceConfig;
import com.astral.dao.entity.SequenceHistory;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceHistoryMapper;
import com.astral.sequence.generator.DatabaseGenerator;
import com.astral.sequence.generator.RedisGenerator;
import com.astral.sequence.generator.SegmentGenerator;
import com.astral.sequence.generator.SequenceGenerator;
import com.astral.sequence.generator.SimpleGenerator;
import com.astral.sequence.generator.SnowflakeGenerator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /** 受支持的生成器类型；自动创建配置前校验，避免把拼写错误的类型落库后永久锁死业务键。 */
    private static final Set<String> SUPPORTED_TYPES = Set.of("SEGMENT", "SNOWFLAKE", "REDIS", "DATABASE", "SIMPLE");

    /** Snowflake 算法生成器 */
    private final SnowflakeGenerator snowflakeGenerator;
    /** 简单内存计数器生成器 */
    private final SimpleGenerator simpleGenerator;
    /** 数据库逐次生成器 */
    private final DatabaseGenerator databaseGenerator;
    /** 号段模式生成器（默认） */
    private final SegmentGenerator segmentGenerator;
    /** 序列历史数据访问接口 */
    private final SequenceHistoryMapper historyMapper;
    /** 序列配置数据访问接口 */
    private final SequenceConfigMapper configMapper;
    /** 实体 ID 全局序列提供者 */
    private final com.astral.sequence.config.EntityIdSequenceProvider entityIdSequenceProvider;
    /** 历史/统计的异步写入器（@Async 必须落在外部 Bean 上才生效） */
    private final SequenceAsyncWriter sequenceAsyncWriter;

    /**
     * 序列配置进程内缓存（key=bizKey，TTL 60s）。
     * <p>实体 ID 自动填充（SequenceMetaObjectHandler）每次 INSERT 都会经
     * {@link #next} 取一次配置，原先每行插入都要 {@code selectByBizKey} 一轮库；
     * 显式序列接口同理。配置行在运行期几乎不变（仅管理端 SequenceConfigController
     * 会改），其写路径调用 {@link #evictConfigCache()} 立即失效，TTL 60s 兜底。
     * 缓存实体只读不写。</p>
     */
    private final Cache<String, SequenceConfig> configCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

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

    /** 自动创建配置时使用的默认步长；与 application.yml 的 astral.sequence.default-step 对齐。 */
    @Value("${astral.sequence.default-step:1000}")
    private int defaultStep = 1000;

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
        return nextResult(bizKey, type).value();
    }

    /**
     * 获取下一个序列号并返回实际使用的类型。
     *
     * @param bizKey 业务键
     * @param type   可选的生成器类型
     * @return 序列号与实际类型
     */
    public SequenceResult nextResult(String bizKey, String type) {
        // 获取或自动创建业务键的配置（读路径走 60s 进程内缓存；不存在时首次会自动建行并回填缓存）
        SequenceConfig config = getOrCreateConfig(bizKey, type);
        // 确定实际使用的类型：优先使用配置中的类型，否则使用默认类型
        String actualType = normalizeType(config.getSequenceType() != null ? config.getSequenceType() : defaultType);

        log.debug("Getting next: bizKey={}, type={}", bizKey, actualType);
        long value = getGenerator(actualType).next(bizKey);
        // 记录生成指标，用于监控和统计
        SequenceMetrics.recordGeneration();
        // 异步保存历史记录，不阻塞主流程
        sequenceAsyncWriter.saveHistoryAsync(bizKey, actualType, value);
        // 异步更新统计数据
        sequenceAsyncWriter.updateStatisticsAsync(bizKey, value);
        return SequenceResult.single(value, actualType);
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
        return batchResult(bizKey, count, type).values();
    }

    /**
     * 批量获取序列号并返回实际使用的类型。
     *
     * @param bizKey 业务键
     * @param count  生成数量
     * @param type   可选的生成器类型
     * @return 批量结果与实际类型
     */
    public SequenceResult batchResult(String bizKey, int count, String type) {
        if (count < 1) {
            throw new BusinessException("SEQ004");
        }
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
        long lastValue = parseLastValue(values);
        if (!batchHistory.isEmpty()) {
            sequenceAsyncWriter.saveHistoryBatchAsync(batchHistory);
            // 使用最后一个序列号更新统计数据，并一次性累计本批生成数量。
            // 原实现直接 parse 最后一个值：批量结果里混入非数字（如生成器返回空串）会抛 NumberFormatException，
            // 而这段在异步写入之前执行，异常会直接打断批量取号接口。这里改为容错解析。
            sequenceAsyncWriter.updateStatisticsAsync(bizKey, lastValue, batchHistory.size());
        }

        return SequenceResult.batch(lastValue, actualType, result);
    }

    /**
     * 从批量结果中取最后一个有效的序列号值
     *
     * @param values 逗号分隔后的序列号数组
     * @return 最后一个可解析的 long；全部无法解析时返回 0
     */
    private long parseLastValue(String[] values) {
        for (int i = values.length - 1; i >= 0; i--) {
            try {
                return Long.parseLong(values[i].trim());
            } catch (NumberFormatException ignored) {
                // 跳过非法片段，继续往前找
            }
        }
        return 0L;
    }

    private String normalizeType(String type) {
        if (type == null) return defaultType.toUpperCase(Locale.ROOT);
        return type.toUpperCase(Locale.ROOT);
    }

    /**
     * 规范化并校验调用方请求的生成器类型。
     *
     * @param type 请求类型，null 表示使用默认类型
     * @return 大写类型名
     * @throws BusinessException 类型为空或不在支持列表时抛出 SEQ002
     */
    private String normalizeRequestedType(String type) {
        String normalized = type == null || type.isBlank()
                ? defaultType.trim().toUpperCase(Locale.ROOT)
                : type.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new BusinessException("SEQ002");
        }
        return normalized;
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
        // 只校验显式传入的类型；null/空表示沿用已绑定配置的类型（首次创建时才回退默认类型）。
        // 必须在落库前校验，否则一个拼写错误会留下无法自动修复的错误配置。
        String requestedType = type == null || type.isBlank() ? null : normalizeRequestedType(type);
        // 所有实体 ID 序列（业务键以 _id 结尾）只允许号段模式，任何路径都不能切换类型
        if (entityIdSequenceProvider.isEntityIdBizKey(bizKey)) {
            if (requestedType != null && !"SEGMENT".equals(requestedType)) {
                throw new BusinessException("SEQ006", bizKey, "SEGMENT", requestedType);
            }
            requestedType = "SEGMENT";
        }
        SequenceConfig config = configCache.get(bizKey, k -> configMapper.selectByBizKey(k));
        if (config == null) {
            // 配置不存在，自动创建
            config = new SequenceConfig();
            config.setBizKey(bizKey);
            config.setSequenceType(requestedType != null ? requestedType : normalizeRequestedType(null));
            config.setStep(defaultStep);
            config.setEnabled(true);
            config.setCreateTime(LocalDateTime.now());
            config.setUpdateTime(LocalDateTime.now());
            try {
                configMapper.insert(config);
                configCache.put(bizKey, config);
                log.info("Auto created config for bizKey: {}, type: {}", bizKey, config.getSequenceType());
            } catch (DuplicateKeyException e) {
                // 首次并发使用同一 bizKey 时两个请求都可能查到 null 并同时插入，
                // 唯一索引 uk_sequence_config_biz_key 会让其中一个失败。这里只处理真实唯一冲突：
                // 重新读取获胜方写入的配置并继续，其他数据库异常必须照常抛出（不能吞掉）。
                log.info("Concurrent auto-create detected for bizKey: {}, re-reading config", bizKey);
                config = configMapper.selectByBizKey(bizKey);
                if (config == null) {
                    throw e;
                }
                configCache.put(bizKey, config);
            }
        }
        // 首次创建竞争的获胜配置与缓存命中的配置遵守同一类型锁定契约。
        String existingType = config.getSequenceType() == null
                ? defaultType.toUpperCase(Locale.ROOT)
                : config.getSequenceType().toUpperCase(Locale.ROOT);
        if (requestedType != null && !existingType.equals(requestedType)) {
            throw new BusinessException("SEQ006", bizKey, existingType, requestedType);
        }
        // 停用的配置不得继续发号：管理端 toggle 的语义是立即停止该业务键取号。
        // 放在分支之后统一判断，覆盖「并发自动创建时读到/重读到已停用配置」的路径。
        if (Boolean.FALSE.equals(config.getEnabled())) {
            throw new BusinessException("SEQ009", bizKey);
        }
        return config;
    }

    /**
     * 失效序列配置缓存。
     * <p>管理端 SequenceConfigController 的 create/update/delete/toggle 等任何
     * 直接改 sequence_config 的写操作完成后必须调用，否则新配置最长延迟 60s 生效。</p>
     */
    public void evictConfigCache() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    configCache.invalidateAll();
                }
            });
        } else {
            configCache.invalidateAll();
        }
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
                    throw new IllegalStateException(
                            "Redis generator is not enabled. Set astral.sequence.types.redis.enabled=true");
                }
                yield redisGenerator;
            }
            case "DATABASE" -> databaseGenerator;
            case "SIMPLE" -> simpleGenerator;
            // 未知类型不能静默降级为 Snowflake：雪花 ID 与连续序列语义完全不同，
            // 一旦配置拼写错误（如 SEGMNET），调用方会拿到无法排查的巨值。
            default -> throw new BusinessException("SEQ002");
        };
    }
}
