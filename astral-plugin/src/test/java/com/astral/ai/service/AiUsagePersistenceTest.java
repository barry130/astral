package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.astral.ai.common.AiException;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.mapper.AiMessageMapper;
import com.astral.ai.mapper.AiQuotaPolicyMapper;
import com.astral.ai.mapper.AiRequestMapper;
import com.astral.ai.mapper.AiUsageMapper;
import com.astral.ai.mapper.AiUserQuotaMapper;
import com.astral.system.service.UserRoleService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real PostgreSQL ledger/transactions, no application start or external model calls. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class AiUsagePersistenceTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private AiRequestService requests;
    private AiUsageService usage;
    private AiModelEntity model;
    private AiAgentEntity agent;
    private AiAgentVersionEntity version;
    private final AiQuotaService.Limits limits =
            new AiQuotaService.Limits(1, 10, null, null, null, null, 1000, 10000, 1000, 3, null, List.of());

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        schema = "usage_audit_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "astral_audit", "");
        jdbc = new JdbcTemplate(source);
        String ddl = Files.readString(Path.of("src/main/resources/sql/ai-schema.sql"));
        jdbc.execute(ddl.substring(0, ddl.indexOf("INSERT INTO sequence_segment")));
        jdbc.execute(Files.readString(Path.of(
                "../astral-server/src/main/resources/db/migration/V20261108001__ai_request_execution_ledger.sql")));
        jdbc.execute(Files.readString(
                Path.of("../astral-server/src/main/resources/db/migration/V20261108005__ai_usage_reconciliation.sql")));
        transactions = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        AtomicLong ids = new AtomicLong(1000);
        GlobalConfigUtils.setGlobalConfig(
                config,
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig())
                        .setMetaObjectHandler(new MetaObjectHandler() {
                            @Override
                            public void insertFill(MetaObject object) {
                                if (getFieldValByName("id", object) == null)
                                    setFieldValByName("id", ids.incrementAndGet(), object);
                                setFieldValByName("createTime", LocalDateTime.now(), object);
                                setFieldValByName("updateTime", LocalDateTime.now(), object);
                            }

                            @Override
                            public void updateFill(MetaObject object) {
                                setFieldValByName("updateTime", LocalDateTime.now(), object);
                            }
                        }));
        config.addMappers("com.astral.ai.mapper");
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        requests = proxy(new AiRequestService(
                session.getMapper(AiRequestMapper.class), session.getMapper(AiMessageMapper.class), transactions));
        var quotas = proxy(new AiQuotaService(
                session.getMapper(AiQuotaPolicyMapper.class),
                session.getMapper(AiUserQuotaMapper.class),
                mock(UserRoleService.class),
                new AiProperties()));
        usage = proxy(new AiUsageService(
                session.getMapper(AiUsageMapper.class), session.getMapper(AiRequestMapper.class), quotas));
        model = new AiModelEntity();
        model.setId(1L);
        model.setProviderId(1L);
        model.setCurrency("CNY");
        model.setInputPrice(BigDecimal.ONE);
        model.setOutputPrice(BigDecimal.ONE);
        agent = new AiAgentEntity();
        agent.setId(1L);
        version = new AiAgentVersionEntity();
        version.setId(1L);
    }

    @AfterEach
    void clean() {
        if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void concurrentFirstReservationsCreateOnePairOfPeriodsAndAdmitOnlyOneRequest() throws Exception {
        var first = requests.create(1L, agent, version, 41L, "ASTRAL_FRONT", "first", "{}", "{}");
        var second = requests.create(1L, agent, version, 41L, "ASTRAL_FRONT", "second", "{}", "{}");
        String one = requests.claim(first.getId(), 60);
        String two = requests.claim(second.getId(), 60);
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> {
                start.await();
                return reserve(first.getId(), one);
            });
            var b = pool.submit(() -> {
                start.await();
                return reserve(second.getId(), two);
            });
            assertEquals(1, a.get(8, TimeUnit.SECONDS) + b.get(8, TimeUnit.SECONDS));
        }
        assertEquals(1, usage.search(41L, 1L, null, null, 1, 100).size());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_quota", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT SUM(request_count) FROM ai_user_quota", Long.class));
    }

    private int reserve(Long id, String token) {
        try {
            usage.reserveStage(id, token, "model-1", model, 100, 100, limits);
            return 1;
        } catch (AiException denied) {
            assertEquals("AI005", denied.getErrorCode());
            return 0;
        }
    }

    @Test
    void nonCnyStageDoesNotPolluteCnyCostLimitsAndInvalidPricesCannotCreateReservations() {
        var request = requests.create(1L, agent, version, 41L, "ASTRAL_FRONT", "currency", "{}", "{}");
        String token = requests.claim(request.getId(), 60);
        model.setCurrency("USD");
        var stage = usage.reserveStage(request.getId(), token, "usd", model, 100, 100, limits);
        usage.settleStage(stage.getId(), 50, 50);
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(jdbc.queryForObject(
                        "SELECT SUM(settled_cost + reserved_cost) FROM ai_user_quota", BigDecimal.class)));
        assertEquals(
                new BigDecimal("0.100000"),
                usage.search(41L, 1L, null, null, 1, 10).getFirst().getSettledAmount());
        model.setInputPrice(new BigDecimal("-1"));
        assertThrows(
                AiException.class,
                () -> usage.reserveStage(request.getId(), token, "invalid", model, 100, 100, limits));
        model.setInputPrice(BigDecimal.ONE);
        model.setCurrency("NOT_CURRENCY");
        assertThrows(
                AiException.class,
                () -> usage.reserveStage(request.getId(), token, "invalid-currency", model, 100, 100, limits));
        assertEquals(1, usage.search(41L, 1L, null, null, 1, 100).size());
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @Test
    void summariesDoNotAddCurrenciesAndBoundaryBelongsOnlyToFollowingDay() {
        jdbc.update(
                "INSERT INTO ai_usage(id,request_id,user_id,agent_id,currency,input_tokens,output_tokens,settled_amount,status,create_time) VALUES "
                        + "(1,1,41,1,'CNY',10,1,1.2,'SETTLED','2026-11-01 12:00:00'),"
                        + "(2,1,41,1,'USD',20,2,2.3,'SETTLED','2026-11-02 00:00:00'),"
                        + "(3,2,41,1,'CNY',30,3,3.4,'SETTLED','2026-11-02 12:00:00')");
        var from = LocalDateTime.of(2026, 11, 1, 0, 0);
        var all = usage.summarize(from, from.plusDays(2), 1L, 41L);
        assertEquals(2, all.requestCount());
        assertEquals(60, all.inputTokens());
        assertNull(all.estimatedCost(), "there is no scalar cost for multiple currencies");
        assertNull(all.currency());
        assertEquals(
                List.of("CNY", "USD"),
                all.costs().stream().map(AiUsageService.CurrencyCost::currency).toList());
        assertEquals(new BigDecimal("4.600000"), all.costs().getFirst().settledAmount());
        assertEquals(new BigDecimal("2.300000"), all.costs().getLast().settledAmount());
        assertEquals(1, all.trend().getFirst().requestCount());
        assertEquals(1, all.trend().getLast().requestCount());
        assertEquals(10, usage.summarize(from, from.plusDays(1), 1L, 41L).inputTokens());
        assertEquals(
                50, usage.summarize(from.plusDays(1), from.plusDays(2), 1L, 41L).inputTokens());
        assertNull(usage.findByRequest(1L).getSettledAmount());
    }

    @Test
    void unknownUsageCanBeReconciledOnceWithActualTokensAndAnAuditableNote() {
        var request = requests.create(1L, agent, version, 41L, "ASTRAL_FRONT", "unknown", "{}", "{}");
        String token = requests.claim(request.getId(), 60);
        var stage = usage.reserveStage(request.getId(), token, "uncertain", model, 100, 100, limits);
        usage.markUnknown(stage.getId());
        assertThrows(AiException.class, () -> usage.reconcile(stage.getId(), 50, 20, 99L, "confirmed fixture bill"));
        requests.cancel(request.getId(), 41L, "fixture", false);
        assertThrows(AiException.class, () -> usage.reconcile(stage.getId(), 50, 20, 99L, " "));
        var result = usage.reconcile(stage.getId(), 50, 20, 99L, "confirmed fixture bill");
        assertEquals("SETTLED", result.getStatus());
        assertEquals(99L, result.getReconciledBy());
        assertEquals("confirmed fixture bill", result.getReconciliationNote());
        assertEquals(new BigDecimal("0.070000"), result.getSettledAmount());
        assertThrows(AiException.class, () -> usage.reconcile(stage.getId(), 0, 0, 99L, "second edit"));
        usage.settleStage(stage.getId(), 200, 200);
        assertEquals(50, usage.findByRequest(request.getId()).getInputTokens());
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(
                        jdbc.queryForObject("SELECT SUM(reserved_cost) FROM ai_user_quota", BigDecimal.class)));
    }
}
