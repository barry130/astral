package com.astral.ai.controller;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentKnowledgeEntity;
import com.astral.ai.entity.AiAgentToolEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.service.AiAgentService;
import com.astral.ai.service.AiAgentValidationService;
import com.astral.ai.service.AiKnowledgeService;
import com.astral.ai.service.AiToolService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.log.annotation.OperateLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 后台：智能体、版本、工具绑定与知识绑定。
 *
 * <p>版本一旦发布即不可变：{@code PUT /agent-versions/{id}} 只接受 DRAFT，
 * 修改已发布配置的唯一路径是「新建草稿 → 校验 → 发布」或「回滚生成本地新版本」。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiresPermission(value = "admin:ai:agent:view", name = "AI 智能体查看", description = "查看智能体与版本")
@RequiredArgsConstructor
public class AiAdminAgentController {

    private final AiAgentService agentService;
    private final AiAgentValidationService validationService;
    private final AiToolService toolService;
    private final AiKnowledgeService knowledgeService;
    private final com.astral.ai.service.AiRouteService routeService;
    private final com.astral.ai.service.AiPromptService promptService;

    private static Long operatorId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            return null;
        }
        try {
            return Long.valueOf(userId.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 智能体 ====================

    @GetMapping("/agents")
    public Result<List<AiAdminDtos.AgentVo>> agents() {
        List<AiAdminDtos.AgentVo> list = new ArrayList<>();
        for (AiAgentEntity entity : agentService.list()) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    @GetMapping("/agents/{id}")
    public Result<AiAdminDtos.AgentVo> agent(@PathVariable Long id) {
        return Result.success(toVo(agentService.require(id)));
    }

    @PostMapping("/agents")
    @RequiresPermission(value = "admin:ai:agent:edit", name = "AI 智能体编辑", description = "新增智能体")
    @OperateLog("新增 AI 智能体")
    public Result<AiAdminDtos.AgentVo> createAgent(@Valid @RequestBody AiAdminDtos.AgentSaveReq req,
                                                   HttpServletRequest request) {
        AiAgentEntity entity = agentService.create(req.agentCode(), req.name(), req.agentType(),
                req.description(), req.icon(), req.clientTypes(), req.entryConfig(), req.permissionCode(),
                req.clientPermissionCodes(), req.quotaPolicyId(), req.allowUserContext(), req.allowFileContext(),
                req.conversationEnabled(), req.feedbackEnabled(), req.sort(), operatorId(request));
        return Result.success("创建成功", toVo(entity));
    }

    @PutMapping("/agents/{id}")
    @RequiresPermission(value = "admin:ai:agent:edit", name = "AI 智能体编辑", description = "修改智能体")
    @OperateLog("修改 AI 智能体")
    public Result<AiAdminDtos.AgentVo> updateAgent(@PathVariable Long id,
                                                   @Valid @RequestBody AiAdminDtos.AgentSaveReq req,
                                                   HttpServletRequest request) {
        AiAgentEntity entity = agentService.update(id, req.name(), req.agentType(), req.description(),
                req.icon(), req.clientTypes(), req.entryConfig(), req.permissionCode(), req.clientPermissionCodes(),
                req.quotaPolicyId(), req.allowUserContext(), req.allowFileContext(), req.conversationEnabled(),
                req.feedbackEnabled(), req.sort(), operatorId(request));
        return Result.success("保存成功", toVo(entity));
    }

    @PostMapping("/agents/{id}/disable")
    @RequiresPermission(value = "admin:ai:agent:publish", name = "AI 智能体发布", description = "停用智能体")
    @OperateLog("停用 AI 智能体")
    public Result<AiAdminDtos.AgentVo> disableAgent(@PathVariable Long id, HttpServletRequest request) {
        return Result.success("已停用", toVo(agentService.disable(id, operatorId(request))));
    }

    @PostMapping("/agents/{id}/enable")
    @RequiresPermission(value = "admin:ai:agent:publish", name = "AI 智能体发布", description = "启用智能体")
    @OperateLog("启用 AI 智能体")
    public Result<AiAdminDtos.AgentVo> enableAgent(@PathVariable Long id, HttpServletRequest request) {
        return Result.success("已启用", toVo(agentService.enable(id, operatorId(request))));
    }

    @DeleteMapping("/agents/{id}")
    @RequiresPermission(value = "admin:ai:agent:edit", name = "AI 智能体编辑", description = "删除智能体")
    @OperateLog("删除 AI 智能体")
    public Result<Void> deleteAgent(@PathVariable Long id, HttpServletRequest request) {
        agentService.delete(id, operatorId(request));
        return Result.success("已删除", null);
    }

    // ==================== 版本 ====================

    @GetMapping("/agents/{id}/versions")
    public Result<List<AiAdminDtos.AgentVersionVo>> versions(@PathVariable Long id) {
        agentService.require(id);
        List<AiAdminDtos.AgentVersionVo> list = new ArrayList<>();
        for (AiAgentVersionEntity version : agentService.listVersions(id)) {
            list.add(toVo(version));
        }
        return Result.success(list);
    }

    @PostMapping("/agents/{id}/versions")
    @RequiresPermission(value = "admin:ai:agent:edit", name = "AI 智能体编辑", description = "新建草稿版本")
    @OperateLog("新建 AI 智能体草稿版本")
    public Result<AiAdminDtos.AgentVersionVo> createVersion(@PathVariable Long id,
                                                            @RequestBody AiAdminDtos.AgentVersionSaveReq req,
                                                            HttpServletRequest request) {
        AiAgentVersionEntity version = agentService.createDraftVersion(id, req.versionName(),
                req.providerRouteId(), req.systemPromptVersionId(), req.contextPolicyJson(),
                req.responseSchemaJson(), req.toolPolicyJson(), req.knowledgePolicyJson(),
                req.modelParametersJson(), req.safetyPolicyJson(), req.quotaOverrideJson(),
                operatorId(request));
        if (req.toolIds() != null) {
            toolService.replaceBindings(version.getId(), req.toolIds(), operatorId(request));
        }
        return Result.success("创建成功", toVo(version));
    }

    @GetMapping("/agent-versions/{versionId}")
    public Result<AiAdminDtos.AgentVersionVo> version(@PathVariable Long versionId) {
        return Result.success(toVo(agentService.requireVersion(versionId)));
    }

    @PutMapping("/agent-versions/{versionId}")
    @RequiresPermission(value = "admin:ai:agent:edit", name = "AI 智能体编辑", description = "修改草稿版本")
    @OperateLog("修改 AI 智能体草稿版本")
    public Result<AiAdminDtos.AgentVersionVo> updateVersion(@PathVariable Long versionId,
                                                            @RequestBody AiAdminDtos.AgentVersionSaveReq req,
                                                            HttpServletRequest request) {
        AiAgentVersionEntity version = agentService.updateDraftVersion(versionId, req.versionName(),
                req.providerRouteId(), req.systemPromptVersionId(), req.contextPolicyJson(),
                req.responseSchemaJson(), req.toolPolicyJson(), req.knowledgePolicyJson(),
                req.modelParametersJson(), req.safetyPolicyJson(), req.quotaOverrideJson(),
                operatorId(request));
        if (req.toolIds() != null) {
            toolService.replaceBindings(version.getId(), req.toolIds(), operatorId(request));
        }
        return Result.success("保存成功", toVo(version));
    }

    @PostMapping("/agent-versions/{versionId}/validate")
    public Result<AiAdminDtos.ValidationVo> validateVersion(@PathVariable Long versionId) {
        return Result.success(validationService.validate(agentService.requireVersion(versionId)));
    }

    @PostMapping("/agent-versions/{versionId}/publish")
    @RequiresPermission(value = "admin:ai:agent:publish", name = "AI 智能体发布", description = "发布版本")
    @OperateLog("发布 AI 智能体版本")
    public Result<AiAdminDtos.AgentVersionVo> publishVersion(@PathVariable Long versionId,
                                                             HttpServletRequest request) {
        AiAgentVersionEntity version = agentService.requireVersion(versionId);
        validationService.requireValid(version);
        return Result.success("发布成功", toVo(agentService.publish(versionId, operatorId(request))));
    }

    @PostMapping("/agents/{id}/rollback")
    @RequiresPermission(value = "admin:ai:agent:publish", name = "AI 智能体发布", description = "回滚到历史版本")
    @OperateLog("回滚 AI 智能体版本")
    public Result<AiAdminDtos.AgentVersionVo> rollback(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Object raw = body == null ? null : body.get("versionId");
        if (raw == null) {
            throw new BusinessException("AI007", "缺少 versionId");
        }
        Long target;
        try {
            target = Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            throw new BusinessException("AI007", "versionId 非法");
        }
        return Result.success("已回滚", toVo(agentService.rollback(id, target, operatorId(request))));
    }

    // ==================== 工具绑定 ====================

    @GetMapping("/agent-versions/{versionId}/tools")
    public Result<List<AiAdminDtos.AgentToolVo>> versionTools(@PathVariable Long versionId) {
        List<AiAgentToolEntity> bindings = toolService.listBindings(versionId);
        List<AiAdminDtos.AgentToolVo> list = new ArrayList<>();
        for (AiAgentToolEntity binding : bindings) {
            AiToolEntity tool;
            try {
                tool = toolService.require(binding.getToolId());
            } catch (RuntimeException e) {
                continue;
            }
            String effective = binding.getRiskOverride() == null || binding.getRiskOverride().isBlank()
                    ? tool.getRiskLevel() : stronger(tool.getRiskLevel(), binding.getRiskOverride());
            String approval = binding.getApprovalPolicy() == null || binding.getApprovalPolicy().isBlank()
                    ? tool.getApprovalPolicy() : binding.getApprovalPolicy();
            list.add(new AiAdminDtos.AgentToolVo(binding.getId(), binding.getAgentVersionId(),
                    tool.getId(), tool.getToolCode(), tool.getName(), tool.getRiskLevel(), effective,
                    approval, binding.getEnabled(), binding.getSort()));
        }
        return Result.success(list);
    }

    @PutMapping("/agent-versions/{versionId}/tools")
    @RequiresPermission(value = "admin:ai:agent:edit", name = "AI 智能体编辑", description = "绑定工具")
    @OperateLog("绑定 AI 智能体工具")
    public Result<List<AiAdminDtos.AgentToolVo>> replaceTools(@PathVariable Long versionId,
                                                              @RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        agentService.requireDraft(agentService.requireVersion(versionId));
        List<Long> ids = new ArrayList<>();
        Object raw = body == null ? null : body.get("toolIds");
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                if (item != null) {
                    ids.add(Long.valueOf(item.toString()));
                }
            }
        }
        toolService.replaceBindings(versionId, ids, operatorId(request));
        return versionTools(versionId);
    }

    // ==================== 知识绑定 ====================

    @GetMapping("/agent-versions/{versionId}/knowledge")
    public Result<List<AiAdminDtos.AgentKnowledgeVo>> versionKnowledge(@PathVariable Long versionId) {
        List<AiAgentKnowledgeEntity> bindings = knowledgeService.listBindings(versionId);
        List<AiAdminDtos.AgentKnowledgeVo> list = new ArrayList<>();
        for (AiAgentKnowledgeEntity binding : bindings) {
            String name = null;
            String type = null;
            try {
                var source = knowledgeService.requireSource(binding.getKnowledgeSourceId());
                name = source.getName();
                type = source.getSourceType();
            } catch (RuntimeException ignored) {
                name = null;
            }
            list.add(new AiAdminDtos.AgentKnowledgeVo(binding.getId(), binding.getAgentVersionId(),
                    binding.getKnowledgeSourceId(), name, type, binding.getEnabled(), binding.getPriority(),
                    binding.getRetrievalTopK(), binding.getMinScore(), binding.getCitationPolicy()));
        }
        return Result.success(list);
    }

    @PutMapping("/agent-versions/{versionId}/knowledge")
    @RequiresPermission(value = "admin:ai:agent:edit", name = "AI 智能体编辑", description = "绑定知识源")
    @OperateLog("绑定 AI 智能体知识源")
    public Result<List<AiAdminDtos.AgentKnowledgeVo>> replaceKnowledge(
            @PathVariable Long versionId,
            @RequestBody List<AiAdminDtos.AgentKnowledgeSaveReq> reqs,
            HttpServletRequest request) {
        agentService.requireDraft(agentService.requireVersion(versionId));
        List<AiAgentKnowledgeEntity> bindings = new ArrayList<>();
        if (reqs != null) {
            for (AiAdminDtos.AgentKnowledgeSaveReq req : reqs) {
                if (req == null || req.sourceId() == null) {
                    continue;
                }
                AiAgentKnowledgeEntity binding = new AiAgentKnowledgeEntity();
                binding.setAgentVersionId(versionId);
                binding.setKnowledgeSourceId(req.sourceId());
                binding.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
                binding.setPriority(req.priority() == null ? 100 : req.priority());
                binding.setRetrievalTopK(req.retrievalTopK());
                binding.setMinScore(req.minScore());
                binding.setCitationPolicy(req.citationPolicy());
                bindings.add(binding);
            }
        }
        knowledgeService.replaceBindings(versionId, bindings);
        return versionKnowledge(versionId);
    }

    // ==================== 转换 ====================

    private AiAdminDtos.AgentVo toVo(AiAgentEntity entity) {
        List<AiAgentVersionEntity> versions = agentService.listVersions(entity.getId());
        Integer publishedNo = null;
        int drafts = 0;
        for (AiAgentVersionEntity version : versions) {
            if (AiConstants.VersionStatus.DRAFT.equals(version.getStatus())) {
                drafts++;
            }
            if (entity.getPublishedVersionId() != null
                    && entity.getPublishedVersionId().equals(version.getId())) {
                publishedNo = version.getVersionNo();
            }
        }
        return new AiAdminDtos.AgentVo(entity.getId(), entity.getAgentCode(), entity.getName(),
                entity.getAgentType(), entity.getDescription(), entity.getIcon(), entity.getStatus(),
                AiJson.splitCsv(entity.getClientTypes()), entity.getEntryConfig(), entity.getPermissionCode(),
                entity.getClientPermissionCodes(), entity.getQuotaPolicyId(), entity.getPublishedVersionId(),
                publishedNo, entity.getAllowUserContext(), entity.getAllowFileContext(),
                entity.getConversationEnabled(), entity.getFeedbackEnabled(), entity.getSort(),
                versions.size(), drafts, entity.getCreateTime(), entity.getUpdateTime());
    }

    private AiAdminDtos.AgentVersionVo toVo(AiAgentVersionEntity version) {
        String routeName = null;
        String promptName = null;
        if (version.getProviderRouteId() != null) {
            try {
                routeName = routeService.require(version.getProviderRouteId()).getName();
            } catch (RuntimeException ignored) {
                routeName = null;
            }
        }
        if (version.getSystemPromptVersionId() != null) {
            try {
                var promptVersion = promptService.requireVersion(version.getSystemPromptVersionId());
                promptName = promptService.findTemplate(promptVersion.getTemplateId()) == null ? null
                        : promptService.findTemplate(promptVersion.getTemplateId()).getName()
                        + " v" + promptVersion.getVersionNo();
            } catch (RuntimeException ignored) {
                promptName = null;
            }
        }
        List<Long> toolIds = new ArrayList<>();
        for (AiAgentToolEntity binding : toolService.listBindings(version.getId())) {
            toolIds.add(binding.getToolId());
        }
        List<Long> knowledgeIds = new ArrayList<>();
        for (AiAgentKnowledgeEntity binding : knowledgeService.listBindings(version.getId())) {
            knowledgeIds.add(binding.getKnowledgeSourceId());
        }
        return new AiAdminDtos.AgentVersionVo(version.getId(), version.getAgentId(), version.getVersionNo(),
                version.getVersionName(), version.getStatus(), version.getProviderRouteId(), routeName,
                version.getSystemPromptVersionId(), promptName, version.getClientTypesSnapshot(),
                version.getEntryConfigSnapshot(), version.getContextPolicyJson(), version.getResponseSchemaJson(),
                version.getToolPolicyJson(), version.getKnowledgePolicyJson(), version.getModelParametersJson(),
                version.getSafetyPolicyJson(), version.getQuotaOverrideJson(), version.getValidationSummaryJson(),
                version.getPublishedBy(), version.getPublishedAt(), version.getRollbackFromVersionId(),
                toolIds, knowledgeIds, version.getCreateTime(), version.getUpdateTime());
    }

    private static String stronger(String base, String override) {
        return AiConstants.RiskLevel.order(override) > AiConstants.RiskLevel.order(base) ? override : base;
    }
}
