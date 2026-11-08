package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiAgentToolEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.security.AiToolPermissionGate;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolProvider;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.auth.security.PermissionChecker;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R5/R6 确认策略 floor 单调性：绑定与后台编辑都不能把「声明要确认」的工具降成自动执行。
 *
 * <p>期望值锚定审计契约原文（docs/audit/2026-11-07/backend-ai-read-recovery.md R5/R6）：
 * {@code ApprovalPolicy.order()}（NONE&lt;USER_CONFIRM&lt;ADMIN_CONFIRM，按 {@code all()} 声明序）
 * + {@code addBinding}/{@code update} 统一「目标 order ≥ floor order 且 ≥ 工具当前 order」，
 * 非法值 AI008；{@code riskOverride} 同样按 {@code riskFloor} 而非仅 DB 当前值比较。
 * 当前缺口：{@code addBinding} 的 approvalPolicy 只 toUpperCase 落库（无枚举校验、无下限）、
 * riskOverride 只比 DB 当前值；{@code update} 的确认下限只对 HIGH floor 生效。</p>
 */
class AiToolServiceApprovalFloorTest {

    private static final Long VERSION_ID = 6101L;
    private static final Long TOOL_ID = 812L;

    private AiToolMapper toolMapper;
    private AiAgentToolMapper agentToolMapper;
    private AiToolService service;

    @BeforeEach
    void setUp() {
        toolMapper = mock(AiToolMapper.class);
        agentToolMapper = mock(AiAgentToolMapper.class);
        when(agentToolMapper.lockVersionStatus(VERSION_ID)).thenReturn(AiConstants.VersionStatus.DRAFT);
        service = new AiToolService(
                toolMapper,
                agentToolMapper,
                List.of(new FixtureProvider()),
                new AiProperties(),
                mock(JdbcTemplate.class),
                mock(AiMcpServerMapper.class),
                new AiToolPermissionGate(mock(PermissionChecker.class)),
                mock(AiToolCallMapper.class));
        service.init();
        when(toolMapper.updateById(any(AiToolEntity.class))).thenReturn(1);
    }

    /** 代码工具：handler 声明 DRAFT + USER_CONFIRM（floor 即声明值），DB 行与声明一致。 */
    private AiToolEntity givenDeclaredConfirmTool() {
        AiToolEntity entity = new AiToolEntity();
        entity.setId(TOOL_ID);
        entity.setToolCode("audit_approval_tool");
        entity.setName("声明确认工具");
        entity.setHandlerKey("audit_approval_fixture");
        entity.setRiskLevel(AiConstants.RiskLevel.DRAFT);
        entity.setApprovalPolicy(AiConstants.ApprovalPolicy.USER_CONFIRM);
        when(toolMapper.selectById(anyLong())).thenReturn(entity);
        return entity;
    }

    /** 代码工具：handler 已把声明提到 HIGH，但 DB 行还是陈旧的 READ。 */
    private AiToolEntity givenStaleRiskTool() {
        AiToolEntity entity = new AiToolEntity();
        entity.setId(TOOL_ID);
        entity.setToolCode("audit_stale_risk_tool");
        entity.setName("陈旧风险工具");
        entity.setHandlerKey("audit_raised_fixture");
        entity.setRiskLevel(AiConstants.RiskLevel.READ);
        entity.setApprovalPolicy(AiConstants.ApprovalPolicy.USER_CONFIRM);
        when(toolMapper.selectById(anyLong())).thenReturn(entity);
        return entity;
    }

    @Test
    void bindingCannotLowerDeclaredConfirmationToNone() {
        givenDeclaredConfirmTool();

        AiException error = assertThrows(
                AiException.class,
                () -> service.addBinding(VERSION_ID, TOOL_ID, null, null, null, AiConstants.ApprovalPolicy.NONE));

        assertTrue(error.getMessage().contains("确认"), error.getMessage());
        verify(agentToolMapper, never()).insert(any(AiAgentToolEntity.class));
    }

    @Test
    void bindingRejectsIllegalApprovalValue() {
        givenDeclaredConfirmTool();

        assertThrows(AiException.class, () -> service.addBinding(VERSION_ID, TOOL_ID, null, null, null, "WHENEVER"));
        verify(agentToolMapper, never()).insert(any(AiAgentToolEntity.class));
    }

