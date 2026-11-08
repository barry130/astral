package com.astral.ai.service;

import static com.astral.ai.tool.AiToolTypes.emptySchema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpInterface;
import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiToolCallEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.mapper.AiToolMapper;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 后台试跑（admin test-run）必须绑定真实操作者的在册权限，并留下 {@code ai_tool_call} 审计行。
 */
class AiToolServiceTestRunTest {

    private static final Long OPERATOR = 910042L;
    private static final String REQUIRED_PERMISSION = "admin:system:user:view";

    private final MutablePermissionSource permissions = new MutablePermissionSource();
    private final RestrictedTool restrictedTool = new RestrictedTool();
    private final PlainTool plainTool = new PlainTool();
    private final List<AiToolCallEntity> insertedRows = new CopyOnWriteArrayList<>();
    private final List<AiToolCallEntity> updatedRows = new CopyOnWriteArrayList<>();
    private AiToolMapper toolMapper;
    private AiToolCallMapper toolCallMapper;
    private AiToolService service;

    @BeforeEach
    void setUp() {
        toolMapper = mock(AiToolMapper.class);
        toolCallMapper = mock(AiToolCallMapper.class);
        // Snapshots: the service mutates one entity across insert and updateById,
        // so captures clone the row state at each persistence call.
        when(toolCallMapper.insert(any(AiToolCallEntity.class))).thenAnswer(invocation -> {
            insertedRows.add(copyOf(invocation.getArgument(0, AiToolCallEntity.class)));
            return 1;
        });
        when(toolCallMapper.updateById(any(AiToolCallEntity.class))).thenAnswer(invocation -> {
            updatedRows.add(copyOf(invocation.getArgument(0, AiToolCallEntity.class)));
            return 1;
        });
        service = new AiToolService(
                toolMapper,
                mock(AiAgentToolMapper.class),
                List.of(() -> List.of(restrictedTool, plainTool)),
                new AiProperties(),
                mock(JdbcTemplate.class),
                mock(AiMcpServerMapper.class),
                new AiToolPermissionGate(new PermissionChecker(permissions)),
                toolCallMapper);
        // @PostConstruct registration does not run in a plain unit test; wire it here (same package).
        service.init();
    }

    @Test
    void unprivilegedOperatorCannotTestRun() {
        permissions.grants.put(OPERATOR, List.of());
        givenTool(1L, "audit_testrun_tool", "audit-testrun-handler", REQUIRED_PERMISSION);

        AiToolResult result = service.testRun(1L, "{}", OPERATOR);

        assertEquals(AiToolResult.REJECTED, result.status());
        assertEquals("无权限调用工具", result.summary());
        assertEquals(0, restrictedTool.calls.get());
        verify(toolCallMapper, never()).insert(any(AiToolCallEntity.class));
    }

    @Test
    void malformedPermissionConfigurationIsRejected() {
        permissions.grants.put(OPERATOR, List.of("*:*:*"));
        givenTool(1L, "audit_testrun_tool", "audit-testrun-handler", "not a permission code");

        AiToolResult result = service.testRun(1L, "{}", OPERATOR);

        assertEquals(AiToolResult.REJECTED, result.status());
        assertEquals("工具权限配置异常，已拒绝执行", result.summary());
        assertEquals(0, restrictedTool.calls.get());
        verify(toolCallMapper, never()).insert(any(AiToolCallEntity.class));
    }

    @Test
    void disabledToolCannotBeTestRun() {
        permissions.grants.put(OPERATOR, List.of("*:*:*"));
        givenTool(1L, "audit_testrun_tool", "audit-testrun-handler", REQUIRED_PERMISSION);
        AiToolEntity entity = toolMapper.selectById(1L);
        entity.setStatus(AiConstants.ToolStatus.DISABLED);

        AiException denied = assertThrows(AiException.class, () -> service.testRun(1L, "{}", OPERATOR));

        assertEquals("AI008", denied.getErrorCode());
        assertTrue(denied.getMessage().contains("工具已停用"), denied.getMessage());
        assertEquals(0, restrictedTool.calls.get());
        verify(toolCallMapper, never()).insert(any(AiToolCallEntity.class));
    }

