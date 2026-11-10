package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpInterface;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.controller.AiAdminToolController;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiConversationEntity;
import com.astral.ai.mapper.AiAgentKnowledgeMapper;
import com.astral.ai.mapper.AiAgentMapper;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiAgentVersionMapper;
import com.astral.ai.mapper.AiConversationMapper;
import com.astral.ai.mapper.AiKnowledgeChunkMapper;
import com.astral.ai.mapper.AiKnowledgeDocumentMapper;
import com.astral.ai.mapper.AiKnowledgeIndexJobMapper;
import com.astral.ai.mapper.AiKnowledgeSourceMapper;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiMessageMapper;
import com.astral.ai.mapper.AiModelMapper;
import com.astral.ai.mapper.AiPromptTemplateMapper;
import com.astral.ai.mapper.AiPromptVersionMapper;
import com.astral.ai.mapper.AiProviderMapper;
import com.astral.ai.mapper.AiProviderRouteMapper;
import com.astral.ai.mapper.AiQuotaPolicyMapper;
import com.astral.ai.mapper.AiRequestEventMapper;
import com.astral.ai.mapper.AiRequestMapper;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.mapper.AiUsageMapper;
import com.astral.ai.mapper.AiUserQuotaMapper;
import com.astral.ai.provider.AiProvider;
import com.astral.ai.provider.AiProviderDtos.ChatRequest;
import com.astral.ai.provider.AiProviderDtos.ChatResult;
import com.astral.ai.provider.AiProviderDtos.HealthResult;
import com.astral.ai.provider.AiProviderDtos.StreamListener;
import com.astral.ai.provider.AiProviderDtos.ToolCall;
import com.astral.ai.provider.AiProviderRegistry;
import com.astral.ai.security.AiSecretCipher;
import com.astral.ai.security.AiToolPermissionGate;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.auth.security.PermissionChecker;
import com.astral.common.util.SqlIdentifiers;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.reflection.MetaObject;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real public orchestration, persistence and transactions; only provider/tool/auth data are fixtures. */
class AiApprovalLifecycleTest {
    private final ScriptedProvider provider = new ScriptedProvider();
    private final AtomicInteger executions = new AtomicInteger();
    private JdbcTemplate jdbc;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private AiChatOrchestrator orchestrator;
    private AiRequestService requests;
    private AiConversationService conversations;
    private AiQuotaService quotas;
    private AiUsageService usage;
    private AiAdminToolController approvals;
    private AiAgentEntity agent;
    private AiAgentVersionEntity version;
    private AiConversationEntity conversation;
    private Runnable toolEffect = () -> {};
    private Runnable afterReservation = () -> {};
    private Runnable afterOwnershipCheck = () -> {};
    private final AtomicInteger toolPersistenceFailures = new AtomicInteger();
    private AiModelService models;
    private AiRouteService routes;
    private boolean automaticTool;
    private AiProperties properties;
    private AiToolService tools;
    private com.astral.system.service.impl.UserRoleServiceImpl domainRoles;
    private com.astral.ai.tool.AdminToolProvider adminTools;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:lifecycle_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        AtomicLong ids = new AtomicLong(1000);
        GlobalConfigUtils.setGlobalConfig(
                configuration,
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig())
                        .setMetaObjectHandler(new MetaObjectHandler() {
                            @Override
                            public void insertFill(MetaObject object) {
                                if (getFieldValByName("id", object) == null) {
                                    setFieldValByName("id", ids.incrementAndGet(), object);
                                }
                                setFieldValByName("createTime", LocalDateTime.now(), object);
                                setFieldValByName("updateTime", LocalDateTime.now(), object);
                            }

                            @Override
                            public void updateFill(MetaObject object) {
                                setFieldValByName("updateTime", LocalDateTime.now(), object);
                            }
                        }));
        configuration.addMappers("com.astral.ai.mapper");
        configuration.addMapper(com.astral.dao.mapper.UserMapper.class);
        configuration.addMapper(com.astral.dao.mapper.RoleMapper.class);
        configuration.addMapper(com.astral.dao.mapper.UserRoleMapper.class);
        configuration.addMapper(com.astral.dao.mapper.PermissionMapper.class);
        configuration.addMapper(com.astral.dao.mapper.RolePermissionMapper.class);
        configuration.addMapper(com.astral.dao.mapper.SysNotifyRuleMapper.class);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        try (var schemas = Files.list(Path.of("src/main/resources/schema"))) {
            for (Path schema : schemas.filter(
                            path -> path.getFileName().toString().startsWith("ai_"))
                    .toList()) {
                var definition = AiJson.read(Files.readString(schema));
                List<String> fields = new ArrayList<>();
                for (var field : definition.path("fields")) {
                    fields.add(field.path("columnName").asString() + " "
                            + field.path("jdbcType").asString()
                            + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
                }
                jdbc.execute(String.format(
                        "CREATE TABLE %s (%s)",
                        SqlIdentifiers.requireSafe(definition.path("tableName").asString()), String.join(",", fields)));
            }
        }
        for (String table : List.of(
                "sys_user", "sys_role", "sys_user_role", "sys_permission", "sys_role_permission", "sys_notify_rule")) {
            var definition = AiJson.read(
                    Files.readString(Path.of("../astral-schema/src/main/resources/schema/" + table + ".json")));
            List<String> fields = new ArrayList<>();
            for (var field : definition.path("fields"))
                fields.add(field.path("columnName").asString() + " "
                        + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                        + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
            jdbc.execute(
                    String.format("CREATE TABLE %s (%s)", SqlIdentifiers.requireSafe(table), String.join(",", fields)));
        }
        jdbc.update(
                "INSERT INTO sys_user(id,username,user_type,status,deleted) VALUES(41,'operator','ADMIN',1,0),(42,'target','ADMIN',1,0)");
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,status,is_super) VALUES(1,'one',1,0),(2,'two',1,0),(3,'three',1,0)");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES(51,42,1)");
        var rules = new com.astral.system.notify.SysNotifyRuleServiceImpl(null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(
                rules, "baseMapper", map(com.astral.dao.mapper.SysNotifyRuleMapper.class));
        var publisher = new com.astral.system.notify.NotifyPublisher(
                rules, null, null, null, null, null, null, null, null, null, null, null);
        var roleRows = map(com.astral.dao.mapper.RoleMapper.class);
        domainRoles = new com.astral.system.service.impl.UserRoleServiceImpl(
                roleRows,
                new com.astral.system.notify.UserRoleChangeNotifier(roleRows, publisher),
                new com.astral.auth.security.PermissionCache(),
                map(com.astral.dao.mapper.UserMapper.class),
                map(com.astral.dao.mapper.RolePermissionMapper.class),
                map(com.astral.dao.mapper.PermissionMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(
                domainRoles, "baseMapper", map(com.astral.dao.mapper.UserRoleMapper.class));
        domainRoles = proxy(domainRoles);
        var users = new com.astral.system.service.impl.UserServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(
                users, "baseMapper", map(com.astral.dao.mapper.UserMapper.class));
        adminTools = new com.astral.ai.tool.AdminToolProvider(
                users, null, domainRoles, null, null, null, null, null, null, null, null, null, null, null, jdbc, null);
        jdbc.execute("CREATE UNIQUE INDEX request_key ON ai_request(user_id, agent_id, idempotency_key)");
        jdbc.execute("CREATE UNIQUE INDEX event_sequence ON ai_request_event(request_id, sequence_no)");
        // Existing rows exercise real atomic quota SQL without replacing PostgreSQL advisory-lock semantics.
        jdbc.update(
                "INSERT INTO ai_user_quota(id,user_id,agent_id,period_type,period_start,request_count,input_tokens,output_tokens,reserved_cost,settled_cost,version) VALUES(1,41,1,'DAY',CURRENT_DATE,0,0,0,0,0,0)");
        jdbc.update(
                "INSERT INTO ai_user_quota(id,user_id,agent_id,period_type,period_start,request_count,input_tokens,output_tokens,reserved_cost,settled_cost,version) VALUES(2,41,1,'MONTH',?,0,0,0,0,0,0)",
                java.time.LocalDate.now().withDayOfMonth(1));
        jdbc.update(
                "INSERT INTO ai_agent(id,agent_code,name,agent_type,status,allow_user_context) VALUES(1,'fixture','Fixture','CUSTOM','PUBLISHED',false)");
        jdbc.update(
                "INSERT INTO ai_agent_version(id,agent_id,version_no,provider_route_id,context_policy_json) VALUES(2,1,1,3,'{}')");
        jdbc.update(
                "INSERT INTO ai_provider(id,provider_code,name,provider_type,enabled,base_url) VALUES(4,'fixture','Fixture','FIXTURE',true,'mock://local')");
        jdbc.update(
                "INSERT INTO ai_model(id,provider_id,model_code,model_name,enabled,input_price,output_price,currency,max_output_tokens) VALUES(5,4,'fixture','Fixture',true,1,2,'CNY',128)");
        jdbc.update("INSERT INTO ai_provider_route(id,provider_id,model_id,enabled) VALUES(3,4,5,true)");
        jdbc.update(
                "INSERT INTO ai_tool(id,tool_code,name,handler_key,executor_type,risk_level,approval_policy,status) VALUES(6,'fixture_tool','Fixture tool','fixture_tool','CODE','DRAFT','USER_CONFIRM','ENABLED')");
        jdbc.update("INSERT INTO ai_agent_tool(id,agent_version_id,tool_id,enabled) VALUES(7,2,6,true)");
        properties = new AiProperties();
        StpInterface permissions = new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return List.of("*:*:*");
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return List.of();
            }
        };
        var gate = new AiToolPermissionGate(new PermissionChecker(permissions));
        var agents = proxy(new AiAgentService(
                map(AiAgentMapper.class),
                map(AiAgentVersionMapper.class),
                map(AiAgentToolMapper.class),
                map(AiAgentKnowledgeMapper.class)));
        requests = proxy(new AiRequestService(map(AiRequestMapper.class), map(AiMessageMapper.class), transactions));
        var ownershipBoundary = new ProxyFactory(requests);
        ownershipBoundary.setProxyTargetClass(true);
        ownershipBoundary.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("owns") && Boolean.TRUE.equals(result))
                afterOwnershipCheck.run();
            return result;
        });
        requests = (AiRequestService) ownershipBoundary.getProxy();
        conversations = proxy(new AiConversationService(map(AiConversationMapper.class), map(AiMessageMapper.class)));
        var events = new AiEventService(map(AiRequestEventMapper.class));
        UserRoleService roles = mock(UserRoleService.class);
        when(roles.list(org.mockito.ArgumentMatchers
                        .<com.baomidou.mybatisplus.core.conditions.Wrapper<com.astral.dao.entity.UserRole>>any()))
                .thenReturn(List.of());
        quotas = proxy(
                new AiQuotaService(map(AiQuotaPolicyMapper.class), map(AiUserQuotaMapper.class), roles, properties));
        usage = proxy(new AiUsageService(map(AiUsageMapper.class), map(AiRequestMapper.class), quotas));
        ProxyFactory reservationBoundary = new ProxyFactory(usage);
        reservationBoundary.setProxyTargetClass(true);
        reservationBoundary.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("reserveStage")) afterReservation.run();
            return result;
        });
        usage = (AiUsageService) reservationBoundary.getProxy();
        var registry = new AiProviderRegistry(List.of(provider));
        var providers = new AiProviderService(
                map(AiProviderMapper.class),
                map(AiModelMapper.class),
                map(AiProviderRouteMapper.class),
                registry,
                new AiSecretCipher(properties));
        tools = new AiToolService(
                map(AiToolMapper.class),
                map(AiAgentToolMapper.class),
                List.of(() -> List.of(new FixtureTool()), adminTools),
                properties,
                jdbc,
                mock(AiMcpServerMapper.class),
                gate,
                map(AiToolCallMapper.class));
        tools.init();
        models = proxy(new AiModelService(map(AiModelMapper.class), providers));
        routes = proxy(new AiRouteService(map(AiProviderRouteMapper.class), map(AiModelMapper.class), providers));
        var toolMapperBoundary = new ProxyFactory(map(AiToolCallMapper.class));
        toolMapperBoundary.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            if (invocation.getMethod().getName().equals("update") && toolPersistenceFailures.get() > 0) {
                toolPersistenceFailures.decrementAndGet();
                throw new org.springframework.dao.TransientDataAccessResourceException(
                        "fixture transient write failure");
            }
            return invocation.proceed();
        });
        orchestrator = new AiChatOrchestrator(
                agents,
                requests,
                conversations,
                events,
                quotas,
                usage,
                providers,
                models,
                routes,
                new AiPromptService(map(AiPromptTemplateMapper.class), map(AiPromptVersionMapper.class)),
                tools,
                new AiKnowledgeService(
                        map(AiKnowledgeSourceMapper.class),
                        map(AiKnowledgeDocumentMapper.class),
                        map(AiKnowledgeChunkMapper.class),
                        map(AiKnowledgeIndexJobMapper.class),
                        map(AiAgentKnowledgeMapper.class),
                        transactions),
                registry,
                (AiToolCallMapper) toolMapperBoundary.getProxy(),
                properties,
                gate);
        approvals = new AiAdminToolController(
                tools, map(AiToolCallMapper.class), requests, agents, conversations, orchestrator);
        agent = agents.require(1L);
        version = agents.requireVersion(2L);
        conversation = conversations.create(41L, agent, "ASTRAL_FRONT", "Fixture");
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void pauseRetainsActualProviderConsumptionAndApprovalResumesWithRealToolResult() throws Exception {
        var start = start("fixture-1");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        await(() -> !"RESERVED".equals(usage.findByRequest(start.requestId()).getStatus()));
        var paused = usage.findByRequest(start.requestId());
        assertEquals(new BigDecimal("0.120000"), paused.getSettledAmount().setScale(6));
        assertEquals(100L, jdbc.queryForObject("SELECT input_tokens FROM ai_user_quota WHERE id=1", Long.class));
        approve(start.requestId(), 41L, true);
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        await(() -> usage.findByRequest(start.requestId()).getInputTokens() == 150);
        var resumed = provider.requests.get(1);
        assertTrue(resumed.messages().stream()
                .anyMatch(message ->
                        message.toolCalls() != null && !message.toolCalls().isEmpty()));
        assertTrue(resumed.messages().stream()
                .anyMatch(message -> "call_fixture".equals(message.toolCallId())
                        && message.content().contains("fixture executed")));
        assertFalse(resumed.messages().stream()
                .anyMatch(message ->
                        "tool".equals(message.role()) && message.content().contains("需要确认")));
        assertEquals(1, executions.get());
        assertEquals(1L, usage.summarize(null, null, 1L, 41L).requestCount());
        assertEquals(
                new BigDecimal("0.180000"),
                usage.findByRequest(start.requestId()).getSettledAmount().setScale(6));
    }

    @Test
    void userConfirmationRejectsOtherUserAndMissingIdentity() throws Exception {
        var start = start("fixture-owner");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        assertThrows(RuntimeException.class, () -> approve(start.requestId(), 42L, true));
        assertThrows(RuntimeException.class, () -> approve(start.requestId(), null, true));
        assertEquals(0, executions.get());
    }

    @Test
    void cancellationPreventsApprovalSideEffectsAndPreservesConsumedUsage() throws Exception {
        var start = start("fixture-cancel");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        requests.cancel(start.requestId(), 41L, "Fixture cancellation", false);
        assertThrows(RuntimeException.class, () -> approve(start.requestId(), 41L, true));
        assertEquals(0, executions.get());
        assertEquals("CANCELLED", requests.require(start.requestId()).getStatus());
        assertTrue(conversations.byRequest(start.requestId()).stream()
                .noneMatch(message -> List.of("PENDING", "STREAMING").contains(message.getStatus())));
    }

    @Test
    void providerMustCompleteAndItsBudgetCannotOutliveRequestLease() throws Exception {
        properties.setRequestTimeoutSeconds(2);
        provider.incomplete = true;
        var start = start("incomplete-provider");
        await(() -> com.astral.ai.common.AiConstants.RequestStatus.isTerminal(
                requests.require(start.requestId()).getStatus()));
        assertEquals("FAILED", requests.require(start.requestId()).getStatus());
        assertEquals("UNKNOWN", usage.findByRequest(start.requestId()).getStatus());
        assertTrue(provider.requests.getFirst().readTimeoutMs() <= 2000);
        assertTrue(provider.requests.getFirst().connectTimeoutMs() <= 2000);
        assertTrue(conversations.byRequest(start.requestId()).stream()
                .noneMatch(message -> List.of("PENDING", "STREAMING").contains(message.getStatus())));
    }

    @Test
    void arbitraryProviderFailureDetailsNeverBecomeUserFacingRequestErrors() throws Exception {
        provider.failure = new IllegalStateException("fixture-secret-upstream-endpoint");
        var start = start("unsafe-provider-error");
        await(() -> "FAILED".equals(requests.require(start.requestId()).getStatus()));
        assertFalse(requests.require(start.requestId()).getErrorSummary().contains("fixture-secret"));
        assertFalse(conversations
                .byRequest(start.requestId())
                .getLast()
                .getContent()
                .contains("fixture-secret"));
    }

    @Test
    void administratorCanRecordUnknownToolOutcomeWithoutReexecutingOrResumingRequest() throws Exception {
        var start = start("reconcile-tool");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        toolEffect = () -> {
            throw new IllegalStateException("response lost after effect");
        };
        approve(start.requestId(), 41L, true);
        Long call =
                jdbc.queryForObject("SELECT id FROM ai_tool_call WHERE request_id=?", Long.class, start.requestId());
        assertEquals("FAILED", requests.require(start.requestId()).getStatus());
        assertThrows(RuntimeException.class, () -> orchestrator.reconcileTool(call, 99L, "SUCCEEDED", " "));
        orchestrator.reconcileTool(call, 99L, "SUCCEEDED", "Verified remote fixture operation");
        var row = map(AiToolCallMapper.class).selectById(call);
        assertEquals("SUCCEEDED", row.getStatus());
        assertEquals(99L, row.getReconciledBy());
        assertEquals("Verified remote fixture operation", row.getReconciliationNote());
        assertEquals("FAILED", requests.require(start.requestId()).getStatus());
        assertEquals(1, executions.get());
        assertEquals(1, provider.requests.size());
        assertThrows(RuntimeException.class, () -> orchestrator.reconcileTool(call, 99L, "FAILED", "repeat"));
        assertTrue(conversations.byRequest(start.requestId()).stream()
                .anyMatch(message -> "TOOL".equals(message.getRole())
                        && message.getContent().contains("Verified remote fixture operation")));
    }

    @Test
    void interruptedToolExecutionKeepsDurableClaimAndCannotBeApprovedTwice() throws Exception {
        var start = start("fixture-crash");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        toolEffect = () -> {
            throw new AssertionError("synthetic interruption after external effect");
        };
        assertThrows(AssertionError.class, () -> approve(start.requestId(), 41L, true));
        assertEquals(
                "RUNNING",
                jdbc.queryForObject(
                        "SELECT status FROM ai_tool_call WHERE request_id=?", String.class, start.requestId()));
        assertNotNull(jdbc.queryForObject(
                "SELECT execution_token FROM ai_tool_call WHERE request_id=?", String.class, start.requestId()));
        assertThrows(RuntimeException.class, () -> approve(start.requestId(), 41L, true));
        assertEquals(1, executions.get());
    }

    @Test
    void wholeToolBatchWaitsForAllResultsAndDuplicateToolNamesKeepDistinctIds() throws Exception {
        provider.batch = true;
        var start = start("fixture-batch");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        var ids = jdbc.queryForList(
                "SELECT id FROM ai_tool_call WHERE request_id=? ORDER BY id", Long.class, start.requestId());
        assertEquals(2, ids.size());
        approveCall(ids.get(0), 41L, true);
        assertEquals("WAITING_APPROVAL", requests.require(start.requestId()).getStatus());
        assertEquals(1, provider.requests.size());
        approveCall(ids.get(1), 41L, false);
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        var results = provider.requests.get(1).messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .toList();
        assertEquals(
                List.of("call_fixture", "call_fixture_2"),
                results.stream().map(message -> message.toolCallId()).toList());
        assertTrue(results.get(0).content().contains("executed"));
        assertTrue(results.get(1).content().contains("拒绝"));
        assertEquals(1, executions.get());
    }

    @Test
    void restartCleanupPreservesPendingApprovalAndExpiresOnlyLostWorkers() throws Exception {
        var start = start("fixture-recovery");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        jdbc.update(
                "UPDATE ai_request SET create_time=? WHERE id=?",
                LocalDateTime.now().minusDays(1),
                start.requestId());
        requests.failStaleRequests("AI012", "执行已超时");
        assertEquals("WAITING_APPROVAL", requests.require(start.requestId()).getStatus());
        approve(start.requestId(), 41L, true);
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));

        var lost = requests.create(conversation.getId(), agent, version, 41L, "ASTRAL_FRONT", "lost", "{}", "{}");
        requests.claim(lost.getId(), 60);
        jdbc.update(
                "UPDATE ai_request SET quota_active=true, lease_until=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1),
                lost.getId());
        assertEquals(1, requests.failStaleRequests("AI012", "执行已超时"));
        assertEquals("FAILED", requests.require(lost.getId()).getStatus());
        assertFalse(requests.require(lost.getId()).getQuotaActive());
        assertEquals(0, requests.failStaleRequests("AI012", "执行已超时"));
    }

    @Test
    void unavailableCredentialsNeverConsumeQuotaOrLeaveReservation() throws Exception {
        jdbc.update("UPDATE ai_provider SET encrypted_api_key='v1:unreadable:fixture' WHERE id=4");
        var start = start("fixture-bad-key");
        await(() -> "FAILED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(0, provider.requests.size());
        assertEquals(0, usage.search(41L, 1L, null, null, 1, 100).size());
        assertEquals(0L, jdbc.queryForObject("SELECT request_count FROM ai_user_quota WHERE id=1", Long.class));
    }

    @Test
    void cancellationBetweenToolCompletionAndNextAssistantNeverLeavesAPendingMessage() throws Exception {
        automaticTool = true;
        jdbc.update("UPDATE ai_tool SET risk_level='READ',approval_policy='NONE' WHERE id=6");
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        toolEffect = () -> afterOwnershipCheck = () -> {
            afterOwnershipCheck = () -> {};
            Long id = requests.findByIdempotency(41L, 1L, "fixture-between-rounds")
                    .getId();
            requests.cancel(id, 41L, "between rounds", false);
            cancelled.countDown();
        };
        var start = start("fixture-between-rounds");
        assertTrue(cancelled.await(8, TimeUnit.SECONDS));
        orchestrator.shutdown();
        assertEquals("CANCELLED", requests.require(start.requestId()).getStatus());
        assertTrue(conversations.byRequest(start.requestId()).stream()
                .noneMatch(message -> List.of("PENDING", "STREAMING").contains(message.getStatus())));
        assertEquals(1, provider.requests.size());
    }

    @Test
    void transientAutomaticResultPersistenceFailureRetriesSavingWithoutRepeatingTheTool() throws Exception {
        automaticTool = true;
        jdbc.update("UPDATE ai_tool SET risk_level='READ',approval_policy='NONE' WHERE id=6");
        toolEffect = () -> toolPersistenceFailures.set(1);
        var start = start("fixture-persist-retry");
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(1, executions.get());
        assertEquals(0, toolPersistenceFailures.get());
        assertEquals(2, provider.requests.size());
        assertEquals(
                "SUCCEEDED",
                jdbc.queryForObject(
                        "SELECT status FROM ai_tool_call WHERE request_id=?", String.class, start.requestId()));
    }

    @Test
    void routeReferencedByAnAgentVersionCannotBeDeleted() {
        assertThrows(com.astral.common.exception.BusinessException.class, () -> routes.delete(3L));
        assertEquals(5L, routes.require(3L).getModelId());
    }

    @Test
    void referencedModelCannotBeDeletedAndInvalidPricesCannotBeSaved() {
        assertThrows(com.astral.common.exception.BusinessException.class, () -> models.delete(5L));
        assertEquals("fixture", models.require(5L).getModelCode());
        var input = new com.astral.ai.entity.AiModelEntity();
        input.setInputPrice(BigDecimal.ONE.negate());
        assertThrows(RuntimeException.class, () -> models.update(5L, input));
        assertEquals(0, BigDecimal.ONE.compareTo(models.require(5L).getInputPrice()));
        input.setProviderId(4L);
        input.setModelCode("invalid");
        input.setCurrency("not-money");
        input.setInputPrice(BigDecimal.ONE);
        assertThrows(RuntimeException.class, () -> models.create(input));
        assertEquals(1, models.list(4L).size());
    }

    @Test
    void cancellationAfterReservationBeforeProviderReleasesUnusedQuota() throws Exception {
        afterReservation = () -> {
            Long id = requests.findByIdempotency(41L, 1L, "fixture-unsent").getId();
            requests.cancel(id, 41L, "cancel before provider invocation", false);
        };
        var start = start("fixture-unsent");
        await(() -> "CANCELLED".equals(requests.require(start.requestId()).getStatus()));
        await(() -> !"RESERVED".equals(usage.findByRequest(start.requestId()).getStatus()));
        assertEquals(0, provider.requests.size());
        assertEquals("RELEASED", usage.findByRequest(start.requestId()).getStatus());
        assertEquals(
                0, usage.findByRequest(start.requestId()).getReservationAmount().signum());
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT SUM(input_tokens+output_tokens+request_count) FROM ai_user_quota", Long.class));
        assertEquals(
                0,
                jdbc.queryForObject("SELECT SUM(reserved_cost+settled_cost) FROM ai_user_quota", BigDecimal.class)
                        .signum());
    }

    @Test
    void cancellationAfterProviderCompletedKeepsKnownActualUsage() throws Exception {
        provider.beforeCompletion = () -> {
            Long id = requests.findByIdempotency(41L, 1L, "fixture-cancel-complete")
                    .getId();
            requests.cancel(id, 41L, "cancel as provider returns", false);
        };
        provider.finalOnly = true;
        var start = start("fixture-cancel-complete");
        await(() -> "CANCELLED".equals(requests.require(start.requestId()).getStatus()));
        await(() -> usage.findByRequest(start.requestId()) != null
                && "SETTLED".equals(usage.findByRequest(start.requestId()).getStatus()));
        assertEquals(50, usage.findByRequest(start.requestId()).getInputTokens());
        assertEquals(5, usage.findByRequest(start.requestId()).getOutputTokens());
        assertEquals(
                new BigDecimal("0.060000"),
                usage.findByRequest(start.requestId()).getSettledAmount().setScale(6));
        assertEquals("CANCELLED", requests.require(start.requestId()).getStatus());
        assertTrue(conversations.byRequest(start.requestId()).stream()
                .noneMatch(message -> List.of("PENDING", "STREAMING").contains(message.getStatus())));
    }

    @Test
    void uncertainWriteFailureDoesNotResumeModelOrPermitReplay() throws Exception {
        var start = start("fixture-uncertain");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        toolEffect = () -> {
            throw new IllegalStateException("response lost after external effect");
        };
        approve(start.requestId(), 41L, true);
        assertEquals(
                "UNKNOWN",
                jdbc.queryForObject(
                        "SELECT status FROM ai_tool_call WHERE request_id=?", String.class, start.requestId()));
        assertEquals("FAILED", requests.require(start.requestId()).getStatus());
        assertThrows(RuntimeException.class, () -> approve(start.requestId(), 41L, true));
        assertEquals(1, executions.get());
        assertEquals(1, provider.requests.size());
    }

    @Test
    void lostProviderUsageRemainsExplicitlyUncertainUntilReconciled() throws Exception {
        provider.beforeCompletion = () -> {
            throw new IllegalStateException("response interrupted");
        };
        var start = start("fixture-unknown-usage");
        await(() -> "FAILED".equals(requests.require(start.requestId()).getStatus()));
        var stage = usage.findByRequest(start.requestId());
        assertEquals("UNKNOWN", stage.getStatus());
        assertTrue(stage.getReservationAmount().signum() > 0);
        assertEquals(BigDecimal.ZERO, stage.getSettledAmount().stripTrailingZeros());
        usage.settleStage(stage.getId(), 100, 10);
        usage.settleStage(stage.getId(), 100, 10);
        assertEquals(
                new BigDecimal("0.120000"),
                usage.findByRequest(start.requestId()).getSettledAmount().setScale(6));
        assertEquals(100, requests.require(start.requestId()).getTokenIn());
    }

    @Test
    void maintenanceDispatchesQueuedWorkAndReconcilesExpiredClaimsWithoutRepeatingEffects() throws Exception {
        var orphan = requests.create(
                conversation.getId(), agent, version, 41L, "ASTRAL_FRONT", "queued-recovery", "{}", "{}");
        var message = new com.astral.ai.entity.AiMessageEntity();
        message.setConversationId(conversation.getId());
        message.setRequestId(orphan.getId());
        message.setRole("ASSISTANT");
        message.setStatus("PENDING");
        message.setContent("");
        conversations.append(message);
        orchestrator.recoverPending();
        await(() -> "WAITING_APPROVAL".equals(requests.require(orphan.getId()).getStatus()));
        toolEffect = () -> {
            throw new AssertionError("fixture process interruption");
        };
        assertThrows(AssertionError.class, () -> approve(orphan.getId(), 41L, true));
        jdbc.update(
                "UPDATE ai_tool_call SET approved_at=? WHERE request_id=?",
                LocalDateTime.now().minusHours(1),
                orphan.getId());
        orchestrator.recoverPending();
        assertEquals(
                "UNKNOWN",
                jdbc.queryForObject(
                        "SELECT status FROM ai_tool_call WHERE request_id=?", String.class, orphan.getId()));
        assertEquals("FAILED", requests.require(orphan.getId()).getStatus());
        assertEquals(1, executions.get());
    }

    @Test
    void messagePagesKeepLatestAnswerAndUseOldestDisplayedIdAsCursor() {
        var controller = new com.astral.ai.controller.AiAppController(
                null, null, conversations, requests, null, null, null, orchestrator);
        for (int i = 1; i <= 35; i++) {
            var message = new com.astral.ai.entity.AiMessageEntity();
            message.setConversationId(conversation.getId());
            message.setRole("ASSISTANT");
            message.setContent("answer-" + i);
            conversations.append(message);
        }
        var caller = new MockHttpServletRequest();
        caller.setAttribute("userId", "41");
        var first = controller.messages(conversation.getId(), caller, null, 30).getData();
        assertEquals("answer-35", first.items().getLast().content());
        assertEquals("answer-6", first.items().getFirst().content());
        var second = controller
                .messages(conversation.getId(), caller, first.nextCursor(), 30)
                .getData();
        assertEquals(5, second.items().size());
        assertEquals("answer-5", second.items().getLast().content());
        assertFalse(second.hasMore());
    }

    @Test
    void concurrentApprovalExecutesOnceAndChangedPlanIsRejectedBeforeClaim() throws Exception {
        var start = start("parallel-approval");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        jdbc.update("UPDATE ai_tool SET executor_config='{}' WHERE id=6");
        assertThrows(RuntimeException.class, () -> approve(start.requestId(), 41L, true));
        assertEquals(0, executions.get());
        jdbc.update("UPDATE ai_tool SET executor_config=NULL WHERE id=6");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var success = new AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var attempts = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                attempts.add(pool.submit(() -> {
                    try {
                        barrier.await();
                        approve(start.requestId(), 41L, true);
                        success.incrementAndGet();
                    } catch (com.astral.ai.common.AiException expected) {
                        assertEquals("AI008", expected.getErrorCode());
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                }));
            }
            for (var attempt : attempts) attempt.get(8, TimeUnit.SECONDS);
        }
        assertEquals(1, success.get());
        assertEquals(1, executions.get());
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
    }

    @Test
    void concurrentStartWithSameKeyCreatesOneRequestAndOneUserMessage() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                barrier.await();
                return start("same-key");
            });
            var second = pool.submit(() -> {
                barrier.await();
                return start(" same-key ");
            });
            assertEquals(
                    first.get(8, TimeUnit.SECONDS).requestId(),
                    second.get(8, TimeUnit.SECONDS).requestId());
        }
        var request = requests.findByIdempotency(41L, 1L, "same-key");
        await(() -> "WAITING_APPROVAL".equals(requests.require(request.getId()).getStatus()));
        assertEquals(
                1,
                conversations.byRequest(request.getId()).stream()
                        .filter(message -> "USER".equals(message.getRole()))
                        .count());
        assertEquals(1, provider.requests.size());
    }

    @Test
    void quotasReserveFullOutputRollbackBothPeriodsAndApplyVersionCostLimit() throws Exception {
        jdbc.update(
                "UPDATE ai_agent_version SET quota_override_json=? WHERE id=2",
                "{\"monthlyCostLimit\":0.01,\"maxOutputTokens\":20}");
        var denied = start("cost-denied");
        await(() -> "FAILED".equals(requests.require(denied.requestId()).getStatus()));
        assertEquals(0, provider.requests.size());
        assertEquals(0L, jdbc.queryForObject("SELECT request_count FROM ai_user_quota WHERE id=1", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT input_tokens FROM ai_user_quota WHERE id=2", Long.class));
        jdbc.update(
                "UPDATE ai_agent_version SET quota_override_json=? WHERE id=2",
                "{\"maxOutputTokens\":20,\"dailyRequests\":1}");
        var allowed = start("quota-allowed");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(allowed.requestId()).getStatus()));
        assertEquals(20, provider.requests.getFirst().maxTokens());
        approve(allowed.requestId(), 41L, true);
        await(() -> "SUCCEEDED".equals(requests.require(allowed.requestId()).getStatus()));
        var exhausted = start("quota-exhausted");
        await(() -> "FAILED".equals(requests.require(exhausted.requestId()).getStatus()));
        assertEquals(2, provider.requests.size());
        assertEquals(1L, jdbc.queryForObject("SELECT request_count FROM ai_user_quota WHERE id=1", Long.class));
    }

    @Test
    void durableConcurrencyRejectsSecondWorkerWithoutLeakingItsReservation() throws Exception {
        jdbc.update("UPDATE ai_agent_version SET quota_override_json=? WHERE id=2", "{\"maxConcurrent\":1}");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var released = new java.util.concurrent.CountDownLatch(1);
        provider.finalOnly = true;
        provider.beforeCompletion = () -> {
            entered.countDown();
            try {
                assertTrue(released.await(8, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        };
        var first = start("concurrent-first");
        try {
            assertTrue(entered.await(8, TimeUnit.SECONDS));
            var denied = start("concurrent-denied");
            await(() -> "FAILED".equals(requests.require(denied.requestId()).getStatus()));
            assertEquals(null, usage.findByRequest(denied.requestId()));
            assertEquals(1, provider.requests.size());
        } finally {
            released.countDown();
        }
        await(() -> "SUCCEEDED".equals(requests.require(first.requestId()).getStatus()));
        var retry = start("concurrent-retry");
        await(() -> "SUCCEEDED".equals(requests.require(retry.requestId()).getStatus()));
        assertEquals(2, provider.requests.size());
        assertEquals(2L, jdbc.queryForObject("SELECT request_count FROM ai_user_quota WHERE id=1", Long.class));
    }

    @Test
    void legacyPlanCannotExecuteAndRepeatedRejectionCannotOverwriteResult() throws Exception {
        var start = start("legacy-plan");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        jdbc.update("UPDATE ai_tool_call SET plan_hash=NULL WHERE request_id=?", start.requestId());
        assertThrows(RuntimeException.class, () -> approve(start.requestId(), 41L, true));
        assertEquals(0, executions.get());
        approve(start.requestId(), 41L, false);
        assertThrows(RuntimeException.class, () -> approve(start.requestId(), 41L, false));
        assertEquals(
                "REJECTED",
                jdbc.queryForObject(
                        "SELECT status FROM ai_tool_call WHERE request_id=?", String.class, start.requestId()));
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(0, executions.get());
    }

    @Test
    void oversizedInputIsRejectedBeforeProviderOrLedger() throws Exception {
        jdbc.update("UPDATE ai_agent_version SET quota_override_json=? WHERE id=2", "{\"maxInputChars\":3}");
        var start = start("oversized-input");
        await(() -> "FAILED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(0, provider.requests.size());
        assertEquals(null, usage.findByRequest(start.requestId()));
    }

    @Test
    void ordinaryTextAnswerDoesNotTriggerAnotherPaidModelCall() throws Exception {
        provider.finalOnly = true;
        provider.plainText = true;
        var start = start("plain-answer");
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(1, provider.requests.size());
        assertEquals(
                "这是普通回答", conversations.byRequest(start.requestId()).getLast().getContent());
    }

    @Test
    void automaticToolCommitsClaimBeforeEffectAndFinalAnswerContainsOnlyFinalRound() throws Exception {
        automaticTool = true;
        jdbc.update("UPDATE ai_tool SET risk_level='READ',approval_policy='NONE' WHERE id=6");
        provider.toolPreamble = "正在查询";
        provider.plainText = true;
        toolEffect = () -> {
            assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive(),
                    "external tool execution must not hold a database transaction");
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM ai_tool_call", String.class));
        };
        var start = start("automatic-round");
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(1, executions.get());
        assertEquals(2, provider.requests.size());
        assertEquals(
                "这是普通回答", conversations.byRequest(start.requestId()).getLast().getContent());
        assertTrue(
                provider.requests.getLast().messages().stream().anyMatch(message -> "正在查询".equals(message.content())));
    }

    @Test
    void failureAfterAutomaticToolClosesOnlyCurrentAssistant() throws Exception {
        automaticTool = true;
        jdbc.update("UPDATE ai_tool SET risk_level='READ',approval_policy='NONE' WHERE id=6");
        provider.failFinal = true;
        var start = start("failed-final");
        await(() -> "FAILED".equals(requests.require(start.requestId()).getStatus()));
        var assistants = conversations.byRequest(start.requestId()).stream()
                .filter(message -> "ASSISTANT".equals(message.getRole()))
                .toList();
        assertEquals(2, assistants.size());
        assertEquals("COMPLETED", assistants.getFirst().getStatus());
        assertEquals("FAILED", assistants.getLast().getStatus());
        assertTrue(assistants.getLast().getContent().contains("请求失败"));
    }

    @Test
    void approvalResumeUsesRemainingPersistentToolRoundBudget() throws Exception {
        jdbc.update("UPDATE ai_agent_version SET tool_policy_json='{\"maxRounds\":1}' WHERE id=2");
        var start = start("one-round");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        approve(start.requestId(), 41L, true);
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(2, provider.requests.size());
        assertTrue(provider.requests.getLast().tools() == null
                || provider.requests.getLast().tools().isEmpty());
        assertEquals(1, executions.get());
    }

    @Test
    void roleApprovalRejectsChangesMadeWhileWaitingAndKeepsTheNewAssignment() throws Exception {
        useRoleTool();
        var start = start("role-plan-stale");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        domainRoles.assignRoles(42L, List.of(1L, 2L));
        approve(start.requestId(), 41L, true);
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(
                List.of(1L, 2L),
                domainRoles.list().stream().map(row -> row.getRoleId()).sorted().toList());
        assertTrue(conversations.byRequest(start.requestId()).stream()
                .anyMatch(message ->
                        "TOOL".equals(message.getRole()) && message.getContent().contains("重新")));
    }

    @Test
    void unchangedRolePlanUsesServerSnapshotDespiteModelSuppliedValue() throws Exception {
        useRoleTool();
        var start = start("role-plan-unchanged");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        approve(start.requestId(), 41L, true);
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(
                List.of(3L),
                domainRoles.list().stream().map(row -> row.getRoleId()).toList());
    }

    @Test
    void changedRoleMeaningAndMissingLegacySnapshotAreRejectedWithoutRoleWrites() throws Exception {
        useRoleTool();
        var start = start("role-meaning-changed");
        await(() ->
                "WAITING_APPROVAL".equals(requests.require(start.requestId()).getStatus()));
        jdbc.update("UPDATE sys_role SET is_super=1 WHERE id=3");
        approve(start.requestId(), 41L, true);
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(
                List.of(1L),
                domainRoles.list().stream().map(row -> row.getRoleId()).toList());
        var handler = adminTools.handlers().stream()
                .filter(h -> "change_role_permission".equals(h.definition().toolCode()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                AiToolResult.REJECTED,
                handler.execute(new AiToolContext(
                                1L,
                                2L,
                                41L,
                                1L,
                                "fixture",
                                "ADMIN",
                                "ASTRAL_FRONT",
                                AiJson.readObject("{\"userId\":42,\"roleIds\":[3]}"),
                                null,
                                true,
                                41L))
                        .status());
    }

    @Test
    void humanTestRunPreparesServerSnapshotAndPreservesTheLastSuperAdministrator() {
        useRoleTool();
        jdbc.update("UPDATE sys_role SET is_super=1 WHERE id=1");
        assertEquals(
                AiToolResult.REJECTED,
                tools.testRun(6L, "{\"userId\":42,\"roleIds\":[3]}", 41L).status());
        assertEquals(
                List.of(1L),
                domainRoles.list().stream().map(row -> row.getRoleId()).toList());
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES(52,41,1)");
        assertEquals(
                AiToolResult.SUCCEEDED,
                tools.testRun(6L, "{\"userId\":42,\"roleIds\":[3]}", 41L).status());
        assertEquals(
                List.of(1L, 3L),
                domainRoles.list().stream().map(row -> row.getRoleId()).sorted().toList());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void missingOrDeletedRoleTargetIsAnAuditedToolRejection(boolean deleted) throws Exception {
        useRoleTool();
        if (deleted) jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=42");
        else provider.toolArguments = "{\"userId\":999,\"roleIds\":[3]}";
        var start = start("missing-role-target");
        await(() -> List.of("WAITING_APPROVAL", "FAILED")
                .contains(requests.require(start.requestId()).getStatus()));
        assertEquals("WAITING_APPROVAL", requests.require(start.requestId()).getStatus());
        approve(start.requestId(), 41L, true);
        await(() -> "SUCCEEDED".equals(requests.require(start.requestId()).getStatus()));
        assertEquals(
                List.of(1L),
                domainRoles.list().stream().map(row -> row.getRoleId()).toList());
        assertTrue(conversations.byRequest(start.requestId()).stream()
                .anyMatch(message ->
                        "TOOL".equals(message.getRole()) && message.getContent().contains("不存在")));
        assertEquals(
                AiToolResult.REJECTED,
                tools.testRun(6L, provider.toolArguments, 41L).status());
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ai_tool_call WHERE source='test-run' AND status='REJECTED'", Long.class));
    }

    private void useRoleTool() {
        jdbc.update(
                "UPDATE ai_tool SET tool_code='change_role_permission', handler_key='admin.changeRolePermission', risk_level='HIGH', approval_policy='ADMIN_CONFIRM' WHERE id=6");
        provider.toolName = "change_role_permission";
        provider.toolArguments = "{\"userId\":42,\"roleIds\":[3],\"_authorizationSnapshot\":\"model-forged\"}";
    }

    private AiChatOrchestrator.Start start(String key) {
        return orchestrator.start(
                agent, version, conversation, 41L, "ASTRAL_FRONT", "Please run fixture", Map.of(), key);
    }

    private void approve(Long requestId, Long actor, boolean yes) {
        Long id = jdbc.queryForObject("SELECT id FROM ai_tool_call WHERE request_id=?", Long.class, requestId);
        assertNotNull(id);
        approveCall(id, actor, yes);
    }

    private void approveCall(Long id, Long actor, boolean yes) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actor != null) {
            request.setAttribute("userId", actor.toString());
        }
        approvals.approve(id, new AiAdminDtos.ToolApproveReq(yes, null), request);
    }

    private <T> T map(Class<T> type) {
        return session.getMapper(type);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "persistent lifecycle condition was not reached");
    }

    private final class FixtureTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "fixture_tool",
                    "Fixture",
                    "fixture",
                    "fixture_tool",
                    automaticTool ? "READ" : "DRAFT",
                    automaticTool ? "NONE" : "USER_CONFIRM",
                    List.of(),
                    List.of(),
                    "{}",
                    null,
                    null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            executions.incrementAndGet();
            toolEffect.run();
            return AiToolResult.ok(
                    "fixture executed", AiJson.mapper().createObjectNode().put("ok", true));
        }
    }

    private static final class ScriptedProvider implements AiProvider {
        private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        private boolean batch;
        private String toolName = "fixture_tool";
        private String toolArguments = "{}";
        private boolean finalOnly;
        private boolean plainText;
        private boolean failFinal;
        private boolean incomplete;
        private Throwable failure;
        private String toolPreamble = "";
        private Runnable beforeCompletion = () -> {};

        @Override
        public String type() {
            return "FIXTURE";
        }

        @Override
        public HealthResult testConnection(ChatRequest request) {
            return HealthResult.ok(0);
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stream(ChatRequest request, StreamListener listener) {
            requests.add(request);
            beforeCompletion.run();
            if (incomplete) return;
            if (failure != null) {
                listener.onError(failure);
                return;
            }
            if (requests.size() == 1 && !finalOnly) {
                listener.onCompleted(new ChatResult(
                        toolPreamble,
                        "tool_calls",
                        100,
                        10,
                        batch
                                ? List.of(
                                        new ToolCall("call_fixture", toolName, toolArguments),
                                        new ToolCall("call_fixture_2", "fixture_tool", "{}"))
                                : List.of(new ToolCall("call_fixture", toolName, toolArguments))));
            } else {
                if (failFinal) {
                    listener.onError(new com.astral.ai.common.AiException("AI006", "fixture failed"));
                    return;
                }
                listener.onCompleted(new ChatResult(
                        plainText
                                ? "这是普通回答"
                                : "{\"answer\":\"Completed\",\"blocks\":[{\"type\":\"text\",\"text\":\"Completed\"}]}",
                        "stop",
                        50,
                        5,
                        List.of()));
            }
        }
    }
}
