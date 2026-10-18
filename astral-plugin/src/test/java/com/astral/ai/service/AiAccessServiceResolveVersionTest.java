package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.auth.security.PermissionChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 版本解析的回归测试。
 * <p>线上事故：用户改完配置发版后，旧会话仍然跑归档的旧版本，于是「发了版却没效果」。
 * 会话绑定的版本只在它仍是当前发布版时才沿用；一旦被归档就必须跟随最新发布版。</p>
 */
class AiAccessServiceResolveVersionTest {

    private AiAgentService agentService;
    private AiAccessService service;

    @BeforeEach
    void setUp() {
        agentService = mock(AiAgentService.class);
        service = new AiAccessService(agentService, mock(PermissionChecker.class));
    }

    @Test
    void followsTheNewPublishedVersionWhenTheBoundOneWasArchived() {
        // 事故场景：会话绑 v3(3006)，后台发了 v4(3007) 并把 v3 归档
        AiAgentEntity agent = agent(3007L);
        when(agentService.requireVersion(3006L)).thenReturn(version(3006L, AiConstants.VersionStatus.ARCHIVED));
        when(agentService.requireVersion(3007L)).thenReturn(version(3007L, AiConstants.VersionStatus.PUBLISHED));

        assertEquals(3007L, service.resolveVersion(agent, 3006L).getId());
    }

    @Test
    void keepsTheBoundVersionWhenItIsStillThePublishedOne() {
        AiAgentEntity agent = agent(3007L);
        when(agentService.requireVersion(3007L)).thenReturn(version(3007L, AiConstants.VersionStatus.PUBLISHED));

        assertEquals(3007L, service.resolveVersion(agent, 3007L).getId());
    }

    @Test
    void ignoresADraftBoundVersion() {
        AiAgentEntity agent = agent(3007L);
        when(agentService.requireVersion(3008L)).thenReturn(version(3008L, AiConstants.VersionStatus.DRAFT));
        when(agentService.requireVersion(3007L)).thenReturn(version(3007L, AiConstants.VersionStatus.PUBLISHED));

        assertEquals(3007L, service.resolveVersion(agent, 3008L).getId());
    }

    @Test
    void usesThePublishedVersionWhenNoConversationBindingExists() {
        AiAgentEntity agent = agent(3007L);
        when(agentService.requireVersion(3007L)).thenReturn(version(3007L, AiConstants.VersionStatus.PUBLISHED));

        assertEquals(3007L, service.resolveVersion(agent, null).getId());
    }

    @Test
    void unpublishedAgentIsRejected() {
        AiAgentEntity agent = agent(null);

        assertThrows(RuntimeException.class, () -> service.resolveVersion(agent, null));
    }

    private static AiAgentEntity agent(Long publishedVersionId) {
        AiAgentEntity agent = new AiAgentEntity();
        agent.setId(1001L);
        agent.setAgentCode("music_assistant");
        agent.setPublishedVersionId(publishedVersionId);
        return agent;
    }

    private static AiAgentVersionEntity version(Long id, String status) {
        AiAgentVersionEntity version = new AiAgentVersionEntity();
        version.setId(id);
        version.setAgentId(1001L);
        version.setStatus(status);
        return version;
    }
}
