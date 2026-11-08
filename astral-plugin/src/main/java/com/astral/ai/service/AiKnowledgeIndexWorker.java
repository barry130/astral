package com.astral.ai.service;

import com.astral.ai.config.AiProperties;
import com.astral.plugin.api.PluginRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded database-backed worker. Uncommitted work rolls back; the next tick resumes the persisted cursor. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiKnowledgeIndexWorker {
    private final AiKnowledgeService knowledgeService;
    private final AiProperties properties;
    private final PluginRegistry pluginRegistry;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("ai-knowledge-index").factory());
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(fixedDelay = 1000, initialDelay = 30000)
    public void dispatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    tick();
                } finally {
                    running.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            running.set(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public void tick() {
        if (!properties.isEnabled() || !pluginRegistry.isEnabled("ai")) return;
        try {
            for (int i = 0; i < 20 && !Thread.currentThread().isInterrupted(); i++) {
                if (!knowledgeService.processNextIndexDocument()) break;
            }
        } catch (RuntimeException failure) {
            log.warn("[AI] 知识索引队列暂不可用，下轮重试", failure);
        }
    }
}
