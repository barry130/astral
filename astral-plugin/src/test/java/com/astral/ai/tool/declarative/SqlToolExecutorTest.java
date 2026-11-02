package com.astral.ai.tool.declarative;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiToolEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlToolExecutor} 的只读护栏单元测试。
 *
 * <p>覆盖保存期校验（关键字黑名单、单语句、首词、列声明、行数/超时区间）、字面量与注释剥离、
 * 具名参数绑定，以及「无数据源/配置非法时工具不可用」的降级行为。</p>
 */
class SqlToolExecutorTest {

    private static final String CONFIG = """
            {"sql":"select id, user_name from astral.sys_user where status = #{status} limit 10",
             "columns":["id","user_name"],"maxRows":10,"timeoutSeconds":3}
            """;

    private static final String SCHEMA = """
            {"type":"object","properties":{"status":{"type":"integer"}},"required":["status"]}
            """;

    private static ObjectNode config(String sql, String columns) {
        ObjectNode node = AiJson.mapper().createObjectNode();
        node.put("sql", sql);
        node.set("columns", AiJson.read(columns));
        return node;
    }

    private static AiException rejected(String sql, String columns) {
        return assertThrows(AiException.class, () -> SqlToolExecutor.requireValidShape(config(sql, columns)));
    }

    // ==================== 只读护栏 ====================

    @Test
    @DisplayName("普通 SELECT 与 WITH … SELECT 通过校验")
    void allowsReadOnlySql() {
        SqlToolExecutor.requireValidShape(config("select 1 from astral.sys_user", "[\"id\"]"));
        SqlToolExecutor.requireValidShape(config("with t as (select id from astral.sys_user) select id from t",
                "[\"id\"]"));
        // 尾分号允许
        SqlToolExecutor.requireValidShape(config("select id from astral.sys_user;", "[\"id\"]"));
    }

    @Test
    @DisplayName("写操作与 DDL 关键字一律拒绝，含数据修改型 CTE")
    void rejectsWrites() {
        assertEquals(true, rejected("insert into astral.sys_user (id) values (1)", "[\"id\"]")
                .getMessage().contains("INSERT"));
        assertEquals(true, rejected("update astral.sys_user set name = 'x'", "[\"id\"]")
                .getMessage().contains("UPDATE"));
        assertEquals(true, rejected("delete from astral.sys_user", "[\"id\"]")
                .getMessage().contains("DELETE"));
        // 首词不是 SELECT/WITH
        assertEquals(true, rejected("truncate table astral.sys_user", "[\"id\"]")
                .getMessage().contains("只允许"));
        // 数据修改型 CTE：首词是 WITH，靠关键字黑名单兜住
        assertEquals(true, rejected("with x as (insert into astral.t values (1) returning id) select id from x",
                "[\"id\"]").getMessage().contains("INSERT"));
        // SELECT … INTO
        assertEquals(true, rejected("select id into astral.tmp from astral.sys_user", "[\"id\"]")
                .getMessage().contains("INTO"));
        // 会话变更（SET）同样被拦
        assertEquals(true, rejected("select 1 from astral.sys_user set statement_timeout = 0", "[\"id\"]")
                .getMessage().contains("SET"));
        // 纯只读的 WITH 不受影响
        SqlToolExecutor.requireValidShape(config("with t as (select 1 as id) select id from t", "[\"id\"]"));
    }

    @Test
    @DisplayName("多语句被拒绝")
    void rejectsMultipleStatements() {
        assertEquals(true, rejected("select id from astral.sys_user; select 2", "[\"id\"]")
                .getMessage().contains("单条"));
        assertEquals(true, rejected("select id from astral.sys_user;;", "[\"id\"]")
                .getMessage().contains("单条"));
    }

    @Test
    @DisplayName("注释与字符串字面量里的关键字/分号不参与判断")
    void ignoresLiteralsAndComments() {
        String sql = """
                select id -- 这里写 delete 也没关系
                from astral.sys_user /* update 同理; */ where name = 'insert; drop'
                """;
        SqlToolExecutor.requireValidShape(config(sql, "[\"id\"]"));
        String stripped = SqlToolExecutor.stripLiteralsAndComments(sql);
        assertFalse(stripped.contains("delete"));
        assertFalse(stripped.contains("update"));
        assertTrue(stripped.contains("select"));
    }

    @Test
    @DisplayName("words() 不把 update_time 当成 UPDATE")
    void wordsKeepUnderscore() {
        List<String> tokens = SqlToolExecutor.words("select update_time, created_at from t");
        assertFalse(tokens.contains("UPDATE"));
        assertTrue(tokens.contains("UPDATE_TIME"));
        SqlToolExecutor.requireValidShape(config("select update_time from astral.sys_user", "[\"update_time\"]"));
    }

    @Test
    @DisplayName("空 SQL / 非对象配置 / 列声明非法都被拒绝")
    void rejectsBrokenConfig() {
        assertThrows(AiException.class, () -> SqlToolExecutor.requireValidShape(config("  ", "[\"id\"]")));
        assertThrows(AiException.class, () -> SqlToolExecutor.validate("", SCHEMA));
        assertThrows(AiException.class, () -> SqlToolExecutor.validate("[]", SCHEMA));
        assertEquals(true, rejected("select 1 from t", "[]").getMessage().contains("columns"));
        assertEquals(true, rejected("select 1 from t", "[\"1abc\"]").getMessage().contains("列名"));
    }

