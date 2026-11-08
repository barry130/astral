package com.astral.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ApiMetricCollectorTest {
    @Test
    void distinctRoutesAndVersionsCannotGrowBeyondBucketLimit() {
        ApiMetricCollector collector = new ApiMetricCollector();
        for (int i = 0; i < 30_000; i++) {
            collector.record("/api/resource/" + i, "GET", i % 2 == 0 ? 200 : 500, 2_000_000, "web", "v" + i);
        }

        var drained = collector.drain();

        assertTrue(drained.size() <= 20_000, "all dimensions together must obey the declared hard limit");
        assertEquals(
                30_000L,
                drained.values().stream()
                        .mapToLong(ApiMetricCollector.ApiMetricValue::getCallCount)
                        .sum());
        assertEquals(
                60_000L,
                drained.values().stream()
                        .mapToLong(ApiMetricCollector.ApiMetricValue::getSumMs)
                        .sum());
        assertEquals(
                15_000L,
                drained.entrySet().stream()
                        .filter(e -> e.getKey().getStatus() >= 400)
                        .mapToLong(e -> e.getValue().getCallCount())
                        .sum());
        assertTrue(collector.drain().isEmpty());
    }

    @Test
    void concurrentRecordingAndDrainingCountsEveryRequestOnce() throws Exception {
        ApiMetricCollector collector = new ApiMetricCollector();
        AtomicBoolean recording = new AtomicBoolean(true);
        AtomicLong total = new AtomicLong();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> drainer = executor.submit(() -> {
                while (recording.get()) {
                    total.addAndGet(collector.drain().values().stream()
                            .mapToLong(ApiMetricCollector.ApiMetricValue::getCallCount)
                            .sum());
                    Thread.yield();
                }
            });
            try {
                var writers = new ArrayList<Future<?>>();
                for (int writer = 0; writer < 8; writer++) {
                    writers.add(executor.submit(() -> {
                        for (int i = 0; i < 10_000; i++) {
                            collector.record("/api/" + i, "GET", 200, 1_000_000, "web", "1");
                        }
                    }));
                }
                for (Future<?> writer : writers) {
                    writer.get();
                }
            } finally {
                recording.set(false);
            }
            drainer.get();
        }
        total.addAndGet(collector.drain().values().stream()
                .mapToLong(ApiMetricCollector.ApiMetricValue::getCallCount)
                .sum());
        assertEquals(80_000L, total.get());
    }
}
