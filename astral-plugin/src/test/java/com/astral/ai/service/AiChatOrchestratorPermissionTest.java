package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.dev33.satoken.stp.StpInterface;
import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.security.AiToolPermissionGate;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.auth.security.PermissionChecker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The public worker execution boundary must use the original requester's live permissions. */
class AiChatOrchestratorPermissionTest {

    private static final Long REQUESTER = 41L;
    private static final String REQUIRED_PERMISSION = "admin:system:user:view";
    private final MutablePermissionSource permissions = new MutablePermissionSource();
    private final RecordingTool handler = new RecordingTool();
    private AiChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // The invoke seam does not use the model/conversation/storage orchestration collaborators.
        // No production permission logic or React/Spring implementation is mocked.
        orchestrator = new AiChatOrchestrator(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new AiProperties(),
                new AiToolPermissionGate(new PermissionChecker(permissions)));
    }

    @AfterEach
    void tearDown() {
        orchestrator.shutdown();
    }

    @Test
    void unprivilegedRequesterCannotExecuteAToolFromAnAsyncWorker() throws Exception {
        permissions.grants.put(REQUESTER, List.of());
        AiToolResult result = invokeFromWorker(false, null);

        assertEquals(AiToolResult.REJECTED, result.status());
        assertEquals(0, handler.calls.get());
        assertOriginalRequesterChecks(1);
    }

    @Test
    void toolPermissionCsvIsTrimmedAndNormalizedWithoutUppercasingClientRules() throws Exception {
        permissions.grants.put(REQUESTER, List.of(REQUIRED_PERMISSION, "admin:system:role:view"));
        AiToolResult result = invokeFromWorker(false, null, " ADMIN:SYSTEM:USER:VIEW , admin:system:role:view ");

        assertEquals(AiToolResult.SUCCEEDED, result.status());
        assertEquals(1, handler.calls.get());
        assertOriginalRequesterChecks(1);
    }

    @Test
    void malformedToolPermissionConfigurationIsRejectedEvenForAnApprovedSuperuser() throws Exception {
        permissions.grants.put(REQUESTER, List.of("*:*:*"));
        AiToolResult result = invokeFromWorker(true, 42L, "not a permission code");

        assertEquals(AiToolResult.REJECTED, result.status());
        assertEquals(0, handler.calls.get());
    }

    @Test
    void administratorApprovalCannotRestorePermissionsRevokedFromTheOriginalRequester() throws Exception {
        permissions.grants.put(REQUESTER, List.of(REQUIRED_PERMISSION));
        assertEquals(AiToolResult.SUCCEEDED, invokeFromWorker(false, null).status());
        assertEquals(1, handler.calls.get());

        permissions.grants.put(REQUESTER, List.of());
        permissions.grants.put(42L, List.of("*:*:*"));
        assertEquals(AiToolResult.REJECTED, invokeFromWorker(true, 42L).status());
        assertEquals(1, handler.calls.get(), "approval must not dispatch after requester revocation");
        assertOriginalRequesterChecks(2);
    }

    @Test
    void emptyStoredPermissionsCannotRemoveARegisteredHandlersPermissionFloor() throws Exception {
        permissions.grants.put(REQUESTER, List.of());
        AiToolResult result = invokeFromWorker(false, null, " ");

        assertEquals(AiToolResult.REJECTED, result.status());
        assertEquals(0, handler.calls.get());
    }

    @Test
    void separatorOnlyPermissionCsvCannotBecomeAPublicConfiguration() throws Exception {
        permissions.grants.put(REQUESTER, List.of("*:*:*"));
        AiToolResult result = invokeFromWorker(true, 42L, ",,");

        assertEquals(AiToolResult.REJECTED, result.status());
        assertEquals(0, handler.calls.get());
    }

    private void assertOriginalRequesterChecks(int minimumChecks) {
        assertTrue(permissions.checkedUsers.size() >= minimumChecks, "live requester permissions must be consulted");
        assertTrue(
                permissions.checkedUsers.stream().allMatch(REQUESTER::equals), "no approver or current-thread actor");
    }

    private AiToolResult invokeFromWorker(boolean approved, Long approvedBy) throws Exception {
        return invokeFromWorker(approved, approvedBy, REQUIRED_PERMISSION);
    }

    private AiToolResult invokeFromWorker(boolean approved, Long approvedBy, String permissionCsv) throws Exception {
        AiRequestEntity request = new AiRequestEntity();
        request.setId(101L);
        request.setConversationId(5L);
        request.setUserId(REQUESTER);
        request.setClientType(AiConstants.ClientType.ASTRAL_FRONT);
        request.setContextSnapshot("{}");
        AiAgentEntity agent = new AiAgentEntity();
        agent.setId(7L);
        agent.setAgentCode("audit-permission-agent");
        agent.setAgentType(AiConstants.AgentType.ADMIN);
        AiToolEntity tool = new AiToolEntity();
        tool.setId(1L);
        tool.setToolCode("audit_permission_tool");
        tool.setPermissionCodes(permissionCsv);
        AiToolService.EffectiveTool effective = new AiToolService.EffectiveTool(
                tool, handler, AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE, 0);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            return worker.submit(() -> orchestrator.invoke(
                            request, agent, effective, AiJson.mapper().createObjectNode(), approved, approvedBy))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    private static final class MutablePermissionSource implements StpInterface {
        private final Map<Object, List<String>> grants = new ConcurrentHashMap<>();
        private final List<Object> checkedUsers = new CopyOnWriteArrayList<>();

        @Override
        public List<String> getPermissionList(Object loginId, String loginType) {
            checkedUsers.add(loginId);
            return grants.getOrDefault(loginId, List.of());
        }

        @Override
        public List<String> getRoleList(Object loginId, String loginType) {
            return List.of();
        }
    }

    private static final class RecordingTool implements AiToolHandler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "audit_permission_tool",
                    "Permission fixture",
                    "An isolated handler SPI fixture",
                    "audit-permission-handler",
                    AiConstants.RiskLevel.READ,
                    AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT),
                    List.of(REQUIRED_PERMISSION),
                    "{\"type\":\"object\"}",
                    null,
                    null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            calls.incrementAndGet();
            return AiToolResult.ok("executed", AiJson.mapper().createObjectNode());
        }
    }
}
