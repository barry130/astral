package com.astral.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.ai.common.AiJson;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.dao.mapper.SysConfigMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.system.service.impl.SysConfigServiceImpl;
import com.astral.system.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.node.ObjectNode;

/** Real public handlers/services/MyBatis; an isolated database interleaves a separately committed write. */
class AdminToolWritePersistenceTest {
    private JdbcTemplate jdbc;
    private UserServiceImpl users;
    private SysConfigServiceImpl configs;
    private AdminToolProvider provider;
    private Runnable afterRead;

    @BeforeEach
    void setUp() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:admin_tools_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        for (String table : new String[] {"sys_user", "sys_config"}) {
            var schema = AiJson.read(
                    Files.readString(Path.of("../astral-schema/src/main/resources/schema/" + table + ".json")));
            var columns = new ArrayList<String>();
            for (var field : schema.path("fields")) {
                columns.add(field.path("columnName").asString() + " "
                        + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                        + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
            }
            jdbc.execute("CREATE TABLE " + table + " (" + String.join(",", columns) + ")");
        }
        jdbc.update(
                "INSERT INTO sys_user(id,username,password,status,user_type,deleted) VALUES(1,'fixture',?,1,'ADMIN',0),(2,'other',?,1,'ADMIN',0)",
                BCrypt.hashpw("Original123"),
                BCrypt.hashpw("Other123"));
        jdbc.update(
                "INSERT INTO sys_config(id,config_key,config_value,config_name,description) VALUES(3,'site.title','Old','Site title','Old description')");
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(SysConfigMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        users = new UserServiceImpl();
        configs = new SysConfigServiceImpl();
        configs.init();
        ReflectionTestUtils.setField(users, "baseMapper", interleave(session.getMapper(UserMapper.class)));
        ReflectionTestUtils.setField(configs, "baseMapper", interleave(session.getMapper(SysConfigMapper.class)));
        provider = new AdminToolProvider(
                users, null, null, configs, null, null, null, null, null, null, null, null, null, null, jdbc, null);
    }

    private Object interleave(Object mapper) {
        var boundary = new ProxyFactory(mapper);
        boundary.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            Object value = invocation.proceed();
            if (afterRead != null
                    && (invocation.getMethod().getName().equals("selectById")
                            || invocation.getMethod().getName().equals("selectList")
                            || invocation.getMethod().getName().equals("selectOne"))) {
                Runnable action = afterRead;
                afterRead = null;
                action.run();
            }
            return value;
        });
        return boundary.getProxy();
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void changingStatusPreservesThePasswordAndAnInterveningProfileEdit() {
        afterRead = () -> jdbc.update(
                "UPDATE sys_user SET password=?,nickname='Concurrent name' WHERE id=1", BCrypt.hashpw("Reset123"));
        try (var sessions = mockStatic(StpUtil.class)) {
            var result = execute(
                    "change_user_status",
                    AiJson.mapper()
                            .createObjectNode()
                            .put("userId", 1)
                            .put("status", 0)
                            .put("reason", "Fixture ban"));
            assertEquals(AiToolResult.SUCCEEDED, result.status());
        }
        var saved = users.getById(1L);
        assertEquals(0, saved.getStatus());
        assertTrue(
                BCrypt.checkpw("Reset123", saved.getPassword()), "status changes must not rehash or restore passwords");
        assertEquals("Concurrent name", saved.getNickname());
        assertEquals("Fixture ban", saved.getStatusReason());
    }

    @Test
    void changingConfigValueDoesNotRevertAnInterveningDescriptionEdit() {
        assertEquals("Old", configs.getConfigValue("site.title"));
        afterRead = () -> jdbc.update("UPDATE sys_config SET description='Concurrent description' WHERE id=3");
        assertEquals(
                AiToolResult.SUCCEEDED,
                execute(
                                "update_config",
                                AiJson.mapper()
                                        .createObjectNode()
                                        .put("configKey", "site.title")
                                        .put("configValue", "New"))
                        .status());
        assertEquals("New", configs.getConfigValue("site.title"));
        assertEquals("Concurrent description", configs.getById(3L).getDescription());
    }

    private AiToolResult execute(String code, ObjectNode arguments) {
        return provider.handlers().stream()
                .filter(handler -> code.equals(handler.definition().toolCode()))
                .findFirst()
                .orElseThrow()
                .execute(new AiToolContext(
                        10L, 11L, 12L, 13L, "fixture", "ADMIN", "ASTRAL_FRONT", arguments, null, true, 12L));
    }
}
