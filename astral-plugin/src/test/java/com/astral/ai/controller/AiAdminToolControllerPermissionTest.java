package com.astral.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.ai.common.AiConstants;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.security.AiToolPermissionGate;
import com.astral.ai.service.AiToolService;
import com.astral.auth.security.PermissionAspect;
import com.astral.auth.security.PermissionChecker;
import com.astral.common.exception.BusinessException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

class AiAdminToolControllerPermissionTest {

    @Test
    void creatingExecutableHttpConfigurationRequiresSuperThroughTheActualControllerProxy() {
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("admin:ai:tool:edit"));
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        SaTokenContext previousContext = SaManager.getSaTokenContext();
        StpInterface previousPermissions = SaManager.getStpInterface();
        var previousBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(910044L);
            AiToolMapper tools = mock(AiToolMapper.class);
            AtomicReference<AiToolEntity> persisted = new AtomicReference<>();
            doAnswer(invocation -> {
                        AiToolEntity saved = invocation.getArgument(0);
                        saved.setId(812L);
                        persisted.set(saved);
                        return 1;
                    })
                    .when(tools)
                    .insert(any(AiToolEntity.class));
            AiProperties properties = new AiProperties();
            // A numeric public fixture parses locally; validation cannot issue a DNS lookup.
            // This save invokes executor configuration validation, not tool execution,
            // network transport, a JDBC connection or an HTTP server.
            properties.getHttpTool().setAllowedHosts(List.of("93.184.216.34"));
            AiToolService service = new AiToolService(
                    tools,
                    mock(AiAgentToolMapper.class),
                    List.of(),
                    properties,
                    mock(JdbcTemplate.class),
                    mock(AiMcpServerMapper.class),
                    new AiToolPermissionGate(new PermissionChecker(permissions)),
                    mock(AiToolCallMapper.class));
            // Only the HTTP-configuration path is exercised; unrelated services are not invoked.
            AspectJProxyFactory factory =
                    new AspectJProxyFactory(new AiAdminToolController(service, null, null, null, null, null));
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            AiAdminToolController controller = factory.getProxy();
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/ai/tools/http");
            request.setAttribute("userId", "910044");
            AiAdminDtos.DeclarativeToolSaveReq body = new AiAdminDtos.DeclarativeToolSaveReq(
                    "audit_http_fixture",
                    "Audit HTTP fixture",
                    "Public configuration fixture",
                    List.of(AiConstants.ClientType.ASTRAL_FRONT),
                    List.of(),
                    "{\"type\":\"object\",\"properties\":{}}",
                    "{\"method\":\"GET\",\"url\":\"https://93.184.216.34/health\"}",
                    AiConstants.RiskLevel.READ,
                    AiConstants.ApprovalPolicy.NONE);

            BusinessException denied = assertThrows(
                    BusinessException.class,
                    () -> controller.createHttpTool(body, request),
                    "delegated tool editing must not grant authority to create executable configuration");
            assertEquals("COMMON005", denied.getErrorCode());
            verify(tools, never()).insert(any(AiToolEntity.class));

            held.set(List.of("*:*:*"));
            var created = controller.createHttpTool(body, request);
            assertEquals(200, created.getCode());
            assertEquals(812L, created.getData().id());
            assertEquals("audit_http_fixture", created.getData().toolCode());
            verify(tools).insert(any(AiToolEntity.class));

            when(tools.selectById(812L)).thenAnswer(ignored -> persisted.get());
            when(tools.updateById(any(AiToolEntity.class))).thenReturn(1);
            MockHttpServletRequest updateRequest = new MockHttpServletRequest("PUT", "/api/v1/admin/ai/tools/812/http");
            updateRequest.setAttribute("userId", "910044");
            AiAdminDtos.DeclarativeToolSaveReq updateBody = new AiAdminDtos.DeclarativeToolSaveReq(
                    "audit_http_fixture",
                    "Updated HTTP fixture",
                    "Updated public configuration fixture",
                    List.of(AiConstants.ClientType.ASTRAL_FRONT),
                    List.of(),
                    "{\"type\":\"object\",\"properties\":{}}",
                    "{\"method\":\"GET\",\"url\":\"https://93.184.216.34/updated-health\"}",
                    AiConstants.RiskLevel.READ,
                    AiConstants.ApprovalPolicy.NONE);
            held.set(List.of("admin:ai:tool:edit"));
            BusinessException updateDenied = assertThrows(
                    BusinessException.class,
                    () -> controller.updateHttpTool(812L, updateBody, updateRequest),
                    "delegated tool editing must not grant authority to replace executable configuration");
            assertEquals("COMMON005", updateDenied.getErrorCode());
            verify(tools, never()).updateById(any(AiToolEntity.class));

