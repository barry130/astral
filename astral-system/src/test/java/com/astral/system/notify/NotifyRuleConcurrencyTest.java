package com.astral.system.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.common.exception.BusinessException;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNotifyRuleChannelMapper;
import com.astral.dao.mapper.SysNotifyRuleMapper;
import com.astral.dao.mapper.SysNotifyRuleRecipientMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.controller.NotifyRuleController;
import com.astral.system.dto.NotifyRuleSaveDto;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import tools.jackson.databind.ObjectMapper;

/** Public rule actions and persistence boundaries; JDBC only schedules real PostgreSQL transactions. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class NotifyRuleConcurrencyTest {
    private JdbcTemplate admin;
    private String schema;
    private String secondApplication;
    private NotifyRuleController controller;
    private SysNotifyRuleChannelMapper channels;
    private SysNotifyRuleRecipientMapper recipients;
    private final ThreadLocal<Boolean> firstCaller = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> secondCaller = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean pauseChannels = new AtomicBoolean();
    private final CountDownLatch channelsRead = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        schema = "notify_rule_" + UUID.randomUUID().toString().replace("-", "");
        secondApplication = schema + "_second";
        admin.execute("CREATE SCHEMA " + schema);
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
        var json = new ObjectMapper();
        for (String table : List.of(
                "sys_notify_rule",
                "sys_notify_rule_channel",
                "sys_notify_rule_recipient",
                "sys_mail_template",
                "sys_sms_template")) {
            var metadata = json.readTree(
                    Files.readString(Path.of("../astral-schema/src/main/resources/schema/" + table + ".json")));
            var columns = new ArrayList<String>();
            for (var field : metadata.path("fields")) {
                columns.add(field.path("columnName").asString() + " "
                        + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                        + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
            }
            jdbc.execute("CREATE TABLE " + table + " (" + String.join(",", columns) + ")");
        }
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        var ids = new AtomicLong(1000);
        GlobalConfigUtils.setGlobalConfig(
                config,
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig())
                        .setMetaObjectHandler(new MetaObjectHandler() {
                            @Override
                            public void insertFill(MetaObject row) {
                                if (getFieldValByName("id", row) == null)
                                    setFieldValByName("id", ids.incrementAndGet(), row);
                            }

                            @Override
                            public void updateFill(MetaObject row) {}
                        }));
        for (var mapper : List.of(
                SysNotifyRuleMapper.class,
                SysNotifyRuleChannelMapper.class,
                SysNotifyRuleRecipientMapper.class,
                SysMailTemplateMapper.class,
                SysSmsTemplateMapper.class)) config.addMapper(mapper);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        channels = session.getMapper(SysNotifyRuleChannelMapper.class);
        recipients = session.getMapper(SysNotifyRuleRecipientMapper.class);
        var rules = new SysNotifyRuleServiceImpl(
                channels,
                recipients,
                session.getMapper(SysMailTemplateMapper.class),
                session.getMapper(SysSmsTemplateMapper.class));
        ReflectionTestUtils.setField(rules, "baseMapper", session.getMapper(SysNotifyRuleMapper.class));
        var transactions = new DataSourceTransactionManager(source);
        var ruleProxy = new ProxyFactory(rules);
        ruleProxy.setProxyTargetClass(true);
        ruleProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var controllerProxy = new ProxyFactory(new NotifyRuleController(
                (SysNotifyRuleService) ruleProxy.getProxy(),
                channels,
                recipients,
                session.getMapper(SysMailTemplateMapper.class),
                session.getMapper(SysSmsTemplateMapper.class),
                null));
        controllerProxy.setProxyTargetClass(true);
        controllerProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        controller = (NotifyRuleController) controllerProxy.getProxy();
    }

    @AfterEach
    void clean() {
        releaseFirst.countDown();
        if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void deletingRuleAlsoRemovesRecipientsAddedByAConcurrentUpdate() throws Exception {
        long id = controller.create(rule(false)).getData();
        pauseChannels.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = executor.submit(() -> {
                firstCaller.set(true);
                try {
                    controller.delete(id);
                } finally {
                    firstCaller.remove();
                }
            });
            try {
                assertTrue(channelsRead.await(5, TimeUnit.SECONDS), "deletion did not read its channel snapshot");
                var update = executor.submit(() -> {
                    secondCaller.set(true);
                    try {
                        controller.update(id, rule(true));
                    } finally {
                        secondCaller.remove();
                    }
                });
                releaseWhenBlockedOrCompleted(update);
                deletion.get(10, TimeUnit.SECONDS);
                try {
                    update.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException failure) {
                    assertEquals("NOTIFY002", ((BusinessException) failure.getCause()).getErrorCode());
                }
            } finally {
                releaseFirst.countDown();
            }
        }
        assertEquals(
                "NOTIFY002",
                assertThrows(BusinessException.class, () -> controller.getById(id))
                        .getErrorCode());
        assertEquals(0, controller.page(1, 10, null, null, null).getData().getTotal());
        assertTrue(channels.selectList(null).isEmpty(), "deleted rule retained channels");
        assertTrue(recipients.selectList(null).isEmpty(), "deleted rule retained a concurrent channel's recipients");
    }

    @Test
    void deletionWaitsForAConcurrentUpdateAndRemovesItsCompleteRecipientSet() throws Exception {
        long id = controller.create(rule(false)).getData();
        pauseChannels.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var update = executor.submit(() -> {
                firstCaller.set(true);
                try {
                    controller.update(id, rule(true));
                } finally {
                    firstCaller.remove();
                }
            });
            try {
                assertTrue(channelsRead.await(5, TimeUnit.SECONDS), "update did not read its channel snapshot");
                var deletion = executor.submit(() -> {
                    secondCaller.set(true);
                    try {
                        controller.delete(id);
                    } finally {
                        secondCaller.remove();
                    }
                });
                releaseWhenBlockedOrCompleted(deletion);
                update.get(10, TimeUnit.SECONDS);
                deletion.get(10, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
        }
        assertEquals(
                "NOTIFY002",
                assertThrows(BusinessException.class, () -> controller.getById(id))
                        .getErrorCode());
        assertTrue(channels.selectList(null).isEmpty());
        assertTrue(recipients.selectList(null).isEmpty());
    }

    private NotifyRuleSaveDto rule(boolean withEmail) {
        var dto = new NotifyRuleSaveDto();
        dto.setRuleName("Concurrent rule");
        dto.setEventCode("systemAlert");
        var inapp = new NotifyRuleSaveDto.ChannelDto();
        inapp.setChannel("INAPP");
        inapp.setRecipients(List.of(recipient("41")));
        if (withEmail) {
            var email = new NotifyRuleSaveDto.ChannelDto();
            email.setChannel("EMAIL");
            email.setRecipients(List.of(recipient("fixture@example.invalid")));
            dto.setChannels(List.of(inapp, email));
        } else {
            dto.setChannels(List.of(inapp));
        }
        return dto;
    }

    private NotifyRuleSaveDto.RecipientDto recipient(String value) {
        var recipient = new NotifyRuleSaveDto.RecipientDto();
        recipient.setType("FIXED");
        recipient.setValue(value);
        return recipient;
    }

    private void releaseWhenBlockedOrCompleted(Future<?> update) throws Exception {
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!update.isDone() && !waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(admin.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock')",
                        Boolean.class,
                        secondApplication));
                if (!waiting) Thread.sleep(10);
            }
            assertTrue(
                    update.isDone() || waiting, "concurrent update neither completed nor waited for a database lock");
        } finally {
            releaseFirst.countDown();
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
                                if (firstCaller.get()
                                        && (statementMethod.getName().equals("execute")
                                                || statementMethod.getName().equals("executeQuery"))
                                        && normalized.startsWith("select")
                                        && normalized.contains("from sys_notify_rule_channel")
                                        && pauseChannels.compareAndSet(true, false)) {
                                    channelsRead.countDown();
                                    if (!releaseFirst.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("channel snapshot was not released");
                                }
                                return value;
                            });
                });
    }
}
