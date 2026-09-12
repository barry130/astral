package com.astral.schema;

import java.util.Locale;

/**
 * SQL方言
 * <p>
 * 用于表结构管理的SQL生成（建表SQL / 变更SQL）。不同方言在以下方面存在差异：
 * <ul>
 *   <li>自增主键：MySQL 使用 AUTO_INCREMENT，PostgreSQL 使用 SERIAL / BIGSERIAL</li>
 *   <li>注释：MySQL 内联 COMMENT '...'，PostgreSQL 使用 COMMENT ON TABLE / COMMENT ON COLUMN</li>
 *   <li>表选项：MySQL 需要 ENGINE / CHARSET，PostgreSQL 无</li>
 *   <li>改列：MySQL 使用 MODIFY COLUMN，PostgreSQL 使用 ALTER COLUMN ... TYPE / SET NOT NULL / SET DEFAULT</li>
 * </ul>
 * 默认方言为 PostgreSQL（与运行时数据库一致）。
 * </p>
 */
public enum SqlDialect {

    /** MySQL 5.7+ / 8.0 */
    MYSQL("mysql", "MySQL"),

    /** PostgreSQL 12+（运行时默认数据库） */
    POSTGRESQL("postgresql", "PostgreSQL");

    private final String code;
    private final String label;

    SqlDialect(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 获取方言编码（接口传参值）
     *
     * @return 方言编码，如 mysql / postgresql
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取方言展示名
     *
     * @return 方言展示名，如 MySQL / PostgreSQL
     */
    public String getLabel() {
        return label;
    }

    /**
     * 解析方言编码
     * <p>
     * 支持别名：mysql；postgresql / postgres / pg。
     * 空值或无法识别时返回默认的 PostgreSQL。
     * </p>
     *
     * @param value 方言编码
     * @return 对应的方言，永远不会返回null
     */
    public static SqlDialect of(String value) {
        if (value == null || value.isBlank()) {
            return POSTGRESQL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "mysql" -> MYSQL;
            case "pg", "postgres", "postgresql" -> POSTGRESQL;
            default -> POSTGRESQL;
        };
    }
}
