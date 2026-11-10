package com.astral.common.util;

import java.util.regex.Pattern;

/**
 * SQL 标识符白名单校验：动态拼入 DDL 的 schema 名 / 表名必须先经此处校验。
 *
 * <p><b>为什么需要它：</b>JDBC 占位符 {@code ?} 只能参数化「值」，不能参数化 DDL 标识符
 * （{@code CREATE SCHEMA ?} / {@code CREATE TABLE ?} 语法不成立）。测试夹具需要按随机 schema 名
 * 建表/清理，只能拼标识符——拼之前必须过白名单，否则静态扫描（CWE-89 字符串拼接）与真实注入
 * 风险都存在。</p>
 *
 * <p><b>使用边界：</b>DDL 中的动态标识符（schema/table/column 名）。DML 的「值」一律走
 * PreparedStatement 占位符，不走此类。生产代码出现动态 DDL 需求时同样必须先经此校验。</p>
 */
public final class SqlIdentifiers {

    /** PostgreSQL 标识符合法集：字母/下划线开头，仅字母数字下划线，≤63 字符（PG NAMEDATALEN-1）。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,62}");

    private SqlIdentifiers() {}

    /**
     * 校验 SQL 标识符合法（字母/下划线开头，仅字母数字下划线，≤63 字符）。
     * 不合法（含引号、分号、空格、注释符等注入载体）立即抛 IllegalArgumentException。
     *
     * @return 原值（链式传入 String.format）
     */
    public static String requireSafe(String identifier) {
        if (identifier == null || !SAFE_NAME.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return identifier;
    }
}
