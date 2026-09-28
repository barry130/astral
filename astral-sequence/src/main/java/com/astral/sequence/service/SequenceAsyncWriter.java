package com.astral.sequence.service;

import com.astral.dao.entity.SequenceHistory;
import com.astral.dao.entity.SequenceStatistics;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 序列历史 / 统计的异步写入器
 * <p><b>为什么必须单独成一个 Bean</b>：Spring 的 {@code @Async} 是通过代理实现的，
 * 只有"从外部 Bean 调用代理对象的方法"才会经过 {@code AsyncExecutionInterceptor}。
 * 此前这些方法写在 {@link GeneratorFactory} 里、又由 {@code GeneratorFactory} 自己调用（自调用），
 * 代理完全不生效 —— 三次 DB 写全部落在业务线程上，序列号生成接口被拖慢，
 * 且历史/统计写入失败会直接把异常抛给调用方。抽成独立 Bean 后异步才真正生效。
 * </p>
 * <p>方法返回 {@link CompletableFuture} 而不是 void：便于调用方在必要时 join，
 * 也避免 Spring 用 {@code AsyncUncaughtExceptionHandler} 静默吞掉异常（这里方法体内已自行兜底）。</p>
 */
@Slf4j
@Component
public class SequenceAsyncWriter {

    /**
     * 历史/统计写入依赖 Mapper/Service，而这些 Bean 的创建又依赖 SqlSessionFactory；
     * 而本写入器被 MyBatis-Plus 的 {@code MetaObjectHandler}（在 SqlSessionFactory 构建阶段就需要就绪）
     * 所引用，直接注入会形成构造期循环依赖导致启动失败。
     * 因此用 {@code @Lazy} 注入：构造时只拿到延迟代理，真正的 Bean 在首次异步调用时
     * （此时容器早已启动完成）才被解析。
     */
    private final SequenceHistoryService historyService;
    private final SequenceStatisticsMapper statisticsMapper;

    public SequenceAsyncWriter(
            @Lazy SequenceHistoryService historyService,
            @Lazy SequenceStatisticsMapper statisticsMapper) {
        this.historyService = historyService;
        this.statisticsMapper = statisticsMapper;
    }

    /**
     * 异步保存单条历史记录
     *
     * @param bizKey 业务键
     * @param type   生成器类型
     * @param value  序列号值
     */
    @Async("sequenceAsyncExecutor")
    public CompletableFuture<Void> saveHistoryAsync(String bizKey, String type, long value) {
        try {
            historyService.save(bizKey, type, value);
        } catch (Exception e) {
            log.warn("Async history save failed: bizKey={}, type={}, value={}", bizKey, type, value, e);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 异步批量保存历史记录
     *
     * @param records 历史记录列表
     */
    @Async("sequenceAsyncExecutor")
    public CompletableFuture<Void> saveHistoryBatchAsync(List<SequenceHistory> records) {
        try {
            historyService.saveBatch(records);
        } catch (Exception e) {
            log.warn("Async batch history save failed: count={}", records == null ? 0 : records.size(), e);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 异步更新统计数据
     * <p>先 {@code insertIfAbsent} 占位再按 biz_key 更新，避免并发下重复插入
     * （select-then-insert 在并发首次调用时会插入多条相同 biz_key 的记录）。</p>
     *
     * @param bizKey       业务键
     * @param currentValue 当前序列号值
     */
    @Async("sequenceAsyncExecutor")
    public CompletableFuture<Void> updateStatisticsAsync(String bizKey, long currentValue) {
        try {
            statisticsMapper.insertIfAbsent(bizKey);
            statisticsMapper.updateCurrentValueByBizKey(bizKey, currentValue, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("Async statistics update failed: bizKey={}", bizKey, e);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 进程内维护统计快照，避免每次都 select-then-update
     *
     * @param bizKey 业务键
     * @param stat   统计实体
     */
    @Async("sequenceAsyncExecutor")
    public CompletableFuture<Void> upsertStatisticsAsync(String bizKey, SequenceStatistics stat) {
        try {
            if (stat == null) {
                return CompletableFuture.completedFuture(null);
            }
            statisticsMapper.insertIfAbsent(bizKey);
            statisticsMapper.updateCurrentValueByBizKey(bizKey, stat.getCurrentValue(), LocalDateTime.now());
        } catch (Exception e) {
            log.warn("Async statistics upsert failed: bizKey={}", bizKey, e);
        }
        return CompletableFuture.completedFuture(null);
    }
}
