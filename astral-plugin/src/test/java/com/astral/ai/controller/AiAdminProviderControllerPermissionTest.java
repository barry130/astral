package com.astral.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.provider.AiProviderDtos.HealthResult;
import com.astral.ai.service.AiModelService;
import com.astral.ai.service.AiProviderService;
import com.astral.auth.security.PermissionAspect;
import com.astral.auth.security.PermissionChecker;
import com.astral.common.exception.BusinessException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * R8 提供方提权契约。
 *
 * <p>期望值锚定审计契约原文（docs/audit/2026-11-07/backend-ai-read-recovery.md R8）：
 * 仅持 {@code admin:ai:provider:edit} 的非超管可把 baseUrl 改成任意公网端点并保留
 * {@code secretRef=环境变量名}，再用连通性测试把已存明文密钥发往攻击者域名——
 * 「编辑供应商 = 读取环境密钥」。修复后 provider 建/改仅超管可执行（secretRef 随之
 * 被同一门禁覆盖）；连通性测试保留给已下放的编辑权限，且只接受已存配置
 * （HTTP 面仅 id + 可选 modelCode，无目标地址入参）。</p>
 */
class AiAdminProviderControllerPermissionTest {

    private static final Long PROVIDER_ID = 812L;
    private static final String OPERATOR = "910046";

    @Test
    void creatingOrUpdatingProviderRequiresSuperThroughTheActualControllerProxy() {
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("admin:ai:provider:edit"));
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        SaTokenContext previousContext = SaManager.getSaTokenContext();
        StpInterface previousPermissions = SaManager.getStpInterface();
        var previousBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(910046L);
            AiProviderService service = mock(AiProviderService.class);
            AiModelService modelService = mock(AiModelService.class);
            when(modelService.list(any())).thenReturn(List.of());
            when(service.credentialSource(any())).thenReturn("NONE");
            AspectJProxyFactory factory =
                    new AspectJProxyFactory(new AiAdminProviderController(service, modelService, null, null));
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            AiAdminProviderController controller = factory.getProxy();

            AiAdminDtos.ProviderSaveReq body = new AiAdminDtos.ProviderSaveReq(
                    "audit_provider_fixture",
                    "Audit provider fixture",
                    "MOCK",
                    // 公网测试夹具（与既有工具测试同源），且 service 已被 mock，不会发起真实网络请求。
                    "https://93.184.216.34/v1",
                    null,
                    "ASTRAL_AI_PROVIDER_KEY",
                    true,
                    15000,
                    120000,
                    1,
                    100,
                    null);
            when(service.create(
                            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(provider());

            BusinessException createDenied = assertThrows(
                    BusinessException.class,
                    () -> controller.createProvider(body, request()),
                    "delegated provider editing must not grant authority to create providers");
            assertEquals("COMMON005", createDenied.getErrorCode());
            verify(service, never())
                    .create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

            held.set(List.of("*:*:*"));
            var created = controller.createProvider(body, request());
            assertEquals(200, created.getCode());
            assertEquals(PROVIDER_ID, created.getData().id());
            assertEquals("audit_provider_fixture", created.getData().providerCode());
            assertEquals("NONE", created.getData().credentialSource());
            verify(service)
                    .create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

            held.set(List.of("admin:ai:provider:edit"));
            BusinessException updateDenied = assertThrows(
                    BusinessException.class,
                    () -> controller.updateProvider(PROVIDER_ID, body, request()),
                    "delegated provider editing must not grant authority to redirect stored credentials");
            assertEquals("COMMON005", updateDenied.getErrorCode());
            verify(service, never())
                    .update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

            held.set(List.of("*:*:*"));
            when(service.update(
                            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(provider());
            var updated = controller.updateProvider(PROVIDER_ID, body, request());
            assertEquals(200, updated.getCode());
            assertEquals(PROVIDER_ID, updated.getData().id());
            verify(service)
                    .update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
    void connectivityTestStaysAvailableToDelegatedEditorsWithoutAcceptingTargets() {
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("admin:ai:provider:edit"));
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        SaTokenContext previousContext = SaManager.getSaTokenContext();
        StpInterface previousPermissions = SaManager.getStpInterface();
        var previousBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(910046L);
            AiProviderService service = mock(AiProviderService.class);
            when(service.require(PROVIDER_ID)).thenReturn(provider());
            when(service.test(PROVIDER_ID, "fixture-model")).thenReturn(HealthResult.ok(12L));
            AspectJProxyFactory factory = new AspectJProxyFactory(
                    new AiAdminProviderController(service, mock(AiModelService.class), null, null));
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            AiAdminProviderController controller = factory.getProxy();

            // 测试端点仅接受 id 与可选 modelCode；目标地址只能来自已存配置，HTTP 面不存在目标入参。
            var tested = controller.testProvider(PROVIDER_ID, "fixture-model");
            assertEquals(200, tested.getCode());
            assertTrue(tested.getData().healthy());
            assertEquals("UNKNOWN", tested.getData().healthStatus());
            verify(service).test(PROVIDER_ID, "fixture-model");
            verify(service, never())
                    .update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/ai/providers");
        request.setAttribute("userId", OPERATOR);
        return request;
    }

    private static AiProviderEntity provider() {
        AiProviderEntity entity = new AiProviderEntity();
        entity.setId(PROVIDER_ID);
        entity.setProviderCode("audit_provider_fixture");
        entity.setName("Audit provider fixture");
        entity.setProviderType("MOCK");
        entity.setBaseUrl("https://93.184.216.34/v1");
        entity.setEnabled(true);
        entity.setSecretRef("ASTRAL_AI_PROVIDER_KEY");
        entity.setConnectTimeoutMs(15000);
        entity.setReadTimeoutMs(120000);
        entity.setMaxRetries(1);
        entity.setWeight(100);
        entity.setHealthStatus("UNKNOWN");
        return entity;
    }
}