    @Test
    void privilegedOperatorRunsAsTheOriginalActorAndIsAudited() {
        permissions.grants.put(OPERATOR, List.of(REQUIRED_PERMISSION));
        givenTool(1L, "audit_testrun_tool", "audit-testrun-handler", REQUIRED_PERMISSION);

        AiToolResult result = service.testRun(1L, "{\"x\":1}", OPERATOR);

        assertEquals(AiToolResult.SUCCEEDED, result.status());
        assertEquals(1, restrictedTool.calls.get());
        AiToolContext context = restrictedTool.contexts.get(0);
        assertEquals(OPERATOR, context.userId(), "the operator is the original actor");
        assertEquals(OPERATOR, context.approvedBy());
        assertTrue(context.approved(), "the manual super-admin trigger is the confirmation");

        assertEquals(1, insertedRows.size());
        AiToolCallEntity inserted = insertedRows.get(0);
        assertEquals(AiConstants.ToolCallSource.TEST_RUN, inserted.getSource());
        assertEquals("audit_testrun_tool", inserted.getToolCode());
        assertEquals("试跑夹具", inserted.getToolName());
        assertEquals(AiConstants.ToolCallStatus.RUNNING, inserted.getStatus());
        assertEquals(AiConstants.ApprovalPolicy.NONE, inserted.getApprovalStatus());
        assertEquals(OPERATOR, inserted.getApprovedBy());
        assertNull(inserted.getRequestId());
        assertEquals("{\"x\":1}", inserted.getArgumentsJson());
        assertNotNull(inserted.getApprovedAt());
        assertNotNull(inserted.getStartedAt());

        assertEquals(1, updatedRows.size());
        AiToolCallEntity updated = updatedRows.get(0);
        assertEquals(AiConstants.ToolCallStatus.SUCCEEDED, updated.getStatus());
        assertNotNull(updated.getFinishedAt());
        assertNull(updated.getErrorMessage());
    }

    @Test
    void plainToolWithoutPermissionRequirementsStaysTestable() {
        permissions.grants.put(OPERATOR, List.of());
        givenTool(2L, "audit_plain_tool", "audit-plain-handler", null);

        AiToolResult result = service.testRun(2L, "{}", OPERATOR);

        assertEquals(AiToolResult.SUCCEEDED, result.status());
        assertEquals(1, plainTool.calls.get());
        verify(toolCallMapper).insert(any(AiToolCallEntity.class));
    }

    private void givenTool(Long id, String toolCode, String handlerKey, String permissionCodes) {
        AiToolEntity entity = new AiToolEntity();
        entity.setId(id);
        entity.setToolCode(toolCode);
        entity.setName("试跑夹具");
        entity.setHandlerKey(handlerKey);
        entity.setExecutorType(AiConstants.ExecutorType.CODE);
        entity.setStatus(AiConstants.ToolStatus.ENABLED);
        entity.setRiskLevel(AiConstants.RiskLevel.READ);
        entity.setApprovalPolicy(AiConstants.ApprovalPolicy.NONE);
        entity.setPermissionCodes(permissionCodes);
        when(toolMapper.selectById(id)).thenReturn(entity);
    }

    private static AiToolCallEntity copyOf(AiToolCallEntity row) {
        AiToolCallEntity copy = new AiToolCallEntity();
        copy.setId(row.getId());
        copy.setRequestId(row.getRequestId());
        copy.setToolCode(row.getToolCode());
        copy.setToolName(row.getToolName());
        copy.setRiskLevel(row.getRiskLevel());
        copy.setStatus(row.getStatus());
        copy.setArgumentsJson(row.getArgumentsJson());
        copy.setResultJson(row.getResultJson());
        copy.setErrorMessage(row.getErrorMessage());
        copy.setApprovalStatus(row.getApprovalStatus());
        copy.setApprovedBy(row.getApprovedBy());
        copy.setApprovedAt(row.getApprovedAt());
        copy.setStartedAt(row.getStartedAt());
        copy.setFinishedAt(row.getFinishedAt());
        copy.setCreateTime(row.getCreateTime());
        copy.setUpdateTime(row.getUpdateTime());
        copy.setSource(row.getSource());
        return copy;
    }

    /** 独立 StpInterface 夹具：按登录 ID 提供在册权限（无外部依赖） */
    private static final class MutablePermissionSource implements StpInterface {
        private final Map<Object, List<String>> grants = new ConcurrentHashMap<>();

        @Override
        public List<String> getPermissionList(Object loginId, String loginType) {
            return grants.getOrDefault(loginId, List.of());
        }

        @Override
        public List<String> getRoleList(Object loginId, String loginType) {
            return List.of();
        }
    }

    /** 注册了权限下限的处理器夹具 */
    private static final class RestrictedTool implements AiToolHandler {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<AiToolContext> contexts = new CopyOnWriteArrayList<>();

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "audit_testrun_tool",
                    "试跑夹具",
                    "An isolated handler SPI fixture",
                    "audit-testrun-handler",
                    AiConstants.RiskLevel.READ,
                    AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT),
                    List.of(REQUIRED_PERMISSION),
                    emptySchema(),
                    null,
                    null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            calls.incrementAndGet();
            contexts.add(context);
            return AiToolResult.ok("executed", AiJson.mapper().createObjectNode());
        }
    }

    /** 无权限要求的处理器夹具（普通工具保持可试跑，不因本切片过度收紧） */
    private static final class PlainTool implements AiToolHandler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "audit_plain_tool",
                    "试跑夹具",
                    "An isolated handler SPI fixture",
                    "audit-plain-handler",
                    AiConstants.RiskLevel.READ,
                    AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT),
                    List.of(),
                    emptySchema(),
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
