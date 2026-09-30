package com.astral.common.util;

/**
 * 分页 / 条数参数归一（PageQuery）。
 *
 * <p><b>为什么需要</b>：管理端接口的 {@code pageNum} / {@code pageSize} / {@code limit}
 * 都是直接来自 query 的裸数字，会原样进入 SQL 的 {@code OFFSET} / {@code LIMIT}：</p>
 * <ul>
 *   <li>{@code pageNum < 1} → 偏移量为负，PostgreSQL 直接报错，接口返回 500；</li>
 *   <li>{@code pageSize} / {@code limit} 过大 → 一次性拉出巨量结果集，内存与连接被放大
 *       （外部只要拼一个 {@code ?pageSize=1000000} 就能触发）。</li>
 * </ul>
 *
 * <p>这里统一钳制到合法区间。<b>对正常调用方取值完全不变</b>（{@code pageNum}、{@code pageSize}
 * 都在上限内时是恒等映射），只把越界值收敛到边界，因此不改变既有功能。</p>
 *
 * <p>本类不依赖任何框架，与 {@link ClientIp} / {@link ClientHeaders} 一样放在 astral-common。</p>
 */
public final class PageQuery {

    /** 单页最大条数：与前端 pageSizeOptions 的最大值一致，正常流量不会触顶 */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 页码上界：纯防御值。超出后 OFFSET 达到亿级，数据库只会空转；
     * 更关键的是调用方（如 TokenServiceImpl）用 {@code (pageNum-1)*pageSize} 的 int 乘法
     * 计算偏移，pageNum ≥ 10,737,420 时溢出为负，直接抛 {@code IndexOutOfBoundsException} 返回 500。
     */
    public static final int MAX_PAGE_NUM = 100_000;

    /** 列表类接口的默认单页条数 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** {@code limit} / {@code topN} 类参数的默认上限 */
    public static final int MAX_LIMIT = 100;

    private PageQuery() {
    }

    /**
     * 页码钳制到 {@code [1, MAX_PAGE_NUM]}。
     *
     * @param pageNum 原始页码
     * @return 合法区间内的页码（下界避免负 OFFSET 报错，上界避免 int 偏移溢出与亿级 OFFSET 空转）
     */
    public static int pageNum(int pageNum) {
        return clamp(pageNum, 1, MAX_PAGE_NUM);
    }

    /**
     * 页码钳制到 {@code [1, MAX_PAGE_NUM]}（long 版）。
     *
     * @param pageNum 原始页码
     * @return 合法区间内的页码
     */
    public static long pageNum(long pageNum) {
        return clamp(pageNum, 1L, (long) MAX_PAGE_NUM);
    }

    /**
     * 单页条数钳制到 {@code [1, MAX_PAGE_SIZE]}。
     *
     * @param pageSize 原始单页条数
     * @return 钳制后的单页条数
     */
    public static int pageSize(int pageSize) {
        return clamp(pageSize, 1, MAX_PAGE_SIZE);
    }

    /**
     * 单页条数钳制到 {@code [1, MAX_PAGE_SIZE]}（long 版）。
     *
     * @param pageSize 原始单页条数
     * @return 钳制后的单页条数
     */
    public static long pageSize(long pageSize) {
        return clamp(pageSize, 1L, (long) MAX_PAGE_SIZE);
    }

    /**
     * 条数上限钳制。
     *
     * @param limit 原始条数
     * @param max   允许的最大条数
     * @return 钳制到 {@code [1, max]} 的条数
     */
    public static int limit(int limit, int max) {
        return clamp(limit, 1, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static long clamp(long value, long min, long max) {
        return Math.min(Math.max(value, min), max);
    }
}
