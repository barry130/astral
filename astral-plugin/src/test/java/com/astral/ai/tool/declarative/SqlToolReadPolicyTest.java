package com.astral.ai.tool.declarative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class SqlToolReadPolicyTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "select password as name from astral.sys_user",
                "select \"password\" as name from \"astral\".\"sys_user\"",
                "select id from astral.sys_user where password = 'probe'",
                "select id from astral.sys_user order by password",
                "with x as (select password as name from astral.sys_user) select name from x",
                "select id from astral.sys_user union all select password from astral.sys_user",
                "select (select encrypted_api_key from astral.ai_provider limit 1) as name",
                "select pg_read_file('/synthetic-fixture') as name",
                "select public.count(id) as name from astral.sys_user",
                "select set_config('application_name','synthetic',true) as name",
                "select row_to_json(u) as name from astral.sys_user u",
                "select u as name from astral.sys_user u",
                "select * from astral.sys_user",
                "select u.* from astral.sys_user u",
                "select name from json_to_record('{}') as x(name text)",
                "select rolpassword as name from pg_catalog.pg_authid",
                "select id as password from astral.sys_user order by length(password)",
                "select id from astral.sys_user where name like 'x' escape password",
                "select count(*) from astral.sys_user group by password",
                "select count(*) from astral.sys_user having min(password) <> ''",
                "select id from astral.sys_user u join astral.sys_user v on u.password=v.password",
                "select \"ID\" from astral.sys_user",
                "select (select password from astral.sys_user limit 1) as name from (select 'safe' as password) x",
                "select a[(select length(password) from astral.sys_user limit 1)] as name from (select '{1,2}'::integer[] as a) x",
                "select name as value from astral.sys_user name"
            })
    void rejectsSensitiveSourcesAndUnapprovedFunctionsBeforeAnyConnection(String sql) {
        assertThrows(AiException.class, () -> SqlToolExecutor.validate(config(sql, "name"), "{}"));
    }

    @Test
    void trustedBusinessExtensionChecksSourceColumnsAndSafeAliasesAcrossJoinAndUnion() {
        AiProperties properties = new AiProperties();
        properties
                .getSqlTool()
                .getAllowedColumns()
                .put("astral.fixture_sales", java.util.List.of("id", "user_id", "amount"));
        String sql = "with totals as (select user_id, sum(amount) as total from astral.fixture_sales group by user_id) "
                + "select u.id, t.total as value from astral.sys_user u join totals t on t.user_id=u.id "
                + "union all select id, 0 as value from astral.sys_user order by value";
        SqlToolExecutor.validate(config(sql, "value"), "{}", properties);
        assertThrows(
                AiException.class,
                () -> SqlToolExecutor.validate(
                        config("select hidden_cost as amount from astral.fixture_sales", "amount"), "{}", properties));
        assertThrows(AiException.class, () -> SqlToolExecutor.validate(config(sql, "value"), "{}"));
        SqlToolExecutor.validate(
                config(
                        "select u.id from astral.sys_user u where exists (select 1 from astral.sys_user v where v.id=u.id)",
                        "id"),
                "{}");
    }

    @Test
    void unqualifiedTableReadsAuthorizedSchemaInsteadOfSearchPathShadow() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:sql_shadow_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        try {
            jdbc.execute("CREATE SCHEMA astral");
            jdbc.execute("CREATE TABLE astral.sys_user(id BIGINT,nickname VARCHAR(64))");
            jdbc.execute("CREATE TABLE public.sys_user(id BIGINT,nickname VARCHAR(64))");
            jdbc.update("INSERT INTO astral.sys_user VALUES (1,'authorized')");
            jdbc.update("INSERT INTO public.sys_user VALUES (1,'shadow-must-not-be-returned')");
            AiToolEntity tool = new AiToolEntity();
            tool.setToolCode("fixture_shadow");
            tool.setExecutorConfig(config("select nickname as name from sys_user", "name"));
            var executor = SqlToolExecutor.create(tool, jdbc, new AiProperties());
            assertNotNull(executor);
            var result = executor.execute(new AiToolContext(
                    1L,
                    1L,
                    1L,
                    1L,
                    "fixture",
                    "CUSTOM",
                    "ASTRAL_FRONT",
                    AiJson.mapper().createObjectNode(),
                    AiJson.mapper().createObjectNode(),
                    false,
                    null));
            assertEquals("SUCCEEDED", result.status());
            assertEquals(
                    "authorized",
                    result.payload().path("rows").get(0).path("name").asString());
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void executesDocumentedSafeQuotedQueryCteAndCountWithBoundArguments() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:sql_policy_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        try {
            jdbc.execute("CREATE SCHEMA astral");
            jdbc.execute(
                    "CREATE TABLE astral.sys_user(id BIGINT,nickname VARCHAR(64),status INTEGER,password VARCHAR(128))");
            jdbc.update("INSERT INTO astral.sys_user VALUES (1,'Fixture',1,'must-never-leave-database')");
            String sql =
                    "with active as (select \"id\" as id, nickname as name from astral.sys_user where status=#{status}) select count(*) as total from active";
            String config = config(sql, "total");
            SqlToolExecutor.validate(
                    config, "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"integer\"}}}");
            AiToolEntity tool = new AiToolEntity();
            tool.setToolCode("fixture_count");
            tool.setExecutorConfig(config);
            var executor = SqlToolExecutor.create(tool, jdbc, new AiProperties());
            assertNotNull(executor);
            var result = executor.execute(new AiToolContext(
                    1L,
                    1L,
                    1L,
                    1L,
                    "fixture",
                    "CUSTOM",
                    "ASTRAL_FRONT",
                    AiJson.readObject("{\"status\":1}"),
                    AiJson.mapper().createObjectNode(),
                    false,
                    null));
            assertEquals("SUCCEEDED", result.status());
            assertEquals(1, result.payload().path("rows").get(0).path("total").asInt());
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
            jdbc.execute("SHUTDOWN");
        }
    }

    private static String config(String sql, String column) {
        var json = AiJson.mapper().createObjectNode();
        json.put("sql", sql);
        json.putArray("columns").add(column);
        return AiJson.write(json);
    }
}
