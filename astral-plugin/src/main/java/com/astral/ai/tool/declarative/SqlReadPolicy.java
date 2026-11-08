package com.astral.ai.tool.declarative;

import com.astral.ai.common.AiException;
import com.astral.ai.config.AiProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

/** A deliberately finite SELECT grammar; every source and expression is checked before JDBC. */
final class SqlReadPolicy {
    private static final Map<String, Set<String>> DEFAULT_COLUMNS = Map.of(
            "astral.sys_user",
                    Set.of("id", "username", "nickname", "status", "user_type", "avatar", "create_time", "update_time"),
            "astral.sys_role", Set.of("id", "role_code", "role_name", "status", "create_time", "update_time"),
            "astral.sys_dict_type", Set.of("id", "dict_code", "dict_name", "status", "create_time", "update_time"),
            "astral.sys_dict_data",
                    Set.of(
                            "id",
                            "dict_type_id",
                            "dict_label",
                            "dict_value",
                            "status",
                            "dict_sort",
                            "create_time",
                            "update_time"));
    private static final Set<String> FUNCTIONS = Set.of(
            "count",
            "sum",
            "avg",
            "min",
            "max",
            "abs",
            "round",
            "ceil",
            "ceiling",
            "floor",
            "lower",
            "upper",
            "length",
            "char_length",
            "substring",
            "substr",
            "trim",
            "btrim",
            "ltrim",
            "rtrim",
            "replace",
            "coalesce",
            "nullif",
            "greatest",
            "least");
    private static final Set<String> BINARY = Set.of(
            "Addition",
            "Subtraction",
            "Multiplication",
            "Division",
            "Modulo",
            "AndExpression",
            "OrExpression",
            "EqualsTo",
            "NotEqualsTo",
            "GreaterThan",
            "GreaterThanEquals",
            "MinorThan",
            "MinorThanEquals",
            "LikeExpression",
            "Concat",
            "IsDistinctExpression");
    private static final Set<String> CAST_TYPES = Set.of(
            "text",
            "varchar",
            "char",
            "character varying",
            "integer",
            "int",
            "bigint",
            "smallint",
            "numeric",
            "decimal",
            "real",
            "double precision",
            "boolean",
            "date",
            "time",
            "timestamp");
    private final Map<String, Set<String>> readable = new HashMap<>(DEFAULT_COLUMNS);
    private int nodes;

    private SqlReadPolicy(AiProperties properties) {
        if (properties != null && properties.getSqlTool() != null) {
            properties.getSqlTool().getAllowedColumns().forEach((table, columns) -> {
                if (table == null
                        || !table.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")
                        || columns == null
                        || columns.isEmpty()
                        || columns.stream()
                                .anyMatch(column -> column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]*"))) {
                    throw invalid("服务端 SQL 来源列策略非法");
                }
                if (table.startsWith("pg_") || table.startsWith("information_schema.")) {
                    throw invalid("不允许开放数据库系统目录");
                }
                readable.put(table, Set.copyOf(columns));
            });
        }
    }

    static void validate(String sql, AiProperties properties) {
        prepare(sql, properties);
    }

    static String prepare(String sql, AiProperties properties) {
        if (sql.length() > 32000) {
            throw invalid("SQL 超过长度上限");
        }
        try {
            var parsed =
                    CCJSqlParserUtil.parse(SqlToolExecutor.PARAM.matcher(sql).replaceAll("?"));
            if (!(parsed instanceof Select select)) {
                throw invalid("只允许 SELECT 查询");
            }
            new SqlReadPolicy(properties).select(select, Map.of(), null, 0);
            return select.toString();
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            // Parser exceptions include the SQL, including any literals; never expose them.
            throw invalid("SQL 语法不受支持或无法安全校验");
        }
    }