    @Test
    void bindingCannotLowerRiskBelowTheExecutorFloorUsingAStaleDatabaseRow() {
        givenStaleRiskTool();

        AiException error = assertThrows(
                AiException.class,
                () -> service.addBinding(VERSION_ID, TOOL_ID, null, null, AiConstants.RiskLevel.READ, null));

        assertTrue(error.getMessage().contains("风险"), error.getMessage());
        verify(agentToolMapper, never()).insert(any(AiAgentToolEntity.class));
    }

    @Test
    void updateCannotClearDeclaredConfirmationOnNonHighFloorTool() {
        AiToolEntity tool = givenDeclaredConfirmTool();

        AiException error = assertThrows(
                AiException.class,
                () -> service.update(TOOL_ID, null, null, null, AiConstants.ApprovalPolicy.NONE, null, 1L));

        assertTrue(error.getMessage().contains("确认"), error.getMessage());
        assertEquals(AiConstants.ApprovalPolicy.USER_CONFIRM, tool.getApprovalPolicy());
    }

    @Test
    void bindingRaiseToAdminConfirmIsAccepted() {
        givenDeclaredConfirmTool();

        AiAgentToolEntity binding =
                service.addBinding(VERSION_ID, TOOL_ID, null, null, null, AiConstants.ApprovalPolicy.ADMIN_CONFIRM);

        assertEquals(AiConstants.ApprovalPolicy.ADMIN_CONFIRM, binding.getApprovalPolicy());
        verify(agentToolMapper).insert(any(AiAgentToolEntity.class));
    }

    @Test
    void updateRaiseToAdminConfirmIsAccepted() {
        AiToolEntity tool = givenDeclaredConfirmTool();

        service.update(TOOL_ID, null, null, null, AiConstants.ApprovalPolicy.ADMIN_CONFIRM, null, 1L);

        assertEquals(AiConstants.ApprovalPolicy.ADMIN_CONFIRM, tool.getApprovalPolicy());
        verify(toolMapper).updateById(any(AiToolEntity.class));
    }

    @Test
    void highFloorStillForcesAdminConfirm() {
        AiToolEntity tool = givenDeclaredConfirmTool();
        tool.setExecutorType(com.astral.ai.common.AiConstants.ExecutorType.HTTP);
        tool.setExecutorConfig("{\"method\":\"POST\",\"url\":\"https://api.example.com/orders\"}");

        AiException error = assertThrows(
                AiException.class,
                () -> service.update(TOOL_ID, null, null, null, AiConstants.ApprovalPolicy.USER_CONFIRM, null, 1L));

        assertTrue(error.getMessage().contains("人工确认"), error.getMessage());
    }

    /** 一个 Bean 提供两个 fixture 处理器（与生产 provider 形态一致）。 */
    private static final class FixtureProvider implements AiToolProvider {
        @Override
        public List<AiToolHandler> handlers() {
            return List.of(new DeclaredConfirmTool(), new RaisedRiskTool());
        }
    }

    /** 声明 DRAFT + USER_CONFIRM 的代码工具 fixture。 */
    private static final class DeclaredConfirmTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "audit_approval_tool",
                    "声明确认工具",
                    "An isolated handler SPI fixture",
                    "audit_approval_fixture",
                    AiConstants.RiskLevel.DRAFT,
                    AiConstants.ApprovalPolicy.USER_CONFIRM,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT),
                    List.of(),
                    "{\"type\":\"object\"}",
                    null,
                    null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return AiToolResult.ok("executed", AiJson.mapper().createObjectNode());
        }
    }

    /** 声明被提到 HIGH 的代码工具 fixture（模拟 handler 升级后 DB 行陈旧）。 */
    private static final class RaisedRiskTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef(
                    "audit_stale_risk_tool",
                    "陈旧风险工具",
                    "An isolated handler SPI fixture",
                    "audit_raised_fixture",
                    AiConstants.RiskLevel.HIGH,
                    AiConstants.ApprovalPolicy.ADMIN_CONFIRM,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT),
                    List.of(),
                    "{\"type\":\"object\"}",
                    null,
                    null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            return AiToolResult.ok("executed", AiJson.mapper().createObjectNode());
        }
    }
}
