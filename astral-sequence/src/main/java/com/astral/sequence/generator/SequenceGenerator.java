package com.astral.sequence.generator;

/**
 * 序列号生成器接口
 * <p>
 * 定义了所有序列号生成器的统一契约。系统支持多种生成策略（Snowflake、号段模式、Redis、
 * 数据库、简单计数器），每种策略都实现此接口。通过接口抽象，可以在运行时动态切换生成策略。
 * </p>
 */
public interface SequenceGenerator {
    /**
     * 获取生成器的类型标识
     *
     * @return 类型标识字符串，如 "SNOWFLAKE"、"SEGMENT"、"REDIS" 等
     */
    String getType();

    /**
     * 生成下一个序列号
     *
     * @param bizKey 业务键，用于区分不同业务的序列号空间
     * @return 生成的序列号
     */
    long next(String bizKey);

    /**
     * 批量生成序列号
     *
     * @param bizKey 业务键
     * @param count  要生成的序列号数量
     * @return 逗号分隔的序列号字符串，例如 "1,2,3,4,5"
     */
    String batch(String bizKey, int count);
}