            held.set(List.of("*:*:*"));
            var updated = controller.updateHttpTool(812L, updateBody, updateRequest);
            assertEquals(200, updated.getCode());
            assertEquals(812L, updated.getData().id());
            assertEquals("Updated HTTP fixture", updated.getData().name());
            assertEquals(updateBody.executorConfig(), updated.getData().executorConfig());
            verify(tools).updateById(any(AiToolEntity.class));
        } finally {
            try {
                if (SaTokenContextForThreadLocalStaff.getModelBoxOrNull() != null && StpUtil.isLogin()) {
                    StpUtil.logout();
                }
            } finally {
                if (previousBox == null) {
                    SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.remove();
                } else {
                    SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.set(previousBox);
                }
                SaManager.setStpInterface(previousPermissions);
                SaManager.setSaTokenContext(previousContext);
            }
        }
    }

    @Test
    void creatingExecutableSqlConfigurationRequiresSuperThroughTheActualControllerProxy() {
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("admin:ai:tool:edit"));
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        SaTokenContext previousContext = SaManager.getSaTokenContext();
        StpInterface previousPermissions = SaManager.getStpInterface();
        var previousBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(910045L);
            AiToolMapper tools = mock(AiToolMapper.class);
            AtomicReference<AiToolEntity> persisted = new AtomicReference<>();
            doAnswer(invocation -> {
                        AiToolEntity saved = invocation.getArgument(0);
                        saved.setId(813L);
                        persisted.set(saved);
                        return 1;
                    })
                    .when(tools)
                    .insert(any(AiToolEntity.class));
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            AiToolService service = new AiToolService(
                    tools,
                    mock(AiAgentToolMapper.class),
                    List.of(),
                    new AiProperties(),
                    jdbc,
                    mock(AiMcpServerMapper.class),
                    new AiToolPermissionGate(new PermissionChecker(permissions)),
                    mock(AiToolCallMapper.class));
            AspectJProxyFactory factory =
                    new AspectJProxyFactory(new AiAdminToolController(service, null, null, null, null, null));
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            AiAdminToolController controller = factory.getProxy();
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/ai/tools/sql");
            request.setAttribute("userId", "910045");
            // A literal read-only query validates locally; no JDBC connection or query is executed.
            AiAdminDtos.DeclarativeToolSaveReq body = new AiAdminDtos.DeclarativeToolSaveReq(
                    "audit_sql_fixture",
                    "Audit SQL fixture",
                    "Public SQL configuration fixture",
                    List.of(AiConstants.ClientType.ASTRAL_FRONT),
                    List.of(),
                    "{\"type\":\"object\",\"properties\":{}}",
                    "{\"sql\":\"SELECT 1 AS audit_value\",\"columns\":[\"audit_value\"],\"maxRows\":1,\"timeoutSeconds\":1}",
                    AiConstants.RiskLevel.READ,
                    AiConstants.ApprovalPolicy.NONE);
            BusinessException denied = assertThrows(
                    BusinessException.class,
                    () -> controller.createSqlTool(body, request),
                    "delegated tool editing must not grant authority to create executable SQL configuration");
            assertEquals("COMMON005", denied.getErrorCode());
            verify(tools, never()).insert(any(AiToolEntity.class));

            held.set(List.of("*:*:*"));
            var created = controller.createSqlTool(body, request);
            assertEquals(200, created.getCode());
            assertEquals(813L, created.getData().id());
            assertEquals("audit_sql_fixture", created.getData().toolCode());
            assertEquals(AiConstants.ExecutorType.SQL, created.getData().executorType());
            verify(tools).insert(any(AiToolEntity.class));

            when(tools.selectById(813L)).thenAnswer(ignored -> persisted.get());
            when(tools.updateById(any(AiToolEntity.class))).thenReturn(1);
            MockHttpServletRequest updateRequest = new MockHttpServletRequest("PUT", "/api/v1/admin/ai/tools/813/sql");
            updateRequest.setAttribute("userId", "910045");
            AiAdminDtos.DeclarativeToolSaveReq updateBody = new AiAdminDtos.DeclarativeToolSaveReq(
                    "audit_sql_fixture",
                    "Updated SQL fixture",
                    "Public SQL configuration fixture",
                    List.of(AiConstants.ClientType.ASTRAL_FRONT),
                    List.of(),
                    "{\"type\":\"object\",\"properties\":{}}",
                    "{\"sql\":\"SELECT 2 AS audit_value\",\"columns\":[\"audit_value\"],\"maxRows\":1,\"timeoutSeconds\":1}",
                    AiConstants.RiskLevel.READ,
                    AiConstants.ApprovalPolicy.NONE);
            held.set(List.of("admin:ai:tool:edit"));
            BusinessException updateDenied = assertThrows(
                    BusinessException.class,
                    () -> controller.updateSqlTool(813L, updateBody, updateRequest),
                    "delegated tool editing must not grant authority to replace executable SQL configuration");
            assertEquals("COMMON005", updateDenied.getErrorCode());
            verify(tools, never()).updateById(any(AiToolEntity.class));

            held.set(List.of("*:*:*"));
            var updated = controller.updateSqlTool(813L, updateBody, updateRequest);
            assertEquals(200, updated.getCode());
            assertEquals(813L, updated.getData().id());
            assertEquals("Updated SQL fixture", updated.getData().name());
            assertEquals(
                    "{\"sql\":\"SELECT 2 AS audit_value\",\"columns\":[\"audit_value\"],\"maxRows\":1,\"timeoutSeconds\":1}",
                    updated.getData().executorConfig());
            verify(tools).updateById(any(AiToolEntity.class));

            MockHttpServletRequest policyRequest = new MockHttpServletRequest("PUT", "/api/v1/admin/ai/tools/813");
            policyRequest.setAttribute("userId", "910045");
            AiAdminDtos.ToolSaveReq confirmedPolicy = new AiAdminDtos.ToolSaveReq(
                    "Updated SQL fixture",
                    "Public SQL configuration fixture",
                    AiConstants.RiskLevel.HIGH,
                    AiConstants.ApprovalPolicy.ADMIN_CONFIRM,
                    AiConstants.ToolStatus.ENABLED);
            var confirmed = controller.updateTool(813L, confirmedPolicy, policyRequest);
            assertEquals(AiConstants.RiskLevel.HIGH, confirmed.getData().riskLevel());
            assertEquals(
                    AiConstants.ApprovalPolicy.ADMIN_CONFIRM,
                    confirmed.getData().approvalPolicy());
            verify(tools, times(2)).updateById(any(AiToolEntity.class));

            AiAdminDtos.ToolSaveReq reducedPolicy = new AiAdminDtos.ToolSaveReq(
                    "Updated SQL fixture",
                    "Public SQL configuration fixture",
                    AiConstants.RiskLevel.READ,
                    AiConstants.ApprovalPolicy.NONE,
                    AiConstants.ToolStatus.ENABLED);
            held.set(List.of("admin:ai:tool:edit"));
            BusinessException policyDenied = assertThrows(
                    BusinessException.class,
                    () -> controller.updateTool(813L, reducedPolicy, policyRequest),
                    "delegated tool editing must not grant authority to lower executable tool confirmation policy");
            assertEquals("COMMON005", policyDenied.getErrorCode());
            verify(tools, times(2)).updateById(any(AiToolEntity.class));

            held.set(List.of("*:*:*"));
            var reduced = controller.updateTool(813L, reducedPolicy, policyRequest);
            assertEquals(200, reduced.getCode());
            assertEquals(813L, reduced.getData().id());
            assertEquals(AiConstants.RiskLevel.READ, reduced.getData().riskLevel());
            assertEquals(AiConstants.ApprovalPolicy.NONE, reduced.getData().approvalPolicy());
            verify(tools, times(3)).updateById(any(AiToolEntity.class));

            MockHttpServletRequest syncRequest = new MockHttpServletRequest("POST", "/api/v1/admin/ai/tools/sync");
            syncRequest.setAttribute("userId", "910045");
            held.set(List.of("admin:ai:tool:edit"));
            BusinessException syncDenied = assertThrows(
                    BusinessException.class,
                    () -> controller.syncTools(syncRequest),
                    "delegated tool editing must not grant authority to synchronize executable tool definitions");
            assertEquals("COMMON005", syncDenied.getErrorCode());
            verify(tools, never()).selectList(null);
            verify(tools, times(3)).updateById(any(AiToolEntity.class));

            held.set(List.of("*:*:*"));
            var synchronizedTools = controller.syncTools(syncRequest);
            assertEquals(200, synchronizedTools.getCode());
            assertEquals(0, synchronizedTools.getData().get("changed"));
            verify(tools).selectList(null);
            verify(tools, times(3)).updateById(any(AiToolEntity.class));

            AiAdminDtos.ToolTestRunReq testRunBody = new AiAdminDtos.ToolTestRunReq("{}");
            MockHttpServletRequest testRunRequest =
                    new MockHttpServletRequest("POST", "/api/v1/admin/ai/tools/813/test-run");
            testRunRequest.setAttribute("userId", "910045");
            held.set(List.of("admin:ai:tool:edit"));
            BusinessException testRunDenied = assertThrows(
                    BusinessException.class,
                    () -> controller.testRun(813L, testRunBody, testRunRequest),
                    "delegated tool editing must not grant authority to manually confirm executable tools");
            assertEquals("COMMON005", testRunDenied.getErrorCode());
            verify(jdbc, never()).getDataSource();

            held.set(List.of("*:*:*"));
            var testRun = controller.testRun(813L, testRunBody, testRunRequest);
            assertEquals(200, testRun.getCode());
            assertEquals(AiConstants.ToolCallStatus.FAILED, testRun.getData().status());
            assertEquals("工具未授权或参数无效：当前环境没有可用的数据源", testRun.getData().summary());
            verify(jdbc).getDataSource();
        } finally {
            try {
                if (SaTokenContextForThreadLocalStaff.getModelBoxOrNull() != null && StpUtil.isLogin()) {
                    StpUtil.logout();
                }
            } finally {
                if (previousBox == null) {
                    SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.remove();
                } else {
                    SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.set(previousBox);
                }
                SaManager.setStpInterface(previousPermissions);
                SaManager.setSaTokenContext(previousContext);
            }
        }
    }
}
