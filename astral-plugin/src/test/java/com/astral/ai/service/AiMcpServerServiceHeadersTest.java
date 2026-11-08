package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.security.AiMcpHeaderSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.ObjectNode;

/**
 * R9 服务落库面：MCP headers 敏感值加密存储、掩码合并、既有三态语义保持。
 *
 * <p>契约（docs/audit/2026-11-07/backend-ai-read-recovery.md R9）：
 * 「headers 中的敏感键（Authorization/api-key/token/secret）加密存储」。服务层是唯一的落库
 * 出口——create/update 必须先把敏感明文换成 {@code v1:} 密文再进 {@code ai_mcp_server}，
 * 回显掩码 {@code ******} 表示未改动并从旧行沿用；headers 未提交时旧行原样保留。</p>
 */
class AiMcpServerServiceHeadersTest {

    private static final Long SERVER_ID = 813L;

    private static AiProperties properties(String kek) {
        AiProperties properties = new AiProperties();
        properties.setSecretKey(kek);
        return properties;
    }

    @Test
    void createStoresSensitiveHeaderValuesEncryptedAtRest() {
        AiMcpServerMapper serverMapper = mock(AiMcpServerMapper.class);
        when(serverMapper.selectCount(any())).thenReturn(0L);
        AiMcpServerService service = new AiMcpServerService(
                serverMapper,
                mock(AiMcpServerFailureRecorder.class),
                mock(AiToolMapper.class),
                properties("unit-test-kek"));

        AiAdminDtos.McpServerSaveReq req = new AiAdminDtos.McpServerSaveReq(
                "audit_mcp_fixture",
                "审计 MCP 夹具",
                "https://93.184.216.34/mcp",
                "{\"Authorization\":\"Bearer abc\",\"X-Api-Key\":\"sk-1\",\"X-Trace-Id\":\"trace-1\"}",
                true,
                null);

        AiMcpServerEntity created = service.create(req, 41L);

        ArgumentCaptor<AiMcpServerEntity> captor = ArgumentCaptor.forClass(AiMcpServerEntity.class);
        verify(serverMapper).insert(captor.capture());
        ObjectNode stored = AiJson.readObject(captor.getValue().getHeadersJson());
        assertTrue(stored.get("Authorization").asString().startsWith("v1:"));
        assertTrue(stored.get("X-Api-Key").asString().startsWith("v1:"));
        assertEquals("trace-1", stored.get("X-Trace-Id").asString());
        // 返回给调用方的同样是密文形态；明文只在 revealForCall 出口出现。
        assertEquals(captor.getValue().getHeadersJson(), created.getHeadersJson());
    }

    @Test
    void updatePreservesMaskedSensitiveHeadersAndReEncryptsLegacyPlaintext() {
        AiMcpServerMapper serverMapper = mock(AiMcpServerMapper.class);
        when(serverMapper.selectById(SERVER_ID)).thenReturn(existing());
        AiMcpServerService service = new AiMcpServerService(
                serverMapper,
                mock(AiMcpServerFailureRecorder.class),
                mock(AiToolMapper.class),
                properties("unit-test-kek"));

        // Authorization 提交的是脱敏回显（掩码=未改动，沿用旧行密文）；
        // X-Auth-Token 是升级前 UI 回显的真实明文（落库时必须换成密文）。
        AiAdminDtos.McpServerSaveReq req = new AiAdminDtos.McpServerSaveReq(
                null,
                "审计 MCP 夹具",
                null,
                "{\"Authorization\":\"******\",\"X-Auth-Token\":\"legacy-plain-token\",\"X-Trace-Id\":\"trace-2\"}",
                null,
                null);

        AiMcpServerEntity updated = service.update(SERVER_ID, req, 42L);

        ObjectNode stored = AiJson.readObject(updated.getHeadersJson());
        // 旧行已是密文的敏感头原样沿用，不因掩码重提而损坏。
        assertEquals("v1:OLD:OLD", stored.get("Authorization").asString());
        // 旧行遗留明文在保存时被顺势重新加密（遗留行自愈）。
        String token = stored.get("X-Auth-Token").asString();
        assertTrue(token.startsWith("v1:"));
        assertNotEquals("legacy-plain-token", token);
        ObjectNode revealed =
                AiJson.readObject(AiMcpHeaderSecurity.revealForCall("unit-test-kek", updated.getHeadersJson()));
        assertEquals("legacy-plain-token", revealed.get("X-Auth-Token").asString());
        assertEquals("trace-2", stored.get("X-Trace-Id").asString());
        assertEquals(stored, AiJson.readObject(updated.getHeadersJson()));
    }

    @Test
    void updateWithoutHeadersPayloadKeepsStoredRowUntouched() {
        AiMcpServerMapper serverMapper = mock(AiMcpServerMapper.class);
        when(serverMapper.selectById(SERVER_ID)).thenReturn(existing());
        AiMcpServerService service = new AiMcpServerService(
                serverMapper,
                mock(AiMcpServerFailureRecorder.class),
                mock(AiToolMapper.class),
                properties("unit-test-kek"));

        AiAdminDtos.McpServerSaveReq req = new AiAdminDtos.McpServerSaveReq(null, "审计 MCP 夹具", null, null, null, null);

        AiMcpServerEntity updated = service.update(SERVER_ID, req, 42L);

        // headers 未提交时旧行原样保留（三态语义不因 R9 收口被破坏）。
        assertEquals(existing().getHeadersJson(), updated.getHeadersJson());
    }

    private static AiMcpServerEntity existing() {
        AiMcpServerEntity entity = new AiMcpServerEntity();
        entity.setId(SERVER_ID);
        entity.setServerCode("audit_mcp_fixture");
        entity.setName("审计 MCP 夹具");
        entity.setUrl("https://93.184.216.34/mcp");
        entity.setHeadersJson(
                "{\"Authorization\":\"v1:OLD:OLD\",\"X-Auth-Token\":\"legacy-plain-token\",\"X-Trace-Id\":\"trace-1\"}");
        entity.setReadOnly(true);
        entity.setStatus("ENABLED");
        return entity;
    }
}