    @Test
    @DisplayName("maxRows / timeoutSeconds 区间受限")
    void clampsLimits() {
        ObjectNode tooMany = config("select id from t", "[\"id\"]");
        tooMany.put("maxRows", 201);
        assertThrows(AiException.class, () -> SqlToolExecutor.requireValidShape(tooMany));

        ObjectNode tooSlow = config("select id from t", "[\"id\"]");
        tooSlow.put("timeoutSeconds", 16);
        assertThrows(AiException.class, () -> SqlToolExecutor.requireValidShape(tooSlow));

        ObjectNode zero = config("select id from t", "[\"id\"]");
        zero.put("maxRows", 0);
        assertThrows(AiException.class, () -> SqlToolExecutor.requireValidShape(zero));
    }

    // ==================== 具名参数 ====================

    @Test
    @DisplayName("collectParams 收集去重后的具名参数")
    void collectsParams() {
        ObjectNode node = config("select * from t where a = #{status} and b = #{status} and c = #{user.id}",
                "[\"id\"]");
        assertEquals(Set.of("status", "user.id"), SqlToolExecutor.collectParams(node));
    }

    @Test
    @DisplayName("占位符必须是参数结构里声明过的")
    void rejectsUndeclaredPlaceholder() throws Exception {
        String json = """
                {"sql":"select id from astral.sys_user where status = #{status} and name = #{name}",
                 "columns":["id"]}
                """;
        AiException error = assertThrows(AiException.class, () -> SqlToolExecutor.validate(json, SCHEMA));
        assertTrue(error.getMessage().contains("name"));
        // 声明齐全时通过
        String schema = """
                {"type":"object","properties":{"status":{"type":"integer"},"name":{"type":"string"}}}
                """;
        SqlToolExecutor.validate(json, schema);
    }

    @Test
    @DisplayName("toPrepared 把 #{name} 换成 ? 并按出现顺序绑定")
    void rendersPreparedStatement() {
        ObjectNode args = AiJson.mapper().createObjectNode();
        args.put("status", 1);
        args.put("name", "轻听");
        List<Object> params = new ArrayList<>();
        String prepared = SqlToolExecutor.toPrepared(
                "select id from t where status = #{status} and user_name = #{name}", args, params);
        assertEquals("select id from t where status = ? and user_name = ?", prepared);
        // 数字按 BigDecimal 绑定：驱动会按目标列类型转换，避免整数类型边界踩坑
        assertEquals(new BigDecimal("1"), params.get(0));
        assertEquals("轻听", params.get(1));
    }

    @Test
    @DisplayName("缺参或非标量参数直接报错，不会退化成分片拼接")
    void rejectsBadArguments() {
        ObjectNode args = AiJson.mapper().createObjectNode();
        assertTrue(assertThrows(AiException.class,
                () -> SqlToolExecutor.toPrepared("select #{status}", args, new ArrayList<>()))
                .getMessage().contains("缺少参数"));

        ObjectNode nested = AiJson.mapper().createObjectNode();
        nested.set("status", AiJson.read("{\"a\":1}"));
        assertTrue(assertThrows(AiException.class,
                () -> SqlToolExecutor.toPrepared("select #{status}", nested, new ArrayList<>()))
                .getMessage().contains("标量"));
    }

    // ==================== 构造与降级 ====================

    @Test
    @DisplayName("没有 JDBC 数据源时工具不可用（返回 null 而不是抛错）")
    void unavailableWithoutDataSource() {
        AiToolEntity tool = new AiToolEntity();
        tool.setToolCode("query_user");
        tool.setExecutorConfig(CONFIG);
        assertNull(SqlToolExecutor.create(tool, null, null));
    }

    @Test
    @DisplayName("执行配置非法时工具不可用，避免坏配置进入对话")
    void unavailableWithBrokenConfig() {
        AiToolEntity tool = new AiToolEntity();
        tool.setToolCode("query_user");
        tool.setExecutorConfig("""
                {"sql":"delete from astral.sys_user","columns":["id"]}
                """);
        assertNull(SqlToolExecutor.create(tool, new org.springframework.jdbc.core.JdbcTemplate(), null));
    }

    @Test
    @DisplayName("合法配置能构造出执行器，定义回读工具记录")
    void buildsExecutor() {
        AiToolEntity tool = new AiToolEntity();
        tool.setToolCode("query_user");
        tool.setName("查询用户");
        tool.setDescription("按状态查询用户");
        tool.setRiskLevel("READ");
        tool.setApprovalPolicy("NONE");
        tool.setClientTypes("ASTRAL_FRONT");
        tool.setPermissionCodes("admin:system:user:view");
        tool.setArgumentsSchema(SCHEMA);
        tool.setExecutorConfig(CONFIG);
        SqlToolExecutor executor = SqlToolExecutor.create(tool, new org.springframework.jdbc.core.JdbcTemplate(), null);
        assertNotNull(executor);
        assertEquals("query_user", executor.definition().toolCode());
        assertEquals(SqlToolExecutor.HANDLER_KEY, executor.definition().handlerKey());
        assertEquals(List.of("ASTRAL_FRONT"), executor.definition().clientTypes());
    }
}
