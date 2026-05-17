package com.astral.common.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 序列号生成指标统计工具类
 *
 * 提供序列号生成的实时监控指标，包括累计生成总量和实时 QPS（每秒查询率）。
 * 使用原子变量保证多线程环境下的计数准确性，适用于性能调优和监控告警场景。
 *
 * QPS 采用滑动窗口算法计算：以 1 秒为窗口周期，窗口结束时根据窗口内的
 * 生成次数计算 QPS，并重置计数器进入下一个窗口。
 */
public class SequenceMetrics {

    /** 累计生成的序列号总数，进程生命周期内只增不减（除非调用 reset） */
    private static final AtomicLong totalGenerated = new AtomicLong(0);

    /** 当前时间窗口内的序列号生成次数，每个窗口结束后重置为零 */
    private static final AtomicLong windowCount = new AtomicLong(0);

    /** 当前时间窗口的起始时间戳（毫秒），用于判断窗口是否已满 1 秒 */
    private static volatile long windowStartTime = System.currentTimeMillis();

    /** 当前计算出的 QPS 值，仅在窗口结束时更新 */
    private static volatile double currentQps = 0.0;

    /**
     * 记录一次序列号生成事件
     *
     * 每次生成序列号后调用此方法，累加总计数和窗口计数，
     * 并尝试更新 QPS（仅当窗口已满 1 秒时才会实际计算）。
     */
    public static void recordGeneration() {
        totalGenerated.incrementAndGet();
        windowCount.incrementAndGet();
        updateQps();
    }

    /**
     * 获取累计生成的序列号总数
     *
     * @return 自进程启动或上次 reset 以来的总生成量
     */
    public static long getTotalGenerated() {
        return totalGenerated.get();
    }

    /**
     * 获取当前 QPS（每秒生成速率）
     *
     * 调用时会尝试更新 QPS 值，确保返回的是最新数据。
     *
     * @return 当前窗口计算出的每秒生成次数
     */
    public static double getQps() {
        updateQps();
        return currentQps;
    }

    /**
     * 更新 QPS 值（线程安全）
     *
     * 使用 synchronized 保证窗口重置操作的原子性，避免多个线程
     * 同时检测到窗口过期时导致计数丢失或重复计算。
     *
     * 计算逻辑：当距窗口起点已过 1000ms 时，将窗口内的计数
     * 换算为每秒速率（count * 1000 / elapsed），然后重置窗口。
     * 使用 elapsed 而非固定 1000 作为除数，可以消除窗口延迟带来的误差。
     */
    private static synchronized void updateQps() {
        long now = System.currentTimeMillis();
        long elapsed = now - windowStartTime;
        if (elapsed >= 1000) {
            long count = windowCount.getAndSet(0);
            currentQps = (double) count * 1000 / elapsed;
            windowStartTime = now;
        }
    }

    /**
     * 重置所有指标数据
     *
     * 将累计计数、窗口计数、窗口起点和 QPS 全部归零，
     * 通常用于监控面板刷新或测试用例清理环境。
     */
    public static void reset() {
        totalGenerated.set(0);
        windowCount.set(0);
        windowStartTime = System.currentTimeMillis();
        currentQps = 0.0;
    }
}
