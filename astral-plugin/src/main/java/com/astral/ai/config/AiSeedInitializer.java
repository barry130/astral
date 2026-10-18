package com.astral.ai.config;

import com.astral.ai.AiPlugin;
import com.astral.ai.common.AiConstants;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentKnowledgeEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiKnowledgeSourceEntity;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.entity.AiPromptTemplateEntity;
import com.astral.ai.entity.AiPromptVersionEntity;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.entity.AiProviderRouteEntity;
import com.astral.ai.entity.AiQuotaPolicyEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.provider.MockAiProvider;
import com.astral.ai.service.AiAgentService;
import com.astral.ai.service.AiAgentValidationService;
import com.astral.ai.service.AiKnowledgeService;
import com.astral.ai.service.AiModelService;
import com.astral.ai.service.AiPromptService;
import com.astral.ai.service.AiProviderService;
import com.astral.ai.service.AiQuotaService;
import com.astral.ai.service.AiRouteService;
import com.astral.ai.service.AiToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 插件启动初始化：登记工具处理器 + 补齐种子配置。
 *
 * <p><b>种子只是初始数据。</b>所有判断都以「编码是否已存在」为准：已存在就整块跳过，
 * 绝不覆盖后台改过的模型、Prompt、工具绑定、额度或入口配置——设计文档 §4.1 要求这些
 * 全部由后台维护，代码只负责首次落库。</p>
 *
 * <p>放在 {@link ApplicationRunner} 而不是 {@code @PostConstruct}，是因为它依赖
 * {@code AiSchemaInitializer} 建表完成、工具处理器已由 {@code AiToolService} 收集完毕。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSeedInitializer implements ApplicationRunner {

    /** 种子提供方编码（本地模拟，正式环境由管理员改成真实提供方） */
    private static final String PROVIDER_CODE = "mock_local";

    /** 种子模型编码 */
    private static final String MODEL_CODE = "mock_chat";

    /** 种子路由编码 */
    private static final String ROUTE_CODE = "default_route";

    /** 种子额度策略编码 */
    private static final String QUOTA_CODE = "default_quota";

    /** 种子知识源编码 */
    private static final String KNOWLEDGE_CODE = "project_docs";

    private final AiProperties properties;
    private final AiProviderService providerService;
    private final AiModelService modelService;
    private final AiRouteService routeService;
    private final AiPromptService promptService;
    private final AiKnowledgeService knowledgeService;
    private final AiAgentService agentService;
    private final AiToolService toolService;
    private final AiQuotaService quotaService;
    private final AiAgentValidationService validationService;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("[AiSeed] astral.plugins.ai.enabled=false，跳过 AI 初始化");
            return;
        }
        try {
            // 工具登记与「后台是否勾选」无关：代码里有的处理器必须全部落库，否则无法被绑定
            int tools = toolService.syncFromCode(null);
            log.info("[AiSeed] 工具处理器登记完成：{} 个", tools);
            if (!properties.isSeedEnabled()) {
                return;
            }
            seed();
        } catch (Exception e) {
            // 种子失败不能阻断整个应用启动：控制台提示后由管理员手工补配置
            log.error("[AiSeed] AI 种子初始化失败，请在后台手工补齐配置", e);
        }
    }

    private void seed() {
        AiProviderEntity provider = ensureProvider();
        AiModelEntity model = ensureModel(provider);
        AiProviderRouteEntity route = ensureRoute(provider, model);
        AiQuotaPolicyEntity quota = ensureQuota();

        Long musicPrompt = ensurePrompt("music_assistant_prompt", "音乐助手提示词", "MUSIC",
                "ai-seed/prompts/music_assistant.md");
        Long adminPrompt = ensurePrompt("admin_assistant_prompt", "管理助手提示词", "ADMIN",
                "ai-seed/prompts/admin_assistant.md");
        Long qaPrompt = ensurePrompt("admin_qa_prompt", "答疑助手提示词", "QA",
                "ai-seed/prompts/admin_qa.md");

        Long knowledgeSourceId = ensureKnowledgeSource();

        ensureAgent(AiPlugin.SeedAgents.MUSIC, "音乐助手", AiConstants.AgentType.MUSIC,
                "用自然语言找歌、排序推荐并给出可执行动作。轻听 App 与轻听 PC 功能一致。",
                "CustomerServiceOutlined",
                List.of(AiConstants.ClientType.QT_UNIAPPX, AiConstants.ClientType.QT_PC),
                AiPlugin.PERM_MUSIC_USE, null, quota.getId(), route.getId(), musicPrompt,
                musicTools(), null, MUSIC_SCHEMA, 10);

        ensureAgent(AiPlugin.SeedAgents.ADMIN, "管理助手", AiConstants.AgentType.ADMIN,
                "查询系统、用户、插件、存储与日志，起草通知邮件，高风险操作出确认卡片。",
                "ToolOutlined",
                List.of(AiConstants.ClientType.ASTRAL_FRONT),
                AiPlugin.Perms.ASSISTANT_USE, null, quota.getId(), route.getId(), adminPrompt,
                adminTools(), null, ADMIN_SCHEMA, 20);

        ensureAgent(AiPlugin.SeedAgents.QA, "答疑助手", AiConstants.AgentType.QA,
                "基于已发布项目文档回答接口、插件、部署与错误码问题，答案带可核验引用。",
                "ReadOutlined",
                List.of(AiConstants.ClientType.ASTRAL_FRONT),
                AiPlugin.Perms.QA_USE, null, quota.getId(), route.getId(), qaPrompt,
                qaTools(), knowledgeSourceId, QA_SCHEMA, 30);

        log.info("[AiSeed] AI 种子配置就绪（提供方 {}，路由 {}，知识源 {}）",
                provider.getProviderCode(), route.getRouteCode(), knowledgeSourceId);
    }

    // ==================== 提供方 / 模型 / 路由 ====================

    private AiProviderEntity ensureProvider() {
        for (AiProviderEntity row : providerService.list()) {
            if (PROVIDER_CODE.equals(row.getProviderCode())) {
                return row;
            }
        }
        AiProviderEntity entity = providerService.create(PROVIDER_CODE, "本地模拟提供方",
                MockAiProvider.TYPE, "mock://local", null, null, true,
                15000, 120000, 1, 100, "开箱即用的离线提供方，不产生费用；正式使用请替换为真实模型", null);
        log.info("[AiSeed] 已创建种子提供方 {}", PROVIDER_CODE);
        return entity;
    }

    private AiModelEntity ensureModel(AiProviderEntity provider) {
        for (AiModelEntity row : modelService.list(provider.getId())) {
            if (MODEL_CODE.equals(row.getModelCode())) {
                return row;
            }
        }
        AiModelEntity entity = new AiModelEntity();
        entity.setProviderId(provider.getId());
        entity.setModelCode(MODEL_CODE);
        entity.setModelName("本地模拟模型");
        entity.setModelKind("CHAT");
        entity.setSupportsStream(true);
        entity.setSupportsTools(true);
        entity.setSupportsJson(true);
        entity.setSupportsVision(false);
        entity.setMaxContextTokens(128000);
        entity.setMaxOutputTokens(4096);
        entity.setCurrency("CNY");
        entity.setEnabled(true);
        entity.setRemark("离线回显模型，用于验证链路，不具备真实推理能力");
        modelService.create(entity);
        log.info("[AiSeed] 已创建种子模型 {}", MODEL_CODE);
        return entity;
    }

    private AiProviderRouteEntity ensureRoute(AiProviderEntity provider, AiModelEntity model) {
        for (AiProviderRouteEntity row : routeService.list()) {
            if (ROUTE_CODE.equals(row.getRouteCode())) {
                return row;
            }
        }
        AiProviderRouteEntity entity = new AiProviderRouteEntity();
        entity.setRouteCode(ROUTE_CODE);
        entity.setName("默认路由");
        entity.setProviderId(provider.getId());
        entity.setModelId(model.getId());
        entity.setTemperature(new java.math.BigDecimal("0.3"));
        entity.setTopP(new java.math.BigDecimal("0.9"));
        entity.setMaxTokens(2048);
        entity.setEnabled(true);
        entity.setRemark("种子路由，后台可改为真实提供方与模型");
        return routeService.create(entity);
    }

    private AiQuotaPolicyEntity ensureQuota() {
        for (AiQuotaPolicyEntity row : quotaService.listPolicies()) {
            if (QUOTA_CODE.equals(row.getPolicyCode())) {
                return row;
            }
        }
        AiQuotaPolicyEntity entity = new AiQuotaPolicyEntity();
        entity.setPolicyCode(QUOTA_CODE);
        entity.setName("默认额度策略");
        entity.setScopeType(AiConstants.QuotaScope.GLOBAL);
        entity.setDailyRequests(2000);
        entity.setMonthlyRequests(30000);
        entity.setDailyInputTokens(2_000_000L);
        entity.setMonthlyInputTokens(30_000_000L);
        entity.setDailyOutputTokens(500_000L);
        entity.setMonthlyOutputTokens(8_000_000L);
        entity.setMaxInputChars(20000);
        entity.setMaxContextTokens(64000);
        entity.setMaxOutputTokens(4096);
        entity.setMaxConcurrent(5);
        entity.setEnabled(true);
        entity.setRemark("种子全局策略，可按角色/用户再下发更严格的策略");
        return quotaService.savePolicy(entity);
    }

    // ==================== 提示词 ====================

    private Long ensurePrompt(String promptCode, String name, String scene, String resourcePath) {
        AiPromptTemplateEntity template = promptService.findTemplateByCode(promptCode);
        if (template != null) {
            AiPromptVersionEntity published = promptService.latestPublished(template.getId());
            if (published != null) {
                return published.getId();
            }
            return promptService.publish(
                    promptService.createVersion(template.getId(), read(resourcePath), null, null,
                            "补齐已发布版本", null).getId(), null).getId();
        }
        AiPromptTemplateEntity created = promptService.createTemplate(promptCode, name, scene,
                "种子提示词，可在后台修改并发布新版本", null);
        AiPromptVersionEntity version = promptService.createVersion(created.getId(), read(resourcePath),
                null, null, "种子创建", null);
        promptService.publish(version.getId(), null);
        log.info("[AiSeed] 已创建种子提示词 {}", promptCode);
        return version.getId();
    }

    // ==================== 知识源 ====================

    private Long ensureKnowledgeSource() {
        for (AiKnowledgeSourceEntity row : knowledgeService.listSources()) {
            if (KNOWLEDGE_CODE.equals(row.getSourceCode())) {
                return row.getId();
            }
        }
        AiKnowledgeSourceEntity source = knowledgeService.createSource(KNOWLEDGE_CODE, "项目文档",
                AiConstants.KnowledgeSourceType.PROJECT_DOCUMENT,
                "接口契约、插件开发与部署说明、数据字典与错误码解释",
                "docs/**", true, "MANUAL", null);
        knowledgeService.saveDocument(source.getId(), "api-contract", "AI 模块接口契约",
                "docs/ai/api-contract.md", "接口", "1.0", read("ai-knowledge/api-contract.md"), null);
        knowledgeService.syncSource(source.getId(), null);
        log.info("[AiSeed] 已创建种子知识源 {}", KNOWLEDGE_CODE);
        return source.getId();
    }

    // ==================== 智能体 ====================

    private void ensureAgent(String agentCode, String name, String agentType, String description, String icon,
                             List<String> clientTypes, String permissionCode, String clientPermissionCodes,
                             Long quotaPolicyId, Long routeId, Long promptVersionId, List<String> toolCodes,
                             Long knowledgeSourceId, String responseSchema, int sort) {
        if (agentService.findByCode(agentCode) != null) {
            return;
        }
        AiAgentEntity agent = agentService.create(agentCode, name, agentType, description, icon, clientTypes,
                entryConfig(agentType), permissionCode, clientPermissionCodes, quotaPolicyId,
                true, false, true, true, sort, null);

        AiAgentVersionEntity draft = agentService.createDraftVersion(agent.getId(), "v1", routeId,
                promptVersionId, contextPolicy(agentType), responseSchema, toolPolicy(agentType),
                knowledgePolicy(agentType), modelParameters(), safetyPolicy(), null, null);

        List<Long> toolIds = resolveToolIds(toolCodes);
        if (!toolIds.isEmpty()) {
            toolService.replaceBindings(draft.getId(), toolIds, null);
        }
        if (knowledgeSourceId != null) {
            knowledgeService.replaceBindings(draft.getId(), List.of(knowledgeBinding(draft.getId(), knowledgeSourceId)));
        }

        // 种子配置由代码生成，校验问题照实写进日志，但仍发布，保证后台开箱可见并可立即修正
        AiAdminDtos.ValidationVo validation = validationService.validate(draft);
        for (AiAdminDtos.ValidationVo.ValidationIssue issue : validation.issues()) {
            if ("ERROR".equals(issue.level())) {
                log.warn("[AiSeed] 智能体 {} 校验问题 [{}/{}]: {}", agentCode, issue.code(), issue.field(),
                        issue.message());
            }
        }
        agentService.publish(draft.getId(), null);
        log.info("[AiSeed] 已创建并发布种子智能体 {}（绑定工具 {} 个）", agentCode, toolIds.size());
    }

    private static AiAgentKnowledgeEntity knowledgeBinding(Long versionId, Long sourceId) {
        AiAgentKnowledgeEntity binding = new AiAgentKnowledgeEntity();
        binding.setAgentVersionId(versionId);
        binding.setKnowledgeSourceId(sourceId);
        binding.setEnabled(true);
        binding.setPriority(10);
        binding.setRetrievalTopK(5);
        binding.setMinScore(AiKnowledgeService.defaultMinScore());
        binding.setCitationPolicy("REQUIRED");
        return binding;
    }

    private List<Long> resolveToolIds(List<String> toolCodes) {
        List<Long> ids = new ArrayList<>();
        for (String code : toolCodes) {
            AiToolEntity tool = toolService.findByCode(code);
            if (tool == null) {
                log.warn("[AiSeed] 工具处理器未登记，跳过绑定：{}", code);
                continue;
            }
            ids.add(tool.getId());
        }
        return ids;
    }

    private static List<String> musicTools() {
        return List.of("search_music", "search_artist", "search_album", "search_playlist",
                "get_song_detail", "get_current_song", "get_current_playlist",
                "get_user_likes", "get_play_history");
    }

    private static List<String> adminTools() {
        return List.of("get_system_health", "get_plugin_status", "get_user_summary",
                "get_permission_summary", "get_storage_summary", "search_operate_logs",
                "search_login_logs", "search_feedback", "get_usage_statistics",
                "check_config_completeness", "draft_notice", "draft_email",
                "draft_feedback_reply", "draft_config_change", "draft_plugin_operation",
                "disable_provider", "update_config", "change_user_status",
                "change_role_permission", "delete_file", "publish_notice", "send_email");
    }

    private static List<String> qaTools() {
        return List.of("search_project_document", "get_api_contract", "get_plugin_guide",
                "get_deployment_guide", "get_dictionary_definition", "get_error_code_explanation",
                "get_current_plugin_status_readonly", "get_current_config_schema_readonly");
    }

    // ==================== 策略 JSON ====================

    private static String entryConfig(String agentType) {
        return switch (agentType) {
            case AiConstants.AgentType.MUSIC -> "{\"entry\":\"music\",\"floatButton\":true,\"welcome\":\"想听点什么？\"}";
            case AiConstants.AgentType.ADMIN -> "{\"entry\":\"assistant\",\"welcome\":\"可以帮你查系统、起草通知或说明配置。\"}";
            default -> "{\"entry\":\"qa\",\"welcome\":\"可以问接口、插件、部署与错误码。\"}";
        };
    }

    private static String contextPolicy(String agentType) {
        return AiConstants.AgentType.MUSIC.equals(agentType)
                ? "{\"maxHistory\":20,\"allowFileContext\":false,\"contextKeys\":[\"currentSong\",\"currentPlaylist\",\"recentlyPlayedSummary\",\"userPreferenceSummary\",\"installedSourceRegistry\"]}"
                : "{\"maxHistory\":20,\"allowFileContext\":false,\"contextKeys\":[\"pageContext\",\"selectedRecord\"]}";
    }

    private static String toolPolicy(String agentType) {
        return switch (agentType) {
            case AiConstants.AgentType.MUSIC -> "{\"mode\":\"whitelist\",\"maxRounds\":3}";
            case AiConstants.AgentType.ADMIN -> "{\"mode\":\"whitelist\",\"maxRounds\":4,\"highRiskExecute\":false}";
            default -> "{\"mode\":\"readonly\",\"maxRounds\":3}";
        };
    }

    private static String knowledgePolicy(String agentType) {
        return AiConstants.AgentType.QA.equals(agentType)
                ? "{\"topK\":5,\"citationRequired\":true,\"allowEmptyAnswer\":true}"
                : "{\"topK\":0,\"citationRequired\":false}";
    }

    private static String modelParameters() {
        return "{\"temperature\":0.3,\"topP\":0.9}";
    }

    private static String safetyPolicy() {
        return "{\"promptInjectionGuard\":true,\"maskSecrets\":true,\"denyArbitrarySql\":true}";
    }

    /** 音乐助手输出结构：answer + 结构化卡片 + 白名单动作 */
    private static final String MUSIC_SCHEMA = """
            {"type":"object","required":["answer"],"properties":{
              "answer":{"type":"string"},
              "blocks":{"type":"array","items":{"type":"object","properties":{
                "type":{"type":"string"},"title":{"type":"string"},"items":{"type":"array"}}}},
              "actions":{"type":"array","items":{"type":"object","properties":{
                "type":{"type":"string"},"songId":{"type":"string"},"sourceId":{"type":"string"}}}}
            }}""";

    /** 管理助手输出结构：answer + 草稿/计划卡片 + 待确认动作 */
    private static final String ADMIN_SCHEMA = """
            {"type":"object","required":["answer"],"properties":{
              "answer":{"type":"string"},
              "blocks":{"type":"array","items":{"type":"object","properties":{
                "type":{"type":"string"},"title":{"type":"string"}}}},
              "actions":{"type":"array"}
            }}""";

    /** 答疑助手输出结构：answer + 引用卡片 */
    private static final String QA_SCHEMA = """
            {"type":"object","required":["answer"],"properties":{
              "answer":{"type":"string"},
              "blocks":{"type":"array","items":{"type":"object","properties":{
                "type":{"type":"string"},"title":{"type":"string"},"citations":{"type":"array"}}}},
              "actions":{"type":"array"}
            }}""";

    // ==================== 资源读取 ====================

    private static String read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.warn("[AiSeed] 种子资源不存在：{}", path);
            return "";
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[AiSeed] 读取种子资源失败：{}", path, e);
            return "";
        }
    }
}
