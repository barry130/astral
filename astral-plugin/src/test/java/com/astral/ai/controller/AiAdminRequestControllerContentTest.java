package com.astral.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiConversationEntity;
import com.astral.ai.entity.AiMessageEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.entity.AiRequestEventEntity;
import com.astral.ai.entity.AiToolCallEntity;
import com.astral.ai.mapper.AiAgentKnowledgeMapper;
import com.astral.ai.mapper.AiAgentMapper;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiAgentVersionMapper;
import com.astral.ai.mapper.AiConversationMapper;
import com.astral.ai.mapper.AiMessageMapper;
import com.astral.ai.mapper.AiRequestEventMapper;
import com.astral.ai.mapper.AiRequestMapper;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.service.AiAgentService;
import com.astral.ai.service.AiEventService;
import com.astral.ai.service.AiRequestService;
import com.astral.auth.registry.PermissionRegistry;
import com.astral.auth.security.PermissionAspect;
import com.astral.auth.security.PermissionCache;
import com.astral.auth.security.PermissionCatalog;
import com.astral.auth.security.PermissionChecker;
import com.astral.common.constant.PermissionType;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.OperateLog;
import com.astral.dao.mapper.LoginLogMapper;
import com.astral.dao.mapper.OperateLogMapper;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.log.aspect.OperateLogAspect;
import com.astral.log.aspect.OperateLogWriter;
import com.astral.log.service.impl.LogServiceImpl;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/** Actual public controller and DTO serialization; only external database mappers are replaced. */
class AiAdminRequestControllerContentTest {
    @Test
    void aContentQueryCannotExposePayloadsThroughTheMetadataEndpoint() {
        AiAdminRequestController controller = controllerWithConfidentialRows();

        var response = controller.request(101L, true);

        assertNotNull(response.getData());
        assertEquals(101L, response.getData().request().id());
        assertFalse(
                AiJson.write(response.getData()).contains("CONFIDENTIAL_"),
                "the metadata route must not expose input, snapshots, tool payloads, events, messages or raw errors");
    }

