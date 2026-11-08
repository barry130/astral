package com.astral.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.ai.common.AiJson;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.service.AiMcpServerService;
import com.astral.auth.security.PermissionAspect;
import com.astral.auth.security.PermissionChecker;
import com.astral.common.exception.BusinessException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.node.ObjectNode;

/**
 * R9 MCP 服务器提权与脱敏契约。
 *
 * <p>期望值锚定审计契约原文（docs/audit/2026-11-07/backend-ai-read-recovery.md R9）：
 * 仅持 {@code admin:ai:mcp:edit} 的非超管可把端点指向任意公网 MCP 服务器并携带本地
 * headers（含凭据）发过去，headers 里的 Authorization/token 又是明文落库——
 * 「编辑 MCP 服务器 = 携带本地凭据外联任意端点」。修复后建/改仅超管可执行；
 * 删除/刷新沿用 {@code admin:ai:mcp:edit}（刷新只使用已存配置，HTTP 面无目标入参）；
 * 出参 headers 敏感值一律脱敏为 {@code ******}。</p>
 */
class AiAdminMcpControllerPermissionTest {

    private static final Long SERVER_ID = 813L;
    private static final String OPERATOR = "910047";

    @Test
    void creatingOrUpdatingServerRequiresSuperThroughTheActualControllerProxy() {
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("admin:ai:mcp:edit"));
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        SaTokenContext previousContext = SaManager.getSaTokenContext();
        StpInterface previousPermissions = SaManager.getStpInterface();
        var previousBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(910047L);
            AiMcpServerService service = mock(AiMcpServerService.class);
            when(service.toolsOfServer(any())).thenReturn(List.of());
            AspectJProxyFactory factory = new AspectJProxyFactory(new AiAdminMcpController(service));
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            AiAdminMcpController controller = factory.getProxy();

            AiAdminDtos.McpServerSaveReq body = new AiAdminDtos.McpServerSaveReq(
                    "audit_mcp_fixture",
                    "审计 MCP 夹具",
                    // 公网测试夹具（与既有工具测试同源），且 service 已被 mock，不会发起真实网络请求。
                    "https://93.184.216.34/mcp",
                    "{\"Authorization\":\"Bearer abc\",\"X-Trace-Id\":\"trace-1\"}",
                    true,
                    null);
            when(service.create(any(), any())).thenReturn(server());

            BusinessException createDenied = assertThrows(
                    BusinessException.class,
                    () -> controller.createServer(body, request()),
                    "delegated MCP editing must not grant authority to aim credential-bearing servers");
            assertEquals("COMMON005", createDenied.getErrorCode());
            verify(service, never()).create(any(), any());

            held.set(List.of("*:*:*"));
            var created = controller.createServer(body, request());
            assertEquals(200, created.getCode());
            assertEquals(SERVER_ID, created.getData().id());
            // 出参脱敏：敏感值绝不回显（无论存储形态是密文还是明文）。
            ObjectNode headers = AiJson.readObject(created.getData().headers());
            assertEquals("******", AiJson.text(headers, "Authorization", null));
            assertEquals("trace-1", AiJson.text(headers, "X-Trace-Id", null));
            verify(service).create(any(), any());

            held.set(List.of("admin:ai:mcp:edit"));
            BusinessException updateDenied = assertThrows(
                    BusinessException.class,
                    () -> controller.updateServer(SERVER_ID, body, request()),
                    "delegated MCP editing must not grant authority to redirect stored credentials");
            assertEquals("COMMON005", updateDenied.getErrorCode());
            verify(service, never()).update(any(), any(), any());

            held.set(List.of("*:*:*"));
            when(service.update(any(), any(), any())).thenReturn(server());
            var updated = controller.updateServer(SERVER_ID, body, request());
            assertEquals(200, updated.getCode());
            assertEquals(SERVER_ID, updated.getData().id());
            ObjectNode updatedHeaders = AiJson.readObject(updated.getData().headers());
            assertEquals("******", AiJson.text(updatedHeaders, "Authorization", null));
            verify(service).update(any(), any(), any());
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
    void refreshAndDeleteStayWithinDelegatedMcpEditUsingStoredConfigOnly() {
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("admin:ai:mcp:edit"));
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        SaTokenContext previousContext = SaManager.getSaTokenContext();
        StpInterface previousPermissions = SaManager.getStpInterface();
        var previousBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(910047L);
            AiMcpServerService service = mock(AiMcpServerService.class);
            when(service.toolsOfServer(any())).thenReturn(List.of());
            when(service.refresh(SERVER_ID, 910047L)).thenReturn(server());
            AspectJProxyFactory factory = new AspectJProxyFactory(new AiAdminMcpController(service));
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            AiAdminMcpController controller = factory.getProxy();

            // 刷新只使用已存配置：HTTP 面仅 id，不携带目标地址或请求头。
            var refreshed = controller.refresh(SERVER_ID, request());
            assertEquals(200, refreshed.getCode());
            assertEquals(3, refreshed.getData().toolCount());
            ObjectNode headers = AiJson.readObject(refreshed.getData().headers());
            assertEquals("******", AiJson.text(headers, "Authorization", null));
            assertEquals("trace-1", AiJson.text(headers, "X-Trace-Id", null));
            verify(service).refresh(SERVER_ID, 910047L);
            verify(service, never()).create(any(), any());
            verify(service, never()).update(any(), any(), any());

            var deleted = controller.deleteServer(SERVER_ID, request());
            assertEquals(200, deleted.getCode());
            assertNull(deleted.getData());
            verify(service).delete(SERVER_ID, 910047L);
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

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/ai/mcp-servers");
        request.setAttribute("userId", OPERATOR);
        return request;
    }

    private static AiMcpServerEntity server() {
        AiMcpServerEntity entity = new AiMcpServerEntity();
        entity.setId(SERVER_ID);
        entity.setServerCode("audit_mcp_fixture");
        entity.setName("审计 MCP 夹具");
        entity.setUrl("https://93.184.216.34/mcp");
        // 已加密的落库形态：出参必须脱敏，绝不回密文或明文。
        entity.setHeadersJson("{\"Authorization\":\"v1:aa:bb\",\"X-Trace-Id\":\"trace-1\"}");
        entity.setReadOnly(true);
        entity.setStatus("ENABLED");
        entity.setToolCount(3);
        return entity;
    }
}
