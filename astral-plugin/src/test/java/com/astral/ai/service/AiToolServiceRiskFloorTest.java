package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiToolMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 后台编辑工具时的风险下限。
 *
 * <p>声明式工具在后台只能被抬高，不能被放宽到执行器要求之下：非 GET 的 HTTP 工具与可写的
 * MCP 工具强制 {@code HIGH + ADMIN_CONFIRM}。这条规则此前只对代码工具生效，因为
 * {@code update} 用 {@code handlers.get(handlerKey)} 取风险，声明式工具的 handlerKey
 * 不在代码注册表里，取到 null 就会把守卫整段跳过。</p>
 */
class AiToolServiceRiskFloorTest {

    private AiToolMapper toolMapper;
    private AiMcpServerMapper mcpServerMapper;
    private AiToolService service;

    @BeforeEach
    void setUp() {
        toolMapper = mock(AiToolMapper.class);
        mcpServerMapper = mock(AiMcpServerMapper.class);
        service = new AiToolService(
                toolMapper,
                mock(AiAgentToolMapper.class),
                List.of(),
                new AiProperties(),
                mock(JdbcTemplate.class),
                mcpServerMapper);
        service.init();
        when(toolMapper.updateById(any(AiToolEntity.class))).thenReturn(1);
    }

    private AiToolEntity givenTool(String executorType, String executorConfig) {
        AiToolEntity entity = new AiToolEntity();
        entity.setId(1L);
        entity.setToolCode("demo_tool");
        entity.setName("演示工具");
        entity.setExecutorType(executorType);
        entity.setExecutorConfig(executorConfig);
        entity.setRiskLevel(AiConstants.RiskLevel.HIGH);
        entity.setApprovalPolicy(AiConstants.ApprovalPolicy.ADMIN_CONFIRM);
        when(toolMapper.selectById(anyLong())).thenReturn(entity);
        return entity;
    }

    private void givenServer(boolean readOnly) {
        AiMcpServerEntity server = new AiMcpServerEntity();
        server.setId(9L);
        server.setServerCode("ops");
        server.setName("运维 MCP");
        server.setUrl("https://93.184.216.34/mcp");
        server.setReadOnly(readOnly);
        server.setStatus("ENABLED");
        when(mcpServerMapper.selectById(anyLong())).thenReturn(server);
    }

    @Test
    void httpWriteToolCannotBeDowngraded() {
        AiToolEntity tool = givenTool(
                AiConstants.ExecutorType.HTTP, "{\"method\":\"POST\",\"url\":\"https://api.example.com/orders\"}");
        AiException error = assertThrows(
                AiException.class, () -> service.update(1L, null, null, AiConstants.RiskLevel.READ, null, null, 1L));
        assertTrue(error.getMessage().contains("最低 HIGH"), error.getMessage());
        assertEquals(AiConstants.RiskLevel.HIGH, tool.getRiskLevel());
    }

    @Test
    void httpWriteToolCannotDropApproval() {
        AiToolEntity tool = givenTool(
                AiConstants.ExecutorType.HTTP, "{\"method\":\"DELETE\",\"url\":\"https://api.example.com/orders\"}");
        AiException error = assertThrows(
                AiException.class,
                () -> service.update(1L, null, null, null, AiConstants.ApprovalPolicy.NONE, null, 1L));
        assertTrue(error.getMessage().contains("必须保留人工确认"), error.getMessage());
        assertEquals(AiConstants.ApprovalPolicy.ADMIN_CONFIRM, tool.getApprovalPolicy());
    }

    @Test
    void httpReadToolCanBeAdjusted() {
        AiToolEntity tool = givenTool(
                AiConstants.ExecutorType.HTTP, "{\"method\":\"GET\",\"url\":\"https://api.example.com/orders\"}");
        service.update(1L, null, null, AiConstants.RiskLevel.DRAFT, AiConstants.ApprovalPolicy.NONE, null, 1L);
        assertEquals(AiConstants.RiskLevel.DRAFT, tool.getRiskLevel());
        assertEquals(AiConstants.ApprovalPolicy.NONE, tool.getApprovalPolicy());
    }

    @Test
    void unknownRiskLevelIsRejected() {
        givenTool(AiConstants.ExecutorType.HTTP, "{\"method\":\"GET\",\"url\":\"https://api.example.com/orders\"}");
        AiException error =
                assertThrows(AiException.class, () -> service.update(1L, null, null, "SUPER", null, null, 1L));
        assertTrue(error.getMessage().contains("风险等级非法"), error.getMessage());
    }

    @Test
    void mcpToolOnWritableServerCannotBeDowngraded() {
        givenServer(false);
        givenTool(AiConstants.ExecutorType.MCP, "{\"serverId\":9,\"toolName\":\"create_issue\"}");
        AiException error = assertThrows(
                AiException.class, () -> service.update(1L, null, null, AiConstants.RiskLevel.READ, null, null, 1L));
        assertTrue(error.getMessage().contains("最低 HIGH"), error.getMessage());
    }

    @Test
    void mcpToolOnWritableServerCannotDropApproval() {
        givenServer(false);
        givenTool(AiConstants.ExecutorType.MCP, "{\"serverId\":9,\"toolName\":\"create_issue\"}");
        AiException error = assertThrows(
                AiException.class,
                () -> service.update(1L, null, null, null, AiConstants.ApprovalPolicy.NONE, null, 1L));
        assertTrue(error.getMessage().contains("必须保留人工确认"), error.getMessage());
    }

    @Test
    void mcpToolOnReadOnlyServerCanBeRead() {
        givenServer(true);
        AiToolEntity tool = givenTool(
                AiConstants.ExecutorType.MCP, "{\"serverId\":9,\"toolName\":\"search_issue\",\"readOnlyHint\":true}");
        service.update(1L, null, null, AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE, null, 1L);
        assertEquals(AiConstants.RiskLevel.READ, tool.getRiskLevel());
        assertEquals(AiConstants.ApprovalPolicy.NONE, tool.getApprovalPolicy());
    }

    @Test
    void readOnlyServerDoesNotOverrideRemoteWriteHint() {
        givenServer(true);
        givenTool(
                AiConstants.ExecutorType.MCP, "{\"serverId\":9,\"toolName\":\"create_issue\",\"readOnlyHint\":false}");
        AiException error = assertThrows(
                AiException.class, () -> service.update(1L, null, null, AiConstants.RiskLevel.READ, null, null, 1L));
        assertTrue(error.getMessage().contains("最低 HIGH"), error.getMessage());
    }

    @Test
    void sqlToolFloorIsRead() {
        AiToolEntity tool = givenTool(
                AiConstants.ExecutorType.SQL, "{\"sql\":\"select id from astral.sys_user\",\"columns\":[\"id\"]}");
        service.update(1L, null, null, AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE, null, 1L);
        assertEquals(AiConstants.RiskLevel.READ, tool.getRiskLevel());
    }
}