    @Test
    void aContentReadIsAuditedWithoutCopyingItsConfidentialPayloads() {
        OperateLogMapper logs = mock(OperateLogMapper.class);
        OperateLogWriter writer = new OperateLogWriter(new LogServiceImpl(logs, mock(LoginLogMapper.class)));
        AspectJProxyFactory factory = new AspectJProxyFactory(controllerWithConfidentialRows());
        factory.addAspect(new OperateLogAspect(writer, new ObjectMapper()));
        AiAdminRequestController controller = factory.getProxy();
        String path = "/api/v1/admin/ai/requests/101/content";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute("userId", "42");
        request.setAttribute("username", "audit-fixture-user");
        RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var response = controller.requestContent(101L);
            assertNotNull(response.getData());
            assertTrue(
                    AiJson.write(response.getData()).contains("CONFIDENTIAL_INPUT"),
                    "the content route must retain the legitimate caller's full content");
            ArgumentCaptor<OperateLog> saved = ArgumentCaptor.forClass(OperateLog.class);
            verify(logs).insert(saved.capture());
            OperateLog audit = saved.getValue();
            assertEquals(42L, audit.getUserId());
            assertEquals(path, audit.getRequestUrl());
            assertEquals("GET", audit.getRequestMethod());
            assertEquals(1, audit.getStatus());
            assertFalse(
                    AiJson.write(audit).contains("CONFIDENTIAL_"),
                    "an audit reader must not gain access to confidential content through operation logs");
        } finally {
            RequestContextHolder.setRequestAttributes(previousAttributes);
        }
    }

    @Test
    void aFailedContentReadRetainsItsOutcomeWithoutAuditingPrivateExceptionMessages() {
        OperateLogMapper logs = mock(OperateLogMapper.class);
        OperateLogWriter writer = new OperateLogWriter(new LogServiceImpl(logs, mock(LoginLogMapper.class)));
        IllegalStateException failure = new IllegalStateException("CONFIDENTIAL_REQUEST_FAILURE");
        AspectJProxyFactory factory = new AspectJProxyFactory(controllerWithConfidentialRows(failure));
        factory.addAspect(new OperateLogAspect(writer, new ObjectMapper()));
        AiAdminRequestController controller = factory.getProxy();
        String path = "/api/v1/admin/ai/requests/101/content";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute("userId", "42");
        request.setAttribute("username", "audit-fixture-user");
        RequestAttributes outerAttributes = RequestContextHolder.getRequestAttributes();
        ServletRequestAttributes previousAttributes =
                new ServletRequestAttributes(new MockHttpServletRequest("GET", "/audit-only/previous-context"));
        RequestContextHolder.setRequestAttributes(previousAttributes);
        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                assertSame(
                        failure,
                        assertThrows(IllegalStateException.class, () -> controller.requestContent(101L)),
                        "the logging side channel must retain the original application failure");
                ArgumentCaptor<OperateLog> saved = ArgumentCaptor.forClass(OperateLog.class);
                verify(logs).insert(saved.capture());
                OperateLog audit = saved.getValue();
                assertEquals(42L, audit.getUserId());
                assertEquals(path, audit.getRequestUrl());
                assertEquals(0, audit.getStatus());
                assertEquals("", audit.getRequestParams());
                assertEquals("", audit.getResponseResult());
                assertEquals("IllegalStateException", audit.getErrorMsg());
                assertFalse(
                        AiJson.write(audit).contains("CONFIDENTIAL_"),
                        "failure audit metadata must not grant access to the private exception message");
            } finally {
                RequestContextHolder.setRequestAttributes(previousAttributes);
            }
            assertSame(
                    previousAttributes,
                    RequestContextHolder.getRequestAttributes(),
                    "the test's request boundary must restore an existing caller context, not clear it");
        } finally {
            RequestContextHolder.setRequestAttributes(outerAttributes);
        }
    }

    @Test
    void registeredContentAuthorityRetainsItsDataTypeSeparatelyFromRequestApiAuthority() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(
                    "auditRequestController", AiAdminRequestController.class, () -> controllerWithConfidentialRows());
            context.refresh();
            PermissionMapper permissions = mock(PermissionMapper.class);
            PermissionCache cache = new PermissionCache();
            PermissionCatalog catalog = new PermissionCatalog(permissions, cache);
            PermissionRegistry registry = new PermissionRegistry(context, permissions, catalog, cache);

            var declarations = registry.collectDeclarations();

            assertNotNull(declarations.get("admin:ai:request:view"));
            assertNotNull(declarations.get("admin:ai:conversation:content:view"));
            assertEquals(
                    PermissionType.API,
                    declarations.get("admin:ai:request:view").getType());
            assertEquals(
                    PermissionType.DATA,
                    declarations.get("admin:ai:conversation:content:view").getType(),
                    "the content scope must not be re-registered as an API permission by the controller");
        }
    }

    @Test
    void contentAccessRequiresBothApiAndDataAuthoritiesThroughActualPermissionAop() {
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("admin:ai:request:view"));
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        SaTokenContext previousContext = SaManager.getSaTokenContext();
        StpInterface previousPermissions = SaManager.getStpInterface();
        var previousBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(910042L);
            AspectJProxyFactory factory = new AspectJProxyFactory(controllerWithConfidentialRows());
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            AiAdminRequestController controller = factory.getProxy();

            assertThrows(
                    BusinessException.class,
                    () -> controller.requestContent(101L),
                    "request-view alone must not authorize confidential content");
            held.set(List.of("admin:ai:conversation:content:view"));
            assertThrows(
                    BusinessException.class,
                    () -> controller.requestContent(101L),
                    "content-view alone must not erase the independent request API requirement");
            held.set(List.of("admin:ai:request:view", "admin:ai:conversation:content:view"));
            assertTrue(
                    AiJson.write(controller.requestContent(101L).getData()).contains("CONFIDENTIAL_INPUT"),
                    "the legitimate caller with both authorities must still receive full content");
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
    void conversationContentRequiresBothAuthoritiesThroughActualPermissionAop() {
        AtomicReference<List<String>> held = new AtomicReference<>(List.of("admin:ai:conversation:view"));
        StpInterface permissions = mock(StpInterface.class);
        when(permissions.getPermissionList(any(), any())).thenAnswer(ignored -> held.get());
        SaTokenContext previousContext = SaManager.getSaTokenContext();
        StpInterface previousPermissions = SaManager.getStpInterface();
        var previousBox = SaTokenContextForThreadLocalStaff.getModelBoxOrNull();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setStpInterface(permissions);
        try {
            SaTokenContextMockUtil.setMockContext();
            StpUtil.login(910042L);
            AspectJProxyFactory factory = new AspectJProxyFactory(controllerWithConfidentialRows());
            factory.addAspect(new PermissionAspect(new PermissionChecker(permissions)));
            AiAdminRequestController controller = factory.getProxy();

            assertEquals(
                    "COMMON005",
                    assertThrows(BusinessException.class, () -> controller.conversationMessages(701L, null, null))
                            .getErrorCode(),
                    "conversation API authority alone must not authorize confidential messages");
            held.set(List.of("admin:ai:conversation:content:view"));
            assertEquals(
                    "COMMON005",
                    assertThrows(BusinessException.class, () -> controller.conversationMessages(701L, null, null))
                            .getErrorCode(),
                    "DATA authority must not replace the independent conversation API authority");
            held.set(List.of("admin:ai:conversation:view", "admin:ai:conversation:content:view"));
            var response = controller.conversationMessages(701L, null, null);
            assertEquals(1, response.getData().size());
            assertEquals(701L, response.getData().getFirst().conversationId());
            assertEquals("CONFIDENTIAL_MESSAGE", response.getData().getFirst().content());
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
    void aConversationContentReadIsAuditedWithoutCopyingItsConfidentialPayloads() {
        OperateLogMapper logs = mock(OperateLogMapper.class);
        OperateLogWriter writer = new OperateLogWriter(new LogServiceImpl(logs, mock(LoginLogMapper.class)));
        AspectJProxyFactory factory = new AspectJProxyFactory(controllerWithConfidentialRows());
        factory.addAspect(new OperateLogAspect(writer, new ObjectMapper()));
        AiAdminRequestController controller = factory.getProxy();
        String path = "/api/v1/admin/ai/conversation-audits/701/messages";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute("userId", "42");
        request.setAttribute("username", "audit-fixture-user");
        RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var response = controller.conversationMessages(701L, null, null);
            assertEquals("CONFIDENTIAL_MESSAGE", response.getData().getFirst().content());
            ArgumentCaptor<OperateLog> saved = ArgumentCaptor.forClass(OperateLog.class);
            verify(logs).insert(saved.capture());
            OperateLog audit = saved.getValue();
            assertEquals(42L, audit.getUserId());
            assertEquals(path, audit.getRequestUrl());
            assertEquals("GET", audit.getRequestMethod());
            assertEquals(1, audit.getStatus());
            assertEquals("", audit.getRequestParams());
            assertEquals("", audit.getResponseResult());
            assertFalse(
                    AiJson.write(audit).contains("CONFIDENTIAL_"),
                    "the audit side channel must not copy confidential conversation content");
        } finally {
            RequestContextHolder.setRequestAttributes(previousAttributes);
        }
    }

    @Test
    void aFailedConversationContentReadIsAuditedWithoutCopyingItsConfidentialFailure() {
        IllegalStateException originalFailure = new IllegalStateException("CONFIDENTIAL_MESSAGE_FAILURE");
        OperateLogMapper logs = mock(OperateLogMapper.class);
        OperateLogWriter writer = new OperateLogWriter(new LogServiceImpl(logs, mock(LoginLogMapper.class)));
        AspectJProxyFactory factory = new AspectJProxyFactory(controllerWithConfidentialRows(null, originalFailure));
        factory.addAspect(new OperateLogAspect(writer, new ObjectMapper()));
        AiAdminRequestController controller = factory.getProxy();
        String path = "/api/v1/admin/ai/conversation-audits/701/messages";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute("userId", "42");
        request.setAttribute("username", "audit-fixture-user");
        RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertSame(
                    originalFailure,
                    assertThrows(IllegalStateException.class, () -> controller.conversationMessages(701L, null, null)));
            ArgumentCaptor<OperateLog> saved = ArgumentCaptor.forClass(OperateLog.class);
            verify(logs).insert(saved.capture());
            OperateLog audit = saved.getValue();
            assertEquals(42L, audit.getUserId());
            assertEquals(path, audit.getRequestUrl());
            assertEquals("GET", audit.getRequestMethod());
            assertEquals(0, audit.getStatus());
            assertEquals("IllegalStateException", audit.getErrorMsg());
            assertEquals("", audit.getRequestParams());
            assertEquals("", audit.getResponseResult());
            assertFalse(AiJson.write(audit).contains("CONFIDENTIAL_"));
        } finally {
            RequestContextHolder.setRequestAttributes(previousAttributes);
        }
    }

    private AiAdminRequestController controllerWithConfidentialRows() {
        return controllerWithConfidentialRows(null);
    }

    private AiAdminRequestController controllerWithConfidentialRows(RuntimeException requestFailure) {
        return controllerWithConfidentialRows(requestFailure, null);
    }

    private AiAdminRequestController controllerWithConfidentialRows(
            RuntimeException requestFailure, RuntimeException messageFailure) {
        AiRequestMapper requests = mock(AiRequestMapper.class);
        AiAgentMapper agents = mock(AiAgentMapper.class);
        AiRequestEventMapper events = mock(AiRequestEventMapper.class);
        AiMessageMapper messages = mock(AiMessageMapper.class);
        AiToolCallMapper calls = mock(AiToolCallMapper.class);
        AiConversationMapper conversations = mock(AiConversationMapper.class);
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId(701L);
        conversation.setAgentId(7L);
        conversation.setUserId(42L);
        conversation.setStatus("ACTIVE");
        when(conversations.selectById(701L)).thenReturn(conversation);

        AiRequestEntity request = new AiRequestEntity();
        request.setId(101L);
        request.setAgentId(7L);
        request.setUserId(42L);
        request.setStatus("SUCCEEDED");
        request.setInputSnapshot("CONFIDENTIAL_INPUT");
        request.setContextSnapshot("CONFIDENTIAL_CONTEXT");
        request.setConfigurationSnapshot("CONFIDENTIAL_CONFIGURATION");
        request.setKnowledgeSnapshot("CONFIDENTIAL_KNOWLEDGE");
        request.setToolPolicySnapshot("CONFIDENTIAL_TOOL_POLICY");
        request.setSafetyPolicySnapshot("CONFIDENTIAL_SAFETY_POLICY");
        request.setErrorSummary("CONFIDENTIAL_ERROR_SUMMARY");
        if (requestFailure == null) {
            when(requests.selectById(101L)).thenReturn(request);
        } else {
            when(requests.selectById(101L)).thenThrow(requestFailure);
        }

        AiAgentEntity agent = new AiAgentEntity();
        agent.setId(7L);
        agent.setName("Audit fixture agent");
        when(agents.selectById(7L)).thenReturn(agent);

        AiToolCallEntity call = new AiToolCallEntity();
        call.setId(201L);
        call.setRequestId(101L);
        call.setToolCode("audit.fixture");
        call.setArgumentsJson("CONFIDENTIAL_TOOL_ARGUMENTS");
        call.setResultJson("CONFIDENTIAL_TOOL_RESULT");
        call.setErrorMessage("CONFIDENTIAL_TOOL_ERROR");
        when(calls.selectList(any())).thenReturn(List.of(call));

        AiRequestEventEntity event = new AiRequestEventEntity();
        event.setSequenceNo(1L);
        event.setEventType("MESSAGE_DELTA");
        event.setDataJson("CONFIDENTIAL_EVENT_DATA");
        when(events.selectList(any())).thenReturn(List.of(event));

        AiMessageEntity message = new AiMessageEntity();
        message.setId(301L);
        message.setConversationId(701L);
        message.setRequestId(101L);
        message.setRole("ASSISTANT");
        message.setContent("CONFIDENTIAL_MESSAGE");
        message.setBlocksJson("CONFIDENTIAL_BLOCKS");
        message.setCitationsJson("CONFIDENTIAL_CITATIONS");
        if (messageFailure == null) {
            when(messages.selectList(any())).thenReturn(List.of(message));
        } else {
            when(messages.selectList(any())).thenThrow(messageFailure);
        }

        return new AiAdminRequestController(
                new AiRequestService(requests, messages, null),
                new AiAgentService(
                        agents,
                        mock(AiAgentVersionMapper.class),
                        mock(AiAgentToolMapper.class),
                        mock(AiAgentKnowledgeMapper.class)),
                new AiEventService(events),
                conversations,
                messages,
                calls);
    }
}
