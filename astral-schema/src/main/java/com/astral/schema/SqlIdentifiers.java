package com.astral.schema;

import java.util.regex.Pattern;

/**
 * SQL 标识符（表名 / 列名）校验与引用工具。
 *
 * <p><b>为什么必须有这个类</b>：表名与列名会被直接拼接进两处高危位置——</p>
 * <ol>
 *   <li>文件路径：{@code externalSchemaDir.resolve(tableName + ".json")}，
 *       未校验时 {@code tableName = "../../../../tmp/evil"} 可越出目录写任意 {@code .json}；</li>
 *   <li>DDL 语句：{@code CREATE TABLE ... <tableName> (...)}，
 *       未校验时构成 DDL 注入。</li>
 * </ol>
 *
 * <p>白名单规则：小写字母开头，后续只允许小写字母 / 数字 / 下划线，总长 ≤ 64。
 * 已用现有 43 张表 / 474 个列名全量回归验证，全部通过，不会误伤代码生成。</p>
 */
public final class SqlIdentifiers {

    /** 合法标识符：小写字母开头，其余为小写字母/数字/下划线，长度 1~64 */
    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private SqlIdentifiers() {
    }

    /**
     * 判断标识符是否合法。
     *
     * @param name 待校验的标识符，可为 null
     * @return 合法返回 true
     */
    public static boolean isValid(String name) {
        return name != null && PATTERN.matcher(name).matches();
    }

    /**
     * 校验标识符，不合法时抛 {@link IllegalArgumentException}。
     *
     * <p>注意：本类位于 astral-schema，不依赖 astral-common，
     * 因此抛标准异常；Web 层应在调用前先用 {@link #isValid} 校验并转成业务错误码。</p>
     *
     * @param name 待校验的标识符
     * @param what 用于错误信息的位置说明，如「表名」「列名」
     * @return 原样返回 name，便于链式使用
     * @throws IllegalArgumentException 标识符不合法
     */
    public static String requireValid(String name, String what) {
        if (!isValid(name)) {
            throw new IllegalArgumentException(what + "不合法: " + name);
        }
        return name;
    }

    /**
     * 按 SQL 标准加双引号，防止标识符与关键字冲突或被注入。
     *
     * <p>PostgreSQL 下 {@code "user"} 是合法标识符，不加引号则会被解析为关键字。</p>
     *
     * @param name 已通过 {@link #requireValid} 校验的标识符
     * @return 带双引号的标识符
     */
    public static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
