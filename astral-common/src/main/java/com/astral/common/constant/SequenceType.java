package com.astral.common.constant;

/**
 * 序列号生成类型枚举
 *
 * 定义系统支持的所有序列号生成策略，每种策略对应不同的底层实现：
 * SNOWFLAKE - Twitter 雪花算法，分布式环境下全局唯一
 * SEGMENT   - 号段模式，从数据库预分配号段，减少数据库压力
 * REDIS     - 基于 Redisson 原子递增，适合已有 Redis 集群的场景
 * DATABASE  - 每次生成均访问数据库，强一致但性能较低
 * SIMPLE    - 内存计数器，仅适用于单机测试环境
 */
public enum SequenceType {

    /** Twitter 雪花算法序列生成器 */
    SNOWFLAKE("snowflake"),

    /** 号段模式序列生成器（默认策略） */
    SEGMENT("segment"),

    /** 基于 Redis 原子操作的序列生成器 */
    REDIS("redis"),

    /** 基于数据库的序列生成器 */
    DATABASE("database"),

    /** 内存计数器序列生成器（仅测试用） */
    SIMPLE("simple");

    /** 枚举对应的字符串值，用于配置文件和 API 参数传递 */
    private final String value;

    /**
     * 构造方法
     *
     * @param value 枚举的字符串表示
     */
    SequenceType(String value) {
        this.value = value;
    }

    /**
     * 获取枚举的字符串值
     *
     * @return 配置文件中使用的类型名称
     */
    public String getValue() {
        return value;
    }

    /**
     * 从字符串值反查枚举实例
     *
     * 采用忽略大小写的匹配方式，提高配置的容错性。
     * 如果未匹配到任何类型，默认返回 SNOWFLAKE 作为兜底策略。
     *
     * @param value 配置或请求传入的类型字符串
     * @return 对应的枚举实例，未匹配时返回 SNOWFLAKE
     */
    public static SequenceType fromValue(String value) {
        for (SequenceType type : values()) {
            if (type.getValue().equalsIgnoreCase(value)) {
                return type;
            }
        }
        return SNOWFLAKE;
    }
}
