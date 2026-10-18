package com.astral.ai.controller;

import com.astral.ai.common.AiConstants;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiPromptTemplateEntity;
import com.astral.ai.entity.AiPromptVersionEntity;
import com.astral.ai.service.AiPromptService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.log.annotation.OperateLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 后台：提示词模板与版本。
 *
 * <p>提示词按「模板 + 版本」组织：智能体版本引用的是<b>某个具体的提示词版本</b>，
 * 因此上线后单独改提示词不会静默改变已发布智能体的行为——必须发新提示词版本并重新发布智能体。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiresPermission(value = "admin:ai:config:view", name = "AI 配置查看", description = "AI 提示词配置查看")
@RequiredArgsConstructor
public class AiAdminPromptController {

    private final AiPromptService promptService;

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

    @GetMapping("/prompts")
    @RequiresPermission(value = "admin:ai:prompt:view", name = "AI 提示词查看", description = "查看提示词模板")
    public Result<List<AiAdminDtos.PromptTemplateVo>> prompts() {
        List<AiAdminDtos.PromptTemplateVo> list = new ArrayList<>();
        for (AiPromptTemplateEntity entity : promptService.listTemplates()) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    @GetMapping("/prompts/{id}")
    @RequiresPermission(value = "admin:ai:prompt:view", name = "AI 提示词查看", description = "查看提示词模板")
    public Result<AiAdminDtos.PromptTemplateVo> prompt(@PathVariable Long id) {
        return Result.success(toVo(promptService.requireTemplate(id)));
    }

    @PostMapping("/prompts")
    @RequiresPermission(value = "admin:ai:prompt:edit", name = "AI 提示词编辑", description = "新增提示词模板")
    @OperateLog("新增 AI 提示词模板")
    public Result<AiAdminDtos.PromptTemplateVo> createPrompt(
            @Valid @RequestBody AiAdminDtos.PromptTemplateSaveReq req, HttpServletRequest request) {
        AiPromptTemplateEntity entity = promptService.createTemplate(req.promptCode(), req.name(), req.scene(),
                req.description(), operatorId(request));
        return Result.success("创建成功", toVo(entity));
    }

    @PutMapping("/prompts/{id}")
    @RequiresPermission(value = "admin:ai:prompt:edit", name = "AI 提示词编辑", description = "修改提示词模板")
    @OperateLog("修改 AI 提示词模板")
    public Result<AiAdminDtos.PromptTemplateVo> updatePrompt(
            @PathVariable Long id,
            @Valid @RequestBody AiAdminDtos.PromptTemplateSaveReq req,
            HttpServletRequest request) {
        AiPromptTemplateEntity entity = promptService.updateTemplate(id, req.name(), req.scene(),
                req.description(), operatorId(request));
        return Result.success("保存成功", toVo(entity));
    }

    // ==================== 版本 ====================

    @GetMapping("/prompts/{id}/versions")
    @RequiresPermission(value = "admin:ai:prompt:view", name = "AI 提示词查看", description = "查看提示词版本")
    public Result<List<AiAdminDtos.PromptVersionVo>> versions(@PathVariable Long id) {
        promptService.requireTemplate(id);
        List<AiAdminDtos.PromptVersionVo> list = new ArrayList<>();
        for (AiPromptVersionEntity version : promptService.listVersions(id)) {
            list.add(toVo(version));
        }
        return Result.success(list);
    }

    @PostMapping("/prompts/{id}/versions")
    @RequiresPermission(value = "admin:ai:prompt:edit", name = "AI 提示词编辑", description = "新建提示词版本")
    @OperateLog("新建 AI 提示词版本")
    public Result<AiAdminDtos.PromptVersionVo> createVersion(
            @PathVariable Long id,
            @Valid @RequestBody AiAdminDtos.PromptVersionSaveReq req,
            HttpServletRequest request) {
        AiPromptVersionEntity version = promptService.createVersion(id, req.systemPrompt(),
                req.userPromptTemplate(), req.variablesJson(), req.changelog(), operatorId(request));
        return Result.success("创建成功", toVo(version));
    }

    @PutMapping("/prompt-versions/{versionId}")
    @RequiresPermission(value = "admin:ai:prompt:edit", name = "AI 提示词编辑", description = "修改提示词草稿")
    @OperateLog("修改 AI 提示词版本")
    public Result<AiAdminDtos.PromptVersionVo> updateVersion(
            @PathVariable Long versionId,
            @Valid @RequestBody AiAdminDtos.PromptVersionSaveReq req,
            HttpServletRequest request) {
        AiPromptVersionEntity version = promptService.updateVersion(versionId, req.systemPrompt(),
                req.userPromptTemplate(), req.variablesJson(), req.changelog(), operatorId(request));
        return Result.success("保存成功", toVo(version));
    }

    @PostMapping("/prompt-versions/{versionId}/publish")
    @RequiresPermission(value = "admin:ai:prompt:edit", name = "AI 提示词编辑", description = "发布提示词版本")
    @OperateLog("发布 AI 提示词版本")
    public Result<AiAdminDtos.PromptVersionVo> publishVersion(@PathVariable Long versionId,
                                                              HttpServletRequest request) {
        return Result.success("发布成功", toVo(promptService.publish(versionId, operatorId(request))));
    }

    // ==================== 转换 ====================

    private AiAdminDtos.PromptTemplateVo toVo(AiPromptTemplateEntity entity) {
        AiPromptVersionEntity published = promptService.latestPublished(entity.getId());
        List<AiPromptVersionEntity> versions = promptService.listVersions(entity.getId());
        return new AiAdminDtos.PromptTemplateVo(entity.getId(), entity.getPromptCode(), entity.getName(),
                entity.getScene(), entity.getDescription(),
                published == null ? AiConstants.VersionStatus.DRAFT : AiConstants.VersionStatus.PUBLISHED,
                published == null ? null : published.getId(),
                published == null ? null : published.getVersionNo(),
                versions.size(), entity.getCreateTime(), entity.getUpdateTime());
    }

    private AiAdminDtos.PromptVersionVo toVo(AiPromptVersionEntity entity) {
        return new AiAdminDtos.PromptVersionVo(entity.getId(), entity.getTemplateId(), entity.getVersionNo(),
                entity.getStatus(), entity.getSystemPrompt(), entity.getUserPromptTemplate(),
                entity.getVariablesJson(), entity.getChangelog(), entity.getPublishedBy(), entity.getPublishedAt(),
                entity.getCreateTime(), entity.getUpdateTime());
    }
}
