package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentKnowledgeEntity;
import com.astral.ai.entity.AiAgentToolEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiKnowledgeSourceEntity;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.entity.AiPromptVersionEntity;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.entity.AiProviderRouteEntity;
import com.astral.ai.entity.AiToolEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 智能体版本发布前校验。
 *
 * <p>{@code /validate} 与 {@code /publish} 共用这里，所以本测试锁的是「什么样的配置能上线」：
 * ERROR 级必须阻断发布，WARN 级只能提示。特别地，<b>MOCK 提供方不需要 API Key</b>——
 * 否则内置种子版本永远发布不了，整个模块在本地就无法开箱验证。</p>
 */
class AiAgentValidationServiceTest {

    private AiAgentService agentService;
    private AiPromptService promptService;
    private AiToolService toolService;
    private AiKnowledgeService knowledgeService;
    private AiRouteService routeService;
    private AiModelService modelService;
    private AiProviderService providerService;
    private AiQuotaService quotaService;
    private AiAgentValidationService service;

    @BeforeEach
    void setUp() {
        agentService = mock(AiAgentService.class);
        promptService = mock(AiPromptService.class);
        toolService = mock(AiToolService.class);
        knowledgeService = mock(AiKnowledgeService.class);
        routeService = mock(AiRouteService.class);
        modelService = mock(AiModelService.class);
        providerService = mock(AiProviderService.class);
        quotaService = mock(AiQuotaService.class);

        service = new AiAgentValidationService(agentService, promptService, toolService, knowledgeService,
                routeService, modelService, providerService, quotaService, new AiProperties());

        // 默认：没有任何工具与知识源绑定
        when(toolService.listBindings(any())).thenReturn(List.of());
        when(knowledgeService.listBindings(any())).thenReturn(List.of());
    }

    // ==================== 整体 ====================

    @Test
    void nullVersionIsReportedAsMissing() {
        AiAdminDtos.ValidationVo result = service.validate(null);

        assertFalse(result.valid());
        assertEquals(1, result.issues().size());
        assertEquals("VERSION_MISSING", result.issues().get(0).code());
        assertEquals("ERROR", result.issues().get(0).level());
    }

    @Test
    void unknownAgentAbortsTheWholeValidation() {
        when(agentService.require(7L)).thenThrow(new AiException("AI002"));
        AiAgentVersionEntity version = versionOfAgent(7L);

        AiAdminDtos.ValidationVo result = service.validate(version);

        assertFalse(result.valid());
        assertEquals(List.of("AGENT_MISSING"), codes(result));
    }

    @Test
    void fullyConfiguredMockAgentIsPublishable() {
        // MOCK 提供方没有任何 API Key，这必须被视为合法配置
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX,QT_PC", "MOCK");

        AiAdminDtos.ValidationVo result = service.validate(versionOfAgent(1L));

        assertTrue(result.valid(), () -> "不应有 ERROR：" + codes(result));
        assertTrue(codes(result).isEmpty(), () -> "不应有任何问题：" + codes(result));
    }

    @Test
    void requireValidThrowsFirstErrorMessageWhenInvalid() {
        AiAgentVersionEntity version = versionOfAgent(1L);
        when(agentService.require(1L)).thenReturn(agent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", null));
        // 未配置路由
        version.setProviderRouteId(null);

        AiException error = assertThrows(AiException.class, () -> service.requireValid(version));
        assertEquals("AI007", error.getErrorCode());
    }

    // ==================== 客户端 ====================

    @Test
    void agentWithoutClientsCannotBePublished() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "  ", "MOCK");

