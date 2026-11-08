package com.astral.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiUsageEntity;
import com.astral.ai.service.AiChatOrchestrator;
import com.astral.ai.service.AiUsageService;
import com.astral.auth.security.PermissionAspect;
import com.astral.auth.security.PermissionChecker;
import com.astral.common.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiReconciliationPermissionTest {
    @Test
    void reconciliationNeedsDedicatedPermissionsAndHttpBodyCannotChooseOperator() throws Exception {
        var held = new AtomicReference<>(List.of("admin:ai:usage:view", "admin:ai:tool:approve"));
        var permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        var oldContext = SaManager.getSaTokenContext();
        var oldPermissions = SaManager.getStpInterface();
        var oldBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(41L);
            var usage = mock(AiUsageService.class);
            var orchestrator = mock(AiChatOrchestrator.class);
            var usageController = secured(new AiAdminUsageController(null, null, usage, null, null), permissions);
            var toolController =
                    secured(new AiAdminToolController(null, null, null, null, null, orchestrator), permissions);
            var request = new MockHttpServletRequest();
            request.setAttribute("userId", "41");
            var body = new AiAdminDtos.UsageReconcileReq(50, 20, "verified fixture");
            assertEquals(
                    "COMMON005",
                    assertThrows(BusinessException.class, () -> usageController.reconcile(83L, body, request))
                            .getErrorCode());
            assertEquals(
                    "COMMON005",
                    assertThrows(
                                    BusinessException.class,
                                    () -> toolController.reconcile(
                                            84L,
                                            new AiAdminDtos.ToolReconcileReq("SUCCEEDED", "verified fixture"),
                                            request))
                            .getErrorCode());
            verifyNoInteractions(usage, orchestrator);
            held.set(List.of("admin:ai:usage:reconcile", "admin:ai:tool:reconcile"));
            var row = new AiUsageEntity();
            row.setId(83L);
            when(usage.reconcile(83L, 50, 20, 41L, "verified fixture")).thenReturn(row);
            when(orchestrator.reconcileTool(84L, 41L, "SUCCEEDED", "verified fixture"))
                    .thenReturn(Map.of("status", "SUCCEEDED"));
            var mvc = MockMvcBuilders.standaloneSetup(usageController, toolController)
                    .build();
            assertEquals(
                    400,
                    mvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/ai/usage/83/reconcile")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"inputTokens\":-1,\"outputTokens\":0,\"note\":\"\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            verifyNoInteractions(usage, orchestrator);
            var response = mvc.perform(
                            MockMvcRequestBuilders.post("/api/v1/admin/ai/usage/83/reconcile")
                                    .requestAttr("userId", "41")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"inputTokens\":50,\"outputTokens\":20,\"note\":\"verified fixture\",\"operator\":999}"))
                    .andReturn()
                    .getResponse();
            assertEquals(200, response.getStatus());
            verify(usage).reconcile(83L, 50, 20, 41L, "verified fixture");
            assertEquals(
                    200,
                    mvc.perform(
                                    MockMvcRequestBuilders.post("/api/v1/admin/ai/tool-calls/84/reconcile")
                                            .requestAttr("userId", "41")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"outcome\":\"SUCCEEDED\",\"note\":\"verified fixture\",\"operator\":999}"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            verify(orchestrator).reconcileTool(84L, 41L, "SUCCEEDED", "verified fixture");
        } finally {
            try {
                if (StpUtil.isLogin()) StpUtil.logout();
            } finally {
                if (oldBox == null) SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.remove();
                else SaTokenContextForThreadLocalStaff.modelBoxThreadLocal.set(oldBox);
                SaManager.setStpInterface(oldPermissions);
                SaManager.setSaTokenContext(oldContext);
            }
        }
    }

    private static <T> T secured(T controller, StpInterface permissions) {
        var factory = new AspectJProxyFactory(controller);
        factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
        return factory.getProxy();
    }
}