    private List<String> select(Select select, Map<String, List<String>> outerCtes, Scope parent, int depth) {
        guard(depth);
        Map<String, List<String>> ctes = new HashMap<>(outerCtes);
        if (select.getWithItemsList() != null) {
            for (var with : select.getWithItemsList()) {
                if (with.isRecursive() || !(with.getParenthesedStatement() instanceof ParenthesedSelect query)) {
                    throw invalid("只支持非递归只读 CTE");
                }
                List<String> output = select(query, ctes, parent, depth + 1);
                if (with.getWithItemList() != null && !with.getWithItemList().isEmpty()) {
                    if (with.getWithItemList().size() != output.size()) {
                        throw invalid("CTE 列数不匹配");
                    }
                    output = with.getWithItemList().stream()
                            .map(item -> {
                                if (!(item.getExpression() instanceof Column column)) {
                                    throw invalid("CTE 列名非法");
                                }
                                return identifier(column.getColumnName());
                            })
                            .toList();
                }
                ctes.put(identifier(with.getAliasName()), output);
            }
        }
        if (select.getForMode() != null
                || select.getForClause() != null
                || select.getForUpdateTable() != null
                || select.getIsolation() != null
                || select.getPivot() != null
                || select.getUnPivot() != null
                || select.getLimitBy() != null) {
            throw invalid("SQL 包含不支持的锁或扩展子句");
        }
        List<String> output;
        Scope scope = new Scope(parent);
        if (select instanceof ParenthesedSelect nested) {
            output = select(nested.getSelect(), ctes, parent, depth + 1);
        } else if (select instanceof SetOperationList set) {
            output = null;
            for (Select child : set.getSelects()) {
                List<String> childColumns = select(child, ctes, parent, depth + 1);
                if (output == null) {
                    output = childColumns;
                } else if (childColumns.size() != output.size()) {
                    throw invalid("集合查询列数不匹配");
                }
            }
            if (output == null) {
                throw invalid("查询不能为空");
            }
        } else if (select instanceof PlainSelect plain) {
            if (plain.getIntoTables() != null
                    || plain.getIntoTempTable() != null
                    || plain.getLateralViews() != null
                    || plain.getOracleHierarchical() != null
                    || plain.getPreferringClause() != null
                    || plain.getWindowDefinitions() != null
                    || plain.getQualify() != null
                    || plain.getForXmlPath() != null
                    || plain.getKsqlWindow() != null
                    || plain.isEmitChanges()
                    || plain.getTop() != null
                    || plain.getSkip() != null
                    || plain.getFirst() != null
                    || plain.getOracleHint() != null
                    || plain.getSampleClause() != null) {
                throw invalid("SQL 包含未获允许的查询子句");
            }
            source(plain.getFromItem(), scope, ctes, depth + 1);
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    if (join.isNatural() || join.isApply() || join.isWindowJoin()) {
                        throw invalid("请使用显式 JOIN 条件");
                    }
                    source(join.getRightItem(), scope, ctes, depth + 1);
                    for (Expression expression : join.getOnExpressions()) {
                        expression(expression, scope, ctes, depth + 1);
                    }
                    if (join.getUsingColumns() != null) {
                        for (Column column : join.getUsingColumns()) {
                            // USING must be readable on every participating source.
                            for (Set<String> columns : scope.sources.values()) {
                                if (!columns.contains(identifier(column.getColumnName()))) {
                                    throw invalid("JOIN 来源列未获允许");
                                }
                            }
                        }
                    }
                }
            }
            output = new ArrayList<>();
            for (var item : plain.getSelectItems()) {
                expression(item.getExpression(), scope, ctes, depth + 1);
                output.add(
                        item.getAlias() != null
                                ? identifier(item.getAlias().getName())
                                : item.getExpression() instanceof Column column
                                        ? identifier(column.getColumnName())
                                        : "?expression" + output.size());
            }
            expression(plain.getWhere(), scope, ctes, depth + 1);
            expression(plain.getHaving(), scope, ctes, depth + 1);
            if (plain.getGroupBy() != null) {
                expression(plain.getGroupBy().getGroupByExpressionList(), scope, ctes, depth + 1);
                if (plain.getGroupBy().getGroupingSets() != null) {
                    for (var group : plain.getGroupBy().getGroupingSets()) {
                        expression(group, scope, ctes, depth + 1);
                    }
                }
            }
            if (plain.getDistinct() != null && plain.getDistinct().getOnSelectItems() != null) {
                for (var item : plain.getDistinct().getOnSelectItems()) {
                    expression(item.getExpression(), scope, ctes, depth + 1);
                }
            }
        } else {
            throw invalid("SQL 查询类型未获允许");
        }
        // PostgreSQL recognizes an output alias only as a standalone ORDER BY item.
        if (select.getOrderByElements() != null) {
            for (OrderByElement item : select.getOrderByElements()) {
                if (item.getExpression() instanceof Column column
                        && (column.getTable() == null || column.getTable().getName() == null)
                        && column.getArrayConstructor() == null
                        && output.contains(identifier(column.getColumnName()))) {
                    continue;
                }
                expression(item.getExpression(), scope, ctes, depth + 1);
            }
        }
        if (select.getLimit() != null) {
            expression(select.getLimit().getOffset(), scope, ctes, depth + 1);
            expression(select.getLimit().getRowCount(), scope, ctes, depth + 1);
            expression(select.getLimit().getByExpressions(), scope, ctes, depth + 1);
        }
        if (select.getOffset() != null) {
            expression(select.getOffset().getOffset(), scope, ctes, depth + 1);
        }
        if (select.getFetch() != null) {
            expression(select.getFetch().getExpression(), scope, ctes, depth + 1);
        }
        return output;
    }

    private void source(FromItem item, Scope scope, Map<String, List<String>> ctes, int depth) {
        if (item == null) {
            return;
        }
        if (item.getPivot() != null || item.getUnPivot() != null || item.getSampleClause() != null) {
            throw invalid("SQL 来源扩展未获允许");
        }
        String name;
        Set<String> columns;
        if (item instanceof Table table) {
            name = identifier(table.getName());
            String schema = table.getSchemaName() == null ? null : identifier(table.getSchemaName());
            if (table.getDatabaseName() != null
                    || table.getNameParts().size() > 2
                    || table.getSqlServerHints() != null
                    || table.getIndexHint() != null) {
                throw invalid("SQL 来源限定不受支持");
            }
            if (schema == null && ctes.containsKey(name)) {
                columns = Set.copyOf(ctes.get(name));
            } else {
                columns = readable.get((schema == null ? "astral" : schema) + "." + name);
                if (columns == null) {
                    throw invalid("SQL 来源表未获服务端读取授权");
                }
                if (schema == null) {
                    // Bind execution to the authorized physical schema, regardless of connection search_path.
                    table.setSchemaName("astral");
                }
            }
        } else if (item instanceof ParenthesedSelect nested) {
            columns = new HashSet<>(select(nested, ctes, scope, depth + 1));
            name = "?derived" + scope.sources.size();
        } else {
            throw invalid("SQL 仅允许已授权的表或只读子查询，不允许表函数");
        }
        if (item.getAlias() != null) {
            if (item.getAlias().getAliasColumns() != null) {
                throw invalid("请在子查询中显式声明列别名");
            }
            name = identifier(item.getAlias().getName());
        }
        if (scope.sources.putIfAbsent(name, columns) != null) {
            throw invalid("SQL 来源别名重复");
        }
    }

    private void expression(Expression expression, Scope scope, Map<String, List<String>> ctes, int depth) {
        if (expression == null) {
            return;
        }
        guard(depth);
        switch (expression) {
            case Column column -> {
                if (column.getArrayConstructor() != null) {
                    throw invalid("SQL 数组表达式未获允许");
                }
                scope.column(column);
            }
            case NullValue ignored -> {}
            case LongValue ignored -> {}
            case DoubleValue ignored -> {}
            case StringValue ignored -> {}
            case BooleanValue ignored -> {}
            case DateValue ignored -> {}
            case TimeValue ignored -> {}
            case TimestampValue ignored -> {}
            case JdbcParameter ignored -> {}
            case Function function -> {
                String name = identifier(function.getName());
                if (function.getMultipartName().size() != 1
                        || !FUNCTIONS.contains(name)
                        || function.getAttribute() != null
                        || function.getNamedParameters() != null
                        || function.getKeep() != null
                        || function.getHavingClause() != null
                        || function.getLimit() != null
                        || function.isEscaped()) {
                    throw invalid("SQL 函数未获允许");
                }
                if (function.getParameters() != null) {
                    for (Expression argument : function.getParameters()) {
                        if (argument.getClass() == AllColumns.class
                                && "count".equals(name)
                                && function.getParameters().size() == 1
                                && ((AllColumns) argument).getExceptColumns() == null
                                && ((AllColumns) argument).getReplaceExpressions() == null) {
                            continue;
                        }
                        expression(argument, scope, ctes, depth + 1);
                    }
                }
                order(function.getOrderByElements(), scope, ctes, depth + 1);
            }
            case BinaryExpression binary -> {
                if (!BINARY.contains(binary.getClass().getSimpleName())) {
                    throw invalid("SQL 运算符未获允许");
                }
                expression(binary.getLeftExpression(), scope, ctes, depth + 1);
                expression(binary.getRightExpression(), scope, ctes, depth + 1);
                if (binary instanceof LikeExpression like) {
                    expression(like.getEscape(), scope, ctes, depth + 1);
                }
            }
            case ExpressionList<?> list -> {
                for (Expression child : list) {
                    expression(child, scope, ctes, depth + 1);
                }
            }
            case SignedExpression signed -> expression(signed.getExpression(), scope, ctes, depth + 1);
            case NotExpression not -> expression(not.getExpression(), scope, ctes, depth + 1);
            case TrimFunction trim -> {
                expression(trim.getExpression(), scope, ctes, depth + 1);
                expression(trim.getFromExpression(), scope, ctes, depth + 1);
            }
            case IsNullExpression isNull -> expression(isNull.getLeftExpression(), scope, ctes, depth + 1);
            case IsBooleanExpression bool -> expression(bool.getLeftExpression(), scope, ctes, depth + 1);
            case Between between -> {
                expression(between.getLeftExpression(), scope, ctes, depth + 1);
                expression(between.getBetweenExpressionStart(), scope, ctes, depth + 1);
                expression(between.getBetweenExpressionEnd(), scope, ctes, depth + 1);
            }
            case InExpression in -> {
                expression(in.getLeftExpression(), scope, ctes, depth + 1);
                expression(in.getRightExpression(), scope, ctes, depth + 1);
            }
            case ExistsExpression exists -> expression(exists.getRightExpression(), scope, ctes, depth + 1);
            case Select query -> select(query, ctes, scope, depth + 1);
            case CaseExpression choice -> {
                expression(choice.getSwitchExpression(), scope, ctes, depth + 1);
                expression(choice.getElseExpression(), scope, ctes, depth + 1);
                for (var when : choice.getWhenClauses()) {
                    expression(when.getWhenExpression(), scope, ctes, depth + 1);
                    expression(when.getThenExpression(), scope, ctes, depth + 1);
                }
            }
            case CastExpression cast -> {
                if (cast.getColDataType() == null
                        || !CAST_TYPES.contains(
                                cast.getColDataType().getDataType().toLowerCase(Locale.ROOT))
                        || cast.getColumnDefinitions() != null
                        || cast.getFormat() != null) {
                    throw invalid("SQL 转换类型未获允许");
                }
                expression(cast.getLeftExpression(), scope, ctes, depth + 1);
            }
            default -> throw invalid("SQL 表达式未获允许；请显式选择可读取的来源列");
        }
    }

    private void order(List<OrderByElement> items, Scope scope, Map<String, List<String>> ctes, int depth) {
        if (items != null) {
            for (OrderByElement item : items) {
                expression(item.getExpression(), scope, ctes, depth + 1);
            }
        }
    }

    private void guard(int depth) {
        if (depth > 32 || ++nodes > 4000) {
            throw invalid("SQL 查询过于复杂");
        }
    }

    private static String identifier(String value) {
        if (value == null) {
            throw invalid("SQL 标识符缺失");
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static AiException invalid(String message) {
        return new AiException("AI008", message);
    }

    private static final class Scope {
        private final Scope parent;
        private final Map<String, Set<String>> sources = new LinkedHashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        void column(Column column) {
            String name = identifier(column.getColumnName());
            Table qualifier = column.getTable();
            if (qualifier != null
                    && qualifier.getName() != null
                    && !qualifier.getName().isEmpty()) {
                if (qualifier.getSchemaName() != null || qualifier.getDatabaseName() != null) {
                    throw invalid("请使用表别名引用列");
                }
                Set<String> columns = sources.get(identifier(qualifier.getName()));
                if (columns != null) {
                    if (!columns.contains(name)) {
                        throw invalid("SQL 来源列未获服务端读取授权");
                    }
                    return;
                }
            } else {
                long matches = sources.values().stream()
                        .filter(columns -> columns.contains(name))
                        .count();
                if (matches == 1) {
                    String source = sources.entrySet().stream()
                            .filter(entry -> entry.getValue().contains(name))
                            .findFirst()
                            .orElseThrow()
                            .getKey();
                    if (!source.startsWith("?derived")) {
                        // Explicit qualification prevents a missing column from becoming a whole-row table reference.
                        column.setTable(new Table("\"" + source.replace("\"", "\"\"") + "\""));
                    }
                    return;
                }
                if (matches > 1) {
                    throw invalid("请用表别名限定重名列");
                }
                // An unapproved local column must not be mistaken for a correlated outer column.
                throw invalid("SQL 来源列未获授权；关联外层列必须使用表别名");
            }
            if (parent != null) {
                parent.column(column);
                return;
            }
            throw invalid("SQL 来源列未获服务端读取授权");
        }
    }
}
