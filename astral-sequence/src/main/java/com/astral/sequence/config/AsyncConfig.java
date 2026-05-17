package com.astral.sequence.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * <p>
 * 配置序列号生成系统使用的异步线程池。
 * 异步操作包括：保存历史记录、更新统计数据等。
 * 这些操作不影响序列号生成的主流程，可以异步执行以提高性能。
 * </p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 序列号异步任务线程池
     * <p>
     * 线程池参数设计：
     * <ul>
     *   <li>核心线程数 2：保证基本的异步处理能力</li>
     *   <li>最大线程数 8：应对突发的大量异步任务</li>
     *   <li>队列容量 10000：缓冲大量待处理任务</li>
     *   <li>拒绝策略 DiscardOldest：队列满时丢弃最旧的任务，保证新任务优先</li>
     * </ul>
     * </p>
     * <p>
     * 选择 DiscardOldestPolicy 的原因：历史记录和统计数据的丢失是可以接受的，
     * 但应该优先处理最新的记录，因为它们更重要。
     * </p>
     *
     * @return 配置好的线程池执行器
     */
    @Bean(name = "sequenceAsyncExecutor")
    public Executor sequenceAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("seq-async-");
        // 当队列满时，丢弃最旧的任务，尝试执行新任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.initialize();
        return executor;
    }
}
