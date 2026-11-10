package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.config.AiProperties;
import com.astral.ai.controller.AiAdminAgentController;
import com.astral.ai.mapper.AiAgentKnowledgeMapper;
import com.astral.ai.mapper.AiAgentMapper;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiAgentVersionMapper;
import com.astral.ai.mapper.AiKnowledgeChunkMapper;
import com.astral.ai.mapper.AiKnowledgeDocumentMapper;
import com.astral.ai.mapper.AiKnowledgeIndexJobMapper;
import com.astral.ai.mapper.AiKnowledgeSourceMapper;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiModelMapper;
import com.astral.ai.mapper.AiPromptTemplateMapper;
import com.astral.ai.mapper.AiPromptVersionMapper;
import com.astral.ai.mapper.AiProviderMapper;
import com.astral.ai.mapper.AiProviderRouteMapper;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.provider.AiProviderRegistry;
import com.astral.ai.security.AiSecretCipher;
import com.astral.common.exception.BusinessException;
import com.astral.common.util.SqlIdentifiers;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.mapping.Environment;
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

/** Public services and real PostgreSQL transactions; only the read boundary is scheduled by the test. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class AiAgentConcurrencyTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private AiAgentService service;
    private AiRouteService routes;
    private Runnable afterRead;
    private Runnable beforeRead;
    private Runnable afterValidation;
    private AiAdminAgentController controller;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        schema = "agent_audit_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute(String.format("CREATE SCHEMA %s", SqlIdentifiers.requireSafe(schema)));
        var dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema, "astral_audit", "");
        jdbc = new JdbcTemplate(dataSource);
        String ddl = Files.readString(Path.of("src/main/resources/sql/ai-schema.sql"));
        jdbc.execute(ddl.substring(0, ddl.indexOf("INSERT INTO sequence_segment")));
        jdbc.execute(Files.readString(Path.of(
                "../astral-server/src/main/resources/db/migration/V20261108004__knowledge_bounded_search.sql")));
        jdbc.update(
                "INSERT INTO ai_agent(id,agent_code,name,agent_type,status,client_types) VALUES(1,'fixture','Fixture','CUSTOM','DRAFT','ASTRAL_FRONT')");
        jdbc.update(
                "INSERT INTO ai_provider_route(id,route_code,name,provider_id,model_id) VALUES(3,'a','A',4,5),(6,'b','B',4,5)");
        jdbc.update(
                "INSERT INTO ai_agent_version(id,agent_id,version_no,version_name,status,provider_route_id) VALUES(2,1,1,'Original','DRAFT',3)");
        jdbc.update(
                "INSERT INTO ai_provider(id,provider_code,name,provider_type,enabled,base_url) VALUES(4,'fixture','Fixture','MOCK',true,'mock://local')");
        jdbc.update(
                "INSERT INTO ai_model(id,provider_id,model_code,model_name,enabled) VALUES(5,4,'fixture','Fixture',true)");
        jdbc.update(
                "INSERT INTO ai_prompt_version(id,template_id,version_no,status,system_prompt) VALUES(7,1,1,'PUBLISHED','Fixture system prompt')");
        jdbc.update("UPDATE ai_agent_version SET system_prompt_version_id=7 WHERE id=2");
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.addMappers("com.astral.ai.mapper");
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var boundary = new ProxyFactory(session.getMapper(AiAgentVersionMapper.class));
        boundary.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> {
            if (beforeRead != null && call.getMethod().getName().equals("selectById")) beforeRead.run();
            Object result = call.proceed();
            if (call.getMethod().getName().equals("selectById") && afterRead != null) {
                Runnable action = afterRead;
                afterRead = null;
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    executor.submit(action).get(10, TimeUnit.SECONDS);
                }
            }
            return result;
        });
        var transactions = new DataSourceTransactionManager(dataSource);
        service = transactional(
                new AiAgentService(
                        session.getMapper(AiAgentMapper.class),
                        (AiAgentVersionMapper) boundary.getProxy(),
                        session.getMapper(AiAgentToolMapper.class),
                        session.getMapper(AiAgentKnowledgeMapper.class)),
                transactions);
        var properties = new AiProperties();
        var providers = new AiProviderService(
                session.getMapper(AiProviderMapper.class),
                session.getMapper(AiModelMapper.class),
                session.getMapper(AiProviderRouteMapper.class),
                new AiProviderRegistry(java.util.List.of()),
                new AiSecretCipher(properties));
        routes = transactional(
                new AiRouteService(
                        session.getMapper(AiProviderRouteMapper.class),
                        session.getMapper(AiModelMapper.class),
                        providers),
                transactions);
        var tools = transactional(
                new AiToolService(
                        session.getMapper(AiToolMapper.class),
                        session.getMapper(AiAgentToolMapper.class),
                        java.util.List.of(),
                        properties,
                        jdbc,
                        session.getMapper(AiMcpServerMapper.class),
                        null,
                        session.getMapper(AiToolCallMapper.class)),
                transactions);
        var knowledge = transactional(
                new AiKnowledgeService(
                        session.getMapper(AiKnowledgeSourceMapper.class),
                        session.getMapper(AiKnowledgeDocumentMapper.class),
                        session.getMapper(AiKnowledgeChunkMapper.class),
                        session.getMapper(AiKnowledgeIndexJobMapper.class),
                        session.getMapper(AiAgentKnowledgeMapper.class),
                        transactions),
                transactions);
        var prompts = new AiPromptService(
                session.getMapper(AiPromptTemplateMapper.class), session.getMapper(AiPromptVersionMapper.class));
        var validation = new ProxyFactory(new AiAgentValidationService(
                service,
                prompts,
                tools,
                knowledge,
                routes,
                new AiModelService(session.getMapper(AiModelMapper.class), providers),
                providers,
                null,
                properties));
        validation.setProxyTargetClass(true);
        validation.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("requireValid") && afterValidation != null)
                afterValidation.run();
            return result;
        });
        controller = transactional(
                new AiAdminAgentController(
                        service, (AiAgentValidationService) validation.getProxy(), tools, knowledge, routes, prompts),
                transactions);
    }

    @AfterEach
    void clean() {
        if (schema != null) admin.execute(String.format("DROP SCHEMA %s CASCADE", SqlIdentifiers.requireSafe(schema)));
    }

    @Test
    void omittedRouteCannotRestoreAConcurrentlyRemovedReference() {
        afterRead = () -> {
            edit(null, 6L);
            routes.delete(3L);
        };
        edit("Renamed", null);
        var version = service.requireVersion(2L);
        assertEquals(6L, version.getProviderRouteId());
        assertEquals("Renamed", version.getVersionName());
    }

    @Test
    void publicationWinsAgainstAnEditThatReadTheOldDraft() {
        afterRead = () -> service.publish(2L, 9L);
        assertThrows(BusinessException.class, () -> edit("Late edit", null));
        var version = service.requireVersion(2L);
        assertEquals("PUBLISHED", version.getStatus());
        assertEquals("Original", version.getVersionName());
    }

    @Test
    void publicationDoesNotRestoreAStaleRouteOrName() {
        afterRead = () -> {
            edit("Current", 6L);
            routes.delete(3L);
        };
        service.publish(2L, 9L);
        var version = service.requireVersion(2L);
        assertEquals(6L, version.getProviderRouteId());
        assertEquals("Current", version.getVersionName());
        assertEquals("PUBLISHED", version.getStatus());
    }

    @Test
    void toolReplacementReadBeforePublicationCannotAlterTheFrozenBindings() {
        jdbc.update("INSERT INTO ai_agent_tool(id,agent_version_id,tool_id,enabled) VALUES(20,2,99,true)");
        afterRead = () -> service.publish(2L, 9L);
        assertThrows(
                com.astral.ai.common.AiException.class,
                () -> controller.replaceTools(
                        2L,
                        java.util.Map.of("toolIds", java.util.List.of()),
                        new org.springframework.mock.web.MockHttpServletRequest()));
        assertEquals(
                1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_tool WHERE agent_version_id=2", Integer.class));
    }

    @Test
    void knowledgeReplacementReadBeforePublicationCannotAlterTheFrozenBindings() {
        jdbc.update(
                "INSERT INTO ai_agent_knowledge(id,agent_version_id,knowledge_source_id,enabled) VALUES(20,2,99,true)");
        afterRead = () -> service.publish(2L, 9L);
        assertThrows(
                BusinessException.class,
                () -> controller.replaceKnowledge(
                        2L, java.util.List.of(), new org.springframework.mock.web.MockHttpServletRequest()));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_knowledge WHERE agent_version_id=2", Integer.class));
    }

    @Test
    void publicationValidatesTheCurrentLockedVersion() {
        jdbc.update("UPDATE ai_provider_route SET enabled=false WHERE id=6");
        afterRead = () -> edit(null, 6L);
        assertThrows(
                com.astral.ai.common.AiException.class,
                () -> controller.publishVersion(2L, new org.springframework.mock.web.MockHttpServletRequest()));
        assertEquals("DRAFT", service.requireVersion(2L).getStatus());
        assertEquals(6L, service.requireVersion(2L).getProviderRouteId());
    }

    @Test
    void validatedSnapshotRemainsLockedThroughPublicPublication() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var completion = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            afterValidation = () -> {
                beforeRead = entered::countDown;
                completion.set(executor.submit(() -> assertThrows(BusinessException.class, () -> edit("Late", null))));
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
                assertFalse(completion.get().isDone(), "draft edit must wait until publication commits");
            };
            controller.publishVersion(2L, new org.springframework.mock.web.MockHttpServletRequest());
            completion.get().get(10, TimeUnit.SECONDS);
        }
        assertEquals("PUBLISHED", service.requireVersion(2L).getStatus());
        assertEquals("Original", service.requireVersion(2L).getVersionName());
    }

    private void edit(String name, Long route) {
        service.updateDraftVersion(2L, name, route, null, null, null, null, null, null, null, null, 8L);
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target, DataSourceTransactionManager transactions) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
