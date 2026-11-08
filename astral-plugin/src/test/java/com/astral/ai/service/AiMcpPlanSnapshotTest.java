package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiAgentToolEntity;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.security.AiToolPermissionGate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiMcpPlanSnapshotTest {
    @Test
    void approvalTracksServerEndpointCredentialsAndWriteCapabilityWithoutMutatingPriorSnapshot() {
        AiToolEntity tool = new AiToolEntity();
        tool.setId(1L);
        tool.setToolCode("mcp_fixture");
        tool.setExecutorType("MCP");
        tool.setExecutorConfig("{\"serverId\":2,\"toolName\":\"fixture\"}");
        tool.setStatus("ENABLED");
        tool.setRiskLevel("READ");
        tool.setApprovalPolicy("NONE");
        AiAgentToolEntity binding = new AiAgentToolEntity();
        binding.setToolId(1L);
        binding.setEnabled(true);
        AiMcpServerEntity server = new AiMcpServerEntity();
        server.setId(2L);
        server.setUrl("https://93.184.216.34/mcp");
        server.setHeadersJson("{\"Authorization\":\"Bearer credential-a\"}");
        server.setReadOnly(true);
        server.setStatus("ENABLED");
        AiToolMapper tools = mock(AiToolMapper.class);
        AiAgentToolMapper bindings = mock(AiAgentToolMapper.class);
        AiMcpServerMapper servers = mock(AiMcpServerMapper.class);
        when(tools.selectById(1L)).thenReturn(tool);
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        when(servers.selectById(2L)).thenReturn(server);
        AiProperties properties = new AiProperties();
        AiToolService service = new AiToolService(
                tools,
                bindings,
                List.of(),
                properties,
                mock(JdbcTemplate.class),
                servers,
                mock(AiToolPermissionGate.class),
                mock(AiToolCallMapper.class));

        var planned = service.resolveEffective(3L, "ASTRAL_FRONT").getFirst();
        String initial = AiToolService.planHash(planned);
        server.setUrl("https://93.184.216.35/mcp");
        String moved = AiToolService.planHash(
                service.resolveEffective(3L, "ASTRAL_FRONT").getFirst());
        assertNotEquals(initial, moved);
        server.setHeadersJson("{\"Authorization\":\"Bearer credential-b\"}");
        String rotated = AiToolService.planHash(
                service.resolveEffective(3L, "ASTRAL_FRONT").getFirst());
        assertNotEquals(moved, rotated);
        server.setReadOnly(false);
        var writable = service.resolveEffective(3L, "ASTRAL_FRONT").getFirst();
        assertEquals("HIGH", writable.risk());
        assertEquals("ADMIN_CONFIRM", writable.approval());
        assertEquals(
                initial,
                AiToolService.planHash(planned),
                "the already planned handler keeps its own immutable server snapshot");
        server.setHeadersJson("{\"Authorization\":\"${secret:MCP_TOKEN}\"}");
        properties.getHttpTool().getSecrets().put("MCP_TOKEN", "tenant-a-credential");
        var referencePlan = service.resolveEffective(3L, "ASTRAL_FRONT").getFirst();
        String referenceHash = AiToolService.planHash(referencePlan);
        properties.getHttpTool().getSecrets().put("MCP_TOKEN", "tenant-b-credential");
        assertNotEquals(
                referenceHash,
                AiToolService.planHash(
                        service.resolveEffective(3L, "ASTRAL_FRONT").getFirst()));
        assertEquals(referenceHash, AiToolService.planHash(referencePlan));
        properties.getHttpTool().getSecrets().clear();
        assertFalse(service.handlerAvailable(tool));
        assertTrue(service.resolveEffective(3L, "ASTRAL_FRONT").isEmpty());
    }
}
