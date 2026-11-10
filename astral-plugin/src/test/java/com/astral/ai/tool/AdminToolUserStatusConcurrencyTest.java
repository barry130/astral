package com.astral.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.ai.common.AiJson;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.common.exception.BusinessException;
import com.astral.common.util.SqlIdentifiers;
import com.astral.dao.mapper.UserMapper;
import com.astral.system.controller.UserController;
import com.astral.system.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Public actions, real PostgreSQL transactions; JDBC only schedules two independent callers. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class AdminToolUserStatusConcurrencyTest {
    private JdbcTemplate admin;
    private String schema;
    private String secondApplication;
    private UserServiceImpl users;
    private AdminToolProvider provider;
    private UserController controller;
    private final CountDownLatch firstCount = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final AtomicBoolean pauseCount = new AtomicBoolean(true);
    private final ThreadLocal<Boolean> secondCaller = ThreadLocal.withInitial(() -> false);

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        schema = "user_status_" + UUID.randomUUID().toString().replace("-", "");
        secondApplication = schema + "_second";
        admin.execute(String.format("CREATE SCHEMA %s", SqlIdentifiers.requireSafe(schema)));
        var database = new DriverManagerDataSource(url + "?currentSchema=" + schema, "astral_audit", "");
        var source = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return schedule(database.getConnection());
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return schedule(database.getConnection(username, password));
            }
        };
        var jdbc = new JdbcTemplate(source);
        var metadata =
                AiJson.read(Files.readString(Path.of("../astral-schema/src/main/resources/schema/sys_user.json")));
        var columns = new ArrayList<String>();
        for (var field : metadata.path("fields")) {
            columns.add(field.path("columnName").asString() + " "
                    + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                    + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
        }
        jdbc.execute(String.format("CREATE TABLE sys_user (%s)", String.join(",", columns)));
        jdbc.update(
                "INSERT INTO sys_user(id,username,status,user_type,deleted) VALUES(1,'first',1,'ADMIN',0),(2,'second',1,'ADMIN',0)");
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(UserMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", session.getMapper(UserMapper.class));
        var transaction = new ProxyFactory(service);
        transaction.setProxyTargetClass(true);
        transaction.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        users = (UserServiceImpl) transaction.getProxy();
        provider = new AdminToolProvider(
                users, null, null, null, null, null, null, null, null, null, null, null, null, null, jdbc, null);
        controller = new UserController(users, null, null, null, null);
    }

    @AfterEach
    void clean() {
        releaseFirst.countDown();
        if (schema != null) admin.execute(String.format("DROP SCHEMA %s CASCADE", SqlIdentifiers.requireSafe(schema)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentStatusActionsKeepAnAvailableAdministrator(boolean secondUsesController) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> disable(1L, false));
            assertTrue(firstCount.await(5, TimeUnit.SECONDS), "first action must have observed both active admins");
            var second = executor.submit(() -> {
                secondCaller.set(true);
                try {
                    return disable(2L, secondUsesController);
                } finally {
                    secondCaller.remove();
                }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (!second.isDone() && !waiting && System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(admin.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock')",
                            Boolean.class,
                            secondApplication));
                    if (!waiting) Thread.sleep(10);
                }
                assertTrue(second.isDone() || waiting, "second action must finish or wait on the shared database lock");
            } finally {
                releaseFirst.countDown();
            }
            assertEquals(AiToolResult.SUCCEEDED, first.get(10, TimeUnit.SECONDS));
            assertEquals(AiToolResult.REJECTED, second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, users.getById(1L).getStatus());
        assertEquals(1, users.getById(2L).getStatus());
    }

    private String disable(long userId, boolean throughController) {
        try (var sessions = mockStatic(StpUtil.class)) {
            if (throughController) {
                try {
                    controller.changeStatus(userId, 0, "Fixture ban");
                    return AiToolResult.SUCCEEDED;
                } catch (BusinessException expected) {
                    assertTrue(expected.getMessage().contains("管理员"));
                    return AiToolResult.REJECTED;
                }
            }
            var arguments = AiJson.mapper()
                    .createObjectNode()
                    .put("userId", userId)
                    .put("status", 0)
                    .put("reason", "Fixture ban");
            return provider.handlers().stream()
                    .filter(handler ->
                            "change_user_status".equals(handler.definition().toolCode()))
                    .findFirst()
                    .orElseThrow()
                    .execute(new AiToolContext(
                            10L, 11L, 12L, 13L, "fixture", "ADMIN", "ASTRAL_FRONT", arguments, null, true, 12L))
                    .status();
        }
    }

    private Connection schedule(Connection connection) throws SQLException {
        if (secondCaller.get()) connection.setClientInfo("ApplicationName", secondApplication);
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (!(result instanceof Statement statement)) return result;
                    String preparedSql = args != null && args.length > 0 && args[0] instanceof String sql ? sql : null;
                    Class<?> statementType =
                            statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {statementType},
                            (statementProxy, statementMethod, statementArgs) -> {
                                Object value;
                                try {
                                    value = statementMethod.invoke(statement, statementArgs);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                                String sql = preparedSql != null
                                        ? preparedSql
                                        : statementArgs != null
                                                        && statementArgs.length > 0
                                                        && statementArgs[0] instanceof String text
                                                ? text
                                                : "";
                                String normalized = sql.toLowerCase(Locale.ROOT);
                                if ((statementMethod.getName().equals("executeQuery")
                                                || statementMethod.getName().equals("execute"))
                                        && normalized.contains("count(")
                                        && normalized.contains("sys_user")
                                        && pauseCount.compareAndSet(true, false)) {
                                    firstCount.countDown();
                                    if (!releaseFirst.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("first action was not released");
                                }
                                return value;
                            });
                });
    }
}
