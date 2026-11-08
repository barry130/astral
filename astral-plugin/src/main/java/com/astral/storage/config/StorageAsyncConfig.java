package com.astral.storage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 存储远端清理线程池。
 * <p>删除文件改为「立即删库行 + 远端对象异步清理」后，远端 DELETE 走此尽力而为通道：
 * 失败只记审计与 WARN 日志留痕，不阻塞用户侧删除结果，也不占用上传/回执线程。</p>
 */
@Configuration
public class StorageAsyncConfig {

    public static final String REMOTE_DELETE_EXECUTOR = "storageRemoteDeleteExecutor";

    @Bean(REMOTE_DELETE_EXECUTOR)
    public ThreadPoolTaskExecutor storageRemoteDeleteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("storage-remote-delete-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setDaemon(true);
        return executor;
    }
}