        assertHasIssue(service.validate(versionOfAgent(1L)), "CLIENT_EMPTY", "ERROR");
    }

    @Test
    void unknownClientTypeCannotBePublished() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX,QT_ANDROID", "MOCK");

        assertHasIssue(service.validate(versionOfAgent(1L)), "CLIENT_UNKNOWN", "ERROR");
    }

    @Test
    void musicAgentOnWebConsoleOnlyWarns() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "ASTRAL_FRONT", "MOCK");

        AiAdminDtos.ValidationVo result = service.validate(versionOfAgent(1L));

        assertHasIssue(result, "MUSIC_FRONT", "WARN");
        // 仅仅是警告，不应阻断发布
        assertTrue(result.valid());
    }

    // ==================== 路由与模型 ====================

    @Test
    void missingRouteBlocksPublish() {
        AiAgentVersionEntity version = versionOfAgent(1L);
        version.setProviderRouteId(null);
        when(agentService.require(1L)).thenReturn(agent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", null));

        AiAdminDtos.ValidationVo result = service.validate(version);

        assertHasIssue(result, "ROUTE_MISSING", "ERROR");
        assertFalse(result.valid());
    }

    @Test
    void disabledRouteBlocksPublish() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "OPENAI", false, true, true, 512);

        assertHasIssue(service.validate(versionOfAgent(1L)), "ROUTE_DISABLED", "ERROR");
    }

    @Test
    void disabledModelAndProviderBlockPublish() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "OPENAI", true, false, true, 512);
        assertHasIssue(service.validate(versionOfAgent(1L)), "MODEL_DISABLED", "ERROR");

        setUp();
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "OPENAI", true, true, false, 512);
        assertHasIssue(service.validate(versionOfAgent(1L)), "PROVIDER_DISABLED", "ERROR");
    }

    @Test
    void realProviderWithoutApiKeyBlocksPublish() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "OPENAI", true, true, true, 512);
        when(providerService.resolveApiKey(any())).thenReturn(null);

        assertHasIssue(service.validate(versionOfAgent(1L)), "PROVIDER_NO_KEY", "ERROR");
    }

    @Test
    void mockProviderIsExemptFromTheApiKeyRequirement() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK", true, true, true, 512);
        when(providerService.resolveApiKey(any())).thenReturn(null);

        AiAdminDtos.ValidationVo result = service.validate(versionOfAgent(1L));

        assertFalse(codes(result).contains("PROVIDER_NO_KEY"), () -> "MOCK 不应要求密钥：" + codes(result));
        assertTrue(result.valid());
    }

    @Test
    void oversizedMaxTokensOnlyWarns() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK", true, true, true, 100_000);

        AiAdminDtos.ValidationVo result = service.validate(versionOfAgent(1L));

        assertHasIssue(result, "MAX_TOKENS_HIGH", "WARN");
        assertTrue(result.valid());
    }

    // ==================== 提示词 ====================

    @Test
    void unboundPromptBlocksPublish() {
        // 线上事故：缺提示词的版本被放行发布，模型失去角色约束只会回散文，
        // 音乐助手因此产不出 blocks/actions，客户端拿不到可点的歌。
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK", true, true, true, 512);
        AiAgentVersionEntity version = versionOfAgent(1L);
        version.setSystemPromptVersionId(null);

        AiAdminDtos.ValidationVo result = service.validate(version);

        assertHasIssue(result, "PROMPT_MISSING", "ERROR");
        assertFalse(result.valid());
    }

    @Test
    void unpublishedPromptBlocksPublish() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        AiPromptVersionEntity prompt = prompt("你是音乐助手。", AiConstants.VersionStatus.DRAFT);
        when(promptService.requireVersion(9L)).thenReturn(prompt);

        assertHasIssue(service.validate(versionOfAgent(1L)), "PROMPT_UNPUBLISHED", "ERROR");
    }

    @Test
    void emptyPromptBlocksPublish() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        when(promptService.requireVersion(9L))
                .thenReturn(prompt("   ", AiConstants.VersionStatus.PUBLISHED));

        assertHasIssue(service.validate(versionOfAgent(1L)), "PROMPT_EMPTY", "ERROR");
    }

    @Test
    void musicPromptWithoutPlaybackGuardWarns() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        when(promptService.requireVersion(9L))
                .thenReturn(prompt("你是音乐助手，负责推荐歌曲。", AiConstants.VersionStatus.PUBLISHED));

        AiAdminDtos.ValidationVo result = service.validate(versionOfAgent(1L));

        assertHasIssue(result, "PROMPT_NO_PLAY_GUARD", "WARN");
        assertTrue(result.valid());
    }

    // ==================== Schema / 参数 / 额度 ====================

    @Test
    void brokenResponseSchemaBlocksPublish() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        AiAgentVersionEntity version = versionOfAgent(1L);
        version.setResponseSchemaJson("{oops");

        assertHasIssue(service.validate(version), "SCHEMA_INVALID", "ERROR");
    }

    @Test
    void musicAgentWithoutResponseSchemaBlocksPublish() {
        // 音乐助手必须靠 schema 约束 {answer,blocks,actions}，空 schema 会让卡片与动作消失。
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        AiAgentVersionEntity version = versionOfAgent(1L);
        version.setResponseSchemaJson("  ");

        AiAdminDtos.ValidationVo result = service.validate(version);

        assertHasIssue(result, "SCHEMA_MISSING", "ERROR");
        assertFalse(result.valid());
    }

    @Test
    void adminAgentWithoutResponseSchemaIsAcceptable() {
        // 管理助手与答疑助手可以只回纯文本，不强求结构化输出。
        wireHealthyAgent(AiConstants.AgentType.ADMIN, "ASTRAL_FRONT", "MOCK");
        AiAgentVersionEntity version = versionOfAgent(1L);
        version.setResponseSchemaJson("  ");

        assertTrue(service.validate(version).valid());
    }

    @Test
    void brokenModelParametersBlockPublish() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        AiAgentVersionEntity version = versionOfAgent(1L);
        version.setModelParametersJson("{not json");

        assertHasIssue(service.validate(version), "PARAMS_INVALID", "ERROR");
    }

    @Test
    void outOfRangeTemperatureBlocksPublish() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        AiAgentVersionEntity version = versionOfAgent(1L);
        version.setModelParametersJson("{\"temperature\":3.5}");

        assertHasIssue(service.validate(version), "TEMPERATURE_RANGE", "ERROR");
    }

    @Test
    void validModelParametersPass() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        AiAgentVersionEntity version = versionOfAgent(1L);
        version.setModelParametersJson("{\"temperature\":0.7,\"topP\":0.9}");

        assertTrue(service.validate(version).valid());
    }

    @Test
    void brokenQuotaOverrideBlocksPublish() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        AiAgentVersionEntity version = versionOfAgent(1L);
        version.setQuotaOverrideJson("{bad");

        assertHasIssue(service.validate(version), "QUOTA_INVALID", "ERROR");
    }

    @Test
    void missingBoundQuotaPolicyBlocksPublish() {
        wireHealthyAgent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK");
        when(agentService.require(1L)).thenReturn(agent(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", 55L));
        when(quotaService.requirePolicy(55L)).thenThrow(new AiException("AI007"));

        assertHasIssue(service.validate(versionOfAgent(1L)), "QUOTA_MISSING", "ERROR");
    }

    // ==================== 工具 ====================

    @Test
    void toolWithUnregisteredHandlerBlocksPublish() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK", true, true, true, 512);
        AiAgentToolEntity binding = binding(101L);
        when(toolService.listBindings(any())).thenReturn(List.of(binding));
        AiToolEntity tool = tool("search_music", AiConstants.RiskLevel.READ, "QT_UNIAPPX,QT_PC");
        when(toolService.require(101L)).thenReturn(tool);
        when(toolService.handlerAvailable(tool)).thenReturn(false);

        assertHasIssue(service.validate(versionOfAgent(1L)), "TOOL_NOT_IMPLEMENTED", "ERROR");
    }

    @Test
    void missingToolRowBlocksPublish() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK", true, true, true, 512);
        when(toolService.listBindings(any())).thenReturn(List.of(binding(101L)));
        when(toolService.require(101L)).thenThrow(new AiException("AI008"));

        assertHasIssue(service.validate(versionOfAgent(1L)), "TOOL_MISSING", "ERROR");
    }

    @Test
    void toolNotAvailableOnTheAgentsClientBlocksPublish() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK", true, true, true, 512);
        AiAgentToolEntity binding = binding(101L);
        when(toolService.listBindings(any())).thenReturn(List.of(binding));
        AiToolEntity tool = tool("search_music", AiConstants.RiskLevel.READ, "QT_PC");
        when(toolService.require(101L)).thenReturn(tool);
        when(toolService.handlerAvailable(tool)).thenReturn(true);

        assertHasIssue(service.validate(versionOfAgent(1L)), "TOOL_CLIENT_MISMATCH", "ERROR");
    }

    @Test
    void musicAgentCannotBindWriteTools() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK", true, true, true, 512);
        AiAgentToolEntity binding = binding(101L);
        when(toolService.listBindings(any())).thenReturn(List.of(binding));
        AiToolEntity tool = tool("delete_file", AiConstants.RiskLevel.HIGH, "QT_UNIAPPX");
        when(toolService.require(101L)).thenReturn(tool);
        when(toolService.handlerAvailable(tool)).thenReturn(true);

        assertHasIssue(service.validate(versionOfAgent(1L)), "MUSIC_TOOL_RISK", "ERROR");
    }

    @Test
    void adminAgentCannotBindMusicTools() {
        wireRoute(AiConstants.AgentType.ADMIN, "ASTRAL_FRONT", "MOCK", true, true, true, 512);
        AiAgentToolEntity binding = binding(101L);
        when(toolService.listBindings(any())).thenReturn(List.of(binding));
        AiToolEntity tool = tool("search_music", AiConstants.RiskLevel.READ, "ASTRAL_FRONT");
        // 真实音乐工具的处理器键固定是 music.* ，后台无法伪造
        tool.setHandlerKey("music.search");
        when(toolService.require(101L)).thenReturn(tool);
        when(toolService.handlerAvailable(tool)).thenReturn(true);

        assertHasIssue(service.validate(versionOfAgent(1L)), "ADMIN_TOOL_SCOPE", "ERROR");
    }

    @Test
    void disabledToolBindingIsIgnored() {
        wireRoute(AiConstants.AgentType.MUSIC, "QT_UNIAPPX", "MOCK", true, true, true, 512);
        AiAgentToolEntity binding = binding(101L);
        binding.setEnabled(false);
        when(toolService.listBindings(any())).thenReturn(List.of(binding));

        assertTrue(service.validate(versionOfAgent(1L)).valid());
    }

    // ==================== 知识库 ====================

    @Test
    void knowledgeSourceNotReadyBlocksPublish() {
        wireRoute(AiConstants.AgentType.QA, "ASTRAL_FRONT", "MOCK", true, true, true, 512);
        AiAgentKnowledgeEntity binding = knowledgeBinding(201L);
        when(knowledgeService.listBindings(any())).thenReturn(List.of(binding));
        AiKnowledgeSourceEntity source = knowledgeSource(true, AiConstants.KnowledgeStatus.INDEXING);
        when(knowledgeService.requireSource(201L)).thenReturn(source);

        assertHasIssue(service.validate(versionOfAgent(1L)), "KNOWLEDGE_NOT_READY", "ERROR");
    }

    @Test
    void disabledKnowledgeSourceBlocksPublish() {
        wireRoute(AiConstants.AgentType.QA, "ASTRAL_FRONT", "MOCK", true, true, true, 512);
        when(knowledgeService.listBindings(any())).thenReturn(List.of(knowledgeBinding(201L)));
        when(knowledgeService.requireSource(201L))
                .thenReturn(knowledgeSource(false, AiConstants.KnowledgeStatus.READY));

        assertHasIssue(service.validate(versionOfAgent(1L)), "KNOWLEDGE_DISABLED", "ERROR");
    }

    @Test
    void readyKnowledgeSourcePasses() {
        wireRoute(AiConstants.AgentType.QA, "ASTRAL_FRONT", "MOCK", true, true, true, 512);
        when(knowledgeService.listBindings(any())).thenReturn(List.of(knowledgeBinding(201L)));
        when(knowledgeService.requireSource(201L))
                .thenReturn(knowledgeSource(true, AiConstants.KnowledgeStatus.READY));

        assertTrue(service.validate(versionOfAgent(1L)).valid());
    }

    // ==================== 辅助 ====================

    private void wireHealthyAgent(String agentType, String clients, String providerType) {
        wireRoute(agentType, clients, providerType, true, true, true, 512);
    }

    private void wireRoute(String agentType, String clients, String providerType,
                           boolean routeEnabled, boolean modelEnabled, boolean providerEnabled, int maxTokens) {
        when(agentService.require(1L)).thenReturn(agent(agentType, clients, null));

        AiProviderRouteEntity route = new AiProviderRouteEntity();
        route.setId(10L);
        route.setProviderId(20L);
        route.setModelId(30L);
        route.setEnabled(routeEnabled);
        route.setMaxTokens(maxTokens);
        when(routeService.require(10L)).thenReturn(route);

        AiModelEntity model = new AiModelEntity();
        model.setId(30L);
        model.setProviderId(20L);
        model.setEnabled(modelEnabled);
        when(modelService.require(30L)).thenReturn(model);

        AiProviderEntity provider = new AiProviderEntity();
        provider.setId(20L);
        provider.setProviderType(providerType);
        provider.setEnabled(providerEnabled);
        when(providerService.require(20L)).thenReturn(provider);
        when(providerService.resolveApiKey(any())).thenReturn("sk-test");

        AiPromptVersionEntity prompt = prompt("你是音乐助手，可以帮用户找歌、播放与整理歌单。",
                AiConstants.VersionStatus.PUBLISHED);
        when(promptService.requireVersion(9L)).thenReturn(prompt);
    }

    private static AiAgentVersionEntity versionOfAgent(Long agentId) {
        AiAgentVersionEntity version = new AiAgentVersionEntity();
        version.setId(100L);
        version.setAgentId(agentId);
        version.setVersionNo(1);
        version.setStatus(AiConstants.VersionStatus.DRAFT);
        version.setProviderRouteId(10L);
        version.setSystemPromptVersionId(9L);
        // 音乐助手发布时强制要求响应 Schema（见 SCHEMA_MISSING），
        // 所以「健康版本」的基线配置必须带上它，否则每个用例都会撞这条规则。
        version.setResponseSchemaJson("{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}");
        return version;
    }

    private static AiAgentEntity agent(String agentType, String clients, Long quotaPolicyId) {
        AiAgentEntity agent = new AiAgentEntity();
        agent.setId(1L);
        agent.setAgentCode("test_agent");
        agent.setName("测试智能体");
        agent.setAgentType(agentType);
        agent.setStatus(AiConstants.AgentStatus.PUBLISHED);
        agent.setClientTypes(clients);
        agent.setQuotaPolicyId(quotaPolicyId);
        return agent;
    }

    private static AiPromptVersionEntity prompt(String systemPrompt, String status) {
        AiPromptVersionEntity prompt = new AiPromptVersionEntity();
        prompt.setId(9L);
        prompt.setStatus(status);
        prompt.setSystemPrompt(systemPrompt);
        return prompt;
    }

    private static AiAgentToolEntity binding(Long toolId) {
        AiAgentToolEntity binding = new AiAgentToolEntity();
        binding.setId(1L);
        binding.setAgentVersionId(100L);
        binding.setToolId(toolId);
        binding.setEnabled(true);
        binding.setSort(0);
        return binding;
    }

    private static AiToolEntity tool(String code, String risk, String clientTypes) {
        AiToolEntity tool = new AiToolEntity();
        tool.setId(101L);
        tool.setToolCode(code);
        tool.setHandlerKey("test.handler");
        tool.setRiskLevel(risk);
        tool.setApprovalPolicy(AiConstants.ApprovalPolicy.NONE);
        tool.setClientTypes(clientTypes);
        tool.setStatus("ENABLED");
        return tool;
    }

    private static AiAgentKnowledgeEntity knowledgeBinding(Long sourceId) {
        AiAgentKnowledgeEntity binding = new AiAgentKnowledgeEntity();
        binding.setId(1L);
        binding.setAgentVersionId(100L);
        binding.setKnowledgeSourceId(sourceId);
        binding.setEnabled(true);
        return binding;
    }

    private static AiKnowledgeSourceEntity knowledgeSource(boolean enabled, String status) {
        AiKnowledgeSourceEntity source = new AiKnowledgeSourceEntity();
        source.setId(201L);
        source.setSourceCode("project_docs");
        source.setEnabled(enabled);
        source.setStatus(status);
        return source;
    }

    private static List<String> codes(AiAdminDtos.ValidationVo result) {
        return result.issues().stream().map(AiAdminDtos.ValidationVo.ValidationIssue::code).toList();
    }

    private static void assertHasIssue(AiAdminDtos.ValidationVo result, String code, String level) {
        boolean found = result.issues().stream()
                .anyMatch(i -> code.equals(i.code()) && level.equals(i.level()));
        assertTrue(found, () -> "期望 " + level + " " + code + "，实际：" + codes(result));
    }
}
