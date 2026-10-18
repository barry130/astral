package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentKnowledgeEntity;
import com.astral.ai.entity.AiAgentToolEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.entity.AiProviderRouteEntity;
import com.astral.ai.entity.AiToolEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体版本发布前校验。
 *
 * <p>这里集中了所有「发布前必须成立」的条件：模型可用、Prompt 已发布、响应 Schema 合法、
 * 工具已登记且不与智能体类型冲突、知识源完成索引、额度不超过全局上限。
 * 校验只读、可重复调用，{@code /validate} 与 {@code /publish} 共用同一套规则。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentValidationService {

    private final AiAgentService agentService;
    private final AiPromptService promptService;
    private final AiToolService toolService;
    private final AiKnowledgeService knowledgeService;
    private final AiRouteService routeService;
    private final AiModelService modelService;
    private final AiProviderService providerService;
    private final AiQuotaService quotaService;
    private final AiProperties properties;

    /**
     * 校验一个版本，返回问题清单。
     *
     * @param version 待校验版本（可以是草稿）
     * @return 校验结果，{@code valid} 为 true 表示可以发布
     */
    public AiAdminDtos.ValidationVo validate(AiAgentVersionEntity version) {
        List<AiAdminDtos.ValidationVo.ValidationIssue> issues = new ArrayList<>();
        if (version == null) {
            issues.add(error("VERSION_MISSING", "版本不存在", "id"));
            return new AiAdminDtos.ValidationVo(false, issues);
        }
        AiAgentEntity agent;
        try {
            agent = agentService.require(version.getAgentId());
        } catch (RuntimeException e) {
            issues.add(error("AGENT_MISSING", "智能体不存在", "agentId"));
            return new AiAdminDtos.ValidationVo(false, issues);
        }

        checkClients(agent, version, issues);
        checkRoute(version, issues);
        checkPrompt(version, issues, agent);
        checkSchema(version, issues, agent);
        checkTools(version, agent, issues);
        checkKnowledge(version, issues);
        checkQuota(agent, version, issues);

        boolean valid = issues.stream().noneMatch(i -> "ERROR".equals(i.level()));
        return new AiAdminDtos.ValidationVo(valid, issues);
    }

    /** 发布前强制校验，问题直接抛出 */
    public void requireValid(AiAgentVersionEntity version) {
        AiAdminDtos.ValidationVo result = validate(version);
        if (!result.valid()) {
            String first = result.issues().stream()
                    .filter(i -> "ERROR".equals(i.level()))
                    .map(i -> i.message())
                    .findFirst().orElse("配置不完整");
            throw new com.astral.ai.common.AiException("AI007", first);
        }
    }

    // ==================== 逐项检查 ====================

    private void checkClients(AiAgentEntity agent, AiAgentVersionEntity version,
                              List<AiAdminDtos.ValidationVo.ValidationIssue> issues) {
        List<String> clients = AiJson.splitCsv(agent.getClientTypes());
        if (clients.isEmpty()) {
            issues.add(error("CLIENT_EMPTY", "智能体未配置可用客户端", "clientTypes"));
            return;
        }
        for (String client : clients) {
            if (!AiConstants.ClientType.all().contains(client)) {
                issues.add(error("CLIENT_UNKNOWN", "未知客户端类型: " + client, "clientTypes"));
            }
        }
        if (AiConstants.AgentType.MUSIC.equals(agent.getAgentType())
                && clients.contains(AiConstants.ClientType.ASTRAL_FRONT)) {
            issues.add(warn("MUSIC_FRONT", "音乐助手配置了管理端入口，仅用于预览", "clientTypes"));
        }
    }

    private void checkRoute(AiAgentVersionEntity version, List<AiAdminDtos.ValidationVo.ValidationIssue> issues) {
        if (version.getProviderRouteId() == null) {
            issues.add(error("ROUTE_MISSING", "未绑定模型路由", "providerRouteId"));
            return;
        }
        AiProviderRouteEntity route;
        try {
            route = routeService.require(version.getProviderRouteId());
        } catch (RuntimeException e) {
            issues.add(error("ROUTE_MISSING", "模型路由不存在", "providerRouteId"));
            return;
        }
        if (!Boolean.TRUE.equals(route.getEnabled())) {
            issues.add(error("ROUTE_DISABLED", "模型路由已停用", "providerRouteId"));
        }
        AiModelEntity model;
        try {
            model = modelService.require(route.getModelId());
        } catch (RuntimeException e) {
            issues.add(error("MODEL_MISSING", "路由绑定的模型不存在", "modelId"));
            return;
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            issues.add(error("MODEL_DISABLED", "路由绑定的模型已停用", "modelId"));
        }
        AiProviderEntity provider;
        try {
            provider = providerService.require(route.getProviderId());
        } catch (RuntimeException e) {
            issues.add(error("PROVIDER_MISSING", "路由绑定的提供方不存在", "providerId"));
            return;
        }
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            issues.add(error("PROVIDER_DISABLED", "路由绑定的提供方已停用", "providerId"));
        }
        // MOCK 是本地模拟实现，不发起外部请求，因此不要求密钥
        if (!com.astral.ai.provider.MockAiProvider.TYPE.equalsIgnoreCase(provider.getProviderType())) {
            String key = providerService.resolveApiKey(provider);
            if (key == null || key.isBlank()) {
                issues.add(error("PROVIDER_NO_KEY", "提供方缺少可用密钥", "providerId"));
            }
        }
        if (route.getMaxTokens() != null && route.getMaxTokens() > properties.getHardMaxOutputTokens()) {
            issues.add(warn("MAX_TOKENS_HIGH", "路由最大输出 Token 超过平台硬上限，运行期会被截断", "maxTokens"));
        }
    }

    private void checkPrompt(AiAgentVersionEntity version, List<AiAdminDtos.ValidationVo.ValidationIssue> issues,
                             AiAgentEntity agent) {
        if (version.getSystemPromptVersionId() == null) {
            // 曾经只是 WARN，结果「缺提示词的版本」照样能发布：模型没有角色约束，
            // 音乐助手会回一段散文、不产出 blocks/actions，客户端拿不到可点的歌。
            issues.add(error("PROMPT_MISSING", "未绑定系统提示词，发布后模型缺少角色约束，无法产出结构化结果",
                    "systemPromptVersionId"));
            return;
        }
        try {
            var prompt = promptService.requireVersion(version.getSystemPromptVersionId());
            if (!AiConstants.VersionStatus.PUBLISHED.equals(prompt.getStatus())) {
                issues.add(error("PROMPT_UNPUBLISHED", "绑定的提示词版本尚未发布", "systemPromptVersionId"));
            }
            String system = prompt.getSystemPrompt();
            if (system == null || system.isBlank()) {
                issues.add(error("PROMPT_EMPTY", "提示词内容为空", "systemPromptVersionId"));
            } else if (system.length() > 20000) {
                issues.add(warn("PROMPT_LONG", "提示词过长，可能挤占上下文", "systemPromptVersionId"));
            }
            if (system != null && AiConstants.AgentType.MUSIC.equals(agent.getAgentType())
                    && !system.contains("播放")) {
                issues.add(warn("PROMPT_NO_PLAY_GUARD",
                        "音乐助手提示词未提及播放约束，请确认已声明不得编造播放地址", "systemPromptVersionId"));
            }
        } catch (RuntimeException e) {
            issues.add(error("PROMPT_MISSING", "绑定的提示词版本不存在", "systemPromptVersionId"));
        }
    }

    private void checkSchema(AiAgentVersionEntity version, List<AiAdminDtos.ValidationVo.ValidationIssue> issues,
                             AiAgentEntity agent) {
        String schema = version.getResponseSchemaJson();
        if (schema == null || schema.isBlank()) {
            // 音乐助手要靠 response schema 约束模型输出 {answer,blocks,actions}；
            // 缺了它模型会回纯文本，客户端就渲染不出歌曲卡片与播放动作。
            if (AiConstants.AgentType.MUSIC.equals(agent.getAgentType())) {
                issues.add(error("SCHEMA_MISSING", "音乐助手必须配置响应 Schema，否则无法产出歌曲卡片与播放动作",
                        "responseSchemaJson"));
            }
            return;
        }
        Object parsed = AiJson.read(schema);
        if (parsed == null) {
            issues.add(error("SCHEMA_INVALID", "响应 Schema 不是合法 JSON", "responseSchemaJson"));
        }
    }

    private void checkTools(AiAgentVersionEntity version, AiAgentEntity agent,
                            List<AiAdminDtos.ValidationVo.ValidationIssue> issues) {
        List<AiAgentToolEntity> bindings = toolService.listBindings(version.getId());
        if (bindings.isEmpty()) {
            return;
        }
        List<String> agentClients = AiJson.splitCsv(agent.getClientTypes());
        for (AiAgentToolEntity binding : bindings) {
            if (!Boolean.TRUE.equals(binding.getEnabled())) {
                continue;
            }
            com.astral.ai.entity.AiToolEntity tool;
            try {
                tool = toolService.require(binding.getToolId());
            } catch (RuntimeException e) {
                issues.add(error("TOOL_MISSING", "绑定的工具不存在", "toolIds"));
                continue;
            }
            if (!toolService.handlerAvailable(tool)) {
                issues.add(error("TOOL_NOT_IMPLEMENTED",
                        "工具 " + tool.getToolCode() + " 缺少服务端处理器", "toolIds"));
            }
            List<String> toolClients = AiJson.splitCsv(tool.getClientTypes());
            boolean compatible = toolClients.isEmpty()
                    || toolClients.stream().anyMatch(agentClients::contains);
            if (!compatible) {
                issues.add(error("TOOL_CLIENT_MISMATCH",
                        "工具 " + tool.getToolCode() + " 不支持当前智能体的客户端", "toolIds"));
            }
            if (AiConstants.AgentType.MUSIC.equals(agent.getAgentType())
                    && !AiConstants.RiskLevel.READ.equals(tool.getRiskLevel())
                    && !AiConstants.RiskLevel.DRAFT.equals(tool.getRiskLevel())) {
                issues.add(error("MUSIC_TOOL_RISK",
                        "音乐助手不能引用高风险工具: " + tool.getToolCode(), "toolIds"));
            }
            if (AiConstants.AgentType.ADMIN.equals(agent.getAgentType()) && isMusicTool(tool)) {
                issues.add(error("ADMIN_TOOL_SCOPE",
                        "管理助手不能引用音乐端工具: " + tool.getToolCode(), "toolIds"));
            }
            if (AiConstants.AgentType.ADMIN.equals(agent.getAgentType())
                    && !AiConstants.ClientType.ASTRAL_FRONT.equals(
                    agentClients.isEmpty() ? "" : agentClients.get(0))) {
                issues.add(warn("ADMIN_CLIENT", "管理助手建议只挂在管理端", "clientTypes"));
            }
        }
    }

    private void checkKnowledge(AiAgentVersionEntity version, List<AiAdminDtos.ValidationVo.ValidationIssue> issues) {
        List<AiAgentKnowledgeEntity> bindings = knowledgeService.listBindings(version.getId());
        boolean anyEnabled = false;
        for (AiAgentKnowledgeEntity binding : bindings) {
            if (!Boolean.TRUE.equals(binding.getEnabled())) {
                continue;
            }
            anyEnabled = true;
            try {
                var source = knowledgeService.requireSource(binding.getKnowledgeSourceId());
                if (!Boolean.TRUE.equals(source.getEnabled())) {
                    issues.add(error("KNOWLEDGE_DISABLED",
                            "知识源已停用: " + source.getName(), "knowledgeSourceIds"));
                }
                if (!AiConstants.KnowledgeStatus.READY.equals(source.getStatus())) {
                    issues.add(error("KNOWLEDGE_NOT_READY",
                            "知识源尚未完成索引: " + source.getName(), "knowledgeSourceIds"));
                }
            } catch (RuntimeException e) {
                issues.add(error("KNOWLEDGE_MISSING", "绑定的知识源不存在", "knowledgeSourceIds"));
            }
        }
        if (!bindings.isEmpty() && !anyEnabled) {
            issues.add(warn("KNOWLEDGE_NONE_ENABLED", "已绑定知识源但全部处于停用状态", "knowledgeSourceIds"));
        }
    }

    private void checkQuota(AiAgentEntity agent, AiAgentVersionEntity version,
                            List<AiAdminDtos.ValidationVo.ValidationIssue> issues) {
        String override = version.getQuotaOverrideJson();
        if (override != null && !override.isBlank() && AiJson.read(override) == null) {
            issues.add(error("QUOTA_INVALID", "额度覆盖配置不是合法 JSON", "quotaOverrideJson"));
        }
        String params = version.getModelParametersJson();
        if (params != null && !params.isBlank()) {
            var node = AiJson.read(params);
            if (node == null) {
                issues.add(error("PARAMS_INVALID", "模型参数不是合法 JSON", "modelParametersJson"));
            } else {
                double temperature = AiJson.text(node, "temperature", null) == null ? -1
                        : Double.parseDouble(AiJson.text(node, "temperature", "0"));
                if (temperature > 2) {
                    issues.add(error("TEMPERATURE_RANGE", "temperature 必须在 0~2 之间", "modelParametersJson"));
                }
            }
        }
        if (agent.getQuotaPolicyId() != null) {
            try {
                quotaService.requirePolicy(agent.getQuotaPolicyId());
            } catch (RuntimeException e) {
                issues.add(error("QUOTA_MISSING", "绑定的额度策略不存在", "quotaPolicyId"));
            }
        }
    }

    private static AiAdminDtos.ValidationVo.ValidationIssue error(String code, String message, String field) {
        return new AiAdminDtos.ValidationVo.ValidationIssue(code, "ERROR", message, field);
    }

    private static AiAdminDtos.ValidationVo.ValidationIssue warn(String code, String message, String field) {
        return new AiAdminDtos.ValidationVo.ValidationIssue(code, "WARN", message, field);
    }

    /**
     * 判断是否为音乐端工具。
     *
     * <p>不能只看 {@code toolCode} 前缀：音乐工具实际编码是 {@code search_music}、{@code get_song_detail}
     * 这类，并不以 {@code music_} 开头。真正可靠的标识是处理器键前缀 {@code music.}，
     * 它由 {@code MusicToolProvider} 注册，后台无法伪造。</p>
     */
    private static boolean isMusicTool(AiToolEntity tool) {
        String handlerKey = tool.getHandlerKey();
        if (handlerKey != null && handlerKey.startsWith("music.")) {
            return true;
        }
        String toolCode = tool.getToolCode();
        return toolCode != null && toolCode.startsWith("music_");
    }
}
