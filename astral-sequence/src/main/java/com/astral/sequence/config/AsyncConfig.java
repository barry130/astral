package com.astral.sequence.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * <p>
 * 配置序列号生成系统使用的异步线程池。
 * 异步操作包括：保存历史记录、更新统计数据等。
 * 这些操作不影响序列号生成的主流程，可以异步执行以提高性能。
 * </p>
 * <p>
 * 线程模型（JDK 21）：
 * <ul>
 *   <li>默认（{@code astral.threads.virtual.enabled=true}）：{@link Executors#newVirtualThreadPerTaskExecutor()}
 *       ——避免阻塞任务占平台线程，降低内存开销（1核2G 场景）</li>
 *   <li>{@code astral.threads.virtual.enabled=false}：平台线程池（core=2, max=8, queue=10000, DiscardOldest）
 *       ——仅显式关闭时使用</li>
 * </ul>
 * </p>
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 是否启用 JDK 21 虚拟线程（默认 true：测试/本地均启用，无需 prod profile） */
    @Value("${astral.threads.virtual.enabled:true}")
    private boolean virtualEnabled;

    /**
     * 序列号异步任务执行器
     * <p>
     * 虚拟线程模式（production）：每任务一个虚拟线程，阻塞 I/O 时挂起而非占平台线程，
     * 显著降低内存；无队列/拒绝策略，适合大量短时异步任务（日志、统计、序列历史）。
     * </p>
     * <p>
     * 平台线程池模式（默认）：core=2, max=8, queue=10000, DiscardOldestPolicy。
     * </p>
     *
     * @return 配置好的执行器
     */
    @Bean(name = "sequenceAsyncExecutor")
    public Executor sequenceAsyncExecutor() {
        if (virtualEnabled) {
            log.info("[AsyncConfig] sequenceAsyncExecutor 使用 JDK 21 虚拟线程");
            return Executors.newVirtualThreadPerTaskExecutor();
        }
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